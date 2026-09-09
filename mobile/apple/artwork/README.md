# iOS app icon

`AppIcon.svg` uses Android's actual launcher source,
`mobile/android/store/icon_variants/lean_phone_browser_tv.svg` (referenced by
`scripts/generate_icons.sh`), with a square,
opaque background. iOS applies the icon's rounded mask.

Regenerate from the repository root with librsvg:

```sh
rsvg-convert -w 1024 -h 1024 -o 'mobile/apple/PlayBridge Phone/PlayBridge Phone/Assets.xcassets/AppIcon.appiconset/AppIcon.png' mobile/apple/artwork/AppIcon.svg
```
