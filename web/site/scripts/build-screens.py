#!/usr/bin/env python3
"""Build the marketing-site product screenshots.

Reads chosen captures from ``docs/screenshots/`` (repository root), crops
status bars / gesture bars, paints over transient system UI (the tvOS
"Screenshot Taken" toast, a receiver device ID), and writes responsive WebP
files plus one JPEG fallback (largest width) into ``web/site/static/screens/``.

    python3 web/site/scripts/build-screens.py

Requires Pillow with WebP support. Output is deterministic for a given
Pillow version; commit the generated files together with this script.

Content rules: only PlayBridge UI and open-licensed media (Big Buck Bunny,
(c) Blender Foundation, CC BY 3.0). No debrid / IPTV / add-on names, no
commercial posters. See ``SCREENS`` below for what is used and why.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageFilter

SITE = Path(__file__).resolve().parent.parent
REPO = SITE.parent.parent
SRC = REPO / "docs" / "screenshots"
OUT = SITE / "static" / "screens"

WEBP_QUALITY = 80
JPEG_QUALITY = 80

# Apple TV captures are 3840x2160; the toast sits top-right.
TVOS_TOAST = (3070, 65, 3775, 250)

# Each entry: output name, source file, optional pre-crop patches (source px),
# optional crop box (source px), output widths.
SCREENS: list[dict] = [
    {
        # Hero TV: Apple TV pre-play for Big Buck Bunny with the autoplay countdown.
        "name": "tv-appletv-preplay",
        "src": "apple-tv-preplay.jpg",
        "patch": [TVOS_TOAST],
        "widths": [480, 720, 960, 1440],
    },
    {
        # Hero phone: Android remote, context tab, Big Buck Bunny on the TV.
        "name": "phone-remote-context",
        "src": "phone-remote-context.jpg",
        "crop": (0, 120, 1080, 2280),  # drop status bar + gesture bar -> 1:2
        "widths": [240, 480],
    },
    {
        # Browser band: GeckoView browser with the blocker test page passing.
        "name": "phone-browser",
        "src": "phone-browser.jpg",
        "crop": (0, 105, 1080, 2265),
        "widths": [360, 720],
    },
    {
        # Pairing band: Android TV receiver waiting for a sender.
        "name": "tv-android-pairing",
        "src": "tv-pairing.png",
        "patch": [(930, 558, 1470, 606)],  # receiver device ID line
        "widths": [480, 960, 1440],
    },
    {
        # Control companion: Android remote in touchpad mode.
        "name": "phone-remote-touchpad",
        "src": "phone-remote-touchpad.jpg",
        "crop": (0, 120, 1080, 2280),
        "widths": [240, 480],
    },
    {
        # Screens companion: Apple TV playback engine picker (AVPlayer / VLC / MPV).
        "name": "tv-appletv-engines",
        "src": "apple-tv-settings.jpg",
        "patch": [TVOS_TOAST],
        "widths": [480, 960],
    },
]


def inpaint_box(img: Image.Image, box: tuple[int, int, int, int]) -> None:
    """Fill ``box`` by blending its border pixels (bilinear Coons patch).

    Good enough for the smooth, blurred backgrounds these overlays sit on.
    """
    x0, y0, x1, y1 = box
    px = img.load()
    w, h = x1 - x0, y1 - y0
    top = [px[x0 + i, y0 - 1] for i in range(w)]
    bottom = [px[x0 + i, y1] for i in range(w)]
    left = [px[x0 - 1, y0 + j] for j in range(h)]
    right = [px[x1, y0 + j] for j in range(h)]
    c00, c10 = px[x0 - 1, y0 - 1], px[x1, y0 - 1]
    c01, c11 = px[x0 - 1, y1], px[x1, y1]
    for j in range(h):
        v = (j + 0.5) / h
        for i in range(w):
            u = (i + 0.5) / w
            out = []
            for k in range(3):
                lr = (1 - u) * left[j][k] + u * right[j][k]
                tb = (1 - v) * top[i][k] + v * bottom[i][k]
                corner = (
                    (1 - u) * (1 - v) * c00[k]
                    + u * (1 - v) * c10[k]
                    + (1 - u) * v * c01[k]
                    + u * v * c11[k]
                )
                out.append(int(round(lr + tb - corner)))
            px[x0 + i, y0 + j] = tuple(max(0, min(255, c)) for c in out)
    # Soften any streaks from noisy border pixels.
    pad = 6
    region = (x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    img.paste(img.crop(region).filter(ImageFilter.GaussianBlur(4)), region[:2])


def build(spec: dict) -> list[Path]:
    img = Image.open(SRC / spec["src"]).convert("RGB")
    for box in spec.get("patch", []):
        inpaint_box(img, box)
    if "crop" in spec:
        img = img.crop(spec["crop"])
    written = []
    widths = sorted(spec["widths"])
    for width in widths:
        height = round(img.height * width / img.width)
        resized = img.resize((width, height), Image.LANCZOS)
        path = OUT / f"{spec['name']}-{width}.webp"
        resized.save(path, "WEBP", quality=WEBP_QUALITY, method=6)
        written.append(path)
        if width == widths[-1]:
            jpg = OUT / f"{spec['name']}-{width}.jpg"
            resized.save(jpg, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
            written.append(jpg)
    print(f"{spec['name']}: {img.width}x{img.height} -> {widths}")
    return written


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    keep: set[Path] = set()
    for spec in SCREENS:
        keep.update(build(spec))
    for stale in OUT.iterdir():
        if stale.is_file() and stale not in keep:
            stale.unlink()
            print(f"removed stale {stale.name}")
    total = sum(p.stat().st_size for p in keep)
    for p in sorted(keep):
        print(f"  {p.name:36} {p.stat().st_size / 1024:7.1f} KB")
    print(f"total {total / 1024:.1f} KB in {len(keep)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
