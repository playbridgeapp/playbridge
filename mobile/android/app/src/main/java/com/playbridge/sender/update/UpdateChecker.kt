package com.playbridge.sender.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the "is there a newer phone build?" check and drives the update flow.
 *
 * Version resolution reuses the website's download endpoint instead of the GitHub API:
 * a GET to [DOWNLOAD_ENDPOINT] with redirects disabled returns a `Location` pointing at
 * the latest release asset, e.g.
 * `…/releases/download/phone-v0.8.0/playbridge-phone-0.8.0-app-universal-release.apk`.
 * We parse the version from the filename and keep the URL for the sideload download. This
 * gives us both facts in one cached request with no API rate limits.
 *
 * Registered as a Koin singleton so cold-start and the Settings button share one [state].
 */
class UpdateChecker(
    private val appContext: Context,
    private val installer: ApkInstaller,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Guards against overlapping checks (cold start + a fast manual tap). */
    private val checking = AtomicBoolean(false)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false) // we want to read the 302 Location ourselves
            .followSslRedirects(false)
            .build()
    }

    /**
     * Check for a newer version.
     *
     * @param manual true when the user tapped "Check for updates" — surfaces Checking /
     *   Up to date / Error states. Cold-start (false) stays silent unless an update exists.
     */
    fun check(manual: Boolean) {
        if (!checking.compareAndSet(false, true)) return
        // Don't stomp an in-flight download/install with a background check.
        val current = _state.value
        if (!manual && (current is UpdateState.Downloading ||
                current is UpdateState.Installing ||
                current is UpdateState.Available)
        ) {
            checking.set(false)
            return
        }
        Log.i(TAG, "check(manual=$manual) starting")
        _state.value = UpdateState.Checking(manual)
        scope.launch {
            val result = runCatching { resolveLatest() }
            checking.set(false)
            result.onSuccess { info ->
                when {
                    // null == already on the latest version (not an error).
                    info == null -> {
                        Log.i(TAG, "check: up to date")
                        _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                    }
                    // Play Store users: on an automatic check, hold off nagging until the
                    // build has been out long enough that the Store has likely rolled it
                    // out. A manual check ignores the grace period.
                    !manual && info.source == InstallSource.PLAY_STORE &&
                        !isPlayGraceElapsed(info.version) -> {
                        Log.i(
                            TAG,
                            "check: ${info.version} available but within Play rollout grace — suppressing"
                        )
                        _state.value = UpdateState.Idle
                    }
                    // "Later" snooze: suppress auto prompts for this exact version until a
                    // newer release ships (or the user taps Check for updates).
                    !manual && isSnoozed(info.version) -> {
                        Log.i(
                            TAG,
                            "check: ${info.version} available but snoozed after Later — suppressing"
                        )
                        _state.value = UpdateState.Idle
                    }
                    else -> {
                        Log.i(TAG, "check: update available ${info.version} (${info.source})")
                        _state.value = UpdateState.Available(info)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "check: failed", e)
                _state.value = if (manual) {
                    UpdateState.Error(e.message ?: "Update check failed.", manual = true)
                } else UpdateState.Idle
            }
        }
    }

    /** Resolve the latest [UpdateInfo], or null if there is nothing newer than installed. */
    private suspend fun resolveLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val installed = installedVersion()
            ?: throw IllegalStateException("Can't read installed version")
        Log.d(TAG, "resolveLatest: installed=$installed, GET $DOWNLOAD_ENDPOINT")

        // GET (not HEAD) with redirects disabled: the Cloudflare Pages function only
        // handles GET, and a 302 carries no body, so this reads the Location header
        // without downloading the APK.
        val request = Request.Builder()
            .url(DOWNLOAD_ENDPOINT)
            .get()
            .header("User-Agent", "PlayBridge-Phone-Updater")
            .build()

        http.newCall(request).execute().use { resp ->
            Log.d(TAG, "resolveLatest: HTTP ${resp.code}, Location=${resp.header("Location")}")

            if (!resp.isRedirect) {
                throw IllegalStateException(
                    "Expected a redirect from download endpoint but got HTTP ${resp.code}"
                )
            }
            val location = resp.header("Location")
                ?: throw IllegalStateException("Redirect (HTTP ${resp.code}) had no Location header")

            val rawVersion = FILENAME_VERSION.find(location)?.groupValues?.getOrNull(1)
            val latest = AppVersion.parse(rawVersion)
                ?: throw IllegalStateException("Couldn't parse version from redirect: $location")
            val source = installSource()
            Log.d(TAG, "resolveLatest: latest=$latest, installed=$installed, source=$source")

            if (latest <= installed) {
                Log.d(TAG, "resolveLatest: no update (latest <= installed)")
                return@withContext null
            }

            // Stamp the first time we saw this version so the Play grace window can start.
            recordFirstSeen(latest)

            UpdateInfo(version = latest, apkUrl = location, source = source)
        }
    }

    /** User accepted an [UpdateState.Available] update. Branch on install source. */
    fun accept(info: UpdateInfo) {
        Log.i(TAG, "accept: ${info.version} via ${info.source}")
        when (info.source) {
            InstallSource.PLAY_STORE -> {
                openPlayStore()
                _state.value = UpdateState.Idle
            }
            InstallSource.SIDELOADED -> downloadAndInstall(info)
        }
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        scope.launch {
            Log.i(TAG, "downloadAndInstall: ${info.apkUrl}")
            _state.value = UpdateState.Downloading(info, fraction = null)
            val apk = runCatching {
                installer.download(info.apkUrl) { fraction ->
                    _state.value = UpdateState.Downloading(info, fraction)
                }
            }.getOrElse { e ->
                Log.w(TAG, "APK download failed", e)
                _state.value = UpdateState.Error(e.message ?: "Download failed.", manual = true)
                return@launch
            }
            // Hand off to the system installer, which shows its own Done/Open screen.
            // We can't observe its outcome, so just clear our UI once it's launched.
            runCatching { installer.launchInstall(apk) }
                .onSuccess {
                    Log.i(TAG, "install: handed off to system installer")
                    _state.value = UpdateState.Idle
                }
                .onFailure { e ->
                    Log.w(TAG, "install: couldn't launch system installer", e)
                    _state.value = UpdateState.Error(
                        e.message ?: "Couldn't open the installer.", manual = true
                    )
                }
        }
    }

    /** True when the app may install packages; otherwise route the user to grant it. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** Open the system "install unknown apps" screen for this package. */
    fun requestInstallPermission() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    /**
     * Dismiss the available-update dialog ("Later").
     *
     * Persists a per-version snooze so automatic cold-start checks do not re-open the
     * dialog on every Activity recreate (common in debug) or app relaunch. Manual
     * "Check for updates" still surfaces the same version.
     */
    fun dismiss() {
        val current = _state.value
        if (current is UpdateState.Available) {
            snooze(current.info.version)
        }
        _state.value = UpdateState.Idle
    }

    private fun openPlayStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_WEB_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(market) }
            .recoverCatching { appContext.startActivity(web) }
    }

    private fun installedVersion(): AppVersion? = runCatching {
        AppVersion.parse(
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        )
    }.getOrNull()

    private fun installSource(): InstallSource {
        // Play flavor: no sideload/install path exists in this binary - always Play.
        if (!this.installer.selfUpdateSupported) return InstallSource.PLAY_STORE
        val pm = appContext.packageManager
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(appContext.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(appContext.packageName)
            }
        }.getOrNull()
        Log.d(TAG, "installSource: installer package = $installer")
        return if (installer == PLAY_STORE_PACKAGE) InstallSource.PLAY_STORE else InstallSource.SIDELOADED
    }

    private fun prefs() =
        appContext.getSharedPreferences("update_checker", Context.MODE_PRIVATE)

    /** Record when a given version was first seen (used to start the Play grace window). */
    private fun recordFirstSeen(version: AppVersion) {
        val key = "first_seen_$version"
        val p = prefs()
        if (!p.contains(key)) {
            p.edit().putLong(key, System.currentTimeMillis()).apply()
            Log.d(TAG, "recordFirstSeen: $version first seen now")
        }
    }

    /**
     * True once [PLAY_GRACE_MILLIS] has elapsed since we first detected [version] — i.e.
     * the Store has had time to roll it out, so nudging a Play user is now reasonable.
     */
    private fun isPlayGraceElapsed(version: AppVersion): Boolean {
        val firstSeen = prefs().getLong("first_seen_$version", System.currentTimeMillis())
        val elapsed = System.currentTimeMillis() - firstSeen
        val done = elapsed >= PLAY_GRACE_MILLIS
        Log.d(TAG, "isPlayGraceElapsed($version): elapsed=${elapsed}ms, grace=${PLAY_GRACE_MILLIS}ms -> $done")
        return done
    }

    /** Remember that the user chose Later for [version] (auto-check only). */
    private fun snooze(version: AppVersion) {
        prefs().edit().putString(KEY_SNOOZED_VERSION, version.toString()).apply()
        Log.i(TAG, "snooze: will not auto-prompt for $version again")
    }

    /**
     * True when [version] was dismissed via Later and is not newer than the snoozed
     * version. A *newer* release always re-prompts.
     */
    private fun isSnoozed(version: AppVersion): Boolean {
        val raw = prefs().getString(KEY_SNOOZED_VERSION, null) ?: return false
        val snoozed = AppVersion.parse(raw) ?: return false
        val matches = version <= snoozed
        Log.d(TAG, "isSnoozed($version): snoozed=$snoozed -> $matches")
        return matches
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val DOWNLOAD_ENDPOINT = "https://playbridge.app/download/android"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
        private const val PLAY_WEB_URL =
            "https://play.google.com/store/apps/details?id=com.playbridge.sender"
        private const val KEY_SNOOZED_VERSION = "snoozed_version"

        /**
         * How long after first detecting a new version we wait before nudging a Play Store
         * user on an automatic check — Play rollout/propagation takes time and the Store
         * auto-updates anyway. Manual checks ignore this. Currently 3 days.
         */
        private const val PLAY_GRACE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** Matches `…playbridge-phone-0.8.0-…` in the resolved asset filename/URL. */
        private val FILENAME_VERSION = Regex("""playbridge-phone-(\d+(?:\.\d+)+)""")
    }
}
