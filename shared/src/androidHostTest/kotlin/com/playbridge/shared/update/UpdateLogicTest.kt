package com.playbridge.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateLogicTest {

    @Test
    fun parseTrimsLeadingVAndDropsNonNumericSuffix() {
        assertEquals(AppVersion(listOf(0, 8, 0)), AppVersion.parse("0.8.0"))
        assertEquals(AppVersion(listOf(0, 7, 2)), AppVersion.parse("v0.7.2"))
        assertEquals(AppVersion(listOf(0, 8, 0)), AppVersion.parse("V0.8.0"))
        assertEquals(AppVersion(listOf(0, 8, 0)), AppVersion.parse("  v0.8.0-rc1  "))
        assertEquals(AppVersion(listOf(0, 8, 0)), AppVersion.parse("0.8.0rc1"))
    }

    @Test
    fun missingComponentsCompareEqualToZero() {
        // compareTo pads missing components with 0; list equality does not.
        assertEquals(0, AppVersion.parse("0.8")!!.compareTo(AppVersion.parse("0.8.0")!!))
        assertEquals(0, AppVersion.parse("1.0")!!.compareTo(AppVersion.parse("1.0.0")!!))
        assertTrue(AppVersion.parse("1.0.1")!! > AppVersion.parse("1.0")!!)
        assertEquals("0.8", AppVersion.parse("0.8").toString())
        assertEquals("0.8.0", AppVersion.parse("0.8.0").toString())
    }

    @Test
    fun comparisonIsNumericNotLexical() {
        assertTrue(AppVersion.parse("0.10.0")!! > AppVersion.parse("0.9.0")!!)
        assertTrue(AppVersion.parse("0.9.0")!! > AppVersion.parse("0.8.9")!!)
        assertTrue(AppVersion.parse("1.0")!! > AppVersion.parse("0.99.9")!!)
        assertTrue(AppVersion.parse("1.2.3.4")!! > AppVersion.parse("1.2.3")!!)
        assertEquals(0, AppVersion.parse("0.8.0")!!.compareTo(AppVersion.parse("0.8.0")!!))
    }

    @Test
    fun rejectsBlankAndNonNumeric() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse("rc1"))
        assertNull(AppVersion.parse("v"))
        assertNull(AppVersion.parse("phone"))
    }

    @Test
    fun stopsAtAnEmptyComponent() {
        assertEquals(AppVersion(listOf(0)), AppVersion.parse("0..1"))
    }

    @Test
    fun phoneConfigSelectsPhoneAssetNotTheReleaseTag() {
        val location = "https://github.com/playbridgeapp/playbridge/releases/download/" +
            "phone-v9.9.9/playbridge-phone-0.8.0-app-universal-release.apk"
        assertEquals(AppVersion.parse("0.8.0"), ReleaseAssets.select(location, UpdateConfigs.phone))
        assertNull(ReleaseAssets.select(location, UpdateConfigs.tvPlayer))
    }

    @Test
    fun tvConfigSelectsTvAssetNotTheReleaseTag() {
        val location = "https://github.com/playbridgeapp/playbridge/releases/download/" +
            "tv-player-v9.9.9/playbridge-tv-player-0.7.2-app-universal-release.apk"
        assertEquals(AppVersion.parse("0.7.2"), ReleaseAssets.select(location, UpdateConfigs.tvPlayer))
        assertNull(ReleaseAssets.select(location, UpdateConfigs.phone))
    }

    @Test
    fun assetSelectionUsesTheFirstFilenameMatch() {
        val location = "https://example.test/playbridge-phone-1.2.3/also/playbridge-phone-4.5.6.apk"
        assertEquals(AppVersion.parse("1.2.3"), ReleaseAssets.select(location, UpdateConfigs.phone))
    }

    @Test
    fun assetPatternRequiresADottedVersion() {
        assertNull(
            ReleaseAssets.select(
                "https://example.test/playbridge-phone-1.apk",
                UpdateConfigs.phone,
            )
        )
        assertNull(
            ReleaseAssets.select(
                "https://example.test/playbridge-tv-0.7.2.apk",
                UpdateConfigs.tvPlayer,
            )
        )
        assertEquals(
            AppVersion.parse("1.2"),
            ReleaseAssets.select("playbridge-phone-1.2-extra", UpdateConfigs.phone),
        )
    }

    @Test
    fun configsKeepEachAppsEndpointUserAgentAndPlayFallback() {
        assertEquals("https://playbridge.app/download/android", UpdateConfigs.phone.downloadEndpoint)
        assertEquals("PlayBridge-Phone-Updater", UpdateConfigs.phone.userAgent)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.playbridge.sender",
            UpdateConfigs.phone.playStoreWebUrl,
        )
        assertEquals("""playbridge-phone-(\d+(?:\.\d+)+)""", UpdateConfigs.phone.assetVersionPattern.pattern)

        assertEquals("https://playbridge.app/download/tv-player", UpdateConfigs.tvPlayer.downloadEndpoint)
        assertEquals("PlayBridge-TV-Updater", UpdateConfigs.tvPlayer.userAgent)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.playbridge.player",
            UpdateConfigs.tvPlayer.playStoreWebUrl,
        )
        assertEquals(
            """playbridge-tv-player-(\d+(?:\.\d+)+)""",
            UpdateConfigs.tvPlayer.assetVersionPattern.pattern,
        )
    }
}
