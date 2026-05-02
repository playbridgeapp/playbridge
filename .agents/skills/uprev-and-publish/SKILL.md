---
name: uprev-and-publish
description: Stage current changes, increment versionCode/versionName for the affected PlayBridge apps (phone, tv-player, and/or tv-browser), and commit/push to main after user approval.
---

# Uprev and Publish

Use this skill when the user says things like:
- "uprev and push"
- "stage, uprev, commit and push"
- "bump the version and push"

## Project Layout

| App        | Path (build.gradle.kts)              | Version Lines                |
|------------|--------------------------------------|------------------------------|
| Phone      | `phone/app/build.gradle.kts`         | `versionCode`, `versionName` |
| TV Player  | `tv/player/app/build.gradle.kts`     | `versionCode`, `versionName` |
| TV Browser | `tv/browser/app/build.gradle.kts`    | `versionCode`, `versionName` |

All apps use the pattern `versionName = "0.1.<versionCode>"`.

## Steps

### 1. Identify which apps are affected

Run `git status` to see which files have been modified.

- If any file under `phone/` is changed → bump Phone.
- If any file under `tv/player/` is changed → bump TV Player.
- If any file under `tv/browser/` is changed → bump TV Browser.
- If multiple have changes → bump all affected apps.

### 2. Read current versions

Read the relevant `build.gradle.kts` files and note the current `versionCode` and `versionName` values.

### 3. Bump versions

For each affected app, increment `versionCode` by 1 and update `versionName` accordingly:

```kotlin
versionCode = <current + 1>
versionName = "0.1.<current + 1>"
```

Edit both lines together in a single tool call per file.

### 4. Mandatory User Confirmation

**CRITICAL: You MUST ask the user for permission before proceeding to the commit and push step.**
Summary the version changes you have made and ask: "Should I proceed with the commit and push?"

### 5. Stage, commit, and push

**ONLY proceed if the user gives explicit approval.**

```bash
git add -A && git commit -m "chore: uprev <app_names> <versions>" && git push
```

Example commit messages:
- `"chore: uprev phone 0.1.43, tv-player 0.1.46"`
- `"chore: uprev tv-browser 0.1.14"`

## Notes

- Never skip staging (`git add -A`) — always include all current working-tree changes.
- Do not bump an app's version if none of its source files were modified.
- Version numbers must stay in sync: `versionName` must always equal `"0.1.<versionCode>"`.
- If the user has explicitly asked to NOT push, only run the `git commit` part.
