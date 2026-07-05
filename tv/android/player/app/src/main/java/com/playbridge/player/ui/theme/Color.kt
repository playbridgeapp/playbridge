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

// Primary / secondary / tertiary containers + accents (shared by Dark + AMOLED)
val PrimaryContainer     = Color(0xFF343C8C)
val OnPrimaryContainer   = Color(0xFFDFE1FF)
val Secondary            = Color(0xFFBFC6FF)
val OnSecondary          = Color(0xFF1E2452)
val SecondaryContainer   = Color(0xFF2E3480)
val OnSecondaryContainer = Color(0xFFBFC6FF)
val Tertiary             = Color(0xFFE6B9E6)
val OnTertiary           = Color(0xFF44284A)
val TertiaryContainer    = Color(0xFF5C3E62)
val OnTertiaryContainer  = Color(0xFFFFD7FA)

// Destructive / error — a clean red (not the baseline salmon-orange)
val ErrorColor       = Color(0xFFE8696E)
val OnErrorColor     = Color(0xFF3A0A0C)
val ErrorContainer   = Color(0xFF6E1C20)
val OnErrorContainer = Color(0xFFFFDAD8)

// Content Colors
val OnSurface        = Color(0xFFE7E2FF)
val OnSurfaceVariant = Color(0xFFB0A8D8)
val OutlineVariant   = Color(0xFF3D3770)
val ScrimColor       = Color(0xFF000000)

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

// Containers / accents
val LightPrimaryContainer    = Color(0xFFDDE1FF)
val LightOnPrimaryContainer  = Color(0xFF00105C)
val LightSecondary           = Color(0xFF3A4180)
val LightOnSecondary         = Color(0xFFFFFFFF)
val LightSecondaryContainer   = Color(0xFFC5CAFF)
val LightOnSecondaryContainer = Color(0xFF0D1780)
val LightTertiary            = Color(0xFF6A3E70)
val LightOnTertiary          = Color(0xFFFFFFFF)
val LightTertiaryContainer   = Color(0xFFF2D6F5)
val LightOnTertiaryContainer = Color(0xFF2A0E30)

// Destructive / error
val LightError            = Color(0xFFB3261E)
val LightOnError          = Color(0xFFFFFFFF)
val LightErrorContainer   = Color(0xFFF9DEDC)
val LightOnErrorContainer = Color(0xFF410E0B)

// Content Colors
val LightOnSurface        = Color(0xFF0A0720)
val LightOnSurfaceVariant = Color(0xFF403A6A)
val LightOutlineVariant   = Color(0xFFC0BBE0)
