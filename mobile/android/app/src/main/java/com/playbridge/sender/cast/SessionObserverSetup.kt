package com.playbridge.sender.cast
import com.playbridge.sender.browser.*

import com.playbridge.sender.downloads.PendingDownload
import com.playbridge.sender.downloads.PendingPopup
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.EngineAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.HitResult
import mozilla.components.concept.engine.mediasession.MediaSession
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest
import mozilla.components.concept.engine.prompt.Choice
import mozilla.components.concept.engine.prompt.PromptRequest
import mozilla.components.concept.engine.window.WindowRequest
import mozilla.components.concept.fetch.Response
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity
import org.koin.compose.koinInject
import java.security.cert.X509Certificate

/**
 * Registers a single [EngineSession.Observer] on the active browser session.
 *
 * Everything here uses public Android Components observer APIs. The previous
 * implementation replaced GeckoSession delegates via `java.lang.reflect.Proxy`
 * and field reflection — fragile across AC/GeckoView upgrades and R8. The
 * mapping is:
 *
 * - NavigationDelegate.onNewSession  → [EngineSession.Observer.onWindowRequest]
 * - NavigationDelegate.onLoadError   → engine-level RequestInterceptor ([Components])
 * - NavigationDelegate.onLoadRequest (magnet/stremio) → RequestInterceptor
 * - ContentDelegate.onContextMenu    → [EngineSession.Observer.onLongPress]
 * - ContentDelegate.onFullScreen     → onFullScreenChange / onMediaFullscreenChanged
 *   (the portrait check now uses media element dimensions instead of the old
 *   `javascript:` location-hash injection hack)
 * - PermissionDelegate (autoplay/DRM) → onContentPermissionRequest
 * - ProgressDelegate (loading/security) → onLoadingStateChange / onSecurityChange
 * - PromptDelegate (alert/confirm/prompt/<select>) → onPromptRequest
 * - MediaSession.Delegate → MediaSessionObserver (registered per tab by TabManager)
 */
@Composable
fun SessionObserverSetup(
    session: EngineSession,
    selectedTab: TabSessionState?,
    store: BrowserStore,
    tabManager: TabManager,
    scope: CoroutineScope,
    currentUrl: MutableState<String>,
    isLoading: MutableState<Boolean>,
    contextMenuUrl: MutableState<String?>,
    previousUrl: MutableState<String>,
    pendingDownload: MutableState<PendingDownload?>,
    isDesktopMode: Boolean,
    isSecureConnection: MutableState<Boolean>,
    siteSecurityInfo: MutableState<SiteSecurityInfo?>,
    pendingPopup: MutableState<PendingPopup?>,
    onXpiDetected: (String) -> Unit,
    onMagnetDetected: (String) -> Unit,
    onStremioAddonDetected: (String) -> Unit,
    onTorrentDownloaded: (ByteArray) -> Unit,
    onFullScreenChange: (Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    val historyDao: HistoryDao = koinInject()
    val settingsRepository: com.playbridge.sender.data.settings.SettingsRepository = koinInject()
    val blockPopups by settingsRepository.blockPopups.collectAsState(initial = true)
    val whitelist by settingsRepository.popupWhitelist.collectAsState(initial = emptySet())
    val blacklist by settingsRepository.popupBlacklist.collectAsState(initial = emptySet())

    val blockPopupsState = rememberUpdatedState(blockPopups)
    val whitelistState = rememberUpdatedState(whitelist)
    val blacklistState = rememberUpdatedState(blacklist)
    val selectedTabState = rememberUpdatedState(selectedTab)
    val fullScreenCb = rememberUpdatedState(onFullScreenChange)

    // Magnet/Stremio links are intercepted at the engine level (RequestInterceptor
    // in Components) so they work in every tab; wire its callbacks here.
    val magnetHandler = rememberUpdatedState(onMagnetDetected)
    val stremioHandler = rememberUpdatedState(onStremioAddonDetected)
    DisposableEffect(Unit) {
        Components.onMagnetDetected = { uri -> magnetHandler.value(uri) }
        Components.onStremioAddonDetected = { uri -> stremioHandler.value(uri) }
        onDispose {
            Components.onMagnetDetected = null
            Components.onStremioAddonDetected = null
        }
    }

    // Apply desktop user agent when the session changes (tab switch) — no reload,
    // since the page isn't loaded in desktop mode yet anyway.
    LaunchedEffect(session) {
        session.toggleDesktopMode(isDesktopMode, reload = false)
    }
    // Reload when the user actively toggles desktop mode on the current tab.
    LaunchedEffect(isDesktopMode) {
        val shouldReload = currentUrl.value != "about:blank"
        session.toggleDesktopMode(isDesktopMode, reload = shouldReload)
        Log.d(TAG, "${if (isDesktopMode) "Enabled" else "Disabled"} Desktop Mode (reload=$shouldReload)")
    }

    DisposableEffect(session, selectedTab?.id) {
        val observer = object : EngineSession.Observer {

            /** Portrait-ness of the last fullscreened media element. */
            private var lastMediaIsPortrait = false

            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                // NOTE: the store's URL is updated by EngineMiddleware's
                // EngineObserver (for every tab, not just the selected one).

                // Get base URL without hash/fragment
                val baseUrl = url.substringBefore("#")
                val previousBaseUrl = previousUrl.value.substringBefore("#")

                // Record history
                if (baseUrl != previousBaseUrl && !url.startsWith("about:")) {
                    scope.launch(Dispatchers.IO) {
                        val title = selectedTabState.value?.content?.title
                        historyDao.insert(
                            HistoryEntity(
                                url = url,
                                title = title,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }

                currentUrl.value = url
                previousUrl.value = url
            }

            override fun onLoadingStateChange(loading: Boolean) {
                isLoading.value = loading
                if (loading) {
                    // Reset detected videos on every top-level page load start —
                    // including reloads of the same URL. Does not fire for
                    // same-document (hash/pushState) navigations, so SPA route
                    // changes keep their detected videos. (Was ProgressDelegate
                    // onPageStart.)
                    selectedTabState.value?.id?.let { VideoDetector.clearTab(it) }
                }
            }

            override fun onTitleChange(title: String) {
                Log.d(TAG, "Title changed: $title")
                // Store title is updated by EngineMiddleware's EngineObserver.

                // Update the history database item with the loaded page title
                val url = currentUrl.value
                if (url.isNotEmpty() && !url.startsWith("about:")) {
                    scope.launch(Dispatchers.IO) {
                        historyDao.insert(
                            HistoryEntity(
                                url = url,
                                title = title,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            override fun onSecurityChange(
                secure: Boolean,
                host: String?,
                issuer: String?,
                certificate: X509Certificate?
            ) {
                isSecureConnection.value = secure
                val certIssuer = certificate?.issuerX500Principal?.name?.let { parseCN(it) } ?: issuer
                val certValidUntil = certificate?.notAfter?.let {
                    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(it)
                }
                siteSecurityInfo.value = SiteSecurityInfo(
                    isSecure = secure,
                    host = host ?: "",
                    certIssuer = certIssuer,
                    certValidUntil = certValidUntil
                )
                Log.d(TAG, "Security changed: isSecure=$secure, host=$host, issuer=$certIssuer")
            }

            // ── Fullscreen ────────────────────────────────────────────

            override fun onMediaFullscreenChanged(
                fullscreen: Boolean,
                elementMetadata: MediaSession.ElementMetadata?
            ) {
                val isPortrait = elementMetadata != null &&
                    elementMetadata.width > 0L && elementMetadata.height > 0L &&
                    elementMetadata.height > elementMetadata.width
                lastMediaIsPortrait = isPortrait
                scope.launch(Dispatchers.Main) {
                    fullScreenCb.value(fullscreen, fullscreen && isPortrait)
                }
            }

            override fun onFullScreenChange(enabled: Boolean) {
                // DOM element fullscreen (non-media or media without a media
                // session). Use the last known media orientation as the hint.
                scope.launch(Dispatchers.Main) {
                    fullScreenCb.value(enabled, enabled && lastMediaIsPortrait)
                }
            }

            // ── Long-press context menu ───────────────────────────────

            override fun onLongPress(hitResult: HitResult) {
                val link = when (hitResult) {
                    is HitResult.UNKNOWN -> hitResult.src
                    is HitResult.IMAGE_SRC -> hitResult.uri
                    is HitResult.IMAGE -> hitResult.src
                    is HitResult.VIDEO -> hitResult.src
                    is HitResult.AUDIO -> hitResult.src
                    else -> null
                }
                if (!link.isNullOrEmpty() && (link.startsWith("http://") || link.startsWith("https://"))) {
                    scope.launch(Dispatchers.Main) { contextMenuUrl.value = link }
                }
            }

            // ── Permissions (autoplay + DRM auto-grant) ───────────────

            override fun onContentPermissionRequest(permissionRequest: PermissionRequest) {
                val granted = permissionRequest.grantIf { perm ->
                    perm is Permission.ContentAutoPlayAudible ||
                        perm is Permission.ContentAutoPlayInaudible ||
                        perm is Permission.ContentMediaKeySystemAccess
                }
                if (granted) {
                    Log.d(TAG, "Granted media permission(s): ${permissionRequest.permissions}")
                } else {
                    permissionRequest.reject()
                }
            }

            // ── Popups (window.open / target=_blank) ──────────────────

            override fun onWindowRequest(windowRequest: WindowRequest) {
                if (windowRequest.type == WindowRequest.Type.CLOSE) {
                    // window.close(): close the tab hosting this session.
                    val closing = windowRequest.prepare()
                    val tabId = tabManager.sessions.entries.find { it.value === closing }?.key
                    if (tabId != null) {
                        scope.launch(Dispatchers.Main) { tabManager.closeTab(tabId, store) }
                    }
                    return
                }

                val uri = windowRequest.url
                val popupSession = windowRequest.prepare()
                val openerUrl = currentUrl.value
                val openerHost = try {
                    java.net.URI(openerUrl).host ?: openerUrl
                } catch (e: Exception) { openerUrl }

                val isWhitelisted = isHostMatch(openerHost, whitelistState.value)
                val isBlacklisted = isHostMatch(openerHost, blacklistState.value)
                val shouldBlock = when {
                    isWhitelisted -> false
                    isBlacklisted -> true
                    else -> blockPopupsState.value
                }

                scope.launch(Dispatchers.Main) {
                    if (shouldBlock) {
                        if (isBlacklisted) {
                            Log.d(TAG, "Popup silently blocked (blacklist) from $openerHost: $uri")
                            try { popupSession.close() } catch (_: Exception) {}
                        } else {
                            Log.d(TAG, "Popup blocked from $openerHost: $uri")
                            pendingPopup.value = PendingPopup(
                                openerHost = openerHost,
                                popupUrl = uri,
                                engineSession = popupSession,
                            )
                        }
                    } else {
                        Log.d(TAG, "Auto-opening new tab for popup: $uri")
                        val tabId = tabManager.createTab(
                            url = uri,
                            store = store,
                            parentId = selectedTabState.value?.id,
                            select = true
                        )
                        // EngineMiddleware registers its EngineObserver on link —
                        // URL/title/nav/state sync and crash handling for free.
                        store.dispatch(
                            EngineAction.LinkEngineSessionAction(tabId, popupSession, skipLoading = true)
                        )
                    }
                }
            }

            // ── Downloads (XPI install / torrent / general) ───────────

            override fun onExternalResource(
                url: String,
                fileName: String?,
                contentLength: Long?,
                contentType: String?,
                cookie: String?,
                userAgent: String?,
                isPrivate: Boolean,
                skipConfirmation: Boolean,
                openInApp: Boolean,
                response: Response?
            ) {
                Log.d(TAG, "Download intercepted: $url, type: $contentType, file: $fileName")

                if (url.endsWith(".xpi") || contentType == "application/x-xpinstall") {
                    Log.d(TAG, "XPI detected, installing addon from: $url")
                    onXpiDetected(url)
                } else if (url.endsWith(".torrent") || contentType == "application/x-bittorrent") {
                    Log.d(TAG, ".torrent file detected: $url")
                    scope.launch(Dispatchers.IO) {
                        try {
                            // Simple URL connection to download the small .torrent file into memory
                            val bytes = java.net.URL(url).readBytes()
                            withContext(Dispatchers.Main) {
                                onTorrentDownloaded(bytes)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download .torrent file", e)
                        }
                    }
                } else {
                    scope.launch(Dispatchers.Main) {
                        if (pendingDownload.value?.url != url) {
                            pendingDownload.value = PendingDownload(
                                url = url,
                                fileName = fileName,
                                contentType = contentType,
                                userAgent = userAgent,
                                cookie = cookie,
                                referer = currentUrl.value
                            )
                        }
                    }
                }
            }

            // ── Prompts (alert / confirm / prompt / <select>) ─────────

            override fun onPromptRequest(promptRequest: PromptRequest) {
                Handler(Looper.getMainLooper()).post {
                    showPrompt(promptRequest)
                }
            }

            private fun showPrompt(promptRequest: PromptRequest) {
                when (promptRequest) {
                    is PromptRequest.SingleChoice ->
                        showSingleChoice(promptRequest.choices, promptRequest.onConfirm, promptRequest.onDismiss)

                    is PromptRequest.MenuChoice ->
                        showSingleChoice(promptRequest.choices, promptRequest.onConfirm, promptRequest.onDismiss)

                    is PromptRequest.MultipleChoice -> {
                        val flat = flattenChoices(promptRequest.choices)
                        val labels = flat.map { it.label }.toTypedArray()
                        val checked = flat.map { it.selected }.toBooleanArray()
                        AlertDialog.Builder(context)
                            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                                checked[which] = isChecked
                            }
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                promptRequest.onConfirm(
                                    flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()
                                )
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> promptRequest.onDismiss() }
                            .setOnCancelListener { promptRequest.onDismiss() }
                            .show()
                    }

                    is PromptRequest.Alert -> {
                        AlertDialog.Builder(context)
                            .setTitle(promptRequest.title.takeIf { it.isNotBlank() })
                            .setMessage(promptRequest.message)
                            .setPositiveButton(android.R.string.ok) { _, _ -> promptRequest.onConfirm(false) }
                            .setOnCancelListener { promptRequest.onDismiss() }
                            .show()
                    }

                    is PromptRequest.Confirm -> {
                        val builder = AlertDialog.Builder(context)
                            .setTitle(promptRequest.title.takeIf { it.isNotBlank() })
                            .setMessage(promptRequest.message)
                            .setOnCancelListener { promptRequest.onDismiss() }

                        if (promptRequest.positiveButtonTitle.isNotBlank()) {
                            builder.setPositiveButton(promptRequest.positiveButtonTitle) { _, _ -> promptRequest.onConfirmPositiveButton(false) }
                        } else {
                            builder.setPositiveButton(android.R.string.ok) { _, _ -> promptRequest.onConfirmPositiveButton(false) }
                        }

                        if (promptRequest.negativeButtonTitle.isNotBlank()) {
                            builder.setNegativeButton(promptRequest.negativeButtonTitle) { _, _ -> promptRequest.onConfirmNegativeButton(false) }
                        } else {
                            builder.setNegativeButton(android.R.string.cancel) { _, _ -> promptRequest.onConfirmNegativeButton(false) }
                        }

                        builder.show()
                    }

                    is PromptRequest.TextPrompt -> {
                        val input = EditText(context).apply {
                            inputType = InputType.TYPE_CLASS_TEXT
                            setText(promptRequest.inputValue)
                        }
                        AlertDialog.Builder(context)
                            .setTitle(promptRequest.title.takeIf { it.isNotBlank() })
                            .setMessage(promptRequest.inputLabel.takeIf { it.isNotBlank() })
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                promptRequest.onConfirm(false, input.text.toString())
                            }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> promptRequest.onDismiss() }
                            .setOnCancelListener { promptRequest.onDismiss() }
                            .show()
                    }

                    is PromptRequest.BeforeUnload -> {
                        AlertDialog.Builder(context)
                            .setTitle(promptRequest.title.takeIf { it.isNotBlank() } ?: "Leave site?")
                            .setMessage("Changes you made may not be saved.")
                            .setPositiveButton("Leave") { _, _ -> promptRequest.onLeave() }
                            .setNegativeButton("Stay") { _, _ -> promptRequest.onStay() }
                            .setOnCancelListener { promptRequest.onDismiss() }
                            .show()
                    }

                    else -> {
                        // Unhandled prompt types (file pickers, auth, color, date,
                        // logins, …) — dismiss so the page doesn't hang waiting.
                        (promptRequest as? PromptRequest.Dismissible)?.onDismiss?.invoke()
                    }
                }
            }

            private fun showSingleChoice(
                choices: Array<Choice>,
                onConfirm: (Choice) -> Unit,
                onDismiss: () -> Unit
            ) {
                val flat = flattenChoices(choices)
                val labels = flat.map { it.label }.toTypedArray()
                val preSelected = flat.indexOfFirst { it.selected }.coerceAtLeast(0)
                AlertDialog.Builder(context)
                    .setSingleChoiceItems(labels, preSelected) { dialog, which ->
                        onConfirm(flat[which])
                        dialog.dismiss()
                    }
                    .setOnCancelListener { onDismiss() }
                    .show()
            }

            /**
             * Flattens a Choice array, recursing into optgroup children.
             * Separator/group-header entries are skipped — they have no
             * selectable value.
             */
            private fun flattenChoices(choices: Array<Choice>): List<Choice> {
                val flat = mutableListOf<Choice>()
                for (c in choices) {
                    if (!c.isASeparator && !c.isGroupType) flat.add(c)
                    c.children?.let { flat.addAll(flattenChoices(it)) }
                }
                return flat
            }
        }

        session.register(observer)
        onDispose { session.unregister(observer) }
    }
}

/** Extracts the CN value from an X.500 principal name string. */
private fun parseCN(principal: String): String? =
    principal.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=") }
        ?.removePrefix("CN=")

private fun isHostMatch(host: String, list: Set<String>): Boolean {
    val trimmedHost = host.trim().lowercase()
    if (trimmedHost.isBlank()) return false
    return list.any { exception ->
        val trimmedException = exception.trim().lowercase()
        trimmedException.isNotBlank() && (trimmedHost == trimmedException || trimmedHost.endsWith(".$trimmedException"))
    }
}

private const val TAG = "SessionObserver"
