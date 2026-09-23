#!/bin/sh
set -eu

if [ "${CONFIGURATION:-}" != "Release" ]; then
    exit 0
fi

app_binary="${TARGET_BUILD_DIR:?}/${EXECUTABLE_PATH:?}"
if [ ! -f "$app_binary" ]; then
    echo "error: Release app binary is missing: $app_binary" >&2
    exit 1
fi

for symbol in pb_cast_core_abi_version pb_session_start pb_discovery_start; do
    if ! nm -gU "$app_binary" | grep -Eq "[[:space:]]_${symbol}$"; then
        echo "error: Release app is missing ${symbol}; run cast/build-apple.sh before building for distribution." >&2
        exit 1
    fi
done

echo "Verified Cast Core is linked into the Release app."
