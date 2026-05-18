#!/usr/bin/env bash
set -e

# Ensure we are in project root (assuming script is placed in project root)
cd "$(dirname "$0")"

# Check if gh cli is installed
if ! command -v gh &> /dev/null; then
    echo "❌ Error: GitHub CLI (gh) is not installed."
    echo "Please install it from https://cli.github.com/ and run 'gh auth login' to authenticate."
    exit 1
fi

echo "Publishing PlayBridge Releases..."

# Get versions from build.gradle.kts files
PHONE_VERSION=$(grep 'versionName =' phone/app/build.gradle.kts | grep -o '"[^"]*"$' | sed 's/"//g')
TV_PLAYER_VERSION=$(grep 'versionName =' tv/player/app/build.gradle.kts | grep -o '"[^"]*"$' | sed 's/"//g')
TV_BROWSER_VERSION=$(grep 'versionName =' tv/browser/app/build.gradle.kts | grep -o '"[^"]*"$' | sed 's/"//g')

echo "========================================="
echo "📱 Phone Version     : $PHONE_VERSION"
echo "📺 TV Player Version : $TV_PLAYER_VERSION"
echo "🌐 TV Browser Version: $TV_BROWSER_VERSION"
echo "========================================="

# Helper function to publish a module
# Args: <module_path> <tag_prefix> <title> <version>
publish_module() {
  local module=$1
  local tag_prefix=$2
  local title=$3
  local version=$4
  local tag="${tag_prefix}-v${version}"

  # Search for all .apk files in the release directory
  local apks=()
  for search_dir in "${module}/app/release" "${module}/app/build/outputs/apk/release"; do
    if [ -d "$search_dir" ]; then
        for apk in "$search_dir"/*.apk; do
          if [ -f "$apk" ]; then
            apks+=("$apk")
          fi
        done
    fi
  done

  if [ ${#apks[@]} -eq 0 ]; then
    echo "⚠️ No APKs found for '$module'. Did you build the release?"
    return
  fi

  echo "Checking if release '$tag' already exists..."
  if gh release view "$tag" >/dev/null 2>&1; then
    echo "⏭️ Release '$tag' already exists. Skipping."
  else
    local prev_tag=$(git tag -l "${tag_prefix}-v*" --sort=-v:refname | grep -v "^${tag}$" | head -n 1)

    local cmd=(gh release create "$tag" "${apks[@]}" --title "$title v${version}" --generate-notes)
    if [ -n "$prev_tag" ]; then
      echo "📝 Generating notes since: $prev_tag"
      cmd+=(--notes-start-tag "$prev_tag")
    fi

    echo "🚀 Creating release '$tag' with ${#apks[@]} APK(s)..."
    "${cmd[@]}"
    echo "✅ Release '$tag' created successfully!"
  fi
}

publish_module "phone"      "phone"      "Phone App"      "$PHONE_VERSION"
echo "-----------------------------------------"
publish_module "tv/player"  "tv-player"  "TV Player App"  "$TV_PLAYER_VERSION"
echo "-----------------------------------------"
publish_module "tv/browser" "tv-browser" "TV Browser App" "$TV_BROWSER_VERSION"

echo "🎉 Done."
