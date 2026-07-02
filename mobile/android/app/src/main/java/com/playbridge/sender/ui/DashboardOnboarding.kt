package com.playbridge.sender.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Tv

/**
 * One-time coach-mark overlay shown over the Dashboard on first launch.
 *
 * Three short steps: what the Dashboard is, how casting to a TV works, and — the
 * one thing people get lost on — how to get BACK to the Dashboard from any screen
 * (the blocks icon in every top bar). Dismissible at any point via Skip; the
 * caller persists "seen" state so it never shows again.
 */
@Composable
fun DashboardOnboardingOverlay(
    onDone: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    val lastStep = 2

    // Full-screen scrim. Consumes clicks so the Dashboard underneath isn't tappable
    // while the tour is up (no ripple — it's a barrier, not a button).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* swallow */ },
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "onboardingStep"
                ) { s ->
                    when (s) {
                        0 -> OnboardingStep(
                            icon = { StepIcon(vector = Icons.Default.Dashboard) },
                            title = "This is your Dashboard",
                            body = "Home base for everything: browse the web, open your Library, " +
                                "cast phone files, and more — each card takes you to a section."
                        )
                        1 -> OnboardingStep(
                            icon = { StepIcon(vector = Icons.Default.Tv) },
                            title = "Cast to your TV",
                            body = "Tap the status pill or the Connection card to pair with your TV. " +
                                "Once connected, any video you find can be sent to the big screen."
                        )
                        else -> OnboardingStep(
                            icon = {
                                StepIcon {
                                    // Short pulse interval so the affordance animation
                                    // demos itself while the user reads this step.
                                    DashboardBlocksIcon(
                                        modifier = Modifier.size(26.dp),
                                        pulseIntervalMs = 2_500L,
                                    )
                                }
                            },
                            title = "Getting back here",
                            body = "See this blocks icon in the top bar? Every screen has it. " +
                                "Tap it any time to return to the Dashboard."
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Progress dots
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(lastStep + 1) { i ->
                        Box(
                            modifier = Modifier
                                .size(if (i == step) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDone) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { if (step < lastStep) step++ else onDone() },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (step < lastStep) "Next" else "Got it")
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStep(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun StepIcon(
    vector: ImageVector? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            content != null -> content()
            vector != null -> Icon(
                imageVector = vector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
