package com.playbridge.geckoview.plugin

import android.app.Application
import android.content.Context
import android.util.Log
import com.playbridge.geckoview.plugin.logging.FileLogger
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class PlayBridgeBrowserApplication : Application() {

    companion object {
        private const val TAG = "PBApplication"

        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        // AMO's stable "latest signed XPI" redirect for uBlock Origin.
        private const val UBLOCK_AMO_XPI =
            "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        private const val UBLOCK_UPDATE_CHECK_PREF = "ublock_last_update_check"
        private const val UBLOCK_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    override fun onCreate() {
        super.onCreate()
        com.playbridge.shared.SharedContext.init(this)
        FileLogger.init(this)

        // Pre-warm GeckoRuntime and ensure extensions once at startup to improve browser launch speed.
        // IMPORTANT: Only do this in the main process! Child processes (Gecko tab processes)
        // should NOT re-initialize the runtime.
        if (isMainProcess()) {
            try {
                val runtime = GeckoRuntime.getDefault(this)
                runtime.warmUp()

                // Auto-allow extension install/update prompts. Required for the AMO
                // install below — this headless browser plugin has no UI to surface
                // permission prompts, and we only ever install uBlock Origin.
                runtime.webExtensionController.promptDelegate =
                    object : WebExtensionController.PromptDelegate {
                        override fun onInstallPromptRequest(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<WebExtension.PermissionPromptResponse>? {
                            Log.i(TAG, "Auto-allowing extension install: ${extension.id}")
                            return GeckoResult.fromValue(
                                WebExtension.PermissionPromptResponse(true, true, true)
                            )
                        }

                        override fun onUpdatePrompt(
                            extension: WebExtension,
                            newPermissions: Array<out String>,
                            newOrigins: Array<out String>,
                            newDataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)

                        override fun onOptionalPrompt(
                            extension: WebExtension,
                            permissions: Array<out String>,
                            origins: Array<out String>,
                            dataCollectionPermissions: Array<out String>
                        ): GeckoResult<AllowOrDeny>? = GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                // uBlock Origin: real AMO install (signed store build) instead of the
                // old bundled ensureBuiltIn copy — see installOrUpdateUblock().
                installOrUpdateUblock(runtime)

                runtime.webExtensionController.ensureBuiltIn(
                    "resource://android/assets/extensions/pb_bridge/",
                    "pb-bridge@playbridge.com"
                ).accept(
                    { ext ->
                        // Register the native message delegate here — on the extension as it's first
                        // ensured — so the background script's connectNative finds it instead of
                        // falling back to an (unsupported) native manifest lookup.
                        if (ext != null) PbBridge.register(ext)
                        Log.i(TAG, "PB Bridge pre-initialized")
                    },
                    { e -> Log.e(TAG, "PB Bridge pre-init failed", e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-warm Gecko", e)
            }
        }
    }

    /**
     * Ensure uBlock Origin is installed as a real AMO install and kept current.
     * Mirrors the phone app's Components.installOrUpdateUblock(). Three cases:
     *
     *  - not installed → install from AMO (an offline first launch simply retries
     *    on the next startup, since this runs every launch);
     *  - legacy bundled built-in copy present → uninstall it once, then AMO-install
     *    (the built-in froze filter lists at build time and stripped locale data,
     *    which is why it blocked worse than a store install);
     *  - AMO copy present → trigger a signed update check via its AMO update_url,
     *    throttled to once a day (GeckoView never updates extensions on its own).
     */
    private fun installOrUpdateUblock(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.list().then({ extensions ->
            val existing = extensions?.firstOrNull { it.id == UBLOCK_ID }
            when {
                existing == null -> installUblockFromAmo(runtime, reason = "fresh install")
                existing.isBuiltIn -> {
                    Log.i(TAG, "Migrating uBlock Origin: bundled built-in → AMO install")
                    controller.uninstall(existing).accept(
                        { installUblockFromAmo(runtime, reason = "migration from built-in") },
                        // Keep the bundled copy working rather than ending up with none.
                        { e -> Log.e(TAG, "Failed to remove built-in uBlock; keeping bundled copy", e) }
                    )
                }
                else -> maybeCheckUblockUpdate(runtime, existing)
            }
            GeckoResult.fromValue(null)
        }, { throwable ->
            Log.e(TAG, "webExtensionController.list() failed; skipping uBlock setup", throwable)
            GeckoResult.fromValue(null)
        })
    }

    private fun installUblockFromAmo(runtime: GeckoRuntime, reason: String) {
        Log.i(TAG, "Installing uBlock Origin from AMO ($reason)…")
        runtime.webExtensionController
            .install(UBLOCK_AMO_XPI, WebExtensionController.INSTALLATION_METHOD_MANAGER)
            .accept(
                { ext -> Log.i(TAG, "uBlock Origin ${ext?.metaData?.version} installed from AMO ($reason)") },
                { e -> Log.e(TAG, "uBlock Origin AMO install failed ($reason) — will retry next launch", e) }
            )
    }

    private fun maybeCheckUblockUpdate(runtime: GeckoRuntime, extension: WebExtension) {
        val prefs = getSharedPreferences("pb_browser_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(UBLOCK_UPDATE_CHECK_PREF, 0L) < UBLOCK_UPDATE_INTERVAL_MS) return
        prefs.edit().putLong(UBLOCK_UPDATE_CHECK_PREF, now).apply()
        runtime.webExtensionController.update(extension).accept(
            { updated ->
                if (updated != null) Log.i(TAG, "uBlock Origin updated to ${updated.metaData?.version}")
                else Log.d(TAG, "uBlock Origin is up to date")
            },
            { e -> Log.w(TAG, "uBlock Origin update check failed", e) }
        )
    }

    private fun isMainProcess(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName() == packageName
        } else {
            // Fallback for older versions (Min SDK is 26)
            val pid = android.os.Process.myPid()
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == pid }?.processName == packageName
        }
    }
}
