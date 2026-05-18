package com.playbridge.player.ui.theme

import androidx.compose.ui.graphics.Color

// ── Dark palette ──────────────────────────────────────────────────────────────

// Surface Hierarchy
val Surface                 = Color(0xFF0D072E)
val SurfaceContainerLow     = Color(0xFF120C37)
val SurfaceContainer        = Color(0xFF181241)
val SurfaceContainerHigh    = Color(0xFF1E1748)
val SurfaceContainerHighest = Color(0xFF241D54)
val SurfaceBright           = Color(0xFF2A2660)

// Brand Colors
val Primary         = Color(0xFF9EA7FF)
val PrimaryDim      = Color(0xFF5565F2)
val OnPrimary       = Color(0xFF0D072E)
val PrimaryFixedDim = Color(0xFF7B84E0)

// Secondary
val SecondaryContainer   = Color(0xFF2E3480)
val OnSecondaryContainer = Color(0xFFBFC6FF)

// Content Colors
val OnSurface        = Color(0xFFE7E2FF)
val OnSurfaceVariant = Color(0xFFB0A8D8)
val OutlineVariant   = Color(0xFF3D3770)

// ── AMOLED palette ────────────────────────────────────────────────────────────

// Surface Hierarchy (pure-black OLED surfaces; brand colours unchanged)
val AmoledSurface                 = Color(0xFF000000)
val AmoledSurfaceContainerLow     = Color(0xFF06051A)
val AmoledSurfaceContainer        = Color(0xFF0C0A28)
val AmoledSurfaceContainerHigh    = Color(0xFF121038)
val AmoledSurfaceContainerHighest = Color(0xFF181548)
val AmoledSurfaceBright           = Color(0xFF1E1A58)

// Brand & content colours are the same as Dark

// ── Light palette ─────────────────────────────────────────────────────────────

// Surface Hierarchy
val LightSurface                 = Color(0xFFF4F1FF)
val LightSurfaceContainerLow     = Color(0xFFEDE9FF)
val LightSurfaceContainer        = Color(0xFFE3DEFF)
val LightSurfaceContainerHigh    = Color(0xFFD8D2FF)
val LightSurfaceContainerHighest = Color(0xFFCCC5FF)
val LightSurfaceBright           = Color(0xFFFFFFFF)

// Brand Colors (darkened primary for contrast on light backgrounds)
val LightPrimary         = Color(0xFF3040CC)
val LightPrimaryDim      = Color(0xFF1C2BAD)
val LightOnPrimary       = Color(0xFFFFFFFF)
val LightPrimaryFixedDim = Color(0xFF4A5ADB)

// Secondary
val LightSecondaryContainer   = Color(0xFFC5CAFF)
val LightOnSecondaryContainer = Color(0xFF0D1780)

// Content Colors
val LightOnSurface        = Color(0xFF0A0720)
val LightOnSurfaceVariant = Color(0xFF403A6A)
val LightOutlineVariant   = Color(0xFFC0BBE0)
