package com.playbridge.sender.browser
import androidx.core.content.edit
import com.playbridge.sender.library.*
import com.playbridge.sender.cast.*
import com.playbridge.sender.ui.TvDeviceGuard
import com.playbridge.sender.ui.WrongDeviceDialog

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.GeckoEngineView
import mozilla.components.browser.engine.gecko.GeckoEngineSession
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.fetch.Response
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.state.TabSessionState
import mozilla.components.browser.state.state.ContentState
import java.util.UUID
import mozilla.components.lib.state.Store

import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.history.BookmarksScreen
import com.playbridge.sender.history.CastHistoryScreen
import com.playbridge.sender.history.HistoryScreen
import com.playbridge.sender.settings.SettingsScreen
import com.playbridge.sender.downloads.DownloadConfirmDialog
import com.playbridge.sender.downloads.DownloadUtils
import com.playbridge.sender.downloads.DownloadsScreen
import com.playbridge.sender.downloads.PendingDownload
import com.playbridge.sender.downloads.PendingPopup
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.ConnectionScreen
import com.playbridge.sender.ui.DashboardScreen
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import mozilla.components.lib.state.ext.flow
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.history.HistoryEntity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import com.playbridge.sender.data.history.TabEntity
import com.playbridge.sender.data.history.HistoryDatabase
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.library.LibraryViewModel
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.network.MediaNetworkPolicy
import playbridge.PlaylistPayload
import playbridge.PlayPayload

private data class PendingPageCast(
    val items: List<PlayPayload>,
    val startIndex: Int,
    val playlistMetadata: playbridge.VisualMetadata?,
    val skipPreplay: Boolean,
    val origin: String,
    val tabId: Int,
    val navigationGeneration: Long,
    val stage: PageCastConsentStage,
    val requestedPrivateOrigins: Set<String>,
)

private data class PendingLinkedPageCast(
    val request: LinkedPageCastOpenRequest,
    val stage: PageCastConsentStage,
    val requestedPrivateOrigins: Set<String>,
)

private data class PendingLinkedOperation(
    val message: org.json.JSONObject,
    val origin: String,
    val tabId: Int,
    val navigationGeneration: Long,
    val requestedPrivateOrigins: Set<String>,
)

private enum class PageCastConsentStage { WEBSITE, PRIVATE_ORIGINS }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnimatedMenuItem(
    index: Int,
    onClick: (() -> Unit)? = null,
    content: @Composable (onClick: () -> Unit) -> Unit
) {
    val alpha = remember { Animatable(0f) }
    val slide = remember { Animatable(50f) } // start 50px down
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val delay = index * 30L // 30ms stagger
        kotlinx.coroutines.delay(delay)
        launch {
            alpha.animateTo(
                1f,
                animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
            )
        }
        launch {
            slide.animateTo(
                0f,
                animationSpec = tween(durationMillis = 300, easing = LinearOutSlowInEasing)
            )
        }
    }

    val wrappedOnClick: () -> Unit = {
        if (onClick != null) {
            scope.launch {
                launch {
                    scale.animateTo(0.95f, tween(100))
                    scale.animateTo(1f, tween(100))
                }
                kotlinx.coroutines.delay(150) // Wait for animation and ripple
                onClick()
            }
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha.value
            this.translationY = slide.value
            this.scaleX = scale.value
            this.scaleY = scale.value
        }
    ) {
        content(wrappedOnClick)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BrowserActivity"
    }

    private val connectionViewModel: ConnectionViewModel by viewModel()
    private val connectionCoordinator: ConnectionCoordinator by inject()
    private val linkedPageCastCoordinator: LinkedPageCastCoordinator by inject()
    private val externalQueueCoordinator: com.playbridge.sender.connection.ExternalQueueCoordinator by inject()
    private val addonRepository: com.playbridge.sender.data.library.AddonRepository by inject()
    private val downloadRepository: com.playbridge.sender.downloads.engine.DownloadRepository by inject()
    private val historyDao: com.playbridge.sender.data.history.HistoryDao by inject()
    private val searchHistoryDao: com.playbridge.sender.data.history.SearchHistoryDao by inject()
    private val downloadDao: com.playbridge.sender.data.downloads.DownloadDao by inject()
    private val browserViewModel: com.playbridge.sender.browser.BrowserViewModel by viewModel()
    private val updateChecker: com.playbridge.sender.update.UpdateChecker by inject()
    private var pendingLinkedDeviceRequest: LinkedPageCastOpenRequest? = null

    /**
     * Third-party protocols do not expose PlayBridge's native playlist command. Keep the
     * ordered list on the phone and let the external queue coordinator advance it as each
     * item finishes.
     */
    private fun startExternalPlaylist(items: List<PlayPayload>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        externalQueueCoordinator.start(
            com.playbridge.sender.connection.TvEpisodeQueuePlan(
                streamType = "playlist",
                forcedSource = null,
                bingeGroup = null,
                startIndex = startIndex.coerceIn(items.indices),
                items = items.map { payload ->
                    com.playbridge.sender.connection.TvQueueEpisode(
                        streamId = "",
                        template = payload,
                    )
                },
            )
        )
    }

    /**
     * Phase-2 cutover: route browser/cast-sheet downloads through the new WorkManager
     * engine ([downloadRepository]) instead of the legacy `DownloadUtils`. HLS auto-picks
     * the top variant for now (quality-picker dialog can return in a later slice).
     */
    private fun enqueueEngineDownload(
        url: String,
        fileName: String?,
        contentType: String?,
        userAgent: String?,
        cookie: String?,
        referer: String?,
        pageTitle: String?,
    ) {
        val headers = buildMap {
            userAgent?.let { put("User-Agent", it) }
            cookie?.let { put("Cookie", it) }
            referer?.let { put("Referer", it) }
        }
        val isHls = url.contains(".m3u8") || contentType?.contains("mpegurl", ignoreCase = true) == true
        val title = deriveDownloadTitle(fileName, pageTitle, url)
        val request = com.playbridge.sender.downloads.engine.DownloadRequest(
            id = java.util.UUID.randomUUID().toString(),
            url = url,
            title = title,
            kind = if (isHls) com.playbridge.sender.downloads.engine.DownloadKind.HLS
                   else com.playbridge.sender.downloads.engine.DownloadKind.FILE,
            mimeType = contentType,
            headers = headers,
        )
        lifecycleScope.launch { downloadRepository.enqueue(request) }
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    /**
     * Executes the Clear Data sheet's selection (Firefox-style "Delete browsing
     * data"). Gecko-side data (cookies, caches, permissions) is cleared through
     * the supported StorageController API — never by deleting cache dirs, which
     * could corrupt Gecko's open files and would also nuke the compiled
     * ad-block rulesets. App-side data (tabs, history, downloads) lives in Room.
     */
    private fun performClearData(selection: ClearDataSelection) {
        // Open tabs first: GeckoView recommends closing sessions before clearing
        // so live pages don't immediately re-accumulate the data being deleted.
        if (selection.openTabs) {
            val store = Components.store
            store.state.tabs.map { it.id }.forEach { Components.tabManager.closeTab(it, store) }
            // Land on a fresh tab (Fenix-style) instead of a dead browser view.
            Components.tabManager.createTab("about:blank", store)
        }

        var flags = 0L
        if (selection.cookiesAndSiteData) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.COOKIES or
                org.mozilla.geckoview.StorageController.ClearFlags.DOM_STORAGES or
                org.mozilla.geckoview.StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (selection.cachedImagesAndFiles) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.ALL_CACHES
        }
        if (selection.sitePermissions) {
            flags = flags or org.mozilla.geckoview.StorageController.ClearFlags.PERMISSIONS
        }
        // clearData is async (returns a GeckoResult) — kick it off now, await below so
        // the confirmation toast doesn't fire before the data is actually gone.
        val geckoClear = if (flags != 0L) {
            Components.runtime.storageController.clearData(flags)
        } else null

        lifecycleScope.launch {
            if (selection.browsingHistory) {
                historyDao.clear()
                searchHistoryDao.deleteAll()
            }
            if (selection.downloads) {
                withContext(Dispatchers.IO) {
                    // cancel() stops any running worker, wipes the temp dir, and
                    // drops the Room row. Files already published to MediaStore
                    // (the user's saved videos) are intentionally left alone.
                    downloadDao.observeAll().first().forEach {
                        downloadRepository.cancel(it.id, removeFiles = true)
                    }
                }
            }
            val geckoOk = geckoClear?.awaitSuccess("clearData") ?: true
            Toast.makeText(
                this@BrowserActivity,
                if (geckoOk) "Browsing data deleted" else "Some site data could not be cleared",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Suspends until this GeckoResult completes; true on success, false (never throws)
     * on failure. Must be called from a Looper thread (Main here) — GeckoResult
     * dispatches its callbacks via the calling thread's Handler.
     */
    private suspend fun <T> org.mozilla.geckoview.GeckoResult<T>.awaitSuccess(what: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            accept(
                { if (cont.isActive) cont.resume(true) { _, _, _ -> } },
                { e ->
                    android.util.Log.w("BrowserActivity", "Gecko $what failed", e)
                    if (cont.isActive) cont.resume(false) { _, _, _ -> }
                }
            )
        }

    /**
     * Best filename we can get: an explicit download filename, else a filename embedded in the
     * page title (e.g. pixeldrain's "Movie.mp4 ~ collection ~ pixeldrain"), else the page title
     * trimmed at its separator, else the URL tail. The extension is finalised in DownloadWorker.
     */
    private fun deriveDownloadTitle(fileName: String?, pageTitle: String?, url: String): String {
        fileName?.takeIf { it.isNotBlank() }?.let { return it }
        pageTitle?.let { pt ->
            val embedded = Regex(
                """[^~|/\\\n]+\.(mp4|mkv|m4v|webm|avi|mov|ts|m2ts|flv|wmv|mp3|m4a|aac|flac|wav|ogg|opus)""",
                RegexOption.IGNORE_CASE,
            ).find(pt)?.value?.trim()
            if (!embedded.isNullOrBlank()) return embedded
            val trimmed = pt.split(" ~ ", " | ", " — ", " - ").firstOrNull()?.trim()
            if (!trimmed.isNullOrBlank()) return trimmed
        }
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "download" }
    }

    /**
     * Process-wide singleton — live EngineSessions survive Activity recreation
     * (theme change, system-initiated recreation). See [Components.tabManager].
     */
    private val tabManager: TabManager get() = Components.tabManager

    /**
     * A web URL captured from the launching/incoming Intent (a link tapped in another app),
     * awaiting consumption by the Compose tree once tabs are restored. Compose state so
     * [onNewIntent] updates trigger recomposition of the consuming effect.
     */
    private val pendingLinkUrl = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseLinkIntent(intent)?.let { pendingLinkUrl.value = it }
    }

    /** Extracts an http/https URL from a VIEW intent, or null if it isn't one we handle. */
    private fun parseLinkIntent(intent: Intent?): String? {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return when (data.scheme?.lowercase()) {
            "http", "https" -> data.toString()
            else -> null
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d("PB_STARTUP", "onResume: existingSessions=${tabManager.sessions.size}")
        lifecycleScope.launch(Dispatchers.Main) {
            val state = Components.store.state   // read CURRENT state when coroutine runs
            Log.d("PB_STARTUP", "onResume coroutine: storeTabs=${state.tabs.size}, selectedTabId=${state.selectedTabId}, sessionForSelectedTab=${tabManager.sessions[state.selectedTabId] != null}")
            tabManager.syncSessions(state.tabs, state.selectedTabId)
            Log.d("PB_STARTUP", "onResume: syncSessions done — sessions=${tabManager.sessions.size}, sessionForSelectedTab=${tabManager.sessions[state.selectedTabId] != null}")
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("PB_STARTUP", "onStop: storeTabs=${Components.store.state.tabs.size}, sessions=${tabManager.sessions.size}")
    }

    override fun onPause() {
        super.onPause()
        saveTabs()
    }

    private fun saveTabs() {
        val selectedId = Components.store.state.selectedTabId
        val tabs = Components.store.state.tabs
        val allStates = tabManager.captureAllStates()
        browserViewModel.saveTabs(tabs, selectedId, allStates, tabManager.parentIds)
    }

    private fun dispatchLinkedOpen(
        request: LinkedPageCastOpenRequest,
        selectedDevice: TvDevice? = null,
    ) {
        lifecycleScope.launch {
            if (linkedPageCastCoordinator.isOpenCancelled(request.bridgeRequestId)) return@launch
            if (selectedDevice == null) {
                when (connectionViewModel.route.value) {
                    is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice,
                    is com.playbridge.sender.cast.CastSessionManager.Route.External,
                    -> {
                        pendingLinkedDeviceRequest = request
                        Components.requestLinkedDevicePicker()
                        return@launch
                    }
                    is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv -> Unit
                }
            }

            val targetDevice = selectedDevice ?: connectionViewModel.tvDevice.first()
            if (targetDevice == null) {
                linkedPageCastCoordinator.reject(request.bridgeRequestId, "no_receiver")
                return@launch
            }
            val currentDevice = connectionViewModel.tvDevice.first()
            val alreadyConnected = connectionViewModel.connectionState.value is
                WebSocketClient.ConnectionState.Connected &&
                currentDevice?.let {
                    com.playbridge.sender.connection.ConnectionMerge.isSameDevice(it, targetDevice)
                } == true
            if (!alreadyConnected) {
                // DevicePicker already initiated pairing/connection for an explicitly selected
                // target. A retained native route reconnects its exact saved target here.
                if (selectedDevice == null) connectionViewModel.connect(targetDevice)
                withTimeoutOrNull(if (selectedDevice == null) 8_000 else 30_000) {
                    combine(
                        connectionViewModel.tvDevice,
                        connectionViewModel.connectionState,
                    ) { device, state -> device to state }.first { (device, state) ->
                        state is WebSocketClient.ConnectionState.Connected &&
                            device?.let {
                                com.playbridge.sender.connection.ConnectionMerge.isSameDevice(it, targetDevice)
                            } == true
                    }
                }
            }
            val connectedDevice = connectionViewModel.tvDevice.first()
            if (linkedPageCastCoordinator.isOpenCancelled(request.bridgeRequestId)) return@launch
            if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected ||
                connectedDevice?.let {
                    com.playbridge.sender.connection.ConnectionMerge.isSameDevice(it, targetDevice)
                } != true
            ) {
                linkedPageCastCoordinator.reject(request.bridgeRequestId, "connect_failed")
                return@launch
            }
            linkedPageCastCoordinator.open(request, targetDevice)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)



        if (!Components.isEngineInitialized()) {
            Components.initialize(applicationContext)
        }
        VideoDetector.init(applicationContext)

        // Fire-and-forget update check on cold start; only surfaces UI if a newer build exists.
        updateChecker.check(manual = false)

        // Capture a web link that launched us (tapped in another app); consumed in Compose.
        pendingLinkUrl.value = parseLinkIntent(intent)

        // Request notification permission for media controls on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        // Install the bundled video detector extension
        Components.installBundledExtension()

        val libraryViewModel: LibraryViewModel by viewModel()
        val settingsRepository: SettingsRepository by inject()

        // Restore tabs
        // Track whether tab restoration is complete to avoid blank screen
        val storeTabsAtStart = Components.store.state.tabs.size
        val tabsRestoredOrReady = mutableStateOf(Components.store.state.tabs.isNotEmpty())
        Log.d("PB_STARTUP", "onCreate: store has $storeTabsAtStart tabs at start, tabsRestoredOrReady=${tabsRestoredOrReady.value}")

        // One-time legacy SharedPreferences migration block
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val legacyPrefs = getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
            if (legacyPrefs.getBoolean("needs_datastore_migration", true)) {
                settingsRepository.setAutoSwitchToRemote(legacyPrefs.getBoolean("auto_switch_to_remote", true))
                settingsRepository.setMaxAliveTabs(legacyPrefs.getInt("max_alive_tabs", 5))
                
                val audioLang = legacyPrefs.getString("preferred_audio_language", null) 
                    ?: legacyPrefs.getString("preferred_audio_lang", "") ?: ""
                settingsRepository.setPreferredAudioLang(audioLang)
                
                val subLang = legacyPrefs.getString("preferred_subtitle_language", null)
                    ?: legacyPrefs.getString("preferred_subtitle_lang", "") ?: ""
                settingsRepository.setPreferredSubtitleLang(subLang)
                
                settingsRepository.setDefaultVideoQuality(legacyPrefs.getString("default_video_quality", "Auto") ?: "Auto")
                
                val maxBitrateStr = legacyPrefs.getString("auto_stream_max_mbps", null)
                val maxBitrateDouble = maxBitrateStr?.toDoubleOrNull() ?: legacyPrefs.getInt("max_bitrate_cap_mbps", 0).toDouble()
                settingsRepository.setMaxBitrateCapMbps(maxBitrateDouble)
                
                settingsRepository.setTvPlayerMode(legacyPrefs.getString("tv_player_mode", "tv") ?: "tv")
                settingsRepository.setDetectVideos(legacyPrefs.getBoolean("detect_videos", true))
                settingsRepository.setBlockPopups(legacyPrefs.getBoolean("block_popups", true))
                
                legacyPrefs.edit { putBoolean("needs_datastore_migration", false) }
                Log.d("BrowserActivity", "Migrated legacy SharedPreferences to Jetpack DataStore successfully.")
            }
        }

        browserViewModel.restoreTabs(tabManager, Components.store) {
            tabsRestoredOrReady.value = true
        }

        setContent {
            // rememberSaveable + Screen.Saver: survives Activity recreation (memory-pressure
            // destroy while the in-app player / another Activity is in front), so Back
            // returns to the exact screen — e.g. a library detail page — instead of
            // resetting to the persisted main tab.
            var currentScreen by androidx.compose.runtime.saveable.rememberSaveable(
                stateSaver = Screen.Saver
            ) {
                val sp = getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                // First launch (no main screen ever persisted) lands on the Dashboard,
                // which doubles as the home for the one-time onboarding overlay.
                mutableStateOf<Screen>(
                    if (!sp.contains("last_main_screen")) Screen.Dashboard
                    else when (sp.getString("last_main_screen", "browser")) {
                        "library" -> Screen.Library
                        "debrid" ->
                            if (com.playbridge.sender.FlavorConfig.DEBRID_SUPPORTED) Screen.DebridLibrary
                            else Screen.Library
                        else -> Screen.Browser
                    }
                )
            }
            // Tracks the last "main" tab so Settings/overlays know where to return.
            // When we start on the Dashboard (first launch), fall back to Browser so
            // closing the Dashboard has somewhere sensible to go.
            var lastMainScreen by remember {
                mutableStateOf(if (currentScreen == Screen.Dashboard) Screen.Browser else currentScreen)
            }
            // The screen the Remote was opened from, so Back returns there (e.g. Phone Files,
            // Connection) rather than always falling back to the last main tab.
            var remoteOrigin by remember { mutableStateOf<Screen?>(null) }
            var connectionInitialTab by remember { mutableStateOf(0) }
            // Keep the Remote's return target pointed at wherever we actually were last, even
            // when playback auto-switches into the Remote directly (which bypasses
            // onScreenChange and would otherwise leave remoteOrigin stale — e.g. stuck on IPTV).
            LaunchedEffect(currentScreen) {
                if (currentScreen != Screen.Remote) remoteOrigin = currentScreen
                if (currentScreen != Screen.Connection) connectionInitialTab = 0
            }
            // The screen the Dashboard was opened from, so its close (X) returns there.
            var dashboardOrigin by remember { mutableStateOf<Screen?>(null) }
            val clipboardManager = LocalClipboardManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
            val context = LocalContext.current
            var showTvWarning by remember { mutableStateOf(TvDeviceGuard.shouldWarn(context)) }
            if (showTvWarning) {
                WrongDeviceDialog(onDismiss = {
                    TvDeviceGuard.dismiss(context)
                    showTvWarning = false
                })
            }
            // Update-available notify / download / install flow. Gated behind the
            // wrong-device dialog so we never stack two dialogs; it surfaces once that's gone.
            if (!showTvWarning) {
                com.playbridge.sender.update.UpdateGate(updateChecker)
            }
            val connectionState by connectionViewModel.connectionState.collectAsState()
            val activeExternalDevice by connectionViewModel.activeExternalDevice.collectAsState()
            val castRoute by connectionViewModel.route.collectAsState()
            val scope = rememberCoroutineScope()

            // Suggestions State
            var isEditing by remember { mutableStateOf(false) }
            var editUrl by remember { mutableStateOf("") }
            val suggestions by browserViewModel.suggestions.collectAsState()
            // URL bar tap panel state (Chrome-like panel shown before user types)
            var urlBarTapped by remember { mutableStateOf(false) }
            var urlPanelClipboard by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(isEditing) {
                if (isEditing) {
                    urlBarTapped = true
                    val clip = clipboardManager.getText()?.text
                    urlPanelClipboard = if (!clip.isNullOrBlank()) clip else null
                } else {
                    urlBarTapped = false
                    urlPanelClipboard = null
                }
            }

            // Connection ViewModel State
            val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)

            // Session and navigation state from BrowserStore
            val store = Components.store
            var browserState by remember {
                Log.d("PB_STARTUP", "Compose: initialising browserState — store has ${store.state.tabs.size} tabs, selectedTabId=${store.state.selectedTabId}")
                mutableStateOf(store.state)
            }

            // Consume a pending web link once tabs are restored: open it in a fresh tab.
            val pendingLink = pendingLinkUrl.value
            LaunchedEffect(pendingLink, tabsRestoredOrReady.value) {
                if (pendingLink != null && tabsRestoredOrReady.value) {
                    tabManager.createTab(pendingLink, store)
                    currentScreen = Screen.Browser
                    pendingLinkUrl.value = null
                }
            }

            // Debounced persistence: any onStateUpdated or store tab/selection change marks the tabs dirty;
            // we save at most once every 1.5s, plus a final flush on dispose.
            val saveDirtyFlow = remember { kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 16) }
            DisposableEffect(Unit) {
                tabManager.onAnyStateUpdated = { _ -> saveDirtyFlow.tryEmit(Unit) }
                onDispose { tabManager.onAnyStateUpdated = null }
            }
            LaunchedEffect(Unit) {
                var lastSaveJob: kotlinx.coroutines.Job? = null
                saveDirtyFlow.collect {
                    lastSaveJob?.cancel()
                    lastSaveJob = scope.launch {
                        kotlinx.coroutines.delay(1500)
                        saveTabs()
                    }
                }
            }

            // Observe store state changes
            LaunchedEffect(store) {
                Log.d("PB_STARTUP", "Compose: store.flow() collector started")
                var lastSelectedId = browserState.selectedTabId
                var lastTabsIds = browserState.tabs.map { it.id }
                store.flow().collect { state ->
                    val newTabsIds = state.tabs.map { it.id }
                    val newSelected = state.selectedTabId

                    val tabsChanged = newTabsIds != lastTabsIds
                    val selectedChanged = newSelected != lastSelectedId

                    if (tabsChanged || selectedChanged) {
                        Log.d("PB_STARTUP", "Compose: store.flow() tab/selection change — tabsChanged=$tabsChanged, selectedChanged=$selectedChanged. Scheduling save.")
                        saveDirtyFlow.tryEmit(Unit)
                    }

                    if (state.tabs.size != browserState.tabs.size || state.selectedTabId != browserState.selectedTabId) {
                        Log.d("PB_STARTUP", "Compose: store.flow() emitted — tabs=${state.tabs.size}, selectedTabId=${state.selectedTabId}")
                    }
                    // Record selection so the close-tab fallback stack stays in sync
                    // even when tabs are selected via direct store dispatches that
                    // bypass tabManager.selectTab.
                    if (newSelected != null && newSelected != lastSelectedId) {
                        tabManager.recordSelection(newSelected)
                    }

                    lastSelectedId = newSelected
                    lastTabsIds = newTabsIds
                    browserState = state
                }
            }

            LaunchedEffect(tabsRestoredOrReady.value) {
                Log.d("PB_STARTUP", "Compose: tabsRestoredOrReady=${tabsRestoredOrReady.value} — force-syncing browserState from store (${store.state.tabs.size} tabs, selectedTabId=${store.state.selectedTabId})")
                browserState = store.state

                if (tabsRestoredOrReady.value && store.state.tabs.isEmpty()) {
                    Log.d("PB_STARTUP", "Compose: tabsRestored=true and store empty — calling ensureAtLeastOneTab")
                    tabManager.ensureAtLeastOneTab(store)
                }
            }

            // Chrome-hidden mode belongs to the selected tab and intentionally remains
            // session-only: it survives tab switches/navigation, but not an app restart.
            var chromeHiddenTabIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
            val tabIds = browserState.tabs.map { it.id }
            LaunchedEffect(tabIds, browserState.selectedTabId) {
                Log.d("PB_STARTUP", "Compose: syncSessions triggered — tabCount=${browserState.tabs.size}, selectedTabId=${browserState.selectedTabId}")
                tabManager.syncSessions(browserState.tabs, browserState.selectedTabId)
                Log.d("PB_STARTUP", "Compose: syncSessions returned — sessions.keys=${tabManager.sessions.keys.size}")
                // Purge per-tab state for any tab that was externally removed
                // from the store (e.g. via Mozilla Components paths that bypass
                // closeTab). This is the safety net for memory leaks.
                tabManager.reconcileWithStoreTabs(tabIds.toSet())
                chromeHiddenTabIds = chromeHiddenTabIds.intersect(tabIds.toSet())
            }

            val sessions = tabManager.sessions

            val selectedTabId = browserState.selectedTabId
            val selectedTab = browserState.tabs.find { it.id == selectedTabId }
            val isBrowserChromeHidden = selectedTabId?.let(chromeHiddenTabIds::contains) == true
            val session = if (selectedTab != null) sessions[selectedTab.id] else null

            Log.d("PB_STARTUP", "Compose: recompose — browserStateTabs=${browserState.tabs.size}, selectedTabId=$selectedTabId, sessionNull=${session == null}, tabsRestored=${tabsRestoredOrReady.value}, sessionsMapSize=${sessions.size}")

            var pendingDownload by remember { mutableStateOf<PendingDownload?>(null) }

            // Hoisted Tabs screen states
            var isTabsSearchVisible by rememberSaveable { mutableStateOf(false) }
            var isTabsMultiSelectMode by rememberSaveable { mutableStateOf(false) }
            var showTabsCloseAllConfirm by remember { mutableStateOf(false) }

            if (session == null) {
                Log.d("PB_STARTUP", "Compose: session==null path — browserStateTabs=${browserState.tabs.size}, selectedTabId=$selectedTabId, tabsRestored=${tabsRestoredOrReady.value}, sessionsInMap=${sessions.keys.joinToString()}")
                if (currentScreen == Screen.Tabs) {
                    PlayBridgeTheme {
                        Scaffold(
                            topBar = {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Tabs") },
                                    actions = {
                                        IconButton(onClick = {
                                            tabManager.createTab("about:blank", store)
                                            currentScreen = Screen.Browser
                                        }) {
                                            Icon(Icons.Default.Add, "New Tab")
                                        }

                                        var menuExpanded by remember { mutableStateOf(false) }
                                        val playingTabIds = tabManager.playingTabIds

                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, "More options")
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Go to playing tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        playingTabIds.keys.firstOrNull()?.let {
                                                            tabManager.selectTab(it, store)
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                                                    enabled = playingTabIds.isNotEmpty()
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Reopen Closed Tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        tabManager.reopenClosedTab(store)?.let {
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Restore, null) },
                                                    enabled = tabManager.canReopenClosedTab()
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text(if (isTabsSearchVisible) "Hide Search" else "Search Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsSearchVisible = !isTabsSearchVisible
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Select Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsMultiSelectMode = true
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("Close All Tabs", color = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showTabsCloseAllConfirm = true
                                                    },
                                                    leadingIcon = { 
                                                        Icon(
                                                            Icons.Default.Delete, 
                                                            null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        ) 
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        ) { innerPadding ->
                            Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                                TabsScreen(
                                    onTabSelected = { tabId ->
                                        tabManager.selectTab(tabId, store)
                                        currentScreen = Screen.Browser
                                    },
                                    onTabClosed = { tabId ->
                                        tabManager.closeTab(tabId, store)
                                    },
                                    onNewTab = {
                                        tabManager.createTab("about:blank", store)
                                        currentScreen = Screen.Browser
                                    },
                                    onTabDuplicate = { tabId ->
                                        tabManager.duplicateTab(tabId, store)
                                    },
                                    onTabBookmark = { tabId ->
                                         val targetTab = store.state.tabs.find { it.id == tabId }
                                         targetTab?.let { tab ->
                                              val url = tab.content.url
                                              if (url.isNotEmpty() && url != "about:blank") {
                                                  browserViewModel.addBookmark(url, tab.content.title)
                                                  Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                                              }
                                         }
                                     },
                                     isSearchVisibleExternal = isTabsSearchVisible,
                                     onSearchVisibleChangeExternal = { isTabsSearchVisible = it },
                                     isMultiSelectModeExternal = isTabsMultiSelectMode,
                                     onMultiSelectModeChangeExternal = { isTabsMultiSelectMode = it },
                                     showCloseAllConfirmExternal = showTabsCloseAllConfirm,
                                     onCloseAllConfirmChangeExternal = { showTabsCloseAllConfirm = it }
                                )
                            }
                        }
                    }
                    return@setContent
                }

                // No spinner — just return and let the background show through while sessions init
                return@setContent
            }

            // UI state variables — keyed to selectedTabId so they reset when switching tabs
            var currentUrl by remember(selectedTabId) { mutableStateOf(selectedTab?.content?.url ?: "about:blank") }
            var isLoading by remember(selectedTabId) { mutableStateOf(false) }
            
            // Back/Forward states are now read from tabManager.navigationStates
            val navState = tabManager.navigationStates[selectedTabId] ?: TabNavigationState()
            val browserCanGoBack = navState.canGoBack
            val browserCanGoForward = navState.canGoForward

            var previousUrl by remember(selectedTabId) { mutableStateOf(selectedTab?.content?.url ?: "") }
            var menuExpanded by remember { mutableStateOf(false) }
            var showMenuSheet by remember { mutableStateOf(false) }
            val sheetState = rememberModalBottomSheetState()
            var showUserAgentSheet by remember { mutableStateOf(false) }
            val userAgentSheetState = rememberModalBottomSheetState()
            var showClearDataSheet by remember { mutableStateOf(false) }

            val composeScope = rememberCoroutineScope()
            // User preferences via SettingsRepository
            val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsState(initial = true)
            val maxAliveTabs by settingsRepository.maxAliveTabs.collectAsState(initial = 5)
            val userAgentPreset by settingsRepository.userAgentPreset.collectAsState(initial = UserAgentPresets.DEFAULT_ID)
            val customUserAgents by settingsRepository.customUserAgents.collectAsState(initial = emptyList())
            val userAgentOverride by remember(userAgentPreset, customUserAgents) {
                derivedStateOf { UserAgentPresets.resolve(userAgentPreset, customUserAgents) }
            }

            LaunchedEffect(maxAliveTabs) {
                tabManager.maxAliveSessions = maxAliveTabs
                Log.d("TabManager", "Updated maxAliveSessions to $maxAliveTabs from DataStore")
            }
            
            val prefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
            val browserSettings = remember { getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE) }


            // Persist the active main screen so it survives app restarts and Settings navigation
            LaunchedEffect(currentScreen) {
                if (currentScreen is Screen.Browser || currentScreen is Screen.Library || currentScreen is Screen.DebridLibrary || currentScreen is Screen.LibraryDetail) {
                    lastMainScreen = currentScreen
                    prefs.edit { putString("last_main_screen", when (currentScreen) {
                        Screen.Library -> "library"
                        Screen.DebridLibrary -> "debrid"
                        else -> "browser"
                    }) }
                }
            }

            val preferredAudioLang by settingsRepository.preferredAudioLang.collectAsState(initial = "")
            val preferredSubLang by settingsRepository.preferredSubtitleLang.collectAsState(initial = "")
            val defaultVideoQuality by settingsRepository.defaultVideoQuality.collectAsState(initial = "Auto")
            val maxBitrateCapMbpsFlow by settingsRepository.maxBitrateCapMbps.collectAsState(initial = 0.0)
            val maxBitrateCapMbps = maxBitrateCapMbpsFlow.takeIf { it > 0.0 }
            val tvPlayerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")
            var pendingPageCast by remember { mutableStateOf<PendingPageCast?>(null) }
            var pendingLinkedPageCast by remember { mutableStateOf<PendingLinkedPageCast?>(null) }
            var pendingLinkedOperation by remember { mutableStateOf<PendingLinkedOperation?>(null) }
            val pageCastCallbackOwner = remember { Any() }
            DisposableEffect(pageCastCallbackOwner) {
                Components.claimPageCastCallbacks(pageCastCallbackOwner)
                onDispose {
                    pendingLinkedPageCast?.request?.let {
                        linkedPageCastCoordinator.cancelOpen(it.bridgeRequestId, "activity_recreated")
                    }
                    pendingLinkedDeviceRequest?.let {
                        linkedPageCastCoordinator.cancelOpen(it.bridgeRequestId, "activity_recreated")
                    }
                    pendingLinkedOperation?.let {
                        linkedPageCastCoordinator.reject(
                            it.message.optString("bridgeRequestId"),
                            "session_ended",
                        )
                    }
                    pendingLinkedDeviceRequest = null
                    Components.clearPageCastCallbacks(pageCastCallbackOwner)
                }
            }

            val dispatchBridgeCast: (
                List<PlayPayload>,
                Int,
                playbridge.VisualMetadata?,
                Boolean,
                Int,
                Long,
            ) -> Unit =
                { items, startIndex, playlistMetadata, skipPreplay, tabId, navigationGeneration ->
                    linkedPageCastCoordinator.supersedeIfActive()
                    Log.d("BrowserActivity", "Cast requested via Extension Bridge: ${items.size} items, startIndex: $startIndex")
                    lifecycleScope.launch {
                        val currentMode = tvPlayerMode.takeIf { it != "tv" }
                        val playPayloads = items.map { item ->
                            item.copy(
                                player_mode = item.player_mode ?: currentMode,
                                preferred_audio_language = item.preferred_audio_language ?: preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = item.preferred_subtitle_language ?: preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = item.default_video_quality ?: defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = item.max_bitrate_cap_mbps ?: maxBitrateCapMbps,
                            )
                        }
                        if (playPayloads.isEmpty() ||
                            !Components.isCurrentPageNavigation(tabId, navigationGeneration)
                        ) return@launch

                        when (castRoute) {
                            is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice -> {
                                runOnUiThread {
                                    Toast.makeText(this@BrowserActivity, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            is com.playbridge.sender.cast.CastSessionManager.Route.External -> {
                                runOnUiThread {
                                    Toast.makeText(
                                        this@BrowserActivity,
                                        "Website casting requires a PlayBridge receiver",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                return@launch
                            }
                            is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv -> Unit
                        }

                        // Reconnect only the selected native route. A retained PlayBridge socket
                        // must not steal a cast intended for This Device or another protocol.
                        val savedDevice = connectionViewModel.tvDevice.first()
                        if (savedDevice == null) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@BrowserActivity,
                                    "Choose a receiver first",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            return@launch
                        }
                        if (connectionViewModel.connectionState.value !is com.playbridge.sender.connection.WebSocketClient.ConnectionState.Connected) {
                            Log.i("BrowserActivity", "TV disconnected, attempting reconnection before bridge cast")
                            runOnUiThread {
                                Toast.makeText(this@BrowserActivity, "Connecting to TV...", Toast.LENGTH_SHORT).show()
                            }
                            connectionViewModel.connect(savedDevice)
                            withTimeoutOrNull(8000) {
                                combine(
                                    connectionViewModel.tvDevice,
                                    connectionViewModel.connectionState,
                                ) { device, state -> device to state }.first { (device, state) ->
                                    state is WebSocketClient.ConnectionState.Connected &&
                                        device?.let {
                                            com.playbridge.sender.connection.ConnectionMerge.isSameDevice(
                                                it,
                                                savedDevice,
                                            )
                                        } == true
                                }
                            }
                            val connectedDevice = connectionViewModel.tvDevice.first()
                            if (connectionViewModel.connectionState.value !is WebSocketClient.ConnectionState.Connected ||
                                connectedDevice?.let {
                                    com.playbridge.sender.connection.ConnectionMerge.isSameDevice(
                                        it,
                                        savedDevice,
                                    )
                                } != true
                            ) {
                                Log.w("BrowserActivity", "Wait for connection timed out or failed. Aborting cast.")
                                runOnUiThread {
                                    Toast.makeText(this@BrowserActivity, "Could not connect to TV", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                        }

                        val cmd = createPlaylistCommandJson(PlaylistPayload(
                                items = playPayloads,
                                start_index = startIndex,
                                visual_metadata = playlistMetadata,
                                skip_preplay = skipPreplay,
                            ))
                        if (!Components.isCurrentPageNavigation(tabId, navigationGeneration)) {
                            return@launch
                        }
                        connectionViewModel.sendCommandAndRecord(
                            commandJson = cmd,
                            type = "playlist",
                            url = playPayloads[startIndex.coerceIn(playPayloads.indices)].url,
                            title = playPayloads[startIndex.coerceIn(playPayloads.indices)].title ?: "Playlist"
                        )
                    }
                }

            // A page may request a cast only after the user approved that exact web origin.
            LaunchedEffect(
                connectionViewModel,
                castRoute,
                preferredAudioLang,
                preferredSubLang,
                defaultVideoQuality,
                maxBitrateCapMbps,
            ) {
                Components.onBridgeCastRequest = bridgeRequest@ {
                        items,
                        startIndex,
                        playlistMetadata,
                        skipPreplay,
                        origin,
                        tabId,
                        navigationGeneration,
                        declaredPrivateOrigins,
                    ->
                    if (startIndex !in items.indices) return@bridgeRequest
                    lifecycleScope.launch {
                        val requestedPrivateOrigins = withContext(Dispatchers.IO) {
                            val origins = declaredPrivateOrigins
                                .mapNotNullTo(linkedSetOf(), MediaNetworkPolicy::privateOrigin)
                            items.forEach { item ->
                                sequenceOf(item.url)
                                    .plus(item.subtitles.asSequence())
                                    .plus(item.subtitle_resources.asSequence().map { it.url })
                                    .mapNotNull(MediaNetworkPolicy::privateOrigin)
                                    .forEach(origins::add)
                            }
                            MediaNetworkPolicy.normalizePrivateOrigins(origins)
                        } ?: return@launch
                        if (!Components.isCurrentPageNavigation(tabId, navigationGeneration)) {
                            return@launch
                        }
                        val websiteApproved = PageCastConsentStore.isApproved(this@BrowserActivity, origin)
                        val unapprovedPrivateOrigins = PageCastConsentStore.unapprovedPrivateOrigins(
                            this@BrowserActivity,
                            origin,
                            requestedPrivateOrigins,
                        )
                        val authorizedItems = items.map {
                            it.copy(allowed_private_origins = requestedPrivateOrigins.toList())
                        }
                        when {
                            !websiteApproved -> pendingPageCast = PendingPageCast(
                                items,
                                startIndex,
                                playlistMetadata,
                                skipPreplay,
                                origin,
                                tabId,
                                navigationGeneration,
                                PageCastConsentStage.WEBSITE,
                                requestedPrivateOrigins,
                            )
                            unapprovedPrivateOrigins.isNotEmpty() -> pendingPageCast = PendingPageCast(
                                items,
                                startIndex,
                                playlistMetadata,
                                skipPreplay,
                                origin,
                                tabId,
                                navigationGeneration,
                                PageCastConsentStage.PRIVATE_ORIGINS,
                                requestedPrivateOrigins,
                            )
                            else -> dispatchBridgeCast(
                                authorizedItems,
                                startIndex,
                                playlistMetadata,
                                skipPreplay,
                                tabId,
                                navigationGeneration,
                            )
                        }
                    }
                }
                Components.onLinkedNativePortDisconnected = {
                    linkedPageCastCoordinator.unlink("native_port_lost")
                }
                Components.onLinkedDevicePicked = { device ->
                    val request = pendingLinkedDeviceRequest
                    if (request != null) {
                        pendingLinkedDeviceRequest = null
                        if (device.resolvedProtocol != com.playbridge.sender.model.CastProtocol.PLAYBRIDGE) {
                            linkedPageCastCoordinator.reject(request.bridgeRequestId, "unsupported_target")
                        } else {
                            dispatchLinkedOpen(request, device)
                        }
                    }
                }
                Components.onLinkedDevicePickerDismissed = {
                    pendingLinkedDeviceRequest?.let {
                        linkedPageCastCoordinator.reject(it.bridgeRequestId, "user_cancelled")
                    }
                    pendingLinkedDeviceRequest = null
                }
                Components.onLinkedCastRequest = linkedRequest@ { message ->
                    val type = message.optString("type")
                    if (type == "linked_cancel_open") {
                        val targetBridgeRequestId = message.optString("targetBridgeRequestId")
                        linkedPageCastCoordinator.cancelOpen(targetBridgeRequestId)
                        if (pendingLinkedPageCast?.request?.bridgeRequestId == targetBridgeRequestId) {
                            pendingLinkedPageCast = null
                        }
                        if (pendingLinkedDeviceRequest?.bridgeRequestId == targetBridgeRequestId) {
                            pendingLinkedDeviceRequest = null
                        }
                        return@linkedRequest
                    }
                    if (type != "linked_open") {
                        val origin = PageCastConsentStore.normalizeOrigin(message.optString("origin"))
                        val tabId = message.optInt("tabId", -1)
                        val navigationGeneration = message.optLong("navigationGeneration", -1)
                        if (origin == null || tabId < 0 || navigationGeneration < 0 ||
                            !linkedPageCastCoordinator.isMessageForActiveSession(message, origin)
                        ) {
                            linkedPageCastCoordinator.reject(message.optString("bridgeRequestId"), "session_ended")
                            return@linkedRequest
                        }
                        lifecycleScope.launch {
                            val requestedPrivateOrigins =
                                linkedPageCastCoordinator.messageRequestedPrivateOrigins(message)
                                    ?: run {
                                        linkedPageCastCoordinator.reject(message.optString("bridgeRequestId"), "invalid_request")
                                        return@launch
                                    }
                            if (!Components.isCurrentPageNavigation(tabId, navigationGeneration) ||
                                !linkedPageCastCoordinator.isMessageForActiveSession(message, origin)
                            ) {
                                linkedPageCastCoordinator.reject(
                                    message.optString("bridgeRequestId"),
                                    "session_ended",
                                )
                                return@launch
                            }
                            val unapprovedPrivateOrigins = PageCastConsentStore.unapprovedPrivateOrigins(
                                this@BrowserActivity, origin, requestedPrivateOrigins,
                            )
                            if (unapprovedPrivateOrigins.isNotEmpty()) {
                                pendingLinkedOperation?.let {
                                    linkedPageCastCoordinator.reject(
                                        it.message.optString("bridgeRequestId"),
                                        "superseded",
                                    )
                                }
                                pendingLinkedOperation = PendingLinkedOperation(
                                    message,
                                    origin,
                                    tabId,
                                    navigationGeneration,
                                    requestedPrivateOrigins,
                                )
                            } else {
                                if (linkedPageCastCoordinator.allowPrivateOriginsForActive(
                                        origin, requestedPrivateOrigins,
                                    )
                                ) {
                                    linkedPageCastCoordinator.handle(message)
                                } else {
                                    linkedPageCastCoordinator.reject(
                                        message.optString("bridgeRequestId"), "resource_limit",
                                    )
                                }
                            }
                        }
                        return@linkedRequest
                    }
                    val request = linkedPageCastCoordinator.parseOpen(message)
                    if (request == null) {
                        linkedPageCastCoordinator.reject(message.optString("bridgeRequestId"), "invalid_request")
                        return@linkedRequest
                    }
                    if (!Components.isCurrentPageNavigation(
                            request.tabId,
                            request.navigationGeneration,
                        )
                    ) {
                        linkedPageCastCoordinator.reject(request.bridgeRequestId, "session_ended")
                        return@linkedRequest
                    }
                    pendingLinkedPageCast?.request?.let {
                        linkedPageCastCoordinator.reject(it.bridgeRequestId, "superseded")
                    }
                    pendingLinkedDeviceRequest?.let {
                        linkedPageCastCoordinator.reject(it.bridgeRequestId, "superseded")
                    }
                    pendingLinkedDeviceRequest = null
                    lifecycleScope.launch {
                        val requestedPrivateOrigins = linkedPageCastCoordinator.requestedPrivateOrigins(request)
                            ?: run {
                                linkedPageCastCoordinator.reject(request.bridgeRequestId, "invalid_request")
                                return@launch
                            }
                        if (!Components.isCurrentPageNavigation(
                                request.tabId,
                                request.navigationGeneration,
                            ) || linkedPageCastCoordinator.isOpenCancelled(request.bridgeRequestId)
                        ) {
                            linkedPageCastCoordinator.cancelOpen(request.bridgeRequestId)
                            return@launch
                        }
                        val websiteApproved =
                            PageCastConsentStore.isApproved(this@BrowserActivity, request.origin)
                        val unapprovedPrivateOrigins = PageCastConsentStore.unapprovedPrivateOrigins(
                            this@BrowserActivity, request.origin, requestedPrivateOrigins,
                        )
                        when {
                            !websiteApproved -> pendingLinkedPageCast = PendingLinkedPageCast(
                                request,
                                PageCastConsentStage.WEBSITE,
                                requestedPrivateOrigins,
                            )
                            unapprovedPrivateOrigins.isNotEmpty() ->
                                pendingLinkedPageCast = PendingLinkedPageCast(
                                    request,
                                    PageCastConsentStage.PRIVATE_ORIGINS,
                                    requestedPrivateOrigins,
                                )
                            else -> dispatchLinkedOpen(
                                request.withPrivateOriginPermission(requestedPrivateOrigins),
                            )
                        }
                    }
                }
                Components.onPageNavigation = { tabId, navigationGeneration ->
                    pendingPageCast?.takeIf {
                        pageRequestSuperseded(
                            it.tabId,
                            it.navigationGeneration,
                            tabId,
                            navigationGeneration,
                        )
                    }?.let { pendingPageCast = null }
                    pendingLinkedPageCast?.request?.takeIf {
                        pageRequestSuperseded(
                            it.tabId,
                            it.navigationGeneration,
                            tabId,
                            navigationGeneration,
                        )
                    }?.let {
                        linkedPageCastCoordinator.cancelOpen(it.bridgeRequestId)
                        pendingLinkedPageCast = null
                    }
                    pendingLinkedDeviceRequest?.takeIf {
                        pageRequestSuperseded(
                            it.tabId,
                            it.navigationGeneration,
                            tabId,
                            navigationGeneration,
                        )
                    }?.let {
                        linkedPageCastCoordinator.cancelOpen(it.bridgeRequestId)
                        pendingLinkedDeviceRequest = null
                    }
                    pendingLinkedOperation?.takeIf {
                        pageRequestSuperseded(
                            it.tabId,
                            it.navigationGeneration,
                            tabId,
                            navigationGeneration,
                        )
                    }?.let {
                        linkedPageCastCoordinator.reject(
                            it.message.optString("bridgeRequestId"),
                            "session_ended",
                        )
                        pendingLinkedOperation = null
                    }
                }
            }

            pendingPageCast?.let { request ->
                val websiteName = PageCastConsentStore.displayName(request.origin)
                val localNetworkPrompt = request.stage == PageCastConsentStage.PRIVATE_ORIGINS
                val privateOriginList = PageCastConsentStore.unapprovedPrivateOrigins(
                    this@BrowserActivity, request.origin, request.requestedPrivateOrigins,
                ).joinToString("\n") {
                    "• ${PageCastConsentStore.displayName(it)}"
                }
                AlertDialog(
                    onDismissRequest = { pendingPageCast = null },
                    title = {
                        Text(
                            if (localNetworkPrompt) "Allow local-network media?"
                            else "Allow $websiteName to cast?",
                        )
                    },
                    text = {
                        Text(
                            if (localNetworkPrompt) {
                                "$websiteName wants the selected receiver to load media from:\n\n" +
                                    "$privateOriginList\n\nOnly allow servers you recognize. " +
                                    "You can reset these grants separately in Settings > Browser > Website casting permissions."
                            } else {
                                "$websiteName can start one-off direct casts and future linked casts. " +
                                    "A linked cast keeps a session open so the website can manage its playlist on your selected device.\n\n" +
                                    "This does not allow access to local-network media. You can reset this permission later " +
                                    "in Settings > Browser > Website casting permissions."
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (localNetworkPrompt) {
                                PageCastConsentStore.approvePrivateOrigins(
                                    this@BrowserActivity, request.origin, request.requestedPrivateOrigins,
                                )
                                pendingPageCast = null
                                dispatchBridgeCast(
                                    request.items.map { it.copy(allowed_private_origins = request.requestedPrivateOrigins.toList()) },
                                    request.startIndex,
                                    request.playlistMetadata,
                                    request.skipPreplay,
                                    request.tabId,
                                    request.navigationGeneration,
                                )
                            } else {
                                PageCastConsentStore.approve(this@BrowserActivity, request.origin)
                                if (PageCastConsentStore.unapprovedPrivateOrigins(
                                        this@BrowserActivity, request.origin, request.requestedPrivateOrigins,
                                    ).isNotEmpty()
                                ) {
                                    pendingPageCast = request.copy(stage = PageCastConsentStage.PRIVATE_ORIGINS)
                                } else {
                                    pendingPageCast = null
                                    dispatchBridgeCast(
                                        request.items.map {
                                            it.copy(allowed_private_origins = request.requestedPrivateOrigins.toList())
                                        },
                                        request.startIndex,
                                        request.playlistMetadata,
                                        request.skipPreplay,
                                        request.tabId,
                                        request.navigationGeneration,
                                    )
                                }
                            }
                        }) { Text("Allow") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingPageCast = null }) { Text("Deny") }
                    },
                )
            }

            pendingLinkedPageCast?.let { pending ->
                val request = pending.request
                val websiteName = PageCastConsentStore.displayName(request.origin)
                val localNetworkPrompt = pending.stage == PageCastConsentStage.PRIVATE_ORIGINS
                val privateOriginList = PageCastConsentStore.unapprovedPrivateOrigins(
                    this@BrowserActivity, request.origin, pending.requestedPrivateOrigins,
                ).joinToString("\n") {
                    "• ${PageCastConsentStore.displayName(it)}"
                }
                AlertDialog(
                    onDismissRequest = {
                        linkedPageCastCoordinator.reject(request.bridgeRequestId, "not_allowed")
                        pendingLinkedPageCast = null
                    },
                    title = {
                        Text(
                            if (localNetworkPrompt) "Allow local-network media?"
                            else "Allow $websiteName to cast?",
                        )
                    },
                    text = {
                        Text(
                            if (localNetworkPrompt) {
                                "$websiteName wants the selected receiver to load playlist media from:\n\n" +
                                    "$privateOriginList\n\nOnly allow servers you recognize. Future linked items " +
                                    "must request any additional server separately."
                            } else {
                                "$websiteName can start casts and stay linked while this page is open so it can manage the TV playlist. " +
                                    "Playback controls remain available in PlayBridge. This does not allow access to local-network media.\n\n" +
                                    "You can reset this permission later in Settings > Browser > Website casting permissions."
                            },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (localNetworkPrompt) {
                                PageCastConsentStore.approvePrivateOrigins(
                                    this@BrowserActivity, request.origin, pending.requestedPrivateOrigins,
                                )
                                pendingLinkedPageCast = null
                                dispatchLinkedOpen(request.withPrivateOriginPermission(pending.requestedPrivateOrigins))
                            } else {
                                PageCastConsentStore.approve(this@BrowserActivity, request.origin)
                                if (PageCastConsentStore.unapprovedPrivateOrigins(
                                        this@BrowserActivity, request.origin, pending.requestedPrivateOrigins,
                                    ).isNotEmpty()
                                ) {
                                    pendingLinkedPageCast = pending.copy(
                                        stage = PageCastConsentStage.PRIVATE_ORIGINS,
                                    )
                                } else {
                                    pendingLinkedPageCast = null
                                    dispatchLinkedOpen(
                                        request.withPrivateOriginPermission(pending.requestedPrivateOrigins),
                                    )
                                }
                            }
                        }) { Text("Allow") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            linkedPageCastCoordinator.reject(request.bridgeRequestId, "not_allowed")
                            pendingLinkedPageCast = null
                        }) { Text("Deny") }
                    },
                )
            }

            pendingLinkedOperation?.let { pending ->
                val websiteName = PageCastConsentStore.displayName(pending.origin)
                val privateOriginList = PageCastConsentStore.unapprovedPrivateOrigins(
                    this@BrowserActivity, pending.origin, pending.requestedPrivateOrigins,
                ).joinToString("\n") {
                    "• ${PageCastConsentStore.displayName(it)}"
                }
                AlertDialog(
                    onDismissRequest = {
                        linkedPageCastCoordinator.reject(
                            pending.message.optString("bridgeRequestId"),
                            "not_allowed",
                        )
                        pendingLinkedOperation = null
                    },
                    title = { Text("Allow local-network media?") },
                    text = {
                        Text(
                            "$websiteName wants to add playlist media from:\n\n$privateOriginList\n\n" +
                                "Only allow servers you recognize. Future linked items must request additional servers separately.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            PageCastConsentStore.approvePrivateOrigins(
                                this@BrowserActivity, pending.origin, pending.requestedPrivateOrigins,
                            )
                            val allowed = linkedPageCastCoordinator.allowPrivateOriginsForActive(
                                pending.origin, pending.requestedPrivateOrigins,
                            )
                            pendingLinkedOperation = null
                            if (allowed) {
                                linkedPageCastCoordinator.handle(pending.message)
                            } else {
                                linkedPageCastCoordinator.reject(
                                    pending.message.optString("bridgeRequestId"), "resource_limit",
                                )
                            }
                        }) { Text("Allow") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            linkedPageCastCoordinator.reject(
                                pending.message.optString("bridgeRequestId"),
                                "not_allowed",
                            )
                            pendingLinkedOperation = null
                        }) { Text("Deny") }
                    },
                )
            }

            val detectVideosEnabled by settingsRepository.detectVideos.collectAsState(initial = true)
            // Detection messages now arrive via native messaging (Components.processMessage),
            // so the setting is enforced there rather than at the old hash-signal parse site.
            LaunchedEffect(detectVideosEnabled) {
                Components.detectVideosEnabled = detectVideosEnabled
            }
            var isDesktopMode by remember { mutableStateOf(false) }
            var isSecureConnection by remember { mutableStateOf(false) }
            var siteSecurityInfo by remember { mutableStateOf<SiteSecurityInfo?>(null) }
            var showSiteInfoSheet by remember { mutableStateOf(false) }
            var showBrowserConnectDialog by remember { mutableStateOf(false) }
            var isFullscreen by remember { mutableStateOf(false) }
            var isFullscreenVideoPortrait by remember { mutableStateOf(false) }

            // Fullscreen: hide/show system bars and handle auto-rotation
            val view = LocalView.current
            LaunchedEffect(isFullscreen, isFullscreenVideoPortrait) {
                val window = this@BrowserActivity.window ?: return@LaunchedEffect
                val controller = WindowInsetsControllerCompat(window, view)
                if (isFullscreen) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    requestedOrientation = if (isFullscreenVideoPortrait) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            // Update simple UI state from selected tab
            LaunchedEffect(selectedTab?.id) {
                if (selectedTab != null) {
                    currentUrl = selectedTab.content.url
                }
            }

            // View state - browser

            // FAB Drag state
            var fabOffsetX by remember { mutableFloatStateOf(0f) }
            var fabOffsetY by remember { mutableFloatStateOf(0f) }

            // Back press handling
            var backPressedTime by remember { mutableLongStateOf(0L) }

            // Magnet parsing state
            var interceptedMagnet by remember { mutableStateOf<String?>(null) }
            var interceptedTorrentBytes by remember { mutableStateOf<ByteArray?>(null) }

            // Stremio addon install state (set when a stremio:// link is clicked in the browser)
            var pendingStremioAddon by remember { mutableStateOf<String?>(null) }
            var isInstallingStremioAddon by remember { mutableStateOf(false) }

            // Video detection state — per-tab
            var showVideoSheet by remember { mutableStateOf(false) }
            val sheetPlayerMode = tvPlayerMode
            var forcePlaylistSheet by remember { mutableStateOf<DetectedVideo?>(null) }
            var forcedVideos by remember { mutableStateOf<List<DetectedVideo>?>(null) }
            var castSheetInitialMode by remember { mutableStateOf("play") }
            var castSheetBrowseOverride by remember { mutableStateOf<String?>(null) }
            var pendingContentPayload by remember { mutableStateOf<playbridge.PlayPayload?>(null) }

            val tvActiveContext by connectionCoordinator.tvActiveContext.collectAsState()
            // Keep the revision as an explicit Compose input. DetectedVideo contains mutable probe
            // fields, so a copied List can remain structurally equal even though its ranking changed.
            val detectedMediaRevision = VideoDetector.processingVersion
            val detectedVideos = when {
                forcedVideos != null -> forcedVideos!!
                forcePlaylistSheet != null -> listOf(forcePlaylistSheet!!)
                else -> VideoDetector.getVideosForTab(selectedTabId ?: "").toList()
            }
            val videoCount = detectedVideos.count { it.isVideo }
            val detectedMediaBadge = buildDetectedMediaBadge(detectedVideos)

            val haptic = LocalHapticFeedback.current

            fun performQuickCast() {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                scope.launch {
                    if (castRoute is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice) {
                        showBrowserConnectDialog = true
                        return@launch
                    }
                    val externalTarget = connectionViewModel.activeExternalDevice.value
                    if (externalTarget != null) {
                        val content = pendingContentPayload
                        if (content != null && videoCount == 0) {
                            connectionViewModel.castToSelectedExternal(
                                com.playbridge.sender.cast.MediaItem(
                                    url = content.url,
                                    headers = content.headers,
                                    title = content.title,
                                    startPositionMs = content.start_position_ms ?: 0L,
                                    visualMetadata = content.visual_metadata,
                                )
                            )
                            Toast.makeText(this@BrowserActivity, "Casting to ${externalTarget.name}", Toast.LENGTH_SHORT).show()
                            if (autoSwitchToRemote) currentScreen = Screen.Remote
                            return@launch
                        }
                        val video = buildCastSheetVideos(detectedVideos)
                            .filter {
                                it.effectiveValidationState != MediaValidationState.FAILED
                            }
                            .firstOrNull()
                        if (video == null) {
                            Toast.makeText(this@BrowserActivity, "No video detected to cast", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val playlist = video.playlistPayload?.map { item ->
                            item.copy(
                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                            )
                        }
                        if (playlist != null) {
                            startExternalPlaylist(playlist)
                        } else {
                            val headers = VideoDetector.mediaHeaders(video)
                            connectionViewModel.castToSelectedExternal(
                                com.playbridge.sender.cast.MediaItem(
                                    url = video.url,
                                    headers = headers,
                                    mimeType = video.contentType,
                                    title = video.title ?: selectedTab?.content?.title,
                                )
                            )
                        }
                        Toast.makeText(this@BrowserActivity, "Casting to ${externalTarget.name}", Toast.LENGTH_SHORT).show()
                        if (autoSwitchToRemote) currentScreen = Screen.Remote
                        return@launch
                    }

                    if (connectionState !is WebSocketClient.ConnectionState.Connected) {
                        tvDevice?.let { device ->
                            Toast.makeText(this@BrowserActivity, "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
                            connectionViewModel.connect(device)
                            withTimeoutOrNull(5000) {
                                while (connectionState !is WebSocketClient.ConnectionState.Connected) {
                                    delay(250)
                                }
                            }
                        }
                    }

                    val connection = connectionState as? WebSocketClient.ConnectionState.Connected
                    if (connection == null) {
                        Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Give a small buffer for the connection to fully stabilize
                    delay(300)

                    // 1. Check Library Content (highest priority)
                    val content = pendingContentPayload
                    if (content != null && videoCount == 0) {
                        val cmd = createSingleVideoCommandJson(
                            PlayPayload(
                                url = content.url,
                                title = content.title,
                                content_type = content.content_type,
                                detected_by = content.detected_by,
                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                                visual_metadata = content.visual_metadata,
                            )
                        )
                        linkedPageCastCoordinator.supersedeIfActive()
                        if (connectionViewModel.webSocketClient.send(cmd)) {
                            // Library content — record identity for the progress tracker.
                            connectionCoordinator.startLocalPlaybackSession(
                                tmdbId = content.visual_metadata?.tmdb_id?.toIntOrNull(),
                                season = content.visual_metadata?.season,
                                episodeStart = content.visual_metadata?.episode,
                            )
                            if (autoSwitchToRemote) {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                currentScreen = Screen.Remote
                            }
                            Toast.makeText(this@BrowserActivity, "Playing ${content.title}", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    // 2. Check detected videos
                    val playable = buildCastSheetVideos(detectedVideos)
                        .filter {
                            it.effectiveValidationState != MediaValidationState.FAILED
                        }

                    if (playable.isNotEmpty()) {
                        val video = playable.first()
                        if (video.playlistPayload != null) {
                            linkedPageCastCoordinator.supersedeIfActive()
                            val cmd = com.playbridge.shared.protocol.createPlaylistCommandJson(
                                payload = playbridge.PlaylistPayload(items = video.playlistPayload)
                            )
                            if (connectionViewModel.webSocketClient.send(cmd)) {
                                // Browser content has no library identity — clear it so the
                                // progress tracker can't attribute it to the previous title.
                                connectionCoordinator.startLocalPlaybackSession(null, null, null)
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                                Toast.makeText(this@BrowserActivity, "Playlist sent to ${connection.serverName}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val headers = VideoDetector.mediaHeaders(video)
                            val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                            val cmd = createSingleVideoCommandJson(
                                PlayPayload(
                                    url = video.url,
                                    title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                    headers = headers,
                                    content_type = video.contentType,
                                    detected_by = video.detectedBy,
                                    player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                    preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                    preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                    default_video_quality = effectiveQuality,
                                    max_bitrate_cap_mbps = maxBitrateCapMbps,
                                )
                            )
                            connectionViewModel.sendCommandAndRecord(cmd, "play", video.url, selectedTab?.content?.title ?: "Video from browser")
                            linkedPageCastCoordinator.supersedeIfActive()
                            if (connectionViewModel.webSocketClient.send(cmd)) {
                                connectionCoordinator.startLocalPlaybackSession(null, null, null) // browser content
                                tabManager.pauseMedia(session)
                                if (autoSwitchToRemote) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    currentScreen = Screen.Remote
                                }
                                Toast.makeText(this@BrowserActivity, "Playing on ${connection.serverName}", Toast.LENGTH_SHORT).show()
                            }
                        }
                        return@launch
                    }

                    // 3. Fallback to Browse
                    val effectiveUrl = currentUrl
                    val cmd = com.playbridge.shared.protocol.createBrowserCommandJson(
                        effectiveUrl,
                        browserMode = sheetPlayerMode.takeIf { it != "tv" },
                        desktopMode = isDesktopMode.takeIf { it }
                    )
                    connectionViewModel.sendCommandAndRecord(cmd, "browser", effectiveUrl, "Browser Page")
                    if (autoSwitchToRemote) {
                        connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                        currentScreen = Screen.Remote
                    }
                    Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                }
            }

            // Parse adaptive manifests as soon as they are detected. This is lightweight metadata
            // work that improves ranking and removes variant/segment duplicates before the sheet
            // opens; thumbnail decoding is scheduled separately below.
            LaunchedEffect(selectedTabId) {
                val tabId = selectedTabId ?: return@LaunchedEffect
                val parsedManifestUrls = mutableSetOf<String>()
                snapshotFlow { VideoDetector.getVideosForTab(tabId).toList() }.collect { videos ->
                    for (video in videos) {
                        if (!video.isVideo || video.qualitiesChecked) continue
                        val isAdaptiveManifest =
                            video.url.contains(".m3u8", ignoreCase = true) ||
                                video.url.contains(".mpd", ignoreCase = true) ||
                                video.contentType?.contains("mpegurl", ignoreCase = true) == true ||
                                video.contentType?.contains("dash", ignoreCase = true) == true
                        if (isAdaptiveManifest && parsedManifestUrls.add(video.url)) {
                            launch { VideoDetector.fetchHlsQualities(video, tabId) }
                        }
                    }
                }
            }

            // Warm only the two best candidates. collectLatest cancels queued speculative work
            // when detection/ranking changes or the user navigates; visible sheet rows use a
            // higher-priority request and can immediately retry a failed prefetch.
            LaunchedEffect(selectedTabId) {
                val tabId = selectedTabId ?: return@LaunchedEffect
                snapshotFlow {
                    @Suppress("UNUSED_EXPRESSION")
                    VideoDetector.processingVersion
                    VideoDetector.getVideosForTab(tabId).toList()
                }.collectLatest { videos ->
                    delay(500L)
                    for (video in thumbnailPrefetchCandidates(videos)) {
                        if (!VideoDetector.hasThumbnail(video.url)) {
                            VideoDetector.fetchThumbnail(
                                video,
                                priority = ThumbnailRequestPriority.PREFETCH,
                            )
                        }
                    }
                }
            }


            // Find in Page state
            var showFindBar by remember { mutableStateOf(false) }

            var pendingPopup by remember { mutableStateOf<PendingPopup?>(null) }

            // Clear finding when bar closes
            LaunchedEffect(showFindBar) {
                if (!showFindBar) {
                    tabManager.clearFind(session)
                }
            }

            // On (re)connect, ask the TV to re-broadcast its now-playing snapshot
            // (context + playlist + tracks + status) so the remote screen repopulates
            // after an app restart — these are otherwise only sent on change events.
            LaunchedEffect(Unit) {
                connectionViewModel.connectionState.collect { state ->
                    if (state is WebSocketClient.ConnectionState.Connected) {
                        connectionViewModel.webSocketClient.send(
                            com.playbridge.shared.protocol.createContextQueryJson()
                        )
                    }
                }
            }

            // WebSocket messages and remote states are now handled dynamically by ConnectionCoordinator

            // Context menu state for "Open in new tab"
            var contextMenuUrl by remember(selectedTabId) { mutableStateOf<String?>(null) }

            // Mutable state wrappers for SessionObserverSetup — keyed to selectedTabId
            val currentUrlState = remember(selectedTabId) { mutableStateOf(currentUrl) }
            val isLoadingState = remember(selectedTabId) { mutableStateOf(false) }
            val contextMenuUrlState = remember(selectedTabId) { mutableStateOf(contextMenuUrl) }
            val previousUrlState = remember(selectedTabId) { mutableStateOf(previousUrl) }
            val pendingDownloadState = remember(selectedTabId) { mutableStateOf(pendingDownload) }
            val isSecureConnectionState = remember(selectedTabId) { mutableStateOf(isSecureConnection) }
            val siteSecurityInfoState = remember(selectedTabId) { mutableStateOf(siteSecurityInfo) }

            val pendingPopupState = remember(selectedTabId) { mutableStateOf(pendingPopup) }

            // Sync wrapper states back to local vars
            currentUrl = currentUrlState.value
            isLoading = isLoadingState.value
            contextMenuUrl = contextMenuUrlState.value
            previousUrl = previousUrlState.value
            pendingDownload = pendingDownloadState.value
            isSecureConnection = isSecureConnectionState.value
            siteSecurityInfo = siteSecurityInfoState.value
            pendingPopup = pendingPopupState.value

            // Sync wrapper states from BrowserStore when the selected tab changes
            // This ensures the URL bar shows the correct URL immediately on tab switch
            LaunchedEffect(selectedTab?.id) {
                if (selectedTab != null) {
                    currentUrlState.value = selectedTab.content.url
                    previousUrlState.value = selectedTab.content.url
                    isSecureConnectionState.value = false
                }
            }

            // Auto-reconnect to the last TV when opening the cast sheet — but only while
            // auto-connect is still enabled. A manual disconnect turns it off, so reopening the
            // sheet then respects that choice instead of immediately reconnecting.
            LaunchedEffect(showVideoSheet) {
                // Don't auto-reconnect the native receiver while another protocol is selected.
                if (showVideoSheet && connectionState is WebSocketClient.ConnectionState.Disconnected &&
                    connectionViewModel.activeExternalDevice.value == null &&
                    castRoute is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv &&
                    connectionViewModel.autoConnectEnabled.value) {
                    tvDevice?.let { device ->
                        Log.d("BrowserActivity", "Cast sheet opened while disconnected. Reconnecting to ${device.name}")
                        connectionViewModel.connect(device)
                    }
                }
            }

            // Link context menu
            LinkContextMenu(
                url = contextMenuUrl,
                isConnected = activeExternalDevice != null ||
                    (castRoute is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv &&
                        connectionState is WebSocketClient.ConnectionState.Connected),
                onPlayOnTv = { linkUrl ->
                    val video = DetectedVideo(
                        url = linkUrl,
                        tabId = -1,
                        detectedBy = "link_menu"
                    )
                    forcedVideos = listOf(video)
                    showVideoSheet = true
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInNewTab = { linkUrl ->
                    tabManager.createTab(linkUrl, store, parentId = store.state.selectedTabId)
                    Toast.makeText(this@BrowserActivity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onOpenInBackground = { linkUrl ->
                    tabManager.createTab(linkUrl, store, parentId = store.state.selectedTabId, select = false)
                    Toast.makeText(this@BrowserActivity, "Opened in background", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onCopyLink = { linkUrl ->
                    clipboardManager.setText(AnnotatedString(linkUrl))
                    Toast.makeText(this@BrowserActivity, "Link copied", Toast.LENGTH_SHORT).show()
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                },
                onDismiss = {
                    contextMenuUrl = null
                    contextMenuUrlState.value = null
                }
            )

            // Register navigation observer & GeckoSession delegates
            SessionObserverSetup(
                session = session,
                selectedTab = selectedTab,
                store = store,
                tabManager = tabManager,
                scope = scope,
                currentUrl = currentUrlState,
                isLoading = isLoadingState,
                contextMenuUrl = contextMenuUrlState,
                previousUrl = previousUrlState,
                pendingDownload = pendingDownloadState,
                isDesktopMode = isDesktopMode,
                userAgentOverride = userAgentOverride,
                isSecureConnection = isSecureConnectionState,
                siteSecurityInfo = siteSecurityInfoState,
                pendingPopup = pendingPopupState,
                onXpiDetected = { url ->
                    runOnUiThread {
                        Toast.makeText(this@BrowserActivity, "Installing extension...", Toast.LENGTH_SHORT).show()
                    }
                    lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            Components.addonManager.installAddon(
                                url = url,
                                onSuccess = { addon ->
                                    Log.d(TAG, "Addon installed: ${addon.id}")
                                    runOnUiThread {
                                        Toast.makeText(this@BrowserActivity, "Extension installed successfully!", Toast.LENGTH_LONG).show()
                                    }
                                },
                                onError = { throwable ->
                                    Log.e(TAG, "Addon install failed", throwable)
                                    runOnUiThread {
                                        Toast.makeText(this@BrowserActivity, "Install failed: ${throwable.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error installing addon", e)
                        }
                    }
                },
                onMagnetDetected = { uri ->
                    interceptedMagnet = uri
                },
                onStremioAddonDetected = { uri ->
                    pendingStremioAddon = uri
                },
                onTorrentDownloaded = { bytes ->
                    interceptedTorrentBytes = bytes
                },
                onFullScreenChange = { fullScreen, isPortrait ->
                    isFullscreenVideoPortrait = isPortrait
                    isFullscreen = fullScreen
                }
            )

            val handleBookmarkClick = {
                val title = selectedTab?.content?.title
                val url = currentUrl
                if (url != "about:blank") {
                    browserViewModel.addBookmark(url, title ?: "")
                    Toast.makeText(this@BrowserActivity, "Bookmark added", Toast.LENGTH_SHORT).show()
                }
            }

            PlayBridgeTheme {
                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                        if (isFullscreen || (currentScreen == Screen.Browser && isBrowserChromeHidden)) {
                            // Media fullscreen and chrome-hidden browsing both hide the toolbar.
                        } else when (currentScreen) {
                            Screen.Browser -> {
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        BrowserToolbar(
                                            currentUrl = currentUrl,
                                            isLoading = isLoading,
                                            isEditing = isEditing,
                                            isSecure = isSecureConnection,
                                            onLogoClick = {
                                                dashboardOrigin = currentScreen
                                                currentScreen = Screen.Dashboard
                                            },
                                            onSecurityIconClick = { showSiteInfoSheet = true },
                                        onEditingChange = { editing ->
                                            isEditing = editing
                                            if (editing) {
                                                editUrl = currentUrl
                                                browserViewModel.setEditUrl(currentUrl)
                                            }
                                        },
                                        onUrlChange = { url ->
                                            editUrl = url
                                            browserViewModel.setEditUrl(url)
                                            urlBarTapped = false
                                        },
                                        onNavigate = { url ->
                                            session.loadUrl(url)
                                            isEditing = false
                                        },
                                        onMagnetDetected = { magnet ->
                                            interceptedMagnet = magnet
                                            isEditing = false
                                        },
                                        isTvConnected = activeExternalDevice != null ||
                                            (castRoute is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv &&
                                                connectionState is WebSocketClient.ConnectionState.Connected),
                                        onTvClick = { showBrowserConnectDialog = true },
                                        onRemoteClick = {
                                            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                            }
                                            currentScreen = Screen.Remote
                                        },
                                        isPlayEnabled = currentUrl.isNotEmpty() && currentUrl != "about:blank",
                                        mediaCount = detectedMediaBadge?.count ?: 0,
                                        mediaKind = detectedMediaBadge?.kind,
                                        onPlayClick = { showVideoSheet = true },
                                        onPlayLongClick = { performQuickCast() }
                                    )

                                    if (showBrowserConnectDialog) {
                                        DeviceConnectionDialog(
                                            onDismiss = { showBrowserConnectDialog = false },
                                            onOpenAllDevices = {
                                                showBrowserConnectDialog = false
                                                connectionInitialTab = 1
                                                currentScreen = Screen.Connection
                                            },
                                            showThisDevice = true,
                                        )
                                    }

                                    // Find on Page Bar
                                     if (showFindBar) {
                                        FindOnPageBar(
                                            onFind = { text -> tabManager.findInPage(session, text) },
                                            onNext = { tabManager.findInPage(session, "", 0) },
                                            onPrev = { tabManager.findInPage(session, "", 1) },
                                            onClose = { showFindBar = false }
                                        )
                                    }
                                }
                            }
                        }
                            Screen.Tabs -> {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Tabs") },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.Browser }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            tabManager.createTab("about:blank", store)
                                            currentScreen = Screen.Browser
                                        }) {
                                            Icon(Icons.Default.Add, "New Tab")
                                        }
                                        
                                        var menuExpanded by remember { mutableStateOf(false) }
                                        val playingTabIds = tabManager.playingTabIds
                                        
                                        Box {
                                            IconButton(onClick = { menuExpanded = true }) {
                                                Icon(Icons.Default.MoreVert, "More options")
                                            }
                                            DropdownMenu(
                                                expanded = menuExpanded,
                                                onDismissRequest = { menuExpanded = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Go to playing tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        playingTabIds.keys.firstOrNull()?.let {
                                                            tabManager.selectTab(it, store)
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                                                    enabled = playingTabIds.isNotEmpty()
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Reopen Closed Tab") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        tabManager.reopenClosedTab(store)?.let {
                                                            currentScreen = Screen.Browser
                                                        }
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Restore, null) },
                                                    enabled = tabManager.canReopenClosedTab()
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text(if (isTabsSearchVisible) "Hide Search" else "Search Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsSearchVisible = !isTabsSearchVisible
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Search, null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Select Tabs") },
                                                    onClick = {
                                                        menuExpanded = false
                                                        isTabsMultiSelectMode = true
                                                    },
                                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) }
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = { Text("Close All Tabs", color = MaterialTheme.colorScheme.error) },
                                                    onClick = {
                                                        menuExpanded = false
                                                        showTabsCloseAllConfirm = true
                                                    },
                                                    leadingIcon = { 
                                                        Icon(
                                                            Icons.Default.Delete, 
                                                            null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        ) 
                                                    }
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                            Screen.Extensions -> {
                                @OptIn(ExperimentalMaterial3Api::class)
                                TopAppBar(
                                    title = { Text("Extensions") },
                                    navigationIcon = {
                                        IconButton(onClick = { currentScreen = Screen.Browser }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                        }
                                    }
                                )
                            }
                            Screen.Connection -> {}
                            Screen.History -> {}
                            Screen.CastHistory -> {}
                            Screen.Bookmarks -> {}
                            Screen.Home -> {}
                            Screen.Remote -> {}
                            Screen.Downloads -> {}
                            Screen.Settings -> {}
                            Screen.Library -> {}
                            is Screen.LibraryDetail -> {}
                            Screen.AddonSettings -> {}
                            Screen.DebridLibrary -> {}
                            Screen.Dashboard -> {}
                            Screen.ScreenMirror -> {}
                            Screen.PhoneFiles -> {}
                            Screen.Iptv -> {}
                            is Screen.IptvDetail -> {}
                            Screen.Collections -> {}
                            is Screen.CollectionDetail -> {}
                        }
                    },
                        bottomBar = {
                            if (currentScreen == Screen.Browser && !isFullscreen && !isBrowserChromeHidden) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 0.dp
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.navigationBars)
                                            .padding(horizontal = 16.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. Back Button
                                        IconButton(
                                            onClick = { session.goBack() },
                                            enabled = browserCanGoBack
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = "Back",
                                                tint = if (browserCanGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        }

                                        // 2. Forward Button
                                        IconButton(
                                            onClick = { session.goForward() },
                                            enabled = browserCanGoForward
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Forward",
                                                tint = if (browserCanGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            )
                                        }

                                         IconButton(
                                             onClick = { if (isLoading) session.stopLoading() else session.reload() }
                                         ) {
                                             Icon(
                                                 imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                                 contentDescription = if (isLoading) "Stop" else "Refresh",
                                                 tint = MaterialTheme.colorScheme.onSurface,
                                                 modifier = Modifier.size(24.dp)
                                             )
                                         }

                                        // 4. Tab button with tab count outline box
                                        IconButton(
                                            onClick = { currentScreen = Screen.Tabs }
                                        ) {
                                            val tabCount = browserState.tabs.size
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .border(
                                                        width = 2.dp,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        shape = RoundedCornerShape(5.dp)
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = tabCount.toString(),
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = if (tabCount >= 100) 8.sp else if (tabCount >= 10) 10.sp else 12.sp
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }

                                        // 5. Hamburger menu button
                                        IconButton(
                                            onClick = { showMenuSheet = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Menu,
                                                contentDescription = "Menu"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    ) { innerPadding ->
                    // content
                    AppNavHost(
                        currentScreen = currentScreen,
                        onScreenChange = { target ->
                            // Remember where Remote / Dashboard were launched from so Back / close
                            // can return there instead of always the last main tab.
                            if (target == Screen.Remote && currentScreen != Screen.Remote) {
                                remoteOrigin = currentScreen
                            }
                            if (target == Screen.Dashboard && currentScreen != Screen.Dashboard) {
                                dashboardOrigin = currentScreen
                            }
                            currentScreen = target
                        },
                        connectionInitialTab = connectionInitialTab,
                        lastMainScreen = lastMainScreen,
                        onLastMainScreenChange = { lastMainScreen = it },
                        remoteReturnScreen = remoteOrigin ?: lastMainScreen,
                        dashboardReturnScreen = dashboardOrigin ?: lastMainScreen,
                        innerPadding = innerPadding,
                        session = session,
                        onMagnetDetected = { interceptedMagnet = it },
                        sessions = tabManager.sessions,
                        tabManager = tabManager,
                        store = store,
                        browserCanGoBack = browserCanGoBack,
                        isEditing = isEditing,
                        onIsEditingChange = { isEditing = it },
                        isFullscreen = isFullscreen,
                        onIsFullscreenChange = { isFullscreen = it },
                        isBrowserChromeHidden = isBrowserChromeHidden,
                        onIsBrowserChromeHiddenChange = { hidden ->
                            selectedTabId?.let { tabId ->
                                chromeHiddenTabIds = if (hidden) {
                                    chromeHiddenTabIds + tabId
                                } else {
                                    chromeHiddenTabIds - tabId
                                }
                            }
                        },
                        backPressedTime = backPressedTime,
                        onBackPressedTimeChange = { backPressedTime = it },
                        onFinishActivity = { finish() },
                        onFullExit = {
                            // Hard exit: stop binge queues + WS + FGS notif, remove from
                            // recents, then kill the process. finishAndRemoveTask alone can
                            // leave the Application (and TvQueueCoordinator) alive — users
                            // still saw the cast notif and next-episode queue_add after Exit.
                            // TV is not sent Stop; only the phone-side session dies.
                            connectionViewModel.castSessionManager.shutdownForAppExit(
                                this@BrowserActivity,
                            )
                            finishAndRemoveTask()
                            android.os.Process.killProcess(android.os.Process.myPid())
                        },
                        showVideoSheet = showVideoSheet,
                        onShowVideoSheetChange = { showVideoSheet = it },
                        forcedVideos = forcedVideos,
                        onForcedVideosChange = { forcedVideos = it },
                        castSheetInitialMode = castSheetInitialMode,
                        onCastSheetInitialModeChange = { castSheetInitialMode = it },
                        castSheetBrowseOverride = castSheetBrowseOverride,
                        onCastSheetBrowseOverrideChange = { castSheetBrowseOverride = it },
                        scope = scope,
                        showFindBar = showFindBar,
                        onShowFindBarChange = { showFindBar = it },
                        isDesktopMode = isDesktopMode,
                        onIsDesktopModeChange = { isDesktopMode = it },
                        detectVideosEnabled = detectVideosEnabled,
                        onDetectVideosEnabledChange = { composeScope.launch { settingsRepository.setDetectVideos(it) } },
                        currentUrl = currentUrl,
                        onCurrentUrlChange = { currentUrl = it },
                        urlBarTapped = urlBarTapped,
                        onUrlBarTappedChange = { urlBarTapped = it },
                        urlPanelClipboard = urlPanelClipboard,
                        onUrlPanelClipboardChange = { urlPanelClipboard = it },
                        contextMenuUrl = contextMenuUrl,
                        onContextMenuUrlChange = { contextMenuUrl = it },
                        suggestions = suggestions,
                        onHandleBookmarkClick = { handleBookmarkClick() },
                        browserViewContent = { s, onLongPress ->
                            BrowserView(session = s, onLongPressLink = onLongPress)
                        },
                        isTabsSearchVisible = isTabsSearchVisible,
                        onTabsSearchVisibleChange = { isTabsSearchVisible = it },
                        isTabsMultiSelectMode = isTabsMultiSelectMode,
                        onTabsMultiSelectModeChange = { isTabsMultiSelectMode = it },
                        showTabsCloseAllConfirm = showTabsCloseAllConfirm,
                        onTabsCloseAllConfirmChange = { showTabsCloseAllConfirm = it }
                    )

                SheetOverlayContainer(
                    // Hamburger Menu Sheet States
                    showMenuSheet = showMenuSheet,
                    onMenuDismiss = { showMenuSheet = false },
                    menuSheetState = sheetState,
                    currentScreen = currentScreen,
                    isDesktopMode = isDesktopMode,
                    detectVideosEnabled = detectVideosEnabled,
                    onBookmarksClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Bookmarks
                        }
                    },
                    onHistoryClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.History
                        }
                    },
                    onDownloadsClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Downloads
                        }
                    },
                    onAddBookmarkClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            handleBookmarkClick()
                        }
                    },
                    onFindInPageClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            showFindBar = true
                        }
                    },
                    onExtensionsClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            currentScreen = Screen.Extensions
                        }
                    },
                    onToggleDesktopMode = { isDesktopMode = !isDesktopMode },
                    onToggleVideoDetect = { composeScope.launch { settingsRepository.setDetectVideos(!detectVideosEnabled) } },
                    userAgentActive = userAgentPreset != UserAgentPresets.DEFAULT_ID,
                    onUserAgentClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            showUserAgentSheet = true
                        }
                    },
                    onFullScreenClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            isEditing = false
                            showFindBar = false
                            selectedTabId?.let { chromeHiddenTabIds = chromeHiddenTabIds + it }
                        }
                    },

                    // Clear Data Sheet States
                    onClearDataClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showMenuSheet = false
                            showClearDataSheet = true
                        }
                    },
                    showClearDataSheet = showClearDataSheet,
                    onClearDataDismiss = { showClearDataSheet = false },
                    openTabsCount = store.state.tabs.size,
                    onClearDataConfirm = { selection ->
                        showClearDataSheet = false
                        performClearData(selection)
                    },

                    // User Agent Sheet States
                    showUserAgentSheet = showUserAgentSheet,
                    onUserAgentDismiss = { showUserAgentSheet = false },
                    userAgentSheetState = userAgentSheetState,
                    userAgentPreset = userAgentPreset,
                    customUserAgents = customUserAgents,
                    onSelectUserAgentPreset = { id ->
                        composeScope.launch { settingsRepository.setUserAgentPreset(id) }
                        scope.launch { userAgentSheetState.hide() }.invokeOnCompletion {
                            showUserAgentSheet = false
                        }
                    },
                    onAddCustomUserAgent = { name, value ->
                        val agent = com.playbridge.sender.browser.CustomUserAgent(
                            id = java.util.UUID.randomUUID().toString(),
                            name = name,
                            value = value,
                        )
                        composeScope.launch {
                            settingsRepository.addCustomUserAgent(agent)
                            settingsRepository.setUserAgentPreset(UserAgentPresets.customSelectionId(agent.id))
                        }
                        scope.launch { userAgentSheetState.hide() }.invokeOnCompletion {
                            showUserAgentSheet = false
                        }
                    },
                    onDeleteCustomUserAgent = { id ->
                        composeScope.launch {
                            settingsRepository.removeCustomUserAgent(id)
                            if (userAgentPreset == UserAgentPresets.customSelectionId(id)) {
                                settingsRepository.setUserAgentPreset(UserAgentPresets.DEFAULT_ID)
                            }
                        }
                    },

                    // Site Info Sheet States
                    showSiteInfoSheet = showSiteInfoSheet,
                    onSiteInfoDismiss = { showSiteInfoSheet = false },
                    siteSecurityInfo = siteSecurityInfo,
                    isSecureConnection = isSecureConnection,
                    currentUrl = currentUrl,

                    // Cast Sheet States
                    showVideoSheet = showVideoSheet,
                    detectedVideos = detectedVideos,
                    detectedMediaRevision = detectedMediaRevision,
                    pendingContentPayload = pendingContentPayload,
                    isTvPlaying = castRoute is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv &&
                        tvActiveContext == "player",
                    onDismissVideoSheet = {
                        showVideoSheet = false
                        forcePlaylistSheet = null
                        forcedVideos = null
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null
                        pendingContentPayload = null
                    },
                    onVideoClick = onVideoClick@ { video, subs ->
                         when (castRoute) {
                             is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice -> {
                                 Toast.makeText(this@BrowserActivity, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                 return@onVideoClick
                             }
                             is com.playbridge.sender.cast.CastSessionManager.Route.External -> {
                                 val externalTarget = activeExternalDevice ?: return@onVideoClick
                                 val playlist = video.playlistPayload?.map { item ->
                                     item.copy(
                                         player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                         preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                         preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                         default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                         max_bitrate_cap_mbps = maxBitrateCapMbps,
                                     )
                                 }
                                 if (playlist != null) {
                                     startExternalPlaylist(playlist)
                                 } else {
                                     val mediaHeaders = video.headers
                                         ?: com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                                     val routeMode = video.effectiveStreamRoute?.let {
                                         com.playbridge.sender.cast.proxy.StreamRouteMode.fromPrefs(it)
                                     }
                                     connectionViewModel.castToSelectedExternal(
                                         com.playbridge.sender.cast.MediaItem(
                                             url = video.url,
                                             headers = mediaHeaders,
                                             mimeType = video.contentType,
                                             mediaKind = when (video.kind) {
                                                 DetectedMediaKind.AUDIO -> com.playbridge.sender.cast.MediaKind.AUDIO
                                                 DetectedMediaKind.IMAGE -> com.playbridge.sender.cast.MediaKind.IMAGE
                                                 else -> com.playbridge.sender.cast.MediaKind.VIDEO
                                             },
                                             title = video.title ?: selectedTab?.content?.title,
                                             startPositionMs = video.startPositionMs,
                                             visualMetadata = video.visualMetadata,
                                             effectiveRoute = routeMode,
                                             routeReason = video.streamRouteReason,
                                         )
                                     )
                                 }
                                 Toast.makeText(this@BrowserActivity, "Casting to ${externalTarget.name}", Toast.LENGTH_SHORT).show()
                                 showVideoSheet = false
                                 forcePlaylistSheet = null
                                 if (autoSwitchToRemote) currentScreen = Screen.Remote
                                 return@onVideoClick
                             }
                             is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv -> Unit
                         }
                         // A bundle (e.g. "Play All" from the debrid screen) carries its real items in
                         // playlistPayload with a "playlist://…" sentinel URL; send it as a playlist,
                         // not as a single video (otherwise the TV tries to play the sentinel URL).
                         val cmd = if (video.playlistPayload != null) {
                             val items = video.playlistPayload.map {
                                 it.copy(
                                     player_mode = sheetPlayerMode.takeIf { m -> m != "tv" },
                                     preferred_audio_language = preferredAudioLang.takeIf { l -> l.isNotEmpty() },
                                     preferred_subtitle_language = preferredSubLang.takeIf { l -> l.isNotEmpty() },
                                     default_video_quality = defaultVideoQuality.takeIf { q -> q != "Auto" },
                                     max_bitrate_cap_mbps = maxBitrateCapMbps,
                                 )
                             }
                             com.playbridge.shared.protocol.createPlaylistCommandJson(
                                 playbridge.PlaylistPayload(items = items)
                             )
                         } else {
                             val headers = com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                             val effectiveQuality = defaultVideoQuality.takeIf { it != "Auto" }
                             createSingleVideoCommandJson(
                                 PlayPayload(
                                     url = video.url,
                                     title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                     headers = headers,
                                     content_type = video.contentType,
                                     media_kind = video.kind.protocolValue,
                                     detected_by = video.detectedBy,
                                     subtitles = subs.orEmpty(),
                                     player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                     preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                     preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                     default_video_quality = effectiveQuality,
                                     max_bitrate_cap_mbps = maxBitrateCapMbps,
                                 )
                             )
                         }
                         val sent = when (connectionState) {
                             is WebSocketClient.ConnectionState.Connected -> {
                                 linkedPageCastCoordinator.supersedeIfActive()
                                 val ok = connectionViewModel.webSocketClient.send(cmd)
                                 if (ok) {
                                     connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                     if (video.playlistPayload == null) browserViewModel.logHistory(video.url, video.title)
                                 }
                                 ok
                             }
                             else -> false
                         }
                         if (sent) {
                             // Cast-sheet content is browser-detected — no library identity.
                             connectionCoordinator.startLocalPlaybackSession(null, null, null)
                             Toast.makeText(this@BrowserActivity, "Play command sent to TV", Toast.LENGTH_SHORT).show()
                         }
                         showVideoSheet = false
                         forcePlaylistSheet = null
                    },
                    onQueueVideo = onQueueVideo@ { video, subtitles ->
                        if (castRoute !is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv) {
                            val message = if (castRoute is com.playbridge.sender.cast.CastSessionManager.Route.External) {
                                "Queue editing is only available with PlayBridge receivers"
                            } else {
                                "Choose a receiver first"
                            }
                            Toast.makeText(this@BrowserActivity, message, Toast.LENGTH_SHORT).show()
                            return@onQueueVideo
                        }
                        when (connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> {
                                val items: List<PlayPayload> = video.playlistPayload ?: run {
                                    val headers = com.playbridge.sender.cast.VideoDetector.mediaHeaders(video)
                                    listOf(
                                        PlayPayload(
                                            url = video.url,
                                            title = video.title ?: selectedTab?.content?.title ?: "Video from browser",
                                            headers = headers,
                                            content_type = video.contentType,
                                            media_kind = video.kind.protocolValue,
                                            detected_by = video.detectedBy,
                                            subtitles = subtitles.orEmpty(),
                                            player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                            preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                            preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                            default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                            max_bitrate_cap_mbps = maxBitrateCapMbps,
                                        )
                                    )
                                }
                                var sent = false
                                items.forEach { item ->
                                    if (connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createQueueAddCommandJson(item))) sent = true
                                }
                                if (sent) Toast.makeText(this@BrowserActivity, "Added to queue", Toast.LENGTH_SHORT).show()
                            }
                            else -> { Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show() }
                        }
                        showVideoSheet = false
                        forcePlaylistSheet = null
                    },
                    onDownloadVideo = { video ->
                        enqueueEngineDownload(video.url, null, video.contentType, video.headers?.get("User-Agent"), video.headers?.get("Cookie"), video.headers?.get("Referer"), selectedTab?.content?.title)
                    },
                    onClearVideos = { com.playbridge.sender.cast.VideoDetector.clearTab(selectedTabId ?: "") },
                    playerMode = sheetPlayerMode,
                    onPlayerModeChange = { mode ->
                        composeScope.launch { settingsRepository.setTvPlayerMode(mode) }
                    },
                    selectedTvDevice = when (castRoute) {
                        is com.playbridge.sender.cast.CastSessionManager.Route.External -> activeExternalDevice
                        is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv -> tvDevice
                        is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice -> null
                    },
                    onOpenAllDevices = {
                        showVideoSheet = false
                        connectionInitialTab = 1
                        currentScreen = Screen.Connection
                    },
                    browseUrl = castSheetBrowseOverride ?: currentUrl,
                    onBrowseClick = onBrowseClick@ { selectedMode, desktopMode ->
                        if (castRoute !is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv) {
                            val message = if (castRoute is com.playbridge.sender.cast.CastSessionManager.Route.External) {
                                "Browser casting requires a PlayBridge receiver"
                            } else {
                                "Choose a receiver first"
                            }
                            Toast.makeText(this@BrowserActivity, message, Toast.LENGTH_SHORT).show()
                            return@onBrowseClick
                        }
                        if (connectionState !is WebSocketClient.ConnectionState.Connected) {
                            Toast.makeText(this@BrowserActivity, "Not connected to TV", Toast.LENGTH_SHORT).show()
                            return@onBrowseClick
                        }
                        val effectiveUrl = castSheetBrowseOverride ?: currentUrl
                        val cmd = com.playbridge.shared.protocol.createBrowserCommandJson(effectiveUrl, browserMode = selectedMode.takeIf { it != "tv" }, desktopMode = desktopMode.takeIf { it })
                        connectionViewModel.sendCommandAndRecord(cmd, "browser", effectiveUrl, "Browser Page")
                        Toast.makeText(this@BrowserActivity, "Sent to TV", Toast.LENGTH_SHORT).show()
                        showVideoSheet = false
                        forcePlaylistSheet = null
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null

                        if (connectionState is WebSocketClient.ConnectionState.Connected) {
                            if (autoSwitchToRemote) {
                                connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                currentScreen = Screen.Remote
                            }
                        }
                    },
                    onOpenNewTab = { url ->
                        tabManager.createTab(url, store)
                        showVideoSheet = false
                        castSheetInitialMode = "play"
                        castSheetBrowseOverride = null
                        currentScreen = Screen.Browser
                    },
                    initialMode = castSheetInitialMode,
                    onContentClick = onContentClick@ { payload ->
                        when (castRoute) {
                            is com.playbridge.sender.cast.CastSessionManager.Route.ThisDevice -> {
                                Toast.makeText(this@BrowserActivity, "Choose a receiver first", Toast.LENGTH_SHORT).show()
                                return@onContentClick
                            }
                            is com.playbridge.sender.cast.CastSessionManager.Route.External -> {
                                val externalTarget = activeExternalDevice ?: return@onContentClick
                                connectionViewModel.castToSelectedExternal(
                                    com.playbridge.sender.cast.MediaItem(
                                        url = payload.url,
                                        headers = payload.headers,
                                        title = payload.title,
                                        startPositionMs = payload.start_position_ms ?: 0L,
                                        visualMetadata = payload.visual_metadata,
                                    )
                                )
                                browserViewModel.logHistory(payload.url, payload.title)
                                Toast.makeText(this@BrowserActivity, "Casting to ${externalTarget.name}", Toast.LENGTH_SHORT).show()
                                showVideoSheet = false
                                forcePlaylistSheet = null
                                if (autoSwitchToRemote) currentScreen = Screen.Remote
                                return@onContentClick
                            }
                            is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv -> Unit
                        }
                        val cmd = createSingleVideoCommandJson(
                            payload.copy(
                                player_mode = sheetPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps,
                            )
                        )
                        val sent = when (connectionState) {
                            is WebSocketClient.ConnectionState.Connected -> {
                                linkedPageCastCoordinator.supersedeIfActive()
                                val ok = connectionViewModel.webSocketClient.send(cmd)
                                if (ok) {
                                    connectionViewModel.webSocketClient.send(com.playbridge.shared.protocol.createContextQueryJson())
                                    browserViewModel.logHistory(payload.url, payload.title)
                                }
                                ok
                            }
                            else -> false
                        }
                        if (sent) Toast.makeText(this@BrowserActivity, "Play command sent to TV", Toast.LENGTH_SHORT).show()
                        showVideoSheet = false
                        forcePlaylistSheet = null
                    },
                    onQueueContent = onQueueContent@ { payload ->
                        if (castRoute !is com.playbridge.sender.cast.CastSessionManager.Route.NativeTv ||
                            connectionState !is WebSocketClient.ConnectionState.Connected
                        ) {
                            Toast.makeText(this@BrowserActivity, "Not connected to a PlayBridge receiver", Toast.LENGTH_SHORT).show()
                            return@onQueueContent
                        }
                        val queued = payload.copy(
                            player_mode = sheetPlayerMode.takeIf { it != "tv" },
                            preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                            preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                            default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                            max_bitrate_cap_mbps = maxBitrateCapMbps,
                        )
                        if (connectionViewModel.webSocketClient.send(
                                com.playbridge.shared.protocol.createQueueAddCommandJson(queued)
                            )
                        ) {
                            Toast.makeText(this@BrowserActivity, "Added to queue", Toast.LENGTH_SHORT).show()
                            showVideoSheet = false
                            forcePlaylistSheet = null
                        }
                    },
                    // Magnet Parsing Sheet States
                    interceptedMagnet = interceptedMagnet,
                    interceptedTorrentBytes = interceptedTorrentBytes,
                    onDismissMagnet = {
                        interceptedMagnet = null
                        interceptedTorrentBytes = null
                    },
                    onPlayMagnetLinks = { links ->
                        val videos = links.map { link ->
                            playbridge.PlayPayload(
                                url = link.downloadUrl,
                                title = link.filename,
                                player_mode = tvPlayerMode.takeIf { it != "tv" },
                                preferred_audio_language = preferredAudioLang.takeIf { it.isNotEmpty() },
                                preferred_subtitle_language = preferredSubLang.takeIf { it.isNotEmpty() },
                                default_video_quality = defaultVideoQuality.takeIf { it != "Auto" },
                                max_bitrate_cap_mbps = maxBitrateCapMbps
                            )
                        }
                        val detectedVideo = DetectedVideo(
                            url = if (links.size == 1) links.first().downloadUrl else "playlist://magnet",
                            tabId = -1,
                            timestamp = System.currentTimeMillis(),
                            isPlayable = true,
                            detectedBy = "magnet_playlist",
                            playlistPayload = if (links.size > 1) videos else null
                        )
                        scope.launch {
                            forcePlaylistSheet = detectedVideo
                            showVideoSheet = true
                        }
                        interceptedMagnet = null
                        interceptedTorrentBytes = null
                    }
                )

                pendingPopup?.let { popup ->
                    val popupPrefs = remember { getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
                    fun openPopupTab() {
                        scope.launch(Dispatchers.Main) {
                            val tabId = tabManager.createTab(url = popup.popupUrl, store = store, parentId = selectedTab?.id, select = true)
                            // Link the popup's engine session in the store —
                            // EngineMiddleware's EngineObserver takes over
                            // URL/title/nav/state sync and crash handling.
                            store.dispatch(
                                mozilla.components.browser.state.action.EngineAction.LinkEngineSessionAction(
                                    tabId,
                                    popup.engineSession,
                                    skipLoading = true
                                )
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().padding(bottom = innerPadding.calculateBottomPadding()), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                        PopupBlockedBar(
                            host = popup.openerHost,
                            onAllowOnce = { openPopupTab(); pendingPopup = null; pendingPopupState.value = null },
                            onAlwaysAllow = {
                                 composeScope.launch { settingsRepository.addPopupWhitelist(popup.openerHost) }
                                 openPopupTab()
                                 pendingPopup = null
                                 pendingPopupState.value = null
                             },
                            onDismiss = {
                                try { popup.engineSession.close() } catch (_: Exception) {}
                                pendingPopup = null
                                pendingPopupState.value = null
                            }
                        )
                    }
                }

                DownloadConfirmDialog(
                    pendingDownload = pendingDownload,
                    onConfirm = { download ->
                        enqueueEngineDownload(download.url, download.fileName, download.contentType, download.userAgent, download.cookie, download.referer, selectedTab?.content?.title)
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onPlayOnTv = { download ->
                        val headers = mutableMapOf<String, String>()
                        if (download.userAgent != null) headers["User-Agent"] = download.userAgent
                        if (download.referer != null) headers["Referer"] = download.referer
                        if (download.cookie != null) headers["Cookie"] = download.cookie

                        val video = DetectedVideo(
                            url = download.url,
                            tabId = -1,
                            contentType = download.contentType,
                            detectedBy = "download",
                            headers = headers
                        )

                        com.playbridge.sender.downloads.engine.BrowserResponseStore.discard(download.url)
                        forcedVideos = listOf(video)
                        showVideoSheet = true
                        pendingDownload = null
                        pendingDownloadState.value = null
                    },
                    onDismiss = {
                        pendingDownload?.url?.let { com.playbridge.sender.downloads.engine.BrowserResponseStore.discard(it) }
                        pendingDownload = null; pendingDownloadState.value = null
                    }
                 )

                pendingStremioAddon?.let { addonUri ->
                    // Convert stremio:// to https:// for a readable host, then derive a label.
                    val httpsUrl = addonUri.replaceFirst("stremio://", "https://")
                    val host = runCatching { java.net.URI(httpsUrl).host }.getOrNull() ?: httpsUrl
                    AlertDialog(
                        onDismissRequest = { if (!isInstallingStremioAddon) pendingStremioAddon = null },
                        title = { Text("Install Stremio addon?") },
                        text = {
                            Column {
                                Text(host, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    httpsUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !isInstallingStremioAddon,
                                onClick = {
                                    isInstallingStremioAddon = true
                                    scope.launch {
                                        val result = runCatching { addonRepository.installAddon(addonUri) }.getOrNull()
                                        isInstallingStremioAddon = false
                                        pendingStremioAddon = null
                                        Toast.makeText(
                                            this@BrowserActivity,
                                            if (result != null) "Installed: ${result.name}" else "Failed to install addon",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            ) {
                                Text(if (isInstallingStremioAddon) "Installing…" else "Install")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                enabled = !isInstallingStremioAddon,
                                onClick = { pendingStremioAddon = null }
                            ) { Text("Cancel") }
                        }
                    )
                }

                // Pairing/Connection Dialog Popup — the single connection popup. Also shown
                // across the reconnect retry cycle so the user sees progress and can bail
                // out: the primary action plays on this device; staying keeps trying.
                val state = connectionState
                val reconnect by connectionViewModel.reconnectStatus.collectAsState()
                val isPairingState = state is WebSocketClient.ConnectionState.WaitingForCodeInput ||
                    state is WebSocketClient.ConnectionState.VerifyingCode
                if (state is WebSocketClient.ConnectionState.Connecting ||
                    isPairingState ||
                    reconnect != null) {

                    var codeText by remember { mutableStateOf("") }
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(state) {
                        if (state is WebSocketClient.ConnectionState.WaitingForCodeInput) {
                            // After a wrong entry the state is re-emitted; clear the field so the
                            // user retypes fresh rather than editing the rejected code.
                            if (state.lastCodeWrong) codeText = ""
                            delay(100)
                            focusRequester.requestFocus()
                        }
                    }

                    AlertDialog(
                        onDismissRequest = {
                            if (isPairingState) connectionViewModel.disconnect()
                            else connectionViewModel.cancelConnectingToThisDevice()
                        },
                        title = {
                            Text(
                                text = when {
                                    state is WebSocketClient.ConnectionState.WaitingForCodeInput ->
                                        "Pairing with ${state.serverName}"
                                    state is WebSocketClient.ConnectionState.VerifyingCode ->
                                        "Verifying Code"
                                    reconnect != null ->
                                        "Reconnecting to ${reconnect?.deviceName ?: "TV"}"
                                    state is WebSocketClient.ConnectionState.Connecting ->
                                        "Connecting to ${state.serverName}"
                                    else -> "Connecting to TV"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        text = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (state is WebSocketClient.ConnectionState.WaitingForCodeInput) {
                                    Text(
                                        text = "Enter the 6-digit code shown on your TV",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )

                                    OutlinedTextField(
                                        value = codeText,
                                        onValueChange = { input ->
                                            if (input.length <= 6 && input.all { it.isDigit() }) {
                                                codeText = input
                                                if (input.length == 6) {
                                                    connectionViewModel.submitPairingCode(input)
                                                }
                                            }
                                        },
                                        placeholder = { Text("000 000") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(
                                            textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp,
                                            fontSize = 20.sp
                                        ),
                                        modifier = Modifier
                                            .width(180.dp)
                                            .focusRequester(focusRequester)
                                    )

                                    if (state.lastCodeWrong) {
                                        Text(
                                            text = "Incorrect code — ${state.attemptsLeft} " +
                                                if (state.attemptsLeft == 1) "try left" else "tries left",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                    }
                                    val rc = reconnect
                                    Text(
                                        text = when {
                                            state is WebSocketClient.ConnectionState.VerifyingCode ->
                                                "Verifying code with ${state.serverName}…"
                                            rc != null ->
                                                "Reconnecting… (attempt ${rc.attempt})"
                                            state is WebSocketClient.ConnectionState.Connecting ->
                                                "Connecting to ${state.serverName}…"
                                            else -> "Connecting to TV…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (rc != null || state is WebSocketClient.ConnectionState.Connecting) {
                                        Text(
                                            text = "Stay on this screen to keep trying, or play on this device instead.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            when {
                                state is WebSocketClient.ConnectionState.WaitingForCodeInput -> {
                                    Button(
                                        onClick = { connectionViewModel.submitPairingCode(codeText) },
                                        enabled = codeText.length == 6
                                    ) {
                                        Text("Verify")
                                    }
                                }
                                // Connecting/reconnecting: the primary (default) action is to
                                // stop chasing the TV and play here. Simply staying on the dialog
                                // keeps the connection attempts running — so there's no separate
                                // "keep trying" button.
                                !isPairingState -> {
                                    Button(onClick = { connectionViewModel.cancelConnectingToThisDevice() }) {
                                        Text("Play on this device")
                                    }
                                }
                            }
                        },
                        dismissButton = {
                            // Only pairing dialogs get a Cancel; the connecting/reconnecting
                            // dialog's single action lives in confirmButton above.
                            if (isPairingState) {
                                TextButton(onClick = { connectionViewModel.disconnect() }) {
                                    Text("Cancel")
                                }
                            }
                        }
                    )
                }
             }
         }
     }
 }

    override fun onDestroy() {
        Log.d("PB_STARTUP", "onDestroy: isFinishing=$isFinishing, sessions=${tabManager.sessions.size}")
        // Only tear sessions down when the Activity is actually finishing.
        // On configuration-change recreation (theme, split screen, etc.) the
        // singleton TabManager keeps the live sessions and the new Activity
        // re-renders them — closing them here caused blank tabs + lost history.
        if (isFinishing) {
            tabManager.closeAllSessions()
        }
        super.onDestroy()
    }

    @Composable
    fun BrowserView(session: EngineSession, onLongPressLink: (String) -> Unit) {
        // ONE persistent GeckoEngineView for all tabs (Fenix-style):
        // SessionFeature/EngineViewPresenter observes the store and renders
        // whatever tab is selected — creating engine sessions on demand,
        // releasing the view for crashed tabs, and re-rendering on session
        // changes. No more per-tab view recreation (the old `key(session)`
        // AndroidView), which caused surface churn on every tab switch.
        // `session` is unused for rendering but kept for callsite compatibility.
        val featureHolder = remember { arrayOfNulls<mozilla.components.feature.session.SessionFeature>(1) }
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Observe (never consume) downs so a Gecko long-press can later be
                    // suppressed when it started inside a system gesture edge zone.
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        EdgeLongPressGuard.recordDown(
                            down.position.x,
                            down.position.y,
                            size.width,
                            size.height,
                        )
                    }
                },
            factory = { context ->
                GeckoEngineView(context).also { view ->
                    featureHolder[0] = mozilla.components.feature.session.SessionFeature(
                        Components.store,
                        Components.sessionUseCases.goBack,
                        Components.sessionUseCases.goForward,
                        view
                    ).also { it.start() }
                }
            },
            onRelease = {
                // release() stops the presenter and releases the engine view.
                try {
                    featureHolder[0]?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "BrowserView: error releasing session feature", e)
                }
                featureHolder[0] = null
            }
        )
    }

    @Composable
    private fun MenuSheetItem(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        selected: Boolean = false,
        tint: androidx.compose.ui.graphics.Color? = null,
        labelColor: androidx.compose.ui.graphics.Color? = null,
        onClick: () -> Unit
    ) {
        Surface(
            onClick = onClick,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = tint ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = labelColor ?: if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable
    private fun MenuGridItem(
        icon: ImageVector,
        label: String,
        modifier: Modifier = Modifier,
        selected: Boolean = false,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        labelColor: Color = MaterialTheme.colorScheme.onSurface,
        onClick: () -> Unit
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer 
                        else Color.Transparent
                    )
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) MaterialTheme.colorScheme.primary else tint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else if (labelColor == Color.Green) labelColor else labelColor.copy(alpha = 0.7f),
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
