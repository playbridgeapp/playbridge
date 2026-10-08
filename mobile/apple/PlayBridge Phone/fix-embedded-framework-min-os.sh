#!/bin/sh
set -eu

# MPVKit's XCFrameworks ship MinimumOSVersion = 100.0, which App Store validation
# rejects for embedded frameworks. Clamp each one to the app's deployment target and
# re-sign it, because the embed step has already sealed the original Info.plist.

frameworks_dir="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}"
# DEPLOYMENT_TARGET_SETTING_NAME is IPHONEOS_DEPLOYMENT_TARGET or TVOS_DEPLOYMENT_TARGET.
eval "deployment_target=\${${DEPLOYMENT_TARGET_SETTING_NAME:?}:?}"
[ -d "$frameworks_dir" ] || exit 0

for framework in "$frameworks_dir"/*.framework; do
    plist="$framework/Info.plist"
    [ -f "$plist" ] || continue
    current=$(/usr/libexec/PlistBuddy -c "Print :MinimumOSVersion" "$plist" 2>/dev/null || true)
    [ -n "$current" ] || continue
    newer=$(printf '%s\n%s\n' "$current" "$deployment_target" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)
    [ "$current" != "$deployment_target" ] && [ "$newer" = "$current" ] || continue

    /usr/libexec/PlistBuddy -c "Set :MinimumOSVersion $deployment_target" "$plist"
    if [ "${CODE_SIGNING_ALLOWED:-NO}" = "YES" ] && [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
        /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" \
            --preserve-metadata=identifier,flags --timestamp=none "$framework"
    fi
    echo "Set $(basename "$framework") MinimumOSVersion $current -> $deployment_target"
done
