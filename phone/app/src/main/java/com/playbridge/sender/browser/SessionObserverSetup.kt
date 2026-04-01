package com.playbridge.sender.browser

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.fetch.Response
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import com.playbridge.sender.data.history.HistoryDao
import com.playbridge.sender.data.history.HistoryEntity
import androidx.compose.runtime.LaunchedEffect

private val REGEX_PLAYBRIDGE_TITLE = Regex("\\[PlayBridge:(\\d+)\\]")

/**
 * Sets up the [EngineSession.Observer] and GeckoSession delegate proxies
 * (NavigationDelegate, ContentDelegate) for the active browser session.
 *
 * This composable encapsulates ~300 lines that were previously inline inside
 * `BrowserActivity.onCreate`.
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
    browserCanGoBack: MutableState<Boolean>,
    browserCanGoForward: MutableState<Boolean>,
    contextMenuUrl: MutableState<String?>,
    previousUrl: MutableState<String>,
    historyDao: HistoryDao,
    pendingDownload: MutableState<PendingDownload?>,
    isDesktopMode: Boolean,
    detectVideosEnabled: Boolean,
    isSecureConnection: MutableState<Boolean>,
    siteSecurityInfo: MutableState<SiteSecurityInfo?>,
    pendingPopup: MutableState<PendingPopup?>,
    onXpiDetected: (String) -> Unit,
    onMagnetDetected: (String) -> Unit,
    onTorrentDownloaded: (ByteArray) -> Unit,
    onVideoHashDetected: (String, String) -> Unit,  // (url, kotlinTabId)
    onFullScreenChange: (Boolean) -> Unit
) {
    val context = LocalContext.current

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
            override fun onLocationChange(url: String, hasUserGesture: Boolean) {
                // Update store state to keep it in sync
                if (selectedTab != null) {
                    store.dispatch(
                        ContentAction.UpdateUrlAction(
                            sessionId = selectedTab.id,
                            url = url
                        )
                    )
                }

                // Get base URL without hash/fragment
                val baseUrl = url.substringBefore("#")
                val previousBaseUrl = previousUrl.value.substringBefore("#")

                // Record history
                if (baseUrl != previousBaseUrl && !url.startsWith("about:")) {
                    scope.launch(Dispatchers.IO) {
                        val title = selectedTab?.content?.title
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

                // Clear detected videos only when navigating to a different page
                if (baseUrl != previousBaseUrl && previousBaseUrl.isNotEmpty()) {
                    if (selectedTab != null) {
                        VideoDetector.clearTab(selectedTab.id)
                        Log.d(TAG, "Cleared detected videos for tab ${selectedTab.id} - navigated from $previousBaseUrl to $baseUrl")
                    }
                }

                previousUrl.value = url

                // Check for playbridge-video hash signal from content script
                if (detectVideosEnabled && url.contains("#playbridge-video=")) {
                    val tabId = selectedTab?.id ?: "_unknown"
                    onVideoHashDetected(url, tabId)
                }
            }

            override fun onLoadingStateChange(loading: Boolean) {
                isLoading.value = loading
            }

            override fun onNavigationStateChange(canGoBack: Boolean?, canGoForward: Boolean?) {
                canGoBack?.let { browserCanGoBack.value = it }
                canGoForward?.let { browserCanGoForward.value = it }
            }

            // Detect video count from page title [PlayBridge:X] marker
            override fun onTitleChange(title: String) {
                Log.d(TAG, "Title changed: $title")
                if (selectedTab != null) {
                    store.dispatch(
                        ContentAction.UpdateTitleAction(
                            sessionId = selectedTab.id,
                            title = title
                        )
                    )
                }
                val match = REGEX_PLAYBRIDGE_TITLE.find(title)
                if (match != null) {
                    val count = match.groupValues[1].toIntOrNull() ?: 0
                    Log.d(TAG, "PlayBridge video count detected: $count")
                }
            }

            // Intercept downloads - this catches XPI files from AMO
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

                // Check if this is an XPI file (Firefox extension)
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
                    // General download interception
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
        }
        session.register(observer)

        // Set up GeckoSession delegates using reflection
        val geckoEngineSession = session as? GeckoEngineSession

        var originalNavDelegate: GeckoSession.NavigationDelegate? = null
        var originalContentDelegate: GeckoSession.ContentDelegate? = null
        var originalPermissionDelegate: GeckoSession.PermissionDelegate? = null
        var geckoSessionInstance: GeckoSession? = null

        if (geckoEngineSession != null) {
            try {
                val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                field.isAccessible = true
                val internalSession = field.get(geckoEngineSession) as? GeckoSession
                geckoSessionInstance = internalSession

                internalSession?.let { gs ->
                    // NavigationDelegate proxy for onNewSession (open in new tab)
                    val existingNav = gs.navigationDelegate
                    originalNavDelegate = existingNav

                    val popupPrefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)

                    val navProxy = java.lang.reflect.Proxy.newProxyInstance(
                        GeckoSession.NavigationDelegate::class.java.classLoader,
                        arrayOf(GeckoSession.NavigationDelegate::class.java)
                    ) { _, method, args ->
                        if (method.name == "onNewSession" && args != null && args.size >= 2) {
                            val uri = args[1] as? String
                            if (uri != null) {
                                val openerUrl = selectedTab?.content?.url ?: ""
                                val openerHost = try {
                                    java.net.URI(openerUrl).host ?: openerUrl
                                } catch (e: Exception) { openerUrl }

                                val blockPopups = popupPrefs.getBoolean("block_popups", true)
                                val whitelist = popupPrefs.getStringSet("popup_whitelist", emptySet()) ?: emptySet()
                                val isWhitelisted = whitelist.contains(openerHost)

                                if (blockPopups && !isWhitelisted) {
                                    Log.d(TAG, "Popup blocked from $openerHost: $uri")
                                    val rawGeckoSession = GeckoSession()
                                    val newEngineSession = GeckoEngineSession(
                                        runtime = Components.runtime,
                                        geckoSessionProvider = { rawGeckoSession },
                                        openGeckoSession = false
                                    )
                                    scope.launch(Dispatchers.Main) {
                                        pendingPopup.value = PendingPopup(
                                            openerHost = openerHost,
                                            popupUrl = uri,
                                            rawGeckoSession = rawGeckoSession,
                                            engineSession = newEngineSession,
                                        )
                                    }
                                    return@newProxyInstance GeckoResult.fromValue(rawGeckoSession)
                                }

                                Log.d(TAG, "Auto-opening new tab for popup: $uri")
                                val rawGeckoSession = GeckoSession()
                                val newEngineSession = GeckoEngineSession(
                                    runtime = Components.runtime,
                                    geckoSessionProvider = { rawGeckoSession },
                                    openGeckoSession = false
                                )
                                scope.launch(Dispatchers.Main) {
                                    val tabId = tabManager.createTab(
                                        url = uri,
                                        store = store,
                                        parentId = selectedTab?.id,
                                        select = true
                                    )
                                    tabManager.sessions[tabId] = newEngineSession
                                }
                                return@newProxyInstance GeckoResult.fromValue(rawGeckoSession)
                            }
                        }

                        if (method.name == "onLoadError" && args != null && args.size >= 3) {
                            val uri = args[1] as? String ?: "unknown"
                            val error = args[2] // WebRequestError
                            Log.e(TAG, "Load error for $uri: $error")

                            // error is a WebRequestError — it has a code, but the error URI must not be http/https
                            // we'll return a data URI generated from ErrorPageUtils
                            return@newProxyInstance GeckoResult.fromValue(
                                ErrorPageUtils.generateErrorPage(uri, "NETWORK_ERROR", error.toString())
                            )
                        }
                        
                        if (method.name == "onLoadRequest" && args != null && args.size >= 2) {
                            val request = args[1]
                            if (request != null) {
                                try {
                                    val uriField = request.javaClass.getField("uri")
                                    val uri = uriField.get(request) as? String
                                    if (uri != null && uri.startsWith("magnet:?")) {
                                        Log.d(TAG, "Intercepted magnet link: $uri")
                                        scope.launch(Dispatchers.Main) {
                                            onMagnetDetected(uri)
                                        }
                                        return@newProxyInstance GeckoResult.fromValue(org.mozilla.geckoview.AllowOrDeny.DENY)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error inspecting load request", e)
                                }
                            }
                        }

                        // Forward to original delegate for everything else
                        if (existingNav != null) {
                            try {
                                if (args != null) method.invoke(existingNav, *args)
                                else method.invoke(existingNav)
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        } else {
                            null
                        }
                    } as GeckoSession.NavigationDelegate
                    gs.navigationDelegate = navProxy

                    // ContentDelegate proxy for context menus
                    val existingContent = gs.contentDelegate
                    originalContentDelegate = existingContent

                    if (existingContent != null) {
                        val contentProxy = java.lang.reflect.Proxy.newProxyInstance(
                            GeckoSession.ContentDelegate::class.java.classLoader,
                            arrayOf(GeckoSession.ContentDelegate::class.java)
                        ) { _, method, args ->
                            if (method.name == "onContextMenuItemSelected" && args != null && args.size >= 2) {
                                val item = args[1]
                                if (item != null) {
                                    try {
                                        val idField = item.javaClass.getField("id")
                                        val id = idField.getInt(item)
                                        val idOpenInNewTabField = item.javaClass.getField("ID_OPEN_IN_NEW_TAB")
                                        val idOpenInNewTab = idOpenInNewTabField.getInt(null)
                                        if (id == idOpenInNewTab) {
                                            return@newProxyInstance true
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            if (method.name == "onContextMenu" && args != null && args.size >= 4) {
                                val element = args[3]
                                if (element != null) {
                                    try {
                                        val linkUriField = element.javaClass.getField("linkUri")
                                        val linkUri = linkUriField.get(element) as? String
                                        if (linkUri != null) {
                                            contextMenuUrl.value = linkUri
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                            if (method.name == "onFullScreen" && args != null && args.size >= 2) {
                                val fullScreen = args[1] as? Boolean ?: false
                                scope.launch(Dispatchers.Main) {
                                    onFullScreenChange(fullScreen)
                                }
                            }
                            try {
                                if (args != null) method.invoke(existingContent, *args)
                                else method.invoke(existingContent)
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        } as GeckoSession.ContentDelegate
                        gs.contentDelegate = contentProxy
                    } else {
                        // No existing content delegate — create a minimal one for fullscreen
                        val fullscreenDelegate = object : GeckoSession.ContentDelegate {
                            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                                scope.launch(Dispatchers.Main) {
                                    onFullScreenChange(fullScreen)
                                }
                            }
                        }
                        gs.contentDelegate = fullscreenDelegate
                    }
                    
                    originalPermissionDelegate = gs.permissionDelegate
                    gs.permissionDelegate = object : GeckoSession.PermissionDelegate {
                        override fun onContentPermissionRequest(
                            session: GeckoSession,
                            perm: GeckoSession.PermissionDelegate.ContentPermission
                        ): GeckoResult<Int> {
                            return when (perm.permission) {
                                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                                GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                                GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                                    Log.d(TAG, "Granting media permission: ${perm.permission} for ${perm.uri}")
                                    GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                                }
                                else -> GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                            }
                        }
                    }

                    // ProgressDelegate for Security/SSL status
                    gs.progressDelegate = object : GeckoSession.ProgressDelegate {
                        override fun onPageStart(session: GeckoSession, url: String) {
                            isLoading.value = true
                        }
                        
                        override fun onPageStop(session: GeckoSession, success: Boolean) {
                            isLoading.value = false
                        }

                        override fun onSecurityChange(
                            session: GeckoSession,
                            securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
                        ) {
                            isSecureConnection.value = securityInfo.isSecure

                            val cert = securityInfo.certificate
                            val certIssuer = cert?.issuerX500Principal?.name?.let { parseCN(it) }
                            val certValidUntil = cert?.notAfter?.let {
                                java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(it)
                            }
                            siteSecurityInfo.value = SiteSecurityInfo(
                                isSecure = securityInfo.isSecure,
                                host = securityInfo.host ?: "",
                                certIssuer = certIssuer,
                                certValidUntil = certValidUntil
                            )

                            Log.d(TAG, "Security changed: isSecure=${securityInfo.isSecure}, host=${securityInfo.host}, issuer=$certIssuer")
                        }
                        
                        override fun onProgressChange(session: GeckoSession, progress: Int) {
                            // Optional: update progress bar precision
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup GeckoSession delegates", e)
            }
        }

        onDispose {
            session.unregister(observer)
            // Restore original delegates
            geckoSessionInstance?.let { gs ->
                if (originalNavDelegate != null) gs.navigationDelegate = originalNavDelegate
                if (originalContentDelegate != null) gs.contentDelegate = originalContentDelegate
                if (originalPermissionDelegate != null) gs.permissionDelegate = originalPermissionDelegate
            }
        }
    }
}

/** Extracts the CN value from an X.500 principal name string. */
private fun parseCN(principal: String): String? =
    principal.split(",")
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=") }
        ?.removePrefix("CN=")

private const val TAG = "SessionObserver"
