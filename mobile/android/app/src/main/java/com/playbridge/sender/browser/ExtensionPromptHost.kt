package com.playbridge.sender.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.playbridge.sender.R

/** Native UI only; never exposes an approval event or token to a web document. */
internal class ExtensionPromptHost {
    private val main = Handler(Looper.getMainLooper())
    private val approvals = ExtensionApprovalCoordinator()
    private var activity: Activity? = null
    private var dialog: AlertDialog? = null
    private var startup: (() -> Unit)? = null

    fun attach(owner: Activity) {
        if (activity === owner) return
        activity?.let { detach(it) }
        activity = owner
        approvals.attach(owner)
        val deferred = startup
        startup = null
        deferred?.invoke()
    }

    fun detach(owner: Activity) {
        if (activity !== owner) return
        activity = null
        approvals.detach(owner)
        dialog?.dismiss()
        dialog = null
    }

    // Only native-owned uBlock setup uses this; website requests are never deferred.
    fun runWhenResumed(action: () -> Unit) = onMain {
        if (usableActivity() != null) action() else startup = action
    }

    fun request(request: ExtensionApprovalRequest, onDecision: (ExtensionApprovalDecision) -> Unit) = onMain {
        val owner = usableActivity()
        if (owner == null) {
            onDecision(ExtensionApprovalDecision())
        } else {
            approvals.request(request, { usableActivity() === owner }, onDecision)?.let { show(owner, it) }
        }
    }

    fun requestWebsiteInstall(owner: Activity, url: String, isCurrent: () -> Boolean, install: () -> Unit) = onMain {
        if (usableActivity() !== owner) return@onMain
        approvals.requestWebsiteInstall(url, isCurrent, install)?.let { show(owner, it) }
    }

    // A new full-window setup guide must not cover an already pending native prompt.
    fun reShowPendingPrompt() = onMain {
        val owner = usableActivity() ?: return@onMain
        val pending = approvals.pending ?: return@onMain
        val previous = dialog
        previous?.setOnDismissListener(null)
        previous?.setOnCancelListener(null)
        previous?.dismiss()
        dialog = null
        show(owner, pending)
    }

    fun cancelInstallPrompt(extensionId: String) = onMain {
        val pending = approvals.pending ?: return@onMain
        if (pending.request.kind != ExtensionApprovalKind.INSTALL || pending.request.extensionId != extensionId) return@onMain
        val shown = dialog
        approvals.decide(pending.id, ExtensionApprovalDecision())
        shown?.dismiss()
        if (dialog === shown) dialog = null
    }

    private fun usableActivity(): Activity? = activity?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun show(owner: Activity, pending: ExtensionApprovalCoordinator.Pending) {
        val request = pending.request
        val title = when (request.kind) {
            ExtensionApprovalKind.WEBSITE_DOWNLOAD -> "Download browser extension?"
            ExtensionApprovalKind.INSTALL -> "Install browser extension?"
            ExtensionApprovalKind.UPDATE -> "Allow new extension permissions?"
            ExtensionApprovalKind.OPTIONAL -> "Allow optional extension permissions?"
        }
        val message = buildString {
            if (request.kind == ExtensionApprovalKind.WEBSITE_DOWNLOAD) {
                append("This website wants to download an extension from:\n")
                append(promptText(request.source))
                append("\n\nDownloading does not approve installation. You will review its verified ID and permissions next.")
            } else {
                append("Name: ${promptText(request.name)}\nID: ${promptText(request.extensionId)}")
                if (request.source.isNotBlank()) append("\nSource: ${promptText(request.source)}")
                append("\n\nPermissions:\n${scopeList(request.permissions)}")
                append("\n\nWebsite access:\n${scopeList(request.origins)}")
                append("\n\nRequested data collection:\n${scopeList(request.dataCollection)}")
                append("\n\nOnly approve an extension you trust. Approval grants the requested scopes.")
                if (request.kind == ExtensionApprovalKind.INSTALL) {
                    append(" Private browsing and technical/interaction data require separate opt-in below.")
                }
            }
        }
        val content = LinearLayout(owner).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(TextView(owner).apply { text = message; setTextIsSelectable(true) })
        }
        val privateMode = if (request.kind == ExtensionApprovalKind.INSTALL) CheckBox(owner).apply {
            setText(R.string.extension_allow_private_browsing)
            isChecked = false
            content.addView(this)
        } else null
        val technicalData = if (request.kind == ExtensionApprovalKind.INSTALL &&
            "technicalAndInteraction" in request.dataCollection) CheckBox(owner).apply {
            setText(R.string.extension_allow_technical_data)
            isChecked = false
            content.addView(this)
        } else null
        try {
            val shown = TouchProtectedExtensionDialog(owner).apply {
                setTitle(title)
                setApprovalContent(content)
                setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel") { _, _ ->
                    approvals.decide(pending.id, ExtensionApprovalDecision())
                }
                setButton(AlertDialog.BUTTON_POSITIVE,
                    if (request.kind == ExtensionApprovalKind.WEBSITE_DOWNLOAD) "Download" else "Approve") { _, _ ->
                    approvals.decide(pending.id, ExtensionApprovalDecision(
                        allowed = true, privateBrowsing = privateMode?.isChecked == true,
                        technicalData = technicalData?.isChecked == true,
                    ))
                }
            }
            shown.setOnCancelListener { approvals.decide(pending.id, ExtensionApprovalDecision()) }
            shown.setOnDismissListener {
                approvals.decide(pending.id, ExtensionApprovalDecision())
                if (dialog === shown) dialog = null
            }
            dialog = shown
            shown.show()
            shown.window?.decorView?.filterTouchesWhenObscured = true
        } catch (_: RuntimeException) {
            approvals.decide(pending.id, ExtensionApprovalDecision())
            dialog?.dismiss()
            dialog = null
        }
    }

    private fun scopeList(scopes: List<String>): String = if (scopes.isEmpty()) "None" else
        scopes.joinToString("\n") { "• ${promptText(it)}" }

    private fun promptText(value: String): String = value.replace(Regex("[\\p{Cc}\\p{Cf}]"), "\uFFFD")
}
