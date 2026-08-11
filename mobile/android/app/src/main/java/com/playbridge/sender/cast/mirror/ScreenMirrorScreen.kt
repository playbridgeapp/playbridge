package com.playbridge.sender.cast.mirror

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.playbridge.sender.cast.CastSessionService
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.connection.WebSocketClient
import org.koin.compose.koinInject

/** Hub destination for the Android-native, paired-receiver-only mirror flow. */
@Composable
fun ScreenMirrorScreen(
    onBack: () -> Unit,
    coordinator: ScreenMirrorCoordinator = koinInject(),
    webSocketClient: WebSocketClient = koinInject(),
    castSessionManager: CastSessionManager = koinInject(),
) {
    val context = LocalContext.current
    val state by coordinator.state.collectAsState()
    val connection by webSocketClient.connectionState.collectAsState()
    val capabilities by webSocketClient.tvCapabilitiesState.collectAsState()
    val route by castSessionManager.route.collectAsState()
    var qualityId by rememberSaveable { mutableStateOf(ScreenMirrorCoordinator.Quality.DEFAULT.id) }
    var deviceAudioEnabled by rememberSaveable {
        mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
    }
    var pendingOptions by remember { mutableStateOf<ScreenMirrorCoordinator.Options?>(null) }
    var audioNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedQuality = ScreenMirrorCoordinator.Quality.fromId(qualityId)
    val projectionManager = remember(context) {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val options = pendingOptions
        pendingOptions = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CastSessionService.startScreenMirror(
                context,
                result.data!!,
                options ?: ScreenMirrorCoordinator.Options(selectedQuality, deviceAudio = false),
            )
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            pendingOptions = pendingOptions?.copy(deviceAudio = false)
            audioNotice = "Audio permission was not granted. Mirroring will continue with video only."
        }
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    val connected = connection is WebSocketClient.ConnectionState.Connected
    val supported = capabilities.screenMirrorWebRtc
    val nativeReceiverSelected = route !is CastSessionManager.Route.External
    val canStart = connected && supported && nativeReceiverSelected && !state.isActive

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        androidx.compose.material3.Icon(
            Icons.AutoMirrored.Filled.ScreenShare,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Screen Mirror", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        val description = when {
            state.phase == ScreenMirrorCoordinator.Phase.MIRRORING -> "Mirroring to your PlayBridge TV"
            state.isActive -> state.message ?: "Connecting to TV…"
            !connected -> "Connect to a PlayBridge TV to start mirroring."
            !nativeReceiverSelected -> "Select your PlayBridge TV instead of an external receiver to mirror."
            !supported -> "This TV needs an update before it can receive WebRTC screen mirroring."
            state.phase == ScreenMirrorCoordinator.Phase.FAILED -> state.message ?: "Could not start mirroring."
            else -> "Share this phone’s screen directly with your PlayBridge TV."
        }
        Text(description, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Some protected apps intentionally show a black screen or block their audio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Device audio", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "Share audio played by compatible apps. Microphone audio is never sent."
                    } else {
                        "Requires Android 10 or newer. This device will mirror video only."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = deviceAudioEnabled,
                enabled = !state.isActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                onCheckedChange = {
                    deviceAudioEnabled = it
                    audioNotice = null
                },
            )
        }
        if (deviceAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Android may label the required playback-capture permission as microphone access, " +
                    "but PlayBridge captures only device playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val activeAudioMessage = when (state.audioStatus) {
            ScreenMirrorCoordinator.AudioStatus.STARTING -> "Starting device audio…"
            ScreenMirrorCoordinator.AudioStatus.ACTIVE -> "Device audio is being shared."
            ScreenMirrorCoordinator.AudioStatus.UNAVAILABLE -> state.audioMessage
            ScreenMirrorCoordinator.AudioStatus.DISABLED -> null
        }
        (activeAudioMessage ?: audioNotice)?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.audioStatus == ScreenMirrorCoordinator.AudioStatus.UNAVAILABLE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(20.dp))
        Text("Quality", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScreenMirrorCoordinator.Quality.entries.forEach { quality ->
                FilterChip(
                    selected = selectedQuality == quality,
                    enabled = !state.isActive,
                    onClick = { qualityId = quality.id },
                    label = { Text(quality.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val qualityMessage = when (selectedQuality) {
            ScreenMirrorCoordinator.Quality.DEFAULT ->
                "Recommended · up to 1280 pixels and 6 Mbps. This is the current default quality."
            ScreenMirrorCoordinator.Quality.HIGH ->
                "Up to 1920 pixels and 10 Mbps. Uses more Wi-Fi bandwidth, battery, and processing power."
            ScreenMirrorCoordinator.Quality.MAXIMUM ->
                "Up to 2560 pixels and 16 Mbps. May stutter, heat the phone, or fail on slower networks and TVs."
        }
        Text(
            qualityMessage,
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedQuality == ScreenMirrorCoordinator.Quality.DEFAULT) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(Modifier.height(28.dp))
        if (state.isActive) {
            Button(onClick = { coordinator.stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop mirroring") }
        } else {
            Button(
                enabled = canStart,
                onClick = {
                    audioNotice = null
                    val options = ScreenMirrorCoordinator.Options(
                        quality = selectedQuality,
                        deviceAudio = deviceAudioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    )
                    pendingOptions = options
                    if (options.deviceAudio && ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start mirroring") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
