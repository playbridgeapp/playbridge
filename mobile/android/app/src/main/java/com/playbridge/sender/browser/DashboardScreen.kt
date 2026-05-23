package com.playbridge.sender.browser

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import com.playbridge.sender.R
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DashboardScreen(
    currentScreen: Screen,
    isConnected: Boolean,
    isSecure: Boolean,
    connectedDeviceName: String?,
    onNavigate: (Screen) -> Unit
) {
    // ── Entrance animations ─────────────────────────────────────────────────
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val logoScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.3f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "logoAlpha"
    )

    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        // ── Animated Ambient Mesh Background ─────────────────────────────────
        val infiniteBgTransition = rememberInfiniteTransition(label = "mesh_bg")
        val animatedAngle1 by infiniteBgTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(28000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "angle1"
        )
        val animatedAngle2 by infiniteBgTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * Math.PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(42000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "angle2"
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Drift Blob 1 (Top-Right-ish)
            val dx1 = width * 0.6f + (width * 0.15f) * cos(animatedAngle1)
            val dy1 = height * 0.25f + (height * 0.1f) * sin(animatedAngle1)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.12f),
                        primaryColor.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = Offset(dx1, dy1),
                    radius = width * 0.8f
                ),
                center = Offset(dx1, dy1),
                radius = width * 0.8f
            )

            // Drift Blob 2 (Bottom-Left-ish)
            val dx2 = width * 0.4f + (width * 0.12f) * cos(animatedAngle2 + Math.PI.toFloat())
            val dy2 = height * 0.7f + (height * 0.08f) * sin(animatedAngle2)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = 0.10f),
                        secondaryColor.copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    center = Offset(dx2, dy2),
                    radius = width * 0.7f
                ),
                center = Offset(dx2, dy2),
                radius = width * 0.7f
            )
        }

        // ── Main Dashboard Scrollable Content ────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // ── Logo with Pulsing Halo ───────────────────────────────────────
            val logoGlowTransition = rememberInfiniteTransition(label = "logo_glow_anim")
            val logoGlowScale by logoGlowTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoGlowScale"
            )
            val logoGlowAlpha by logoGlowTransition.animateFloat(
                initialValue = 0.15f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoGlowAlpha"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .scale(logoScale)
                    .alpha(logoAlpha)
            ) {
                // Pulsing glow ring
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .scale(logoGlowScale)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = logoGlowAlpha))
                )

                Image(
                    painter = painterResource(id = R.drawable.ic_playbridge_logo),
                    contentDescription = "PlayBridge Logo",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(logoAlpha)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "CONSOLE HUB",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                ),
                color = primaryColor.copy(alpha = 0.7f),
                modifier = Modifier.alpha(logoAlpha)
            )

            // ── Interactive Connection Status Pill ────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            val statusAlpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(600, delayMillis = 200),
                label = "statusAlpha"
            )

            val displayDeviceName = connectedDeviceName?.let {
                if (it.length > 18) it.take(15) + "..." else it
            } ?: "TV"

            // Green when connected over wss, amber when connected over plain ws.
            val accent = if (isSecure) Color(0xFF4CAF50) else Color(0xFFFFA000)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isConnected)
                    accent.copy(alpha = 0.15f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    if (isConnected) accent.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .alpha(statusAlpha)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 280.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { onNavigate(Screen.Connection) }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isConnected) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val pulseAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotPulse"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = pulseAlpha))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isConnected && isSecure -> "Connected to $displayDeviceName securely"
                            isConnected -> "Connected to $displayDeviceName"
                            else -> "No device connected"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isConnected)
                            accent
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── Navigation Cards Grid ───────────────────────────────────────
            val items = listOf(
                DashboardItem(
                    icon = Icons.Default.Language,
                    title = "Browser",
                    subtitle = "Browse the web",
                    screen = Screen.Browser,
                    gradientColors = listOf(Color(0xFF1565C0), Color(0xFF1E88E5))
                ),
                DashboardItem(
                    icon = Icons.AutoMirrored.Filled.LibraryBooks,
                    title = "Library",
                    subtitle = "Your media library",
                    screen = Screen.Library,
                    gradientColors = listOf(Color(0xFF6A1B9A), Color(0xFF8E24AA))
                ),
                DashboardItem(
                    icon = Icons.Default.Cloud,
                    title = "Debrid",
                    subtitle = "Cloud torrents",
                    screen = Screen.DebridLibrary,
                    gradientColors = listOf(Color(0xFF00838F), Color(0xFF00ACC1))
                ),
                DashboardItem(
                    icon = Icons.Default.Tv,
                    title = "Connection",
                    subtitle = if (isConnected) "Connected" else "Not connected",
                    screen = Screen.Connection,
                    gradientColors = if (isConnected)
                        listOf(Color(0xFF2E7D32), Color(0xFF43A047))
                    else
                        listOf(Color(0xFF424242), Color(0xFF616161))
                ),
                DashboardItem(
                    icon = Icons.Default.History,
                    title = "Cast History",
                    subtitle = "Recent casts",
                    screen = Screen.CastHistory,
                    gradientColors = listOf(Color(0xFFE65100), Color(0xFFFB8C00))
                )
            )

            // Top row: 2 large cards (Browser + Library)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.take(2).forEachIndexed { index, item ->
                    DashboardCard(
                        item = item,
                        isActive = isCurrentScreen(currentScreen, item.screen),
                        animDelay = 100 + index * 80,
                        visible = visible,
                        modifier = Modifier.weight(1f),
                        tall = true,
                        onClick = { onNavigate(item.screen) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom row: 3 cards (Debrid + Connection + Cast History)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.drop(2).forEachIndexed { index, item ->
                    DashboardCard(
                        item = item,
                        isActive = isCurrentScreen(currentScreen, item.screen),
                        animDelay = 260 + index * 80,
                        visible = visible,
                        modifier = Modifier.weight(1f),
                        tall = false,
                        onClick = { onNavigate(item.screen) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Navigation Top Bar Shortcuts ─────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = { onNavigate(currentScreen) },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Dashboard",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun DashboardCard(
    item: DashboardItem,
    isActive: Boolean,
    animDelay: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    tall: Boolean,
    onClick: () -> Unit
) {
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis = animDelay),
        label = "cardAlpha"
    )
    val cardTranslateY by animateFloatAsState(
        targetValue = if (visible) 0f else 60f,
        animationSpec = tween(500, delayMillis = animDelay, easing = FastOutSlowInEasing),
        label = "cardTranslateY"
    )

    // Tactile press scale response
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "pressScale"
    )

    // Active state scale enhancement
    val activeScale by animateFloatAsState(
        targetValue = if (isActive) 1.02f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 200f),
        label = "activeScale"
    )
    val finalScale = pressScale * activeScale

    val height = if (tall) 150.dp else 120.dp

    // Glass-like translucent border adaptive to theme luminance
    val surfaceLuminance = MaterialTheme.colorScheme.surface.luminance()
    val isDarkTheme = surfaceLuminance < 0.5f
    val borderBrush = Brush.linearGradient(
        colors = if (isDarkTheme) {
            listOf(
                Color.White.copy(alpha = if (isActive) 0.35f else 0.15f),
                Color.White.copy(alpha = 0.03f)
            )
        } else {
            listOf(
                Color.Black.copy(alpha = if (isActive) 0.18f else 0.08f),
                Color.Black.copy(alpha = 0.02f)
            )
        }
    )

    Card(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                alpha = cardAlpha
                translationY = cardTranslateY
                scaleX = finalScale
                scaleY = finalScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        border = BorderStroke(1.dp, borderBrush),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 6.dp else 1.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(item.gradientColors))
                .then(
                    if (isActive) Modifier.background(Color.White.copy(alpha = 0.12f))
                    else Modifier
                )
        ) {
            // Specular glass-like sheen overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 350f
                        )
                    )
            )

            // Subtle decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 15.dp, y = (-15).dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-10).dp, y = 30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            )

            val horizontalPadding = if (tall) 16.dp else 10.dp
            val verticalPadding = 16.dp

            val titleStyle = if (tall) {
                MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    letterSpacing = (-0.2).sp
                )
            }

            val subtitleStyle = if (tall) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Icon with subtle background
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        style = subtitleStyle,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Sleek "ACTIVE" glass chip indicator
            if (isActive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infiniteActiveTransition = rememberInfiniteTransition(label = "active_badge")
                    val badgeDotAlpha by infiniteActiveTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "badgeDotAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = badgeDotAlpha))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun isCurrentScreen(current: Screen, target: Screen): Boolean {
    return when (target) {
        Screen.Library -> current == Screen.Library || current == Screen.AddonSettings
        else -> current == target
    }
}

private data class DashboardItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val screen: Screen,
    val gradientColors: List<Color>
)
