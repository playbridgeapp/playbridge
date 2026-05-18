#!/usr/bin/env bash
# Generate all PlayBridge icons from the two source SVGs.
# Requires: rsvg-convert (librsvg), magick (ImageMagick 7), python3
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SQ="$REPO/mobile/android/store/icon_variants/lean_phone_browser_tv.svg"
BN="$REPO/mobile/android/store/icon_variants/lean_tv_banner_icon.svg"
BG="#e6f0ff"

# Glyph-only SVG (no background rect) for adaptive icon foregrounds
GLYPH=$(mktemp /tmp/pb_glyph_XXXXXX.svg)
cat > "$GLYPH" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
  <g transform="rotate(-13 512 512)" fill="none" stroke="#1a3fa6"
     stroke-width="170" stroke-linecap="round" stroke-linejoin="round">
    <path d="M 332 772 L 332 252"/>
    <path d="M 332 252 L 552 252 C 692 252 772 332 772 422
             C 772 512 692 582 552 582 L 332 582"/>
  </g>
</svg>
SVG

# Banner split into three layers for tvOS imagestack parallax.
# Back = gradient only (opaque). Middle = ghost glyph (transparent).
# Front = solid glyph (transparent). All share viewBox 1667×1000 so they
# register pixel-perfect when stacked.
BACK=$(mktemp /tmp/pb_back_XXXXXX.svg)
cat > "$BACK" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1667 1000">
  <defs>
    <radialGradient id="tv" cx="0.3" cy="0.2" r="0.9">
      <stop offset="0" stop-color="#f2f7ff"/>
      <stop offset="1" stop-color="#e6f0ff"/>
    </radialGradient>
  </defs>
  <rect width="1667" height="1000" fill="url(#tv)"/>
</svg>
SVG

MIDDLE=$(mktemp /tmp/pb_middle_XXXXXX.svg)
cat > "$MIDDLE" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1667 1000">
  <g opacity="0.18" transform="translate(220 -10) scale(1.05) rotate(-13 512 512)"
     fill="none" stroke="#1a3fa6" stroke-width="170"
     stroke-linecap="round" stroke-linejoin="round">
    <path d="M 380 800 L 380 280"/>
    <path d="M 380 280 L 600 280 C 740 280 820 360 820 450
             C 820 540 740 610 600 610 L 380 610"/>
  </g>
</svg>
SVG

FRONT=$(mktemp /tmp/pb_front_XXXXXX.svg)
cat > "$FRONT" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1667 1000">
  <g transform="translate(322 -12) rotate(-13 512 512)"
     fill="none" stroke="#1a3fa6" stroke-width="170"
     stroke-linecap="round" stroke-linejoin="round">
    <path d="M 380 800 L 380 280"/>
    <path d="M 380 280 L 600 280 C 740 280 820 360 820 450
             C 820 540 740 610 600 610 L 380 610"/>
  </g>
</svg>
SVG

# Round-icon variant: same glyph, circular background (replaces the rounded-rect
# tile with a full circle) — used for ic_launcher_round on circular launchers.
ROUND=$(mktemp /tmp/pb_round_XXXXXX.svg)
cat > "$ROUND" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
  <circle cx="512" cy="512" r="512" fill="#e6f0ff"/>
  <g transform="rotate(-13 512 512)" fill="none" stroke="#1a3fa6"
     stroke-width="170" stroke-linecap="round" stroke-linejoin="round">
    <path d="M 332 772 L 332 252"/>
    <path d="M 332 252 L 552 252 C 692 252 772 332 772 422
             C 772 512 692 582 552 582 L 332 582"/>
  </g>
</svg>
SVG

# Render full square SVG at a given square size
sq() { rsvg-convert -w "$2" -h "$2" "$SQ" -o "$1"; }

# Render the circular-background variant at a given square size
rd() { rsvg-convert -w "$2" -h "$2" "$ROUND" -o "$1"; }

# Render an arbitrary SVG to PNG at exact pixel dimensions (transparency preserved)
render() { rsvg-convert -w "$3" -h "$4" "$1" -o "$2"; }

# Render banner SVG scaled-to-fit, centred on a coloured canvas (flat composite,
# used for Android TV banners and tvOS top-shelf — neither parallaxes).
banner() {
  local out="$1" cw="$2" ch="$3"
  local rw rh
  read -r rw rh < <(python3 -c "
sw,sh,cw,ch=1667,1000,$cw,$ch
s=min(cw/sw,ch/sh)
print(int(sw*s),int(sh*s))")
  local tmp; tmp=$(mktemp /tmp/pb_bn_XXXXXX.png)
  rsvg-convert -w "$rw" -h "$rh" "$BN" -o "$tmp"
  magick -size "${cw}x${ch}" "xc:${BG}" "$tmp" -gravity center -composite "$out"
  rm "$tmp"
}

# Write ic_launcher_background.xml
bg_xml() {
  cat <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#e6f0ff</color>
</resources>
XML
}

# Android mipmap densities: density:full_size  (108dp = adaptive safe+bleed)
DENSITIES=(mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432)

android_mipmaps() {
  local res_dir="$1"
  for dp in "${DENSITIES[@]}"; do
    local dn=${dp%%:*} sz=${dp##*:}
    local d="$res_dir/mipmap-$dn"; mkdir -p "$d"
    # ic_launcher — square tile (rounded-rect bg + glyph)
    sq "$d/ic_launcher.png" "$sz"
    # ic_launcher_round — circular bg + glyph, for circular launchers
    rd "$d/ic_launcher_round.png" "$sz"
    # ic_launcher_foreground — glyph only, sized to ~66% (safe zone), on transparent
    local fg_sz=$(python3 -c "print(int($sz*0.66))")
    local tmp; tmp=$(mktemp /tmp/pb_fg_XXXXXX.png)
    rsvg-convert -w "$fg_sz" -h "$fg_sz" "$GLYPH" -o "$tmp"
    magick -size "${sz}x${sz}" xc:transparent "$tmp" -gravity center -composite \
      "$d/ic_launcher_foreground.png"
    rm "$tmp"
  done
}

# ── Android Phone ──────────────────────────────────────────────────────────────
echo "→ Phone"
sq "$REPO/mobile/android/app/src/main/ic_launcher-playstore.png" 512
android_mipmaps "$REPO/mobile/android/app/src/main/res"
bg_xml > "$REPO/mobile/android/app/src/main/res/values/ic_launcher_background.xml"

# ── Android TV Player ──────────────────────────────────────────────────────────
echo "→ TV Player"
sq "$REPO/tv/android/player/app/src/main/ic_launcher-playstore.png" 512
android_mipmaps "$REPO/tv/android/player/app/src/main/res"
bg_xml > "$REPO/tv/android/player/app/src/main/res/values/ic_launcher_background.xml"
banner "$REPO/tv/android/player/store/play_store_tv_banner_1280x720.png" 1280 720

# ── Android TV Browser ─────────────────────────────────────────────────────────
echo "→ TV Browser"
sq "$REPO/tv/android/browser/app/src/main/ic_launcher-playstore.png" 512
android_mipmaps "$REPO/tv/android/browser/app/src/main/res"
bg_xml > "$REPO/tv/android/browser/app/src/main/res/values/ic_launcher_background.xml"
banner "$REPO/tv/android/browser/store/play_store_browser_tv_banner_1280x720.png" 1280 720

# ── macOS Desktop ──────────────────────────────────────────────────────────────
echo "→ macOS"
MAC="$REPO/desktop/macos/Runner/Assets.xcassets/AppIcon.appiconset"
for size in 16 32 64 128 256 512 1024; do
  sq "$MAC/app_icon_${size}.png" "$size"
done
sq "$REPO/desktop/assets/tray_icon.png" 32

# ── Firefox Extension ──────────────────────────────────────────────────────────
echo "→ Extension"
sq "$REPO/extension/src/icon.png" 128

# ── Web ────────────────────────────────────────────────────────────────────────
echo "→ Web"
cp "$SQ" "$REPO/web/site/static/favicon.svg"
# Nav/Footer use an inline Svelte component (LogoMark) rather than the favicon.
# Keep it in sync with the brand glyph — sized via the `size` prop, no tile.
cat > "$REPO/web/site/src/lib/icons/LogoMark.svelte" <<'SVELTE'
<script lang="ts">
  interface Props { size?: number; }
  let { size = 24 }: Props = $props();
</script>

<svg width={size} height={size} viewBox="0 0 1024 1024" fill="none" aria-hidden="true">
  <g transform="rotate(-13 512 512)" fill="none" stroke="#1a3fa6"
     stroke-width="170" stroke-linecap="round" stroke-linejoin="round">
    <path d="M 332 772 L 332 252"/>
    <path d="M 332 252 L 552 252 C 692 252 772 332 772 422
             C 772 512 692 582 552 582 L 332 582"/>
  </g>
</svg>
SVELTE

# ── Apple TV ───────────────────────────────────────────────────────────────────
echo "→ Apple TV"
TVOS="$REPO/tv/apple/PlayBridge TV/PlayBridge TV/Assets.xcassets/App Icon & Top Shelf Image.brandassets"

# tvOS layered icons. Each .imagestack contains three .imagestacklayer folders
# (Back / Middle / Front). tvOS slides them in opposite directions on focus to
# produce the parallax effect — which only works when each layer holds *its own*
# content with transparency above and gradient/opacity beneath. Sizes match the
# tvOS spec: App Icon = 400×240 @1x + 800×480 @2x, App Icon - App Store =
# 1280×768 @1x + 2560×1536 @2x.
write_layer_contents() {
  local imageset="$1" filename="$2"
  cat > "$imageset/Contents.json" <<JSON
{
  "images" : [
    {
      "idiom" : "tv",
      "filename" : "${filename}.png",
      "scale" : "1x"
    },
    {
      "idiom" : "tv",
      "filename" : "${filename}@2x.png",
      "scale" : "2x"
    }
  ],
  "info" : {
    "version" : 1,
    "author" : "xcode"
  }
}
JSON
}

render_stack() {
  local stack="$1" w="$2" h="$3"
  local w2=$((w*2)) h2=$((h*2))
  local back="$stack/Back.imagestacklayer/Content.imageset"
  local mid="$stack/Middle.imagestacklayer/Content.imageset"
  local fr="$stack/Front.imagestacklayer/Content.imageset"
  render "$BACK"   "$back/back.png"      "$w"  "$h"
  render "$BACK"   "$back/back@2x.png"   "$w2" "$h2"
  render "$MIDDLE" "$mid/middle.png"     "$w"  "$h"
  render "$MIDDLE" "$mid/middle@2x.png"  "$w2" "$h2"
  render "$FRONT"  "$fr/logo.png"        "$w"  "$h"
  render "$FRONT"  "$fr/logo@2x.png"     "$w2" "$h2"
  write_layer_contents "$back" back
  write_layer_contents "$mid"  middle
  write_layer_contents "$fr"   logo
}

render_stack "$TVOS/App Icon.imagestack"             400  240
render_stack "$TVOS/App Icon - App Store.imagestack" 1280 768

# Top Shelf banners — single flat image, no parallax at this layer.
banner "$TVOS/Top Shelf Image.imageset/banner.png"            1920 720
banner "$TVOS/Top Shelf Image Wide.imageset/banner_wide.png"  2320 720

# Universal Logo imageset (referenced from Swift as Image("Logo")) — square icon
# rendered at 1x/2x/3x so SwiftUI picks the right density.
TVAPP="$REPO/tv/apple/PlayBridge TV/PlayBridge TV"
LOGO_SET="$TVAPP/Assets.xcassets/Logo.imageset"
sq "$LOGO_SET/logo.png"    512
sq "$LOGO_SET/logo@2x.png" 1024
sq "$LOGO_SET/logo@3x.png" 1536
cat > "$LOGO_SET/Contents.json" <<'JSON'
{
  "images" : [
    {
      "filename" : "logo.png",
      "idiom" : "universal",
      "scale" : "1x"
    },
    {
      "filename" : "logo@2x.png",
      "idiom" : "universal",
      "scale" : "2x"
    },
    {
      "filename" : "logo@3x.png",
      "idiom" : "universal",
      "scale" : "3x"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
JSON
# Sibling loose logo.png next to the .xcassets (referenced as a bundle resource).
sq "$TVAPP/logo.png" 512

rm -f "$GLYPH" "$ROUND" "$BACK" "$MIDDLE" "$FRONT"
echo "✓ Done"
