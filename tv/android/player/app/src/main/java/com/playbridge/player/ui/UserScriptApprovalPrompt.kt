package com.playbridge.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
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
    val sender = review.senderName?.takeIf { it.isNotBlank() }
    // A long @match list is the only thing that scrolls. The all-sites warning is one
    // line and must stay on screen; a 1080p TV is often only ~540dp tall.
    val scrollMatches = review.matches.size > MATCHES_SHOWN_WITHOUT_SCROLL && !review.runsOnAllSites
    var listFocusable by remember(review) { mutableStateOf(false) }
    LaunchedEffect(review) {
        // Deny wins over match-list rows. Enable list focus only after that, so a long
        // list cannot take the initial D-pad focus.
        try {
            denyFocus.requestFocus()
        } catch (_: Exception) {
        }
        listFocusable = scrollMatches
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 48.dp, vertical = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.user_script_prompt_title),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (sender != null) {
            Text(
                text = stringResource(R.string.user_script_from, sender),
                color = Color(0xFF00D9FF),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            UserScriptMetaRow(
                review = review,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        // Outside the facts card on purpose: a weighted card clipped this block on 1080p,
        // hiding "Runs on ALL sites" above the Approve button.
        Text(
            text = stringResource(R.string.user_script_matches_label),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 16.sp,
        )
        Spacer(modifier = Modifier.height(6.dp))
        UserScriptMatchBody(
            review = review,
            scroll = scrollMatches,
            contentColor = Color.White,
            listFocusable = listFocusable,
            modifier = if (scrollMatches) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        UserScriptDecisionRow(
            denyFocus = denyFocus,
            onDeny = { ServerService.denyPendingUserScript() },
            onApprove = { ServerService.approvePendingUserScript() },
        )
        Text(
            text = stringResource(R.string.user_script_timeout),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
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
        ?: if (showTimeout) null else stringResource(R.string.user_script_local_source)
    val scrollMatches = review.matches.size > MATCHES_SHOWN_WITHOUT_SCROLL && !review.runsOnAllSites
    Surface(
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.widthIn(max = 880.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (sender != null) {
                Text(
                    text = stringResource(R.string.user_script_from, sender),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            UserScriptMetaRow(review)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.user_script_matches_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            UserScriptMatchBody(
                review = review,
                scroll = scrollMatches,
                contentColor = MaterialTheme.colorScheme.onSurface,
                listFocusable = scrollMatches,
                // Cap the list so Approve stays on screen. weight() is unsafe here: the
                // dialog surface is wrap-content and would pass an infinite max height.
                modifier = if (scrollMatches) Modifier.heightIn(max = 180.dp) else Modifier,
            )
            Spacer(modifier = Modifier.height(12.dp))
            UserScriptDecisionRow(denyFocus, onDeny, onApprove)
            if (showTimeout) {
                Text(
                    text = stringResource(R.string.user_script_timeout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptDecisionRow(
    denyFocus: FocusRequester,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
) {
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
}

private const val MATCHES_SHOWN_WITHOUT_SCROLL = 3
private val ScriptWarningRed = Color(0xFFFF5252)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptMetaRow(review: UserScriptReview, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CompactFact(
            label = stringResource(R.string.user_script_name),
            value = review.scriptName,
            modifier = Modifier.weight(1.5f),
        )
        CompactFact(
            label = stringResource(R.string.user_script_size),
            value = pluralStringResource(R.plurals.user_script_size_bytes, review.sizeBytes, review.sizeBytes),
            modifier = Modifier.weight(0.9f),
        )
        CompactFact(
            label = stringResource(R.string.user_script_hash),
            value = review.hashPrefix,
            monospace = true,
            modifier = Modifier.weight(1.1f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CompactFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UserScriptMatchBody(
    review: UserScriptReview,
    scroll: Boolean,
    contentColor: Color,
    listFocusable: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        review.runsOnAllSites -> Text(
            text = stringResource(R.string.user_script_all_sites_warning),
            color = ScriptWarningRed,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 28.sp,
            modifier = modifier,
        )
        review.matches.isEmpty() -> Text(
            text = stringResource(R.string.user_script_no_valid_matches),
            color = ScriptWarningRed,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            modifier = modifier,
        )
        scroll -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(review.matches) { _, pattern ->
                Text(
                    text = pattern,
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    modifier = if (listFocusable) Modifier.focusable() else Modifier,
                )
            }
        }
        else -> Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            review.matches.forEach { pattern ->
                Text(
                    text = pattern,
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                )
            }
        }
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
