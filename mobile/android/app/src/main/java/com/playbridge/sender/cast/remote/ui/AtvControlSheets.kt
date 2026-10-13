package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.atv.AtvPairingController
import com.playbridge.sender.cast.atv.AtvPairingFailure
import com.playbridge.sender.cast.atv.AtvPairingPhase
import com.playbridge.sender.cast.atv.AtvPairingUi
import com.playbridge.sender.cast.atv.normalizePairingCode
import com.playbridge.sender.cast.atv.normalizePairingCodeInput
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

internal val ATV_LAUNCH_APPS = listOf(
    "YouTube" to "com.google.android.youtube.tv",
    "Netflix" to "com.netflix.ninja",
    "Prime Video" to "com.amazon.amazonvideo.livingroom",
    "Disney+" to "com.disney.disneyplus",
    "Plex" to "com.plexapp.android",
    "PlayBridge" to "com.playbridge.player",
)

@Composable
internal fun AtvPairingRoute(onDismiss: () -> Unit, controller: AtvPairingController = koinInject()) {
    val ui by controller.pairing.collectAsState()
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    LaunchedEffect(Unit) {
        val phase = controller.pairing.value.phase
        if (phase == AtvPairingPhase.Idle || phase == AtvPairingPhase.Failed || phase == AtvPairingPhase.Paired) controller.begin()
    }
    LaunchedEffect(ui.phase) {
        if (ui.phase == AtvPairingPhase.Paired) onDismiss()
    }
    AtvPairingSheet(
        ui = ui,
        onSubmit = controller::submitCode,
        onCancel = {
            controller.cancel()
            onDismiss()
        },
        onRetry = controller::begin,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtvPairingSheet(
    ui: AtvPairingUi,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        RemoteDialogVolumeKeys()
        AtvPairingContent(ui, onSubmit, onCancel, onRetry)
    }
}

@Composable
internal fun AtvPairingContent(
    ui: AtvPairingUi,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val fieldInView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Full control", style = MaterialTheme.typography.titleMedium)
        Text("Lets PlayBridge work like your TV remote: any app, Home, typing.")
        when (ui.phase) {
            AtvPairingPhase.Starting -> Text("Starting pairing…")
            AtvPairingPhase.Verifying -> Text("Checking the code…")
            AtvPairingPhase.Failed -> {
                Text(pairingMessage(ui.failure))
                Button(onClick = onRetry, modifier = Modifier.semantics { contentDescription = "Retry pairing" }) { Text("Retry") }
            }
            AtvPairingPhase.WaitingForCode, AtvPairingPhase.Idle, AtvPairingPhase.Paired -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = normalizePairingCodeInput(it)
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldInView)
                        .onFocusChanged { if (it.isFocused) scope.launch { fieldInView.bringIntoView() } }
                        .semantics { contentDescription = "Pairing code" },
                    label = { Text("Pairing code") },
                    supportingText = { Text("6 characters shown on the TV") },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                Button(
                    onClick = {
                        val normalized = normalizePairingCode(code)
                        if (normalized == null) invalid = true else onSubmit(normalized)
                    },
                    enabled = ui.phase == AtvPairingPhase.WaitingForCode,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pair" },
                ) { Text("Pair") }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.semantics { contentDescription = "Cancel pairing" }) { Text("Cancel") }
    }
}

private fun pairingMessage(failure: AtvPairingFailure?): String = when (failure) {
    AtvPairingFailure.BadCode -> "pairing code was rejected"
    AtvPairingFailure.Unreachable -> "Android TV remote is unreachable"
    AtvPairingFailure.Timeout -> "Android TV remote timed out"
    AtvPairingFailure.Closed -> "Android TV remote connection closed"
    AtvPairingFailure.Protocol, null -> "invalid Android TV remote command or protocol message"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtvOpenAppSheet(dispatch: (RemoteAction) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RemoteDialogVolumeKeys()
        AtvOpenAppContent(dispatch, onDismiss)
    }
}

@Composable
internal fun AtvOpenAppContent(dispatch: (RemoteAction) -> Unit, onDismiss: () -> Unit) {
    var other by remember { mutableStateOf("") }
    val fieldInView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Open app", style = MaterialTheme.typography.titleMedium)
        ATV_LAUNCH_APPS.forEach { (label, packageId) ->
            OutlinedButton(
                onClick = {
                    dispatch(RemoteAction.LaunchTvApp(packageId))
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(label)
                    Text(packageId, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedTextField(
            value = other,
            onValueChange = { other = it.trim() },
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(fieldInView)
                .onFocusChanged { if (it.isFocused) scope.launch { fieldInView.bringIntoView() } }
                .semantics { contentDescription = "App package" },
            label = { Text("Other…") },
            singleLine = true,
        )
        Button(
            onClick = {
                dispatch(RemoteAction.LaunchTvApp(other))
                onDismiss()
            },
            enabled = other.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Launch") }
    }
}
