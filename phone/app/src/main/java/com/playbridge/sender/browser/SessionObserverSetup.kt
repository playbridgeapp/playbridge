package com.playbridge.sender.browser

import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import org.mozilla.geckoview.GeckoSessionSettings
import androidx.compose.runtime.LaunchedEffect

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
    isSecureConnection: MutableState<Boolean>,
    onXpiDetected: (String) -> Unit,
    onVideoHashDetected: (String) -> Unit
) {
    // Desktop Mode User Agent
    val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36"
    
    // React to Desktop Mode changes
    LaunchedEffect(isDesktopMode, session) {
        val gs = (session as? GeckoEngineSession)?.let { 
             try {
                val field = GeckoEngineSession::class.java.getDeclaredField("geckoSession")
                field.isAccessible = true
                field.get(it) as? GeckoSession
             } catch(e: Exception) { null }
        }
        
        gs?.let { geckoSession ->
            if (isDesktopMode) {
                geckoSession.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                geckoSession.settings.userAgentOverride = desktopUserAgent
                Log.d(TAG, "Enabled Desktop Mode")
            } else {
                geckoSession.settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                geckoSession.settings.userAgentOverride = null // Reset to default
                Log.d(TAG, "Disabled Desktop Mode")
            }
            // Reload to apply changes if content is loaded
            if (currentUrl.value != "about:blank") {
                session.reload()
            }
        }
    }
    DisposableEffect(session) {
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
                    VideoDetector.clear()
                    Log.d(TAG, "Cleared detected videos - navigated from $previousBaseUrl to $baseUrl")
                }

                previousUrl.value = url

                // Check for playbridge-video hash signal from content script
                if (url.contains("#playbridge-video=")) {
                    onVideoHashDetected(url)
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
                val match = Regex("\\[PlayBridge:(\\d+)\\]").find(title)
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

                    val navProxy = java.lang.reflect.Proxy.newProxyInstance(
                        GeckoSession.NavigationDelegate::class.java.classLoader,
                        arrayOf(GeckoSession.NavigationDelegate::class.java)
                    ) { _, method, args ->
                        if (method.name == "onNewSession" && args != null && args.size >= 2) {
                            val uri = args[1] as? String
                            if (uri != null) {
                                Log.d(TAG, "Opening new tab for: $uri")
                                scope.launch(Dispatchers.Main) {
                                    tabManager.createTab(uri, store, parentId = selectedTab?.id)
                                }
                                return@newProxyInstance GeckoResult.fromValue(null)
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
                            try {
                                if (args != null) method.invoke(existingContent, *args)
                                else method.invoke(existingContent)
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        } as GeckoSession.ContentDelegate
                        gs.contentDelegate = contentProxy
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
                            Log.d(TAG, "Security changed: isSecure=${securityInfo.isSecure}, host=${securityInfo.host}")
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
            }
        }
    }
}

private const val TAG = "SessionObserver"
