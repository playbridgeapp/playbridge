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
TV_VERSION=$(grep 'versionName =' tv/app/build.gradle.kts | grep -o '"[^"]*"$' | sed 's/"//g')

echo "========================================="
echo "📱 Phone Version : $PHONE_VERSION"
echo "📺 TV Version    : $TV_VERSION"
echo "========================================="

# Helper function to publish a module
publish_module() {
  local module=$1
  local version=$2
  local tag="${module}-v${version}"
  local title=""
  local apk_dir="${module}/app/build/outputs/apk/release"

  if [ "$module" == "phone" ]; then
      title="Phone App v${version}"
  else
      title="TV App v${version}"
  fi
  
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
    echo "⚠️ No $module APKs found. Did you build the release?"
    return
  fi

  echo "Checking if release '$tag' already exists..."
  if gh release view "$tag" >/dev/null 2>&1; then
    echo "⏭️ Release '$tag' already exists. Skipping."
  else
    local prev_tag=$(git tag -l "${module}-v*" --sort=-v:refname | grep -v "^${tag}$" | head -n 1)
    
    local cmd=(gh release create "$tag" "${apks[@]}" --title "$title" --generate-notes)
    if [ -n "$prev_tag" ]; then
      echo "📝 Generating notes since: $prev_tag"
      cmd+=(--notes-start-tag "$prev_tag")
    fi

    echo "🚀 Creating release '$tag' with ${#apks[@]} APK(s)..."
    "${cmd[@]}"
    echo "✅ Release '$tag' created successfully!"
  fi
}

publish_module "phone" "$PHONE_VERSION"
echo "-----------------------------------------"
publish_module "tv" "$TV_VERSION"

echo "🎉 Done."
