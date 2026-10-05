package com.playbridge.sender.browser

import java.net.URI

internal enum class ExtensionApprovalKind { WEBSITE_DOWNLOAD, INSTALL, UPDATE, OPTIONAL }

internal data class ExtensionApprovalRequest(
    val kind: ExtensionApprovalKind,
    val extensionId: String = "",
    val name: String = "",
    val source: String = "",
    val permissions: List<String> = emptyList(),
    val origins: List<String> = emptyList(),
    val dataCollection: List<String> = emptyList(),
)

internal data class ExtensionApprovalDecision(
    val allowed: Boolean = false,
    val privateBrowsing: Boolean = false,
    val technicalData: Boolean = false,
)

internal fun isExtensionDownload(url: String, contentType: String?, fileName: String? = null): Boolean =
    contentType?.substringBefore(';')?.trim().equals("application/x-xpinstall", ignoreCase = true) ||
        fileName?.endsWith(".xpi", ignoreCase = true) == true ||
        runCatching { URI(url).path?.endsWith(".xpi", ignoreCase = true) == true }.getOrDefault(false)

internal fun extensionDownloadSource(url: String): String? = runCatching {
    val uri = URI(url)
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
        return@runCatching null
    }
    // Never expose authenticated query strings/fragments in a native prompt.
    URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
}.getOrNull()

/** Main-thread-only, one pending native prompt. No website response can resolve it. */
internal class ExtensionApprovalCoordinator {
    internal data class Pending(val id: Long, val request: ExtensionApprovalRequest)
    private var owner: Any? = null
    private var nextId = 0L
    var pending: Pending? = null
        private set
    private var callback: ((ExtensionApprovalDecision) -> Unit)? = null
    private var isCurrent: () -> Boolean = { true }

    fun attach(owner: Any) {
        if (this.owner === owner) return
        val previous = this.owner
        if (previous != null) detach(previous)
        this.owner = owner
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        pending?.let { decide(it.id, ExtensionApprovalDecision()) }
    }

    fun request(
        request: ExtensionApprovalRequest,
        isCurrent: () -> Boolean = { true },
        onDecision: (ExtensionApprovalDecision) -> Unit,
    ): Pending? {
        if (owner == null || pending != null || !runCatching { isCurrent() }.getOrDefault(false)) {
            onDecision(ExtensionApprovalDecision())
            return null
        }
        val snapshot = request.copy(
            permissions = request.permissions.toList(), origins = request.origins.toList(),
            dataCollection = request.dataCollection.toList(),
        )
        return Pending(++nextId, snapshot).also {
            pending = it
            callback = onDecision
            this.isCurrent = isCurrent
        }
    }

    fun decide(id: Long, decision: ExtensionApprovalDecision) {
        val current = pending ?: return
        if (current.id != id) return
        val complete = callback ?: return
        val allowed = decision.allowed && owner != null && runCatching { isCurrent() }.getOrDefault(false)
        val result = ExtensionApprovalDecision(
            allowed = allowed,
            privateBrowsing = allowed && current.request.kind == ExtensionApprovalKind.INSTALL && decision.privateBrowsing,
            technicalData = allowed && current.request.kind == ExtensionApprovalKind.INSTALL &&
                "technicalAndInteraction" in current.request.dataCollection && decision.technicalData,
        )
        // Clear first: completing an install can synchronously request its permission prompt.
        pending = null
        callback = null
        isCurrent = { true }
        complete(result)
    }

    fun requestWebsiteInstall(
        url: String,
        isCurrent: () -> Boolean,
        install: () -> Unit,
    ): Pending? {
        val source = extensionDownloadSource(url) ?: return null
        return request(ExtensionApprovalRequest(ExtensionApprovalKind.WEBSITE_DOWNLOAD, source = source), isCurrent) {
            if (it.allowed) install()
        }
    }
}
