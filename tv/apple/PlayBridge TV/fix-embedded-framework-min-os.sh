#!/bin/bash
set -euo pipefail

# MPVKit 1.0.0 device frameworks advertise minos 100.0 in LC_BUILD_VERSION,
# independently of Info.plist. Repair embedded copies after Xcode's embed step;
# never modify the package artifacts. Simulator load commands are left alone.
[[ "${PLATFORM_NAME:-}" == "appletvos" ]] || exit 0
frameworks_dir="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}"
deployment_target="${TVOS_DEPLOYMENT_TARGET:?}"
[[ -d "$frameworks_dir" ]] || exit 0

work_dir=$(mktemp -d "${TARGET_TEMP_DIR:-${TMPDIR:-/tmp}}/playbridge-framework-minos.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT

version_greater() {
    awk -v current="$1" -v target="$2" 'BEGIN {
        split(current, a, "."); split(target, b, ".");
        for (i = 1; i <= 3; i++) {
            if (a[i] + 0 > b[i] + 0) exit 0;
            if (a[i] + 0 < b[i] + 0) exit 1;
        }
        exit 1;
    }'
}

# vtool/codesign can print routine signature messages. Keep successful builds to
# one line per changed framework, but retain complete diagnostics on failure.
run_quietly() {
    if "$@" > "$work_dir/tool.log" 2>&1; then
        return 0
    else
        cat "$work_dir/tool.log" >&2
        return 1
    fi
}

for framework in "$frameworks_dir"/*.framework; do
    plist="$framework/Info.plist"
    [[ -f "$plist" ]] || continue
    executable=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$plist")
    binary="$framework/$executable"
    [[ -f "$binary" ]] || continue
    description=$(/usr/bin/file -b "$binary")
    [[ "$description" == *Mach-O* && "$description" != *"ar archive"* ]] || continue

    arch_list=$(xcrun lipo -archs "$binary")
    read -r -a architectures <<< "$arch_list"
    patched=NO
    for arch in "${architectures[@]}"; do
        build_version=$(xcrun vtool -arch "$arch" -show-build "$binary")
        values=$(printf '%s\n' "$build_version" | awk '
            $1 == "cmd" { build = ($2 == "LC_BUILD_VERSION"); platform = ""; minos = "" }
            build && $1 == "platform" { platform = $2 }
            build && $1 == "minos" { minos = $2 }
            build && platform == "TVOS" && $1 == "sdk" { print minos, $2 }
        ')
        [[ -n "$values" ]] || continue
        read -r minos sdk <<< "$values"
        if version_greater "$minos" "$deployment_target"; then
            mode=$(stat -f '%Lp' "$binary")
            run_quietly xcrun vtool -arch "$arch" \
                -set-build-version tvos "$deployment_target" "$sdk" \
                -replace -output "$work_dir/framework" "$binary"
            chmod "$mode" "$work_dir/framework"
            mv -f "$work_dir/framework" "$binary"
            patched=YES
        fi
    done
    [[ "$patched" == "YES" ]] || continue

    current=$(/usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "$plist" 2>/dev/null || true)
    if [[ "$current" != "$deployment_target" ]]; then
        if [[ -n "$current" ]]; then
            /usr/libexec/PlistBuddy -c "Set :MinimumOSVersion $deployment_target" "$plist"
        else
            /usr/libexec/PlistBuddy -c "Add :MinimumOSVersion string $deployment_target" "$plist"
        fi
    fi
    if [[ "${CODE_SIGNING_ALLOWED:-NO}" != "NO" && -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]]; then
        run_quietly /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" \
            --preserve-metadata=identifier,entitlements,flags --timestamp=none "$framework"
    fi
    echo "Fixed $(basename "$framework") embedded minimum OS -> $deployment_target"
done
