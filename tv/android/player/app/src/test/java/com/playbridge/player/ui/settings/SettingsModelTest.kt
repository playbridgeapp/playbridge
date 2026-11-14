package com.playbridge.player.ui.settings

import com.playbridge.player.model.PairedDevice
import com.playbridge.player.ui.theme.AppTheme
import com.playbridge.shared.update.UpdateState
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun ms(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        java.util.Calendar.getInstance(utc).apply { clear(); set(year, month - 1, day, hour, 0) }.timeInMillis

    @Test
    fun `category list ends with advanced and keeps the everyday order`() {
        assertEquals(SettingsCategory.Advanced, SettingsCategory.entries.last())
        assertEquals(
            listOf("Playback", "Subtitles", "Skip intros and recaps", "Phones", "Appearance", "About and updates"),
            SettingsCategory.everyday.map { it.label },
        )
    }

    @Test
    fun `still watching labels`() {
        assertEquals("30 minutes", SettingsText.stillWatchingThresholdLabel(30))
        assertEquals("1 hour", SettingsText.stillWatchingThresholdLabel(60))
        assertEquals("1.5 hours", SettingsText.stillWatchingThresholdLabel(90))
        assertEquals("2 hours", SettingsText.stillWatchingThresholdLabel(120))
        assertEquals("30 seconds", SettingsText.stillWatchingResponseLabel(30))
        assertEquals("1 minute", SettingsText.stillWatchingResponseLabel(60))
        assertEquals("5 minutes", SettingsText.stillWatchingResponseLabel(300))
    }

    @Test
    fun `theme labels are plain language`() {
        assertEquals("Dark", SettingsText.themeLabel(AppTheme.DARK))
        assertEquals("Black, for OLED", SettingsText.themeLabel(AppTheme.AMOLED))
        assertEquals("Light", SettingsText.themeLabel(AppTheme.LIGHT))
    }

    @Test
    fun `option label falls back to the raw value`() {
        val options = listOf(SettingOption("a", "Alpha"))
        assertEquals("Alpha", options.labelFor("a"))
        assertEquals("zzz", options.labelFor("zzz"))
    }

    @Test
    fun `host and address labels`() {
        assertEquals("api.introdb.app", SettingsText.hostOf("https://api.introdb.app/"))
        assertEquals("10.0.0.2:8000", SettingsText.hostOf(" http://10.0.0.2:8000 "))
        assertEquals("Automatic", SettingsText.addressLabel(""))
        assertEquals("Automatic", SettingsText.addressLabel("auto"))
        assertEquals("192.168.1.5", SettingsText.addressLabel("192.168.1.5"))
    }

    @Test
    fun `scripts summary`() {
        assertEquals("Allowed · 2 installed", SettingsText.scriptsSummary(true, 2))
        assertEquals("Not allowed · none installed", SettingsText.scriptsSummary(false, 0))
    }

    @Test
    fun `update status text and busy state`() {
        assertEquals("Checking…", SettingsText.updateStatus(UpdateState.Checking(manual = true)))
        assertEquals("You have the latest version", SettingsText.updateStatus(UpdateState.UpToDate))
        assertTrue(SettingsText.updateStatus(UpdateState.Error("offline", manual = true)).contains("offline"))
        assertTrue(SettingsText.isUpdateBusy(UpdateState.Checking(manual = false)))
        assertFalse(SettingsText.isUpdateBusy(UpdateState.UpToDate))
        assertFalse(SettingsText.isUpdateBusy(UpdateState.Idle))
    }

    @Test
    fun `last connected uses calendar days and adds the year only when it differs`() {
        val now = ms(2026, 11, 12, 20)
        assertEquals("Last connected today", SettingsText.lastConnected(ms(2026, 11, 12, 1), now, utc, Locale.ENGLISH))
        assertEquals("Last connected 2 November", SettingsText.lastConnected(ms(2026, 11, 2), now, utc, Locale.ENGLISH))
        assertEquals("Last connected 28 October 2025", SettingsText.lastConnected(ms(2025, 10, 28), now, utc, Locale.ENGLISH))
    }

    @Test
    fun `phones with the same name show the start of their id`() {
        val now = ms(2026, 11, 12)
        val a = PairedDevice(id = "1", name = "Pixel", deviceUUID = "abcdef123456", lastConnected = ms(2026, 11, 2))
        val b = PairedDevice(id = "2", name = "Pixel", deviceUUID = "99887766aaaa", lastConnected = ms(2026, 11, 2))
        val c = PairedDevice(id = "3", name = "Galaxy", deviceUUID = "zzz", lastConnected = ms(2026, 11, 2))
        val all = listOf(a, b, c)
        assertEquals("Last connected 2 November · abcdef12", SettingsText.phoneSubtitle(a, all, now, utc, Locale.ENGLISH))
        assertEquals("Last connected 2 November", SettingsText.phoneSubtitle(c, all, now, utc, Locale.ENGLISH))
    }

    @Test
    fun `connected phones label`() {
        assertNull(SettingsText.connectedPhonesLabel(0))
        assertEquals("1 phone connected now", SettingsText.connectedPhonesLabel(1))
        assertEquals("3 phones connected now", SettingsText.connectedPhonesLabel(3))
    }
}
