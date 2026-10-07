package com.playbridge.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.playbridge.player.R
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.theme.ThemedDialog
import com.playbridge.player.userscript.InstalledScriptSummary
import com.playbridge.player.userscript.ScriptApprovalState
import com.playbridge.player.userscript.UserScriptReview
import com.playbridge.player.userscript.UserScriptRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UserScriptApprovalPrompt(review: UserScriptReview) {
    val denyFocus = remember { FocusRequester() }
    BackHandler { ServerService.denyPendingUserScript() }
    LaunchedEffect(review) {
        try {
            denyFocus.requestFocus()
        } catch (_: Exception) {
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        UserScriptReviewCard(
            title = stringResource(R.string.user_script_prompt_title),
            review = review,
            showTimeout = true,
            denyFocus = denyFocus,
            onDeny = { ServerService.denyPendingUserScript() },
            onApprove = { ServerService.approvePendingUserScript() },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UserScriptSettingsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { UserScriptRuntime.get(context) }
    val scope = rememberCoroutineScope()
    var installEnabled by remember { mutableStateOf(false) }
    var scripts by remember { mutableStateOf(emptyList<InstalledScriptSummary>()) }
    var reviewing by remember { mutableStateOf<InstalledScriptSummary?>(null) }
    var removing by remember { mutableStateOf<InstalledScriptSummary?>(null) }

    fun reload() {
        controller.ensureMigrated()
        installEnabled = controller.isInstallEnabled()
        scripts = controller.listInstalled()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(controller) { reload() }

    reviewing?.let { script ->
        UserScriptReviewDialog(
            review = script.toReview(senderName = null),
            onDeny = { reviewing = null },
            onApprove = {
                reviewing = null
                scope.launch {
                    withContext(Dispatchers.IO) { controller.approveInstalled(script.name) }
                    reload()
                }
            },
        )
    }
    removing?.let { script ->
        UserScriptRemoveDialog(
            name = script.name,
            onCancel = { removing = null },
            onRemove = {
                removing = null
                scope.launch {
                    withContext(Dispatchers.IO) { controller.removeInstalled(script.name) }
                    reload()
                }
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        SettingToggleItem(
            label = stringResource(R.string.user_script_toggle),
            description = stringResource(R.string.user_script_toggle_desc),
            checked = installEnabled,
            onCheckedChange = { enabled ->
                installEnabled = enabled
                controller.setInstallEnabled(enabled)
            },
        )
        Text(
            text = stringResource(R.string.user_script_installed_header),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (scripts.isEmpty()) {
            Text(
                text = stringResource(R.string.user_script_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            scripts.forEach { script ->
                InstalledScriptRow(
                    script = script,
                    onApprove = { reviewing = script },
                    onRemove = { removing = script },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InstalledScriptRow(
    script: InstalledScriptSummary,
    onApprove: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(script.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = sitesLabel(script.matches, script.runsOnAllSites),
                style = MaterialTheme.typography.bodyMedium,
                color = if (script.runsOnAllSites) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stateLabel(script.state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (script.state != ScriptApprovalState.APPROVED) {
                    Button(onClick = onApprove) {
                        Text(stringResource(R.string.user_script_approve))
                    }
                }
                OutlinedButton(onClick = onRemove) {
                    Text(stringResource(R.string.user_script_remove))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptReviewDialog(
    review: UserScriptReview,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
) {
    val denyFocus = remember { FocusRequester() }
    LaunchedEffect(review) {
        try {
            denyFocus.requestFocus()
        } catch (_: Exception) {
        }
    }
    ThemedDialog(onDismissRequest = onDeny) {
        UserScriptReviewCard(
            title = stringResource(R.string.user_script_review_title),
            review = review,
            showTimeout = false,
            denyFocus = denyFocus,
            onDeny = onDeny,
            onApprove = onApprove,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptRemoveDialog(
    name: String,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(name) {
        try {
            cancelFocus.requestFocus()
        } catch (_: Exception) {
        }
    }
    ThemedDialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 640.dp)) {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.user_script_remove_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.user_script_remove_body, name),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) {
                        Text(stringResource(R.string.user_script_cancel))
                    }
                    Button(
                        onClick = onRemove,
                        colors = ButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(R.string.user_script_remove))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptReviewCard(
    title: String,
    review: UserScriptReview,
    showTimeout: Boolean,
    denyFocus: FocusRequester,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
) {
    val sender = review.senderName?.takeIf { it.isNotBlank() }
        ?: stringResource(if (showTimeout) R.string.user_script_unknown_device else R.string.user_script_local_source)
    Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 880.dp)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 40.dp, vertical = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.user_script_from, sender),
                style = MaterialTheme.typography.titleLarge,
            )
            ReviewLine(stringResource(R.string.user_script_name), review.scriptName)
            ReviewLine(
                stringResource(R.string.user_script_size),
                pluralStringResource(R.plurals.user_script_size_bytes, review.sizeBytes, review.sizeBytes),
            )
            ReviewLine(
                stringResource(R.string.user_script_hash),
                review.hashPrefix,
                monospace = true,
            )
            Text(
                text = stringResource(R.string.user_script_matches_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (review.runsOnAllSites) {
                Text(
                    text = stringResource(R.string.user_script_all_sites_warning),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                )
            } else if (review.matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.user_script_no_valid_matches),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                review.matches.forEach { pattern ->
                    Text(
                        text = pattern,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = onDeny, modifier = Modifier.focusRequester(denyFocus)) {
                    Text(
                        stringResource(R.string.user_script_deny),
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
                Button(onClick = onApprove) {
                    Text(
                        stringResource(R.string.user_script_approve),
                        fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
            }
            if (showTimeout) {
                Text(
                    text = stringResource(R.string.user_script_timeout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ReviewLine(label: String, value: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun sitesLabel(matches: List<String>, runsOnAllSites: Boolean): String {
    if (runsOnAllSites) return stringResource(R.string.user_script_all_sites_warning)
    if (matches.isEmpty()) return stringResource(R.string.user_script_no_valid_matches)
    return matches.joinToString(", ")
}

@Composable
private fun stateLabel(state: ScriptApprovalState): String = when (state) {
    ScriptApprovalState.APPROVED -> stringResource(R.string.user_script_state_approved)
    ScriptApprovalState.NEEDS_APPROVAL -> stringResource(R.string.user_script_state_needs_approval)
    ScriptApprovalState.CHANGED -> stringResource(R.string.user_script_state_changed)
}
