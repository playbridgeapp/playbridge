#!/bin/bash

# PlayBridge - uBlock Origin GeckoView Updater
# This script downloads a specific version of uBlock Origin, unzips it into the Android assets
# directory, and patches the Firefox manifest.json to work seamlessly on Android GeckoView.

if [ -z "$1" ]; then
    echo "Fetching latest uBlock Origin version from GitHub..."
    # Query GitHub API for the latest release tag (fallback to grep/awk so jq isn't required)
    VERSION=$(curl -sL https://api.github.com/repos/gorhill/uBlock/releases/latest | grep '"tag_name":' | head -n 1 | awk -F '"' '{print $4}')
    
    if [ -z "$VERSION" ]; then
        echo "Error: Could not fetch the latest version automatically."
        echo "Please provide it manually: ./update_ublock.sh [VERSION]"
        exit 1
    fi
    echo "Latest version found: $VERSION"
else
    VERSION=$1
fi
EXT_DIR="tv/app/src/main/assets/extensions/ublock_origin"
ZIP_FILE="ublock.zip"
URL="https://github.com/gorhill/uBlock/releases/download/$VERSION/uBlock0_$VERSION.firefox.signed.xpi"

echo "=========================================="
echo " Updating uBlock Origin to v$VERSION"
echo "=========================================="

# 1. Clean up old extension folder
echo "1. Removing old extension directory..."
rm -rf "$EXT_DIR"
mkdir -p "$EXT_DIR"

# 2. Download the official signed .xpi release from GitHub
echo "2. Downloading Firefox .xpi archive ($URL)..."
curl -L -o "$ZIP_FILE" "$URL"

if [ $? -ne 0 ]; then
    echo "Error: Failed to download uBlock Origin version $VERSION."
    rm -f "$ZIP_FILE"
    exit 1
fi

# 3. Unzip into the assets folder 
# (GeckoView on Android requires folders, not .xpi files, when loaded from local assets)
echo "3. Extracting archive into Android assets..."
unzip -q "$ZIP_FILE" -d "$EXT_DIR"
rm -f "$ZIP_FILE"

# 4. Patch manifest.json for Android compatibility
echo "4. Patching manifest.json for GeckoView compatibility..."
MANIFEST_TMP="$EXT_DIR/manifest_tmp.json"
MANIFEST="$EXT_DIR/manifest.json"

# We must use awk/sed or simpler tools to manually strip specific permissions.
# GeckoView will aggressively crash the extension load if it sees Desktop Firefox APIs
# like 'menus' or 'privacy'. 
# We also delete the 'default_locale' requirement since Android's asset bundler automatically deletes
# folders starting with an underscore (like '_locales'), which causes a File Not Found crash.

# Strip out desktop permissions
sed -i '' '/"menus",/d' "$MANIFEST"
sed -i '' '/"privacy",/d' "$MANIFEST"

# Replace all translation keys (__MSG_*) with a hardcoded generic English string so 
# we don't have to parse actual JSON or bundle the Firefox translation _locales directory.
sed -i '' 's/"__MSG_[a-zA-Z0-9_]*__"/"uBlock action"/g' "$MANIFEST"

# Hardcode the English description instead of looking for the deleted translations
sed -i '' 's/"__MSG_extShortDesc__"/"Finally, an efficient blocker. Easy on CPU and memory."/' "$MANIFEST"

# Remove the translation enforcement to prevent the _locales packaging crash
sed -i '' '/"default_locale":/d' "$MANIFEST"

echo ""
echo "=========================================="
echo " Success! uBlock Origin v$VERSION has been installed and patched."
echo " Next steps: Rebuild the Android TV application."
echo "=========================================="
