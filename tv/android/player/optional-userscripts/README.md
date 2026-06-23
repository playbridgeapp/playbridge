# Optional user scripts (NOT shipped in the APK)

Scripts here are **not** bundled into the app and are **not** injected by default. The
published build contains none of this code. The TV player only runs one of these if you
place it on the device yourself, into the player app's external files dir.


## How to load one

### Option A — from the phone (easiest)

In the phone app's browser remote, open the **More** sheet and tap **Scripts**. The manager
lists the scripts currently installed on the TV and lets you:

- **Install from file…** — pick a `.js` saved on the phone; it's sent over the existing
  paired connection and saved on the TV under its own filename.
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

Either way, the player reads every `*.js` in that dir fresh on each page load, so it takes
effect on the next navigation. **Remove it to turn it back off** — delete the file, or from
the phone send an empty `user_script` for that name (the TV deletes it).


## Adding your own

The player loads **every** `*.js` in the dir (see `SystemWebViewEngine.externalUserScripts`),
so any filename works. Each script should be a self-contained IIFE that self-guards against
double injection (it's re-injected on every page load).
