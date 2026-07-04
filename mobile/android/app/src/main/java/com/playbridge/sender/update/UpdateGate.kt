package com.playbridge.sender.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.runtime.collectAsState

/**
 * Renders the update flow driven by [UpdateChecker.state]. Drop it once near the top of
 * the activity's composition; it shows nothing while [UpdateState.Idle].
 *
 * - [UpdateState.Available] → notify dialog (Play Store or sideload action)
 * - [UpdateState.Downloading] / [UpdateState.Installing] → progress dialog
 * - manual [UpdateState.Checking] / [UpdateState.UpToDate] / [UpdateState.Error] → toast
 */
@Composable
fun UpdateGate(checker: UpdateChecker) {
    val context = LocalContext.current
    val state by checker.state.collectAsState()

    // Lightweight feedback for the manual "Check for updates" path.
    LaunchedEffect(state) {
        when (val s = state) {
            is UpdateState.UpToDate ->
                Toast.makeText(context, "You're on the latest version.", Toast.LENGTH_SHORT).show()
            is UpdateState.Error ->
                if (s.manual) Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }

    when (val s = state) {
        is UpdateState.Available -> AvailableDialog(
            info = s.info,
            onAccept = {
                if (s.info.source == InstallSource.SIDELOADED && !checker.canInstall()) {
                    checker.requestInstallPermission()
                } else {
                    checker.accept(s.info)
                }
            },
            onDismiss = checker::dismiss,
        )

        is UpdateState.Downloading -> ProgressDialog(
            title = "Downloading ${s.info.version}",
            fraction = s.fraction,
        )

        is UpdateState.Installing -> ProgressDialog(
            title = "Installing ${s.info.version}",
            fraction = null,
        )

        else -> Unit
    }
}

@Composable
private fun AvailableDialog(
    info: UpdateInfo,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isPlay = info.source == InstallSource.PLAY_STORE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Text(
                if (isPlay) {
                    "Version ${info.version} is available. Open the Play Store to update."
                } else {
                    "Version ${info.version} is available. Download and install it now?"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(if (isPlay) "Open Play Store" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}

@Composable
private fun ProgressDialog(title: String, fraction: Float?) {
    AlertDialog(
        onDismissRequest = { /* non-cancelable while working */ },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}
