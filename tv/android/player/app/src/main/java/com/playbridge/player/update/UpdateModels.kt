package com.playbridge.player.update

/**
 * How the app was installed on this device. Decides which update path we take:
 * a Play Store copy is sent to the store listing; anything else self-updates.
 */
enum class InstallSource {
    /** Installed by Google Play (`com.android.vending`). */
    PLAY_STORE,

    /** Sideloaded APK, adb, or a third-party store — we handle the update ourselves. */
    SIDELOADED,
}

/**
 * A dotted-triple app version (e.g. `0.7.2`) parsed for comparison.
 *
 * Non-numeric suffixes are ignored so `0.7.2` and any future `0.7.2-rc1`-style tag still
 * parse to their numeric core. Missing components default to 0 (`0.7` == `0.7.0`).
 */
data class AppVersion(val parts: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val max = maxOf(parts.size, other.parts.size)
        for (i in 0 until max) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** Parse `"0.7.2"` (or `null`/garbage → null). Trims a leading `v`. */
        fun parse(raw: String?): AppVersion? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val parts = cleaned
                .split('.')
                .map { chunk -> chunk.takeWhile { it.isDigit() } }
                .takeWhile { it.isNotEmpty() }
                .map { it.toInt() }
            return if (parts.isEmpty()) null else AppVersion(parts)
        }
    }
}

/**
 * Details of an available update resolved from the download endpoint.
 *
 * @param version   latest version parsed from the release asset filename
 * @param apkUrl    direct download URL for the sideload path (the redirect target)
 * @param source    how the current app was installed — decides the update action
 */
data class UpdateInfo(
    val version: AppVersion,
    val apkUrl: String,
    val source: InstallSource,
)

/** UI-facing state for the whole check → notify → download → install flow. */
sealed interface UpdateState {
    /** Nothing to show. */
    data object Idle : UpdateState

    /** A check is running. [manual] checks surface this; cold-start checks stay quiet. */
    data class Checking(val manual: Boolean) : UpdateState

    /** Already on the latest version. Only shown for [manual] checks. */
    data object UpToDate : UpdateState

    /** An update exists — show the notify dialog. */
    data class Available(val info: UpdateInfo) : UpdateState

    /** Downloading the APK (sideload path). [fraction] is 0f..1f, or null if size unknown. */
    data class Downloading(val info: UpdateInfo, val fraction: Float?) : UpdateState

    /** APK handed to the system installer; awaiting the user's confirm screen. */
    data class Installing(val info: UpdateInfo) : UpdateState

    /** Something failed. Only surfaced for [manual] checks (or an in-progress download). */
    data class Error(val message: String, val manual: Boolean) : UpdateState
}
