#!/usr/bin/env bash
# Generate all PlayBridge icons from the two source SVGs.
# Requires: rsvg-convert (librsvg), magick (ImageMagick 7), python3
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SQ="$REPO/mobile/android/store/icon_variants/lean_phone_browser_tv.svg"
BN="$REPO/mobile/android/store/icon_variants/lean_tv_banner_icon.svg"
BG="#e6f0ff"

# Glyph-only SVG (no background rect) for adaptive icon foregrounds + tvOS layers
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

# Render full square SVG at a given square size
sq() { rsvg-convert -w "$2" -h "$2" "$SQ" -o "$1"; }

# Render glyph (transparent bg) centred on a coloured canvas
canvas() {
  local out="$1" cw="$2" ch="$3" glyph_size="$4"
  local tmp; tmp=$(mktemp /tmp/pb_g_XXXXXX.png)
  rsvg-convert -w "$glyph_size" -h "$glyph_size" "$GLYPH" -o "$tmp"
  magick -size "${cw}x${ch}" "xc:${BG}" "$tmp" -gravity center -composite "$out"
  rm "$tmp"
}

# Render banner SVG scaled-to-fit, centred on a coloured canvas
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
    # ic_launcher / ic_launcher_round  — full icon (bg + glyph)
    sq "$d/ic_launcher.png" "$sz"
    sq "$d/ic_launcher_round.png" "$sz"
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

# ── Web favicon ────────────────────────────────────────────────────────────────
echo "→ Web"
cp "$SQ" "$REPO/web/site/static/favicon.svg"

# ── Apple TV ───────────────────────────────────────────────────────────────────
echo "→ Apple TV"
TVOS="$REPO/tv/apple/PlayBridge TV/PlayBridge TV/Assets.xcassets/App Icon & Top Shelf Image.brandassets"

# Home screen icon layers (400×240)
canvas "$TVOS/App Icon.imagestack/Front.imagestacklayer/Content.imageset/logo.png" \
  400 240 180
magick -size 400x240 "xc:${BG}" \
  "$TVOS/App Icon.imagestack/Back.imagestacklayer/Content.imageset/back.png"

# App Store icon layers (same dimensions)
canvas "$TVOS/App Icon - App Store.imagestack/Front.imagestacklayer/Content.imageset/logo.png" \
  400 240 180
magick -size 400x240 "xc:${BG}" \
  "$TVOS/App Icon - App Store.imagestack/Back.imagestacklayer/Content.imageset/back.png"

# Top Shelf banners
banner "$TVOS/Top Shelf Image.imageset/banner.png" 1920 720
banner "$TVOS/Top Shelf Image Wide.imageset/banner_wide.png" 2320 720

rm -f "$GLYPH"
echo "✓ Done"
