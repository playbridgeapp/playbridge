package com.playbridge.sender.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.PlayOnStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.diagnostics.CastAttemptDiagnostics
import com.playbridge.sender.history.CastReplayStore
import org.koin.compose.koinInject

/** Gathers what each Dashboard tile's subtitle reports from the stores its screen already reads. */
@Composable
internal fun rememberDashboardTileStates(
    playOnStatus: PlayOnStatus,
    castSessionManager: CastSessionManager,
    nativeConnected: Boolean,
    iptvPlaylists: Int,
    collections: Int,
    debridProvider: String?,
    webSocketClient: WebSocketClient = koinInject(),
    castDiagnostics: CastAttemptDiagnostics = koinInject(),
    replayStore: CastReplayStore = koinInject(),
): DashboardTileStates {
    val context = LocalContext.current
    val mirror by castSessionManager.screenMirrorState.collectAsStateWithLifecycle()
    val route by castSessionManager.route.collectAsStateWithLifecycle()
    val session by castSessionManager.sessionState.collectAsStateWithLifecycle()
    val capabilities by webSocketClient.tvCapabilitiesState.collectAsStateWithLifecycle()
    val attempts by castDiagnostics.attempts.collectAsStateWithLifecycle()
    val replaySources by replayStore.sources.collectAsStateWithLifecycle()
    val commandHistoryFlow = remember { DatabaseProvider.getDatabase(context).commandHistoryDao().getAll() }
    val commandHistory by commandHistoryFlow.collectAsStateWithLifecycle(initialValue = null)

    // Same permission set Phone files asks for; any one of them lists media.
    val mediaAccess = mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    val externalSelected = route is CastSessionManager.Route.External
    val externalSupported = externalSelected &&
        session.targetKind in setOf(TargetKind.GOOGLE_CAST, TargetKind.DLNA) &&
        Capability.SCREEN_MIRROR in session.capabilities

    // Diagnostics attempts never hold titles; their replay source might.
    val newestAttempt = attempts.maxByOrNull { it.startedAtMs }
    val newestLegacy = commandHistory?.maxByOrNull { it.timestamp }
    val lastCastTitle = when {
        newestAttempt != null && newestAttempt.startedAtMs >= (newestLegacy?.timestamp ?: 0L) ->
            replaySources[newestAttempt.id]?.title
        else -> newestLegacy?.title
    }?.takeIf { it.isNotBlank() }

    return DashboardTileStates(
        mediaAccess = mediaAccess,
        mirror = mirrorTileState(
            mirroring = mirror.isActive,
            tvChosen = playOnStatus.tvChosen,
            tvName = playOnStatus.deviceName,
            externalSelected = externalSelected,
            externalSupported = externalSupported,
            nativeConnected = nativeConnected,
            nativeSupported = capabilities.screenMirrorWebRtc,
        ),
        iptvPlaylists = iptvPlaylists,
        collections = collections,
        debridLinked = debridProvider != null,
        debridProvider = debridProvider,
        castHistoryEmpty = commandHistory?.let { it.isEmpty() && attempts.isEmpty() },
        lastCastTitle = lastCastTitle,
    )
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
