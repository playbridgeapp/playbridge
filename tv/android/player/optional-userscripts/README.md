# Optional user scripts (NOT shipped in the APK)

Scripts here are **not** bundled into the app and are **not** injected by default. The
published build contains none of this code. The TV player runs one only after the TV
owner approves that exact file.


## How to load one

### Option A — from the phone (easiest)

In the phone app's browser remote, open the **More** sheet and tap **Scripts**. The manager
lists the scripts currently installed on the TV and lets you:

- **Install from file…** — pick a `.js` saved on the phone; it's sent over the existing
  paired connection. The TV asks before saving it under its own filename.
- **Remove** (trash icon) — deletes that script from the TV.

Nothing is bundled in either app — you supply the file. (Implemented as the `user_script` /
`user_script_query` WebSocket messages → `ServerService` writes/lists/deletes in the dir
below and reports the list back to the phone.)

### Option B — directly onto the device

Copy the script into the player app's external files dir:

```
/sdcard/Android/data/com.playbridge.player/files/<script-name>.js
```

For example, with adb:

```
adb push userscript.js \
  /sdcard/Android/data/com.playbridge.player/files/userscript.js
```

A phone install is saved as pending and does not run until the TV shows an approval
prompt (sender, name, size, SHA-256 prefix, and `@match` list — or a warning if the
script has no `@match` and would run on every site). Deny or a 60 second timeout
deletes it. **Allow phones to install browser scripts** in TV settings defaults off;
an upgrade that already has scripts turns that toggle on, but those files still need
a one-time approval before they run.

Copying a file in with adb or a file manager does **not** run it. Open TV settings →
Browser → Installed scripts and approve it there. If the file changes after approval,
it stops running until you approve the new contents.

A script is injected only on top-level pages that match a `// @match` line in its
`==UserScript==` header (Chrome match-pattern rules). No `@match` means all sites,
and only after the TV owner accepted that warning. **Remove it to turn it back off**
— delete it in TV settings, or from the phone send an empty `user_script` for that
name (the TV deletes it without another prompt).


## Adding your own

The player considers every `*.js` in that dir (see `SystemWebViewEngine.approvedUserScripts`).
Each script should be a self-contained IIFE that self-guards against double injection
(it's re-injected on every matching page load).
