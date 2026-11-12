package com.playbridge.player.ui.settings

import com.playbridge.player.model.PairedDevice
import com.playbridge.player.ui.theme.AppTheme
import com.playbridge.shared.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Settings categories, in the order the list shows them. [Advanced] is listed last under a
 * "For troubleshooting" label.
 */
enum class SettingsCategory(val label: String) {
    Playback("Playback"),
    Subtitles("Subtitles"),
    Skip("Skip intros and recaps"),
    Phones("Phones"),
    Appearance("Appearance"),
    About("About and updates"),
    Advanced("Advanced"),
    ;

    /** The categories shown above the "For troubleshooting" label. */
    companion object {
        val everyday: List<SettingsCategory> = entries.filter { it != Advanced }
    }
}

/** One choice in a picker. [description] shows under the label in the picker list. */
data class SettingOption(val value: String, val label: String, val description: String? = null)

/** Label of the option whose value is [selected], or [selected] itself when it is not listed. */
fun List<SettingOption>.labelFor(selected: String): String =
    firstOrNull { it.value == selected }?.label ?: selected

/** Plain-language labels and descriptions shared by the settings screens. Pure, so they are unit tested. */
object SettingsText {
    fun stillWatchingThresholdLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes minutes"
        minutes % 60 == 0 -> "${minutes / 60} ${if (minutes == 60) "hour" else "hours"}"
        else -> "${minutes / 60.0} hours"
    }

    fun stillWatchingResponseLabel(seconds: Int): String =
        if (seconds < 60) "$seconds seconds" else "${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"

    fun themeLabel(theme: AppTheme): String = when (theme) {
        AppTheme.DARK -> "Dark"
        AppTheme.AMOLED -> "Black, for OLED"
        AppTheme.LIGHT -> "Light"
    }

    /** "https://api.introdb.app/" becomes "api.introdb.app" so it fits in a row. */
    fun hostOf(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')

    fun addressLabel(preferredIp: String): String =
        if (preferredIp.isBlank() || preferredIp == "auto") "Automatic" else preferredIp

    fun scriptsSummary(allowed: Boolean, installedCount: Int): String {
        val installed = if (installedCount == 0) "none installed" else "$installedCount installed"
        return "${if (allowed) "Allowed" else "Not allowed"} · $installed"
    }

    fun updateStatus(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "Look for a newer version of PlayBridge TV"
        is UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "You have the latest version"
        is UpdateState.Available -> "Version ${state.info.version} is available"
        is UpdateState.Downloading -> "Downloading version ${state.info.version}…"
        is UpdateState.Installing -> "Installing version ${state.info.version}…"
        is UpdateState.Error -> "Couldn't check for updates: ${state.message}"
    }

    fun isUpdateBusy(state: UpdateState): Boolean =
        state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing

    /**
     * "Last connected today", "Last connected 2 November" (with the year when it is not this year).
     * Works on calendar days in [zone].
     */
    fun lastConnected(
        lastConnectedMs: Long,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = zone }
        if (day.format(lastConnectedMs) == day.format(nowMs)) return "Last connected today"
        val year = SimpleDateFormat("yyyy", Locale.ROOT).apply { timeZone = zone }
        val pattern = if (year.format(lastConnectedMs) == year.format(nowMs)) "d MMMM" else "d MMMM yyyy"
        val text = SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(lastConnectedMs)
        return "Last connected $text"
    }

    /**
     * Second line of a paired phone's row. Two phones with the same name get the start of their
     * device id so they can be told apart.
     */
    fun phoneSubtitle(device: PairedDevice, all: List<PairedDevice>, nowMs: Long, zone: TimeZone, locale: Locale): String {
        val base = lastConnected(device.lastConnected, nowMs, zone, locale)
        val clash = all.count { it.name == device.name } > 1 && device.deviceUUID.isNotEmpty()
        return if (clash) "$base · ${device.deviceUUID.take(8)}" else base
    }

    fun connectedPhonesLabel(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 phone connected now"
        else -> "$count phones connected now"
    }
}
