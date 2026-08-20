# DESIGN.md — playbridge.app

Marketing-site art direction for `web/site/`.
This is the source of truth for playbridge.app generation, visual edits, and agent work.

App UI (phone, TV, desktop chrome) lives in the root [`DESIGN.md`](../../DESIGN.md).
Do not import app indigo/violet tokens, Manrope headlines, or pill-button chrome into this site.
Do not restyle native apps from this file.

---

## Thesis

A living-room instrument, not a SaaS dashboard.

The site should feel like late-night TV: a phone in the dark, a pairing prompt, a remote that just works.
Engineered, quiet, local. Not a startup launch page.

Not: generic AI-tool marketing, glass bento, gradient-mesh hero, “cast anything” hype.

## Offer

- **Audience:** people who want their phone, browser, desktop, or CLI to drive a TV or computer on the same network.
- **Primary action:** install a receiver.
- **Secondary action:** pick a sender after the receiver exists.
- **Proof allowed:** open source, local network, no accounts, no telemetry, multi-engine playback (ExoPlayer, MPV, VLC, AVPlayer), pairing that stays trusted.
- **Proof forbidden:** invented user counts, fake press, invented partners, logo walls, testimonials we do not have.

Copy claims stay in `src/lib/data/site.ts`. This file does not invent new product facts.

## Genre

Editorial-tech + operational.

Asymmetrical editorial composition with technical markers (mono labels, 01/02/03, hairlines, CLI).
Not studio-glass. Not playful 3D. Not operational-dense like an admin tool.

---

## Type

One display move. Two supporting voices. Never a fourth family.

| Role | Face | Use |
| :--- | :--- | :--- |
| Display | IBM Plex Serif | H1 and major section titles only |
| Body / UI | IBM Plex Sans | Paragraphs, nav, buttons, cards |
| Utility (chrome) | System UI mono (`--font-mono-ui`) | Eyebrows, Alpha chips, step numbers, markers |
| Utility (code) | JetBrains Mono (`--font-mono`) | CLI / install command blocks only |

Loaded in `src/app.css`. Latin-only faces, `font-display: optional`, preloaded in `+layout.svelte`.
Do not use `text-wrap: balance` or `ch` widths on the hero (they twitch on refresh).
Do not add Inter. Do not put JetBrains on pills or eyebrows.

### Scale

| Token | Size | Weight | Tracking | Leading |
| :--- | :--- | :--- | :--- | :--- |
| Display | `clamp(40px, 5.6vw, 68px)` | 600–700 | −0.035em | 1.02 |
| Section title | `clamp(28px, 3vw, 38px)` | 600 | −0.025em | 1.10 |
| Card title | 16–18px | 500–600 | −0.01em | 1.30 |
| Body | 15–18px | 400 | 0 | 1.55, measure 38–42ch |
| Eyebrow / marker | 11px Mono | 400–500 | 0.14–0.16em | uppercase |

Hierarchy must stay extreme. Do not collapse display toward body.

### Type rules

- One accent word in the H1 is allowed (`One bridge.`).
- No gradient fills on text.
- No letter-spaced grotesque headlines.
- CLI and release markers stay mono.
- If a model botches glyphs, keep the layout and typeset by hand.

---

## Color

Lock the marketing tokens already in `src/app.css`. Do not silently switch back to app indigo.

| Token | Hex / value | Role |
| :--- | :--- | :--- |
| `--bg` | `#06091e` | Page ground |
| `--bg-2` | `#080c22` | Lower wash |
| `--surface` | `rgba(10, 20, 48, 0.55)` | Translucent panels |
| `--surface-solid` | `#0a1530` | Solid cards |
| `--text` | `#dce8f8` | Primary text — never `#FFFFFF` |
| `--text-dim` | `#94afd0` | Body |
| `--text-faint` | `#556e8f` | Eyebrows, meta |
| `--accent` | `#4a90e2` | CTA, current step, one highlight |
| `--accent-strong` | `#0d3a8a` | Gradient end / deep accent |
| `--line` | `rgba(200, 220, 255, 0.07)` | Hairlines |
| `--line-strong` | `rgba(200, 220, 255, 0.16)` | Control borders |
| `--on-accent` | `#06091e` | Text on accent fills (dark theme) |

Light theme (`html[data-theme=light]`): paper `#f6f3ec`, ink `#1b1914`, accent `#1f4e8c`, `--on-accent` `#f6f3ec`. No grain, no color washes, flat buttons (no candy-blue hover).
Default follows `prefers-color-scheme`; the nav toggle persists `pb-theme` in `localStorage`.
Hero phone/TV mocks are a **fixed dark device kit** in both themes (charcoal shell, black screen, `#4a90e2` on-device accent). Do not theme the hardware.

### Color rules

- Accent is rare: primary CTA, current step, one highlight word, focus ring.
- No second brand color. No rainbow hover.
- No purple/indigo wash. That belongs to the apps, not this site.
- Selection: accent fill, `--bg` text.
- Atmosphere is static: faint grid + grain on dark; paper only on light. No drifting blobs, no animated grid.

---

## Layout

- Max width: 1080–1200px. `.wrap` uses `padding-inline` only, with safe-area: 20px on small screens, 32px from 721px.
- Spacing rhythm: editorial 16 / 32 / 64. Section padding ~120px desktop, ~56–72px phone.
- Grid: 12-col, left-weighted. More air than app UI.
- Radius: 8–12px on controls and cards (soft). Pills only for tiny status chips.
- Elevation: flat lines + 1px hairline. No Material shadows. No colored glow under every card.

### Page rhythm

1. Hero — message, one primary CTA, cinematic product band
2. How it works — senders cast, receivers play (01–04 already exist)
3. Install a receiver
4. Senders / receivers as a pairing, not two icon-catalog grids
5. Two or three deep feature scenes, not six identical rows
6. Footer

### Hero

- Left-weighted H1 + one subhead + one primary CTA (`Get a receiver`).
- Status pill: filled Alpha chip + `Open source · local network` (sentence case).
- GitHub is a text link, not a third button.
- Device scene: phone **left**, TV **right** on desktop; TV stacked above a centered phone on small screens, with a gap (no overlap).
- TV/phone stay the dark device kit. Cast beam + scrub + Casting pulse is the one allowed loop.
- Schematic diagrams are supporting evidence, not the cover image.

### Features

Three bands, facts from `site.ts` / the product README — not six identical rows:
1. Find — phone browser + library/files
2. Connect — 6-digit pairing + remote
3. Play — queue/resume + PlayBridge receivers or DLNA

Do not headline debrid, Stremio, add-ons, or IPTV. DLNA lives on **Receivers** (`/receivers#dlna`), with a pointer from the Android sender.
Keep custom mocks in `src/lib/components/visuals/`.

---

## Motion

Subtle. One hero moment. Supporting motion only.

| Kind | Timing |
| :--- | :--- |
| Hover / press | 120–200ms |
| Button / focus | 180–260ms |
| Section enter | 400–700ms, fade + rise 12–16px |
| Hero type reveal | 600–900ms, optional mask |
| Stagger | 40–90ms |

### Motion rules

- Ease-out in, ease-in out. No bounce. No elastic.
- One looping demo is enough (hero cast beam / scrub / Casting). No animated background grid.
- No second scroll library. No magnetic buttons.
- No WebGL unless it is the hero itself, has a static poster, and pauses offscreen.
- `prefers-reduced-motion: reduce` jumps to the end state.

---

## Icons and media

- Site chrome uses the custom stroke set in `src/lib/icons/Icon.svelte`. Do not add a second icon family on the marketing site.
- Brand marks (Android, Apple, Firefox, GitHub) only for real platforms we ship.
- Feature visuals: real product UI in phone/TV chrome. No stock living rooms. No generated people.
- No model-authored decorative SVG illustrations.
- Alt text required. Missing-media fallbacks required.

---

## Copy constraints

Locked lines (do not “improve” unless the user asks):

- H1: `Your phone. Your TV. One bridge.`
- Hero sub: `Browse on your phone. Watch on the TV — same Wi‑Fi, no account.`
- Document title: `PlayBridge app — Cast your phone to your TV`
- Primary CTA: `Get a receiver` — not “Learn more” or “Get started.”
- Status word: Alpha. Do not upgrade it.

Voice: specific, calm, technical. Short sentences. Name the actual surface (6-digit pairing, touchpad, GeckoView, CLI, DLNA).

Never use: streamline, unlock, next-generation, seamlessly, revolutionize, AI-powered (for this product), award-winning.

---

## Components (site)

| Piece | Rule |
| :--- | :--- |
| Primary button | Solid `--accent`, `--on-accent` text, 10px radius, 14px, not a pill. Hover = `--accent-strong`. |
| Secondary button | Hairline + `--btn-bg` |
| Ghost / text link | No chrome |
| Nav | Sticky, blur ok, theme toggle, one install CTA, GitHub as text |
| Alpha chip | Filled accent, `--on-accent` / `--bg` text, system mono. Smaller on phone. |
| Eyebrow | `--font-mono-ui`, uppercase, faint |
| Step number | Mono, accent, `01` format |
| Focus | 2px accent-mix outline, 3px offset |
| Code / install | Mono block, copyable, honest meta only |

---

## Iteration

Agents and future AI passes must follow this loop:

1. Load this file before editing visuals or marketing copy.
2. Change **one or two** tokens or one section per pass.
3. Do not regenerate the whole Svelte tree.
4. Hero first. Then how-it-works. Then install. Then features.
5. If this file conflicts with a screenshot, source HTML, or an explicit user request, the direct source wins.
6. Keep product facts honest. If a claim is missing, ask — do not invent it.

---

## Negative prompt

Do not introduce:

- Purple / indigo SaaS gradients
- Soft gradient blobs as the hero
- Ornamental bento grids
- Glassmorphism on every card
- Inter display type
- Lucide-on-every-row feature lists
- Centered generic hero with three equal CTAs
- Fake testimonials, partner logos, or user counts
- Generated faces or stock lifestyle photography
- Motion that does not explain, confirm, or guide
- A full-page rewrite “to make it more premium”
- Any piracy framing, illegal-streaming framing, or “download anything” hype

---

## File map

| Path | Owns |
| :--- | :--- |
| `src/app.css` | Tokens, type, buttons, shared layout, themes |
| `src/lib/theme.ts` | Light/dark persist + apply |
| `src/lib/fonts.ts` | Preload + `@font-face` URLs |
| `src/lib/data/site.ts` | Copy, install facts, feature list |
| `src/lib/components/` | Sections |
| `src/lib/components/visuals/` | Product mocks (CastScene is theme-locked hardware) |
| Root `DESIGN.md` | Native app UI — out of scope here |

When tokens change, update `:root` in `src/app.css` and the tables in this file together.
