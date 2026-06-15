package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.playbridge.sender.cast.CastSheet
import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.sender.data.debrid.DebridRepository
import com.playbridge.sender.data.debrid.DebridUnrestrictedLink
import com.playbridge.sender.data.library.StremioSubtitleService
import com.playbridge.sender.library.MagnetParsingSheet
import playbridge.PlayPayload
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetOverlayContainer(
    // Hamburger Menu Sheet States
    showMenuSheet: Boolean,
    onMenuDismiss: () -> Unit,
    menuSheetState: SheetState,
    currentScreen: Screen,
    isDesktopMode: Boolean,
    detectVideosEnabled: Boolean,
    onBookmarksClick: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    onFindInPageClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleVideoDetect: () -> Unit,

    // Site Info Sheet States
    showSiteInfoSheet: Boolean,
    onSiteInfoDismiss: () -> Unit,
    siteSecurityInfo: SiteSecurityInfo?,
    isSecureConnection: Boolean,
    currentUrl: String,

    // Cast Sheet States
    showVideoSheet: Boolean,
    detectedVideos: List<DetectedVideo>,
    pendingContentPayload: PlayPayload?,
    isTvPlaying: Boolean,
    onDismissVideoSheet: () -> Unit,
    onVideoClick: (DetectedVideo, List<String>?) -> Unit,
    onQueueVideo: (DetectedVideo, List<String>?) -> Unit = { _, _ -> },
    onDownloadVideo: (DetectedVideo) -> Unit,
    onClearVideos: () -> Unit,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    selectedTvDevice: com.playbridge.sender.model.TvDevice? = null,
    onOpenAllDevices: () -> Unit = {},
    browseUrl: String = "",
    onBrowseClick: ((String, Boolean) -> Unit)? = null,
    onOpenNewTab: ((String) -> Unit)? = null,
    initialMode: String = "play",
    mediaflowProxyUrl: String = "",
    mediaflowProxyPassword: String = "",
    mediaflowAutoSelect: Boolean = true,
    onContentClick: (PlayPayload) -> Unit = {},
    onQueueContent: (PlayPayload) -> Unit = {},

    // Magnet Parsing Sheet States
    interceptedMagnet: String?,
    interceptedTorrentBytes: ByteArray?,
    onDismissMagnet: () -> Unit,
    onPlayMagnetLinks: (List<DebridUnrestrictedLink>) -> Unit
) {
    val subtitleService: StremioSubtitleService = koinInject()
    val debridRepository: DebridRepository = koinInject()

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Hamburger Menu Sheet
        if (showMenuSheet) {
            MenuSheet(
                sheetState = menuSheetState,
                currentScreen = currentScreen,
                isDesktopMode = isDesktopMode,
                detectVideosEnabled = detectVideosEnabled,
                onDismissRequest = onMenuDismiss,
                onBookmarksClick = onBookmarksClick,
                onHistoryClick = onHistoryClick,
                onDownloadsClick = onDownloadsClick,
                onAddBookmarkClick = onAddBookmarkClick,
                onFindInPageClick = onFindInPageClick,
                onExtensionsClick = onExtensionsClick,
                onSettingsClick = onSettingsClick,
                onToggleDesktopMode = onToggleDesktopMode,
                onToggleVideoDetect = onToggleVideoDetect
            )
        }

        // 2. Site Info Sheet
        if (showSiteInfoSheet) {
            val context = LocalContext.current
            val sheetInfo = siteSecurityInfo ?: SiteSecurityInfo(
                isSecure = isSecureConnection,
                host = try {
                    java.net.URI(currentUrl).host ?: currentUrl
                } catch (e: Exception) {
                    currentUrl
                }
            )
            SiteInfoSheet(info = sheetInfo, onDismiss = onSiteInfoDismiss)
        }

        // 3. Cast Sheet
        if (showVideoSheet) {
            CastSheet(
                videos = detectedVideos,
                onDismiss = onDismissVideoSheet,
                onVideoClick = onVideoClick,
                onQueueVideo = onQueueVideo,
                onDownload = onDownloadVideo,
                onClear = onClearVideos,
                isTvPlaying = isTvPlaying,
                playerMode = playerMode,
                onPlayerModeChange = onPlayerModeChange,
                selectedTvDevice = selectedTvDevice,
                onOpenAllDevices = onOpenAllDevices,
                browseUrl = browseUrl,
                onBrowseClick = onBrowseClick,
                onOpenNewTab = onOpenNewTab,
                initialMode = initialMode,
                mediaflowProxyUrl = mediaflowProxyUrl,
                mediaflowProxyPassword = mediaflowProxyPassword,
                mediaflowAutoSelect = mediaflowAutoSelect,
                subtitleService = subtitleService,
                contentPayload = pendingContentPayload,
                onContentClick = onContentClick,
                onQueueContent = onQueueContent,
                detectionEnabled = detectVideosEnabled,
                onEnableDetection = if (!detectVideosEnabled) onToggleVideoDetect else null
            )
        }

        // 4. Magnet Parsing Sheet
        if (interceptedMagnet != null || interceptedTorrentBytes != null) {
            val context = LocalContext.current
            var provider by remember(interceptedMagnet, interceptedTorrentBytes) {
                mutableStateOf<com.playbridge.sender.data.debrid.DebridProvider?>(null)
            }
            var hasCheckedProvider by remember(interceptedMagnet, interceptedTorrentBytes) {
                mutableStateOf(false)
            }

            LaunchedEffect(interceptedMagnet, interceptedTorrentBytes) {
                val active = debridRepository.getActiveProvider()
                provider = active
                hasCheckedProvider = true
                if (active == null) {
                    Toast.makeText(
                        context,
                        "No Debrid provider configured. Configure it in Settings.",
                        Toast.LENGTH_LONG
                    ).show()
                    onDismissMagnet()
                }
            }

            if (hasCheckedProvider && provider != null) {
                MagnetParsingSheet(
                    magnetUri = interceptedMagnet,
                    torrentBytes = interceptedTorrentBytes,
                    provider = provider!!,
                    onDismiss = onDismissMagnet,
                    onPlayLinks = onPlayMagnetLinks
                )
            }
        }
    }
}
