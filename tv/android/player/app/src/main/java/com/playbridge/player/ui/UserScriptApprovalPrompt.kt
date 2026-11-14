package com.playbridge.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playbridge.player.R
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.SectionHeading
import com.playbridge.player.ui.components.SwitchRow
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.theme.ThemedDialog
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import com.playbridge.player.userscript.InstalledScriptSummary
import com.playbridge.player.userscript.ScriptApprovalState
import com.playbridge.player.userscript.USER_SCRIPT_APPROVAL_TIMEOUT_MS
import com.playbridge.player.userscript.UserScriptReview
import com.playbridge.player.userscript.UserScriptRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen approval for a script a phone wants to install. Deny has the initial focus. */
@Composable
fun UserScriptApprovalPrompt(review: UserScriptReview) {
    val colors = TvTheme.colors
    val denyFocus = remember { FocusRequester() }
    BackHandler { ServerService.denyPendingUserScript() }
    val sender = review.senderName?.takeIf { it.isNotBlank() }
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
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .padding(TvSpacing.screenGutter),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sender != null) {
                    Text(
                        text = stringResource(R.string.user_script_from, sender),
                        style = TvText.row,
                        color = colors.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.user_script_install_title),
                    style = TvText.pageTitle.copy(fontSize = 44.sp, lineHeight = 50.sp),
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(R.string.user_script_install_warning),
                    style = TvText.row.copy(fontWeight = FontWeight.Normal, lineHeight = 29.sp),
                    color = colors.onSurfaceMuted,
                )
            }
            ExpiryBar(key = review)
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                PillButton(
                    text = stringResource(R.string.user_script_install_deny),
                    onClick = { ServerService.denyPendingUserScript() },
                    height = 60.dp,
                    modifier = Modifier.focusRequester(denyFocus),
                )
                PillButton(
                    text = stringResource(R.string.user_script_install_approve),
                    onClick = { ServerService.approvePendingUserScript() },
                    style = PillStyle.Secondary,
                    height = 60.dp,
                )
            }
        }
        ScriptFactsCard(
            review = review,
            scrollMatches = scrollMatches,
            listFocusable = listFocusable,
            modifier = Modifier.width(460.dp),
        )
    }
}

/** "Expires in 45 seconds" with a bar that drains. Only this node recomposes each second. */
@Composable
private fun ExpiryBar(key: Any) {
    val colors = TvTheme.colors
    var remainingMs by remember(key) { mutableLongStateOf(USER_SCRIPT_APPROVAL_TIMEOUT_MS) }
    LaunchedEffect(key) {
        while (remainingMs > 0) {
            delay(1_000L)
            remainingMs -= 1_000L
        }
    }
    val seconds = secondsLeft(remainingMs)
    Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(TvSpacing.s)) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).drawBehind {
                val radius = CornerRadius(3.dp.toPx())
                drawRoundRect(colors.outline, size = size, cornerRadius = radius)
                val fraction = (remainingMs.toFloat() / USER_SCRIPT_APPROVAL_TIMEOUT_MS).coerceIn(0f, 1f)
                drawRoundRect(colors.accent, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            },
        )
        Text(
            text = pluralStringResource(R.plurals.user_script_expires_in, seconds, seconds),
            style = TvText.caption,
            color = colors.onSurfaceMuted,
        )
    }
}

/** Script name, where it runs, size and fingerprint. Shared by the prompt and the settings review. */
@Composable
private fun ScriptFactsCard(
    review: UserScriptReview,
    scrollMatches: Boolean,
    listFocusable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = TvTheme.colors
    Column(
        modifier = modifier.background(colors.container, TvRadius.cardShape).padding(30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Fact(stringResource(R.string.user_script_name)) {
            Text(review.scriptName, style = TvText.cardTitle.copy(fontSize = 21.sp), color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Fact(stringResource(R.string.user_script_matches_label)) {
            UserScriptMatchBody(
                review = review,
                scroll = scrollMatches,
                listFocusable = listFocusable,
                modifier = if (scrollMatches) Modifier.heightIn(max = 150.dp).fillMaxWidth() else Modifier.fillMaxWidth(),
            )
        }
        Fact(stringResource(R.string.user_script_size)) {
            Text(formatScriptSize(review.sizeBytes), style = TvText.row, color = colors.onSurface)
        }
        Fact(stringResource(R.string.user_script_fingerprint)) {
            Text(
                groupFingerprint(review.hashPrefix),
                style = TvText.value,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.xxs)) {
        Text(label, style = TvText.small, color = TvTheme.colors.onSurfaceMuted)
        value()
    }
}

@Composable
fun UserScriptSettingsSection() {
    val context = LocalContext.current
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

    Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.l), modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = stringResource(R.string.user_script_toggle),
            description = stringResource(R.string.user_script_toggle_desc),
            checked = installEnabled,
            onCheckedChange = { enabled ->
                installEnabled = enabled
                controller.setInstallEnabled(enabled)
            },
        )
        SectionHeading(text = stringResource(R.string.user_script_installed_header))
        if (scripts.isEmpty()) {
            Text(
                text = stringResource(R.string.user_script_none),
                style = TvText.caption,
                color = TvTheme.colors.onSurfaceMuted,
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

@Composable
private fun InstalledScriptRow(
    script: InstalledScriptSummary,
    onApprove: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = TvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.container, TvRadius.optionShape)
            .padding(horizontal = 26.dp, vertical = TvSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
            Text(script.name, style = TvText.settingTitle, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = sitesLabel(script.matches, script.runsOnAllSites),
                style = TvText.caption,
                color = if (script.runsOnAllSites) colors.error else colors.onSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateLabel(script.state),
                style = TvText.caption,
                color = if (script.state == ScriptApprovalState.APPROVED) colors.success else colors.warning,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
            if (script.state != ScriptApprovalState.APPROVED) {
                PillButton(
                    text = stringResource(R.string.user_script_approve),
                    onClick = onApprove,
                    height = TvSize.pillHeightCompact,
                )
            }
            PillButton(
                text = stringResource(R.string.user_script_remove),
                onClick = onRemove,
                style = PillStyle.Secondary,
                height = TvSize.pillHeightCompact,
            )
        }
    }
}

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
            denyFocus = denyFocus,
            onDeny = onDeny,
            onApprove = onApprove,
        )
    }
}

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
    val colors = TvTheme.colors
    ThemedDialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .background(colors.container, TvRadius.cardShape)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
        ) {
            Text(stringResource(R.string.user_script_remove_title), style = TvText.cardHeadline, color = colors.onSurface)
            Text(
                stringResource(R.string.user_script_remove_body, name),
                style = TvText.row.copy(fontWeight = FontWeight.Normal),
                color = colors.onSurfaceMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                PillButton(
                    text = stringResource(R.string.user_script_cancel),
                    onClick = onCancel,
                    modifier = Modifier.focusRequester(cancelFocus),
                )
                TvFocusSurface(
                    onClick = onRemove,
                    modifier = Modifier.height(TvSize.pillHeight),
                    shape = TvRadius.pill,
                    container = colors.error,
                    focusedContainer = colors.error,
                    ringColor = colors.focusRing,
                ) {
                    Text(
                        stringResource(R.string.user_script_remove),
                        style = TvText.button,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = TvSpacing.xl),
                    )
                }
            }
        }
    }
}

@Composable
private fun UserScriptReviewCard(
    title: String,
    review: UserScriptReview,
    denyFocus: FocusRequester,
    onDeny: () -> Unit,
    onApprove: () -> Unit,
) {
    val colors = TvTheme.colors
    val sender = review.senderName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.user_script_local_source)
    val scrollMatches = review.matches.size > MATCHES_SHOWN_WITHOUT_SCROLL && !review.runsOnAllSites
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .background(colors.containerLow, TvRadius.cardShape)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(TvSpacing.xl),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.xs)) {
            Text(title, style = TvText.cardHeadline, color = colors.onSurface)
            Text(stringResource(R.string.user_script_from, sender), style = TvText.row, color = colors.onSurfaceMuted)
        }
        ScriptFactsCard(review = review, scrollMatches = scrollMatches, listFocusable = scrollMatches)
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
            PillButton(
                text = stringResource(R.string.user_script_deny),
                onClick = onDeny,
                modifier = Modifier.focusRequester(denyFocus),
            )
            PillButton(
                text = stringResource(R.string.user_script_approve),
                onClick = onApprove,
                style = PillStyle.Secondary,
            )
        }
    }
}

private const val MATCHES_SHOWN_WITHOUT_SCROLL = 3

@Composable
private fun UserScriptMatchBody(
    review: UserScriptReview,
    scroll: Boolean,
    listFocusable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = TvTheme.colors
    when {
        review.runsOnAllSites -> Text(
            text = stringResource(R.string.user_script_all_sites_warning),
            style = TvText.cardTitle,
            color = colors.error,
            modifier = modifier,
        )
        review.matches.isEmpty() -> Text(
            text = stringResource(R.string.user_script_no_valid_matches),
            style = TvText.row.copy(fontWeight = FontWeight.Bold),
            color = colors.error,
            modifier = modifier,
        )
        scroll -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(review.matches) { pattern ->
                Text(
                    text = pattern,
                    style = TvText.row.copy(fontWeight = FontWeight.Normal),
                    color = colors.onSurface,
                    fontFamily = FontFamily.Monospace,
                    modifier = if (listFocusable) Modifier.focusable() else Modifier,
                )
            }
        }
        else -> Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            review.matches.forEach { pattern ->
                Text(
                    text = pattern,
                    style = TvText.row.copy(fontWeight = FontWeight.Normal),
                    color = colors.onSurface,
                    fontFamily = FontFamily.Monospace,
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
