# PlayBridge Design System
_Design Specification v1.0 — Last verified: 2026-03-29_

This document defines the canonical visual language for all PlayBridge UI surfaces — Phone (sender), TV (receiver), and any future platforms. It is the single source of truth for color tokens, typography, component rules, and structural principles. All Compose UI in `phone/app/src/main/java/com/playbridge/sender/ui/theme/` and `tv/app/src/main/java/com/playbridge/receiver/ui/theme/` must conform to this specification.

---

## 1. Creative North Star: "The Digital Curator"

The PlayBridge interface is not a utility tool — it is an **ambient companion**. Every screen must feel like a premium physical object: stacked sheets of matte-finished glass or high-quality architectural vellum.

**Core Principles:**
- **Intentional Asymmetry** — Break the predictable grid. Align heavy headline text to the left; float action chips to the right. Visual tension is intentional.
- **Tonal Architecture** — Structure is defined by background color shifts, never by lines or borders.
- **Soft Precision** — High-radius corners (up to 20dp) paired with razor-sharp typography. Approachable but intelligent.

---

## 2. Color Tokens (Dark-Mode-First)

All surfaces use a deep indigo/muted violet palette. Content "pops" via luminance, not contrast.

### Surface Hierarchy

| Token | Hex | Role |
| :--- | :--- | :--- |
| `surface` | `#0d072e` | Base — the infinite background |
| `surface_container_low` | `#120c37` | Sectioning — large grouped areas |
| `surface_container` | `#181241` | Interaction Cards — primary clickable content |
| `surface_container_high` | `#1e1748` | Hover/focus state elevation |
| `surface_container_highest` | `#241d54` | Floating elements and active states |
| `surface_bright` | `#2a2660` | Glassmorphic overlays at 60% opacity |

### Brand & Content Colors

| Token | Hex | Role |
| :--- | :--- | :--- |
| `primary` | `#9ea7ff` | Primary brand — text, icons, active chips |
| `primary_dim` | `#5565f2` | CTA gradient start point |
| `on_primary` | `#0d072e` | Text on primary-colored backgrounds |
| `primary_fixed_dim` | `#7b84e0` | Secondary icons (legible, non-competing) |
| `secondary_container` | `#2e3480` | Selected filter chips |
| `on_secondary_container` | `#bfc6ff` | Text within secondary containers |
| `on_surface` | `#e7e2ff` | Body text — NEVER use pure `#FFFFFF` |
| `on_surface_variant` | `#b0a8d8` | Placeholder text, secondary labels (at 60% opacity for inputs) |
| `outline_variant` | `#3d3770` | Ghost borders (accessibility only, at 15% opacity) |

### Gradients

| Name | Definition | Usage |
| :--- | :--- | :--- |
| CTA Gradient | `#5565f2` → `#9ea7ff` at 135° | Primary buttons, hero CTAs |
| Glassmorphism | `surface_container` at 80% opacity + 16dp backdrop blur | Navigation bars, top app bars |
| Floating Overlay | `surface_bright` at 60% opacity | Bottom sheets when video is beneath |

### The "No-Line" Rule

> **PROHIBITED**: 1px solid borders to separate content. Never `Divider()` with default settings.

- **Preferred**: Background color shifts between adjacent surfaces.
- **Fallback** (accessibility only): `outline_variant` at **15% opacity** — never 100%.
- **List separation**: 12dp vertical padding between items. No visual dividers.

---

## 3. Typography

Dual-font pairing. Headlines use **Manrope** for geometric authority; UI text uses **Inter** for legibility.

### Font Imports (Gradle / fonts.xml)
```
Manrope — Display, Headlines
Inter    — Body, Labels, UI
```

### Type Scale

| Token | Font | Size | Line Height | Weight | Letter Spacing | Transform |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `display_lg` | Manrope | 3.0rem | 1.1 | 800 | −0.02em | Sentence |
| `headline_lg` | Manrope | 2.0rem | 1.15 | 700 | −0.02em | Sentence |
| `title_lg` | Inter | 1.375rem | 1.3 | 600 | 0 | Sentence |
| `body_md` | Inter | 0.875rem | 1.5 | 400 | 0 | Sentence |
| `label_sm` | Inter | 0.6875rem | 1.4 | 700 | 0.06em | ALL CAPS |

**The Hierarchy Goal:** The scale gap between `display_lg` and `label_sm` is what makes the interface feel _designed_ rather than templated. Never collapse this range.

### Compose Mapping (`Type.kt`)

```kotlin
val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        letterSpacing = (-0.02).em
    ),
    headlineLarge = TextStyle(
        fontFamily = ManropeFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.02).em
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.06.em
        // Render ALL CAPS via `text.uppercase()` at call site
    )
)
```

---

## 4. Elevation & Depth (Tonal Layering)

Traditional drop shadows are replaced by surface luminance steps.

### The Layering Principle
- To make a card feel "raised": move it one step **up** the surface scale.
  - e.g., `surface_container` → `surface_container_high` (no shadow needed).
- Standard Material 3 elevated shadows are **prohibited**.

### Ambient Shadows (High-Floating Elements Only)

| Property | Value |
| :--- | :--- |
| Color | `#000000` at 12% opacity |
| Blur | 24dp |
| Spread | −4dp (keeps shadow under the element) |

> Use only for elements like Bottom Sheets that float above the full background.

### Glassmorphism
- **Navigation bar / Top App Bar**: `surface_container` at 80% opacity + 16dp backdrop blur.
- **Overlay sheets**: `surface_bright` at 60% opacity.

---

## 5. Shape & Radii

| Context | Radius | Compose Shape |
| :--- | :--- | :--- |
| Large cards / Bottom sheets | 20dp | `RoundedCornerShape(20.dp)` |
| Small cards / Dialogs | 16dp | `RoundedCornerShape(16.dp)` |
| Buttons (Primary/Secondary) | Pill (9999px) | `CircleShape` |
| Text inputs | Pill (9999px) | `CircleShape` |
| Action chips (circular) | Full circle | 48dp × 48dp, `CircleShape` |
| Filter chips | Pill | `RoundedCornerShape(50)` |

---

## 6. Component Specifications

### Buttons

| Type | Background | Text Color | Radius |
| :--- | :--- | :--- | :--- |
| **Primary** | CTA Gradient (`primary_dim` → `primary`) | `on_primary` | Pill |
| **Secondary** | `surface_container_high` | `primary` | Pill |
| **Tertiary / Ghost** | Transparent | `primary` | Pill |

- Minimum touch target: 48dp × 48dp.
- Internal padding: 16dp horizontal, 14dp vertical.

### Text Inputs

- Shape: Pill (full radius)
- Background: `surface_container_highest`
- Border: **None** (no stroke)
- Placeholder: `on_surface_variant` at 60% opacity
- Active cursor: `primary`

### Cards

- Background: `surface_container`
- Radius: 20dp
- No stroke.
- Inner padding: minimum 20dp.
- Press/hover state: elevate background to `surface_container_highest`.

### Chips

| Type | Size | Unselected BG | Selected BG | Radius |
| :--- | :--- | :--- | :--- | :--- |
| Action | 48dp × 48dp | `surface_container_high` | `primary` | Full circle |
| Filter | Auto | `surface_container_low` | `secondary_container` | Pill |

### Lists

- **No dividers.** No `Divider()` composable.
- Separate items with **12dp vertical spacing** (`Spacer(modifier = Modifier.height(12.dp))`).
- Alternatively, alternate very subtle background tints (`surface_container` vs `surface_container_low`) for dense lists.

### Bottom Sheets

- Background: `surface_bright` at 60% opacity, or `surface_container_highest` (opaque) when content beneath is non-video.
- Radius: 20dp top corners only.
- Ambient shadow: `#000000` at 12% opacity, 24dp blur, −4dp spread.
- Handle: 32dp × 4dp pill in `on_surface_variant` at 40% opacity.

### Navigation Bar

- Background: `surface_container` at 80% opacity + 16dp backdrop blur.
- Active indicator: `primary_dim` → `primary` gradient pill, 64dp × 32dp.
- Active icon/label: `primary`
- Inactive icon/label: `on_surface_variant`

---

## 7. Spacing & Layout

The system uses an **8dp base grid**. Always think in multiples of 8 (or 4 for micro-spacing).

| Token | Value | Usage |
| :--- | :--- | :--- |
| `space-xs` | 4dp | Icon-to-text gap |
| `space-sm` | 8dp | Internal component padding |
| `space-md` | 16dp | Card inner padding, section spacing |
| `space-lg` | 24dp | Between cards |
| `space-xl` | 32dp | Screen edge padding |
| `space-2xl` | 48dp | Major section separation |

**Rule:** When in doubt, add 8dp more. Extreme white space is a feature.

---

## 8. Icon Guidelines

- Use icons from **Material Symbols Rounded** (weight 300–400, optical size 24).
- Icon color: `primary_fixed_dim` — keeps icons legible but secondary to text.
- Icon-only buttons: **always provide** `contentDescription`. If accompanied by a text label in the same composable, set `contentDescription = null` to prevent TalkBack double-reading.
- Touch target: 48dp × 48dp minimum, regardless of visual icon size.

---

## 9. Do's and Don'ts

### ✅ Do
- Use extreme white space. If you think there's enough padding, add 8dp more.
- Align icons and text with mathematical precision — misalignment is loud in a minimal system.
- Create visual tension: heavy headline text left-aligned, action chips floating right.
- Use `primary_fixed_dim` for icons.
- Use `on_surface` (`#e7e2ff`) for all body text.
- Test for legibility at `label_sm` scale before finalizing any screen.

### ❌ Don't
- Don't use `#FFFFFF` for text — ever. The "bloom" effect causes eye strain in dark mode.
- Don't use standard Material 3 elevated shadows.
- Don't use 1px borders or `Divider()`.
- Don't nest more than **three surface levels** in a single screen. Visual hierarchy must stay shallow and clear.
- Don't use `FontFamily.Default` — always use Manrope or Inter.
- Don't use more than two typefaces on any screen.

---

## 10. Platform Considerations

### Phone App (`com.playbridge.sender`)
- These guidelines apply to all Compose screens in `phone/app/src/main/java/com/playbridge/sender/`.
- Theme entry point: `phone/app/src/main/java/com/playbridge/sender/ui/theme/`.
- Key theme files to update: `Color.kt`, `Type.kt`, `Theme.kt`.
- The browser chrome (toolbar, tab bar) should use glassmorphism — `surface_container` at 80% opacity + backdrop blur simulation via layered surfaces.

### TV App (`com.playbridge.receiver`)
- The TV app uses a 10-foot UI model. Scale all spacing tokens by **1.5×** and all font sizes by **1.25×** for TV screens.
- Focus state: replace hover with a `primary` colored 2dp outline + `surface_container_highest` card background.
- TV screens have `tv/app/src/main/java/com/playbridge/receiver/ui/theme/`.

---

## 11. Figma / Reference Token Map

For tooling integration, these tokens map 1:1 to any Figma variable library:

```
surface               → Background/Base
surface_container_low → Background/Section
surface_container     → Background/Card
surface_container_highest → Background/Float
primary               → Brand/Primary
primary_dim           → Brand/PrimaryDim
on_surface            → Text/Primary
on_surface_variant    → Text/Secondary
outline_variant       → Border/Ghost
```

---

_This document should be updated whenever new component patterns are introduced to the codebase. Jules design-alignment prompts reference this file as the source of truth._
