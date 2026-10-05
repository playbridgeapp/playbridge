package com.playbridge.sender.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.playbridge.sender.R
import com.playbridge.sender.browser.UblockSetupStatus

/** Full-window tour; uBlock is optional and still uses the native permission gate. */
@Composable
fun DashboardOnboardingOverlay(
    onDone: () -> Unit,
    ublockStatus: UblockSetupStatus = UblockSetupStatus.CHECKING,
    onCheckUblock: () -> Unit = {},
    onInstallUblock: () -> Unit = {},
    onCancelUblock: () -> Unit = {},
    onGuideShown: () -> Unit = {},
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val lastStep = 3

    Dialog(
        onDismissRequest = {
            when {
                ublockStatus.isBusy -> onCancelUblock()
                step > 0 -> step--
                else -> onDone()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize().testTag("onboarding-fullscreen")) {
            LaunchedEffect(Unit) {
                onGuideShown()
                onCheckUblock()
            }
            Column(
                modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.onboarding_open_guide), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDone, enabled = !ublockStatus.isBusy) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "onboardingStep",
                    ) { current ->
                        Column(
                            modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                                .verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            when (current) {
                                0 -> OnboardingStep(
                                    icon = { StepIcon(vector = Icons.Default.Dashboard) },
                                    title = stringResource(R.string.onboarding_dashboard_title),
                                    body = stringResource(R.string.onboarding_dashboard_body),
                                )
                                1 -> OnboardingStep(
                                    icon = { StepIcon(vector = Icons.Default.Tv) },
                                    title = stringResource(R.string.onboarding_cast_title),
                                    body = stringResource(R.string.onboarding_cast_body),
                                )
                                2 -> {
                                    OnboardingStep(
                                        icon = { StepIcon(vector = Icons.Default.Shield) },
                                        title = stringResource(R.string.onboarding_ublock_title),
                                        body = stringResource(R.string.onboarding_ublock_body),
                                    )
                                    Spacer(Modifier.height(24.dp))
                                    UblockSetupControls(ublockStatus, onCheckUblock, onInstallUblock, onCancelUblock)
                                }
                                else -> OnboardingStep(
                                    icon = {
                                        StepIcon {
                                            DashboardBlocksIcon(modifier = Modifier.size(36.dp), pulseIntervalMs = 2_500L)
                                        }
                                    },
                                    title = stringResource(R.string.onboarding_return_title),
                                    body = stringResource(R.string.onboarding_return_body),
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.onboarding_progress, step + 1, lastStep + 1),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(lastStep + 1) { index ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(
                            if (index == step) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        ))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { step-- }, enabled = step > 0 && !ublockStatus.isBusy) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                    Button(
                        onClick = { if (step < lastStep) step++ else onDone() },
                        enabled = !ublockStatus.isBusy,
                    ) {
                        Text(stringResource(when {
                            step == lastStep -> R.string.onboarding_done
                            step == 2 && ublockStatus != UblockSetupStatus.INSTALLED -> R.string.onboarding_not_now
                            else -> R.string.onboarding_next
                        }))
                    }
                }
            }
        }
    }
}

@Composable
private fun UblockSetupControls(
    status: UblockSetupStatus,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val message = when (status) {
        UblockSetupStatus.CHECKING -> R.string.onboarding_ublock_checking
        UblockSetupStatus.INSTALLING -> R.string.onboarding_ublock_installing
        UblockSetupStatus.CANCELLING -> R.string.onboarding_ublock_cancelling
        UblockSetupStatus.INSTALLED -> R.string.onboarding_ublock_installed
        UblockSetupStatus.CHECK_FAILED -> R.string.onboarding_ublock_check_failed
        UblockSetupStatus.INSTALL_FAILED -> R.string.onboarding_ublock_install_failed
        UblockSetupStatus.NOT_INSTALLED -> null
    }
    if (status == UblockSetupStatus.CHECKING || status.isBusy) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
    }
    message?.let {
        Text(stringResource(it), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    when (status) {
        UblockSetupStatus.NOT_INSTALLED, UblockSetupStatus.INSTALL_FAILED -> Button(onClick = onInstall) {
            Text(stringResource(if (status == UblockSetupStatus.NOT_INSTALLED) R.string.onboarding_ublock_install
                else R.string.onboarding_ublock_retry_install))
        }
        UblockSetupStatus.CHECK_FAILED -> Button(onClick = onCheck) {
            Text(stringResource(R.string.onboarding_ublock_retry_check))
        }
        UblockSetupStatus.INSTALLING -> TextButton(onClick = onCancel) {
            Text(stringResource(R.string.onboarding_ublock_cancel))
        }
        else -> Unit
    }
}

@Composable
private fun OnboardingStep(icon: @Composable () -> Unit, title: String, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StepIcon(vector: ImageVector? = null, content: (@Composable () -> Unit)? = null) {
    Box(
        modifier = Modifier.size(80.dp).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            vector != null -> Icon(vector, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp))
        }
    }
}
