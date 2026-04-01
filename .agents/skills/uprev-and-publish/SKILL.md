---
name: uprev-and-publish
description: Stage current changes, increment versionCode/versionName for the affected PlayBridge apps (phone and/or tv), commit, and push to main.
---

# Uprev and Publish

Use this skill when the user says things like:
- "uprev and push"
- "stage, uprev, commit and push"
- "bump the version and push"

## Project Layout

| App   | build.gradle.kts path                          | version lines          |
|-------|------------------------------------------------|------------------------|
| Phone | `phone/app/build.gradle.kts`                   | `versionCode`, `versionName` |
| TV    | `tv/player/app/build.gradle.kts`               | `versionCode`, `versionName` |

Both apps use the pattern `versionName = "0.1.<versionCode>"`.

## Steps

### 1. Identify which apps are affected

Run `git status` to see which files have been modified.

- If any file under `phone/` is changed → bump phone.
- If any file under `tv/` is changed → bump TV.
- If both have changes → bump both.

### 2. Read current versions

Read the relevant `build.gradle.kts` files and note the current `versionCode` and `versionName` values.

### 3. Bump versions

For each affected app, increment `versionCode` by 1 and update `versionName` accordingly:

```
versionCode = <current + 1>
versionName = "0.1.<current + 1>"
```

Edit both lines together in a single tool call per file.

### 4. Stage, commit, and push

```bash
git add -A && git commit -m "chore: uprev phone <new_phone_version>, tv <new_tv_version>" && git push
```

If only one app was bumped, omit the other from the commit message, e.g.:
- Phone only: `"chore: uprev phone 0.1.29"`
- TV only: `"chore: uprev tv 0.1.34"`

## Notes

- Never skip staging (`git add -A`) — always include all current working-tree changes.
- Do not bump an app's version if none of its source files were modified.
- Version numbers must stay in sync: `versionName` must always equal `"0.1.<versionCode>"`.
