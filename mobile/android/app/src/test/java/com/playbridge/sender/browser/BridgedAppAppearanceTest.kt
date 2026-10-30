package com.playbridge.sender.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgedAppAppearanceTest {
    private val app = BridgedApp("https://app.example", " Streams ", "https://app.example/", null)

    @Test
    fun `palette ids stops and order match Apple and remain contrast safe`() {
        val expected = listOf(
            Triple("pine", 0x153D2C, 0x23573F), Triple("teal", 0x16433F, 0x235E58),
            Triple("olive", 0x3E4218, 0x565C24), Triple("ochre", 0x513B13, 0x6C501D),
            Triple("brick", 0x5E2428, 0x7C353B), Triple("cranberry", 0x581F36, 0x763047),
            Triple("plum", 0x46284F, 0x603B6B), Triple("indigo", 0x2B335B, 0x404D7B),
            Triple("ocean", 0x193E57, 0x295773), Triple("graphite", 0x343840, 0x4B515B),
        )
        assertEquals(expected, BridgedAppAppearance.palette.map { Triple(it.id, it.dark, it.light) })
        for ((_, dark, light) in expected) {
            assertTrue(BridgedAppAppearance.contrast(dark) >= 4.5)
            assertTrue(BridgedAppAppearance.contrast(light) >= 4.5)
            assertTrue(dark != light)
        }
    }

    @Test
    fun `FNV1a UTF8 origin fixtures match Apple`() {
        for ((origin, index) in listOf(
            "https://app.example" to 9, "https://streams.example" to 7,
            "https://example.com" to 9, "http://localhost:5182" to 8,
            "https://app.example:8443" to 2, "https://xn--bcher-kva.example" to 9,
        )) assertEquals(origin, index, BridgedAppAppearance.paletteIndex(origin))
    }

    @Test
    fun `only RGB hex theme declarations are accepted`() {
        for ((text, rgb) in listOf("#123" to 0x112233, "#aBc" to 0xAABBCC, "#12abEF" to 0x12ABEF, "#000000" to 0)) {
            assertEquals(rgb, BridgedAppAppearance.parseTheme(text))
            val declared = BridgedAppStore.parseManifest(app.origin, JSONObject()
                .put("protocol", "playbridge-app-v1").put("name", "Streams").put("theme_color", text))!!
            assertEquals(text, declared.themeColor)
        }
        for (text in listOf(null, "", "red", "rgb(1,2,3)", "#abcd", "#11223344", "123456", "#ggg", " #fff", "#fff\n")) {
            assertNull(text, BridgedAppAppearance.parseTheme(text))
        }
        val invalid = BridgedAppStore.parseManifest(app.origin, JSONObject()
            .put("protocol", "playbridge-app-v1").put("name", "Streams").put("theme_color", 123))!!
        assertNull(invalid.themeColor)
    }

    @Test
    fun `theme clamp passes white contrast for both stops including light saturated colors`() {
        for (r in 0..255 step 17) for (g in 0..255 step 17) for (b in 0..255 step 17) {
            val (dark, light) = BridgedAppAppearance.themeGradient((r shl 16) or (g shl 8) or b)
            assertTrue(BridgedAppAppearance.contrast(dark) >= 4.5)
            assertTrue(BridgedAppAppearance.contrast(light) >= 4.5)
            assertTrue(dark != light)
        }
        assertEquals(0x0D2738 to 0x12344A, BridgedAppAppearance.themeGradient(0x12344A))
    }

    @Test
    fun `user choice beats site color which beats hashed palette`() {
        val hashed = BridgedAppAppearance.palette[9]
        assertEquals(hashed.dark to hashed.light, BridgedAppAppearance.gradient(app))
        val themed = app.copy(themeColor = "#abc")
        assertEquals(BridgedAppAppearance.themeGradient(0xAABBCC), BridgedAppAppearance.gradient(themed))
        assertEquals(0x16433F to 0x235E58, BridgedAppAppearance.gradient(themed.copy(tileColorId = "teal")))
        assertEquals(BridgedAppAppearance.gradient(themed), BridgedAppAppearance.gradient(themed.copy(tileColorId = "unknown")))
        assertEquals(BridgedAppAppearance.gradient(app), BridgedAppAppearance.gradient(app.copy(themeColor = "red")))
    }

    @Test
    fun `round trip edits and cancelled drafts preserve color and old JSON stays automatic`() {
        val colored = app.copy(tileColorId = "teal", themeColor = "#ABC", tabId = "session")
        assertEquals(listOf(colored), BridgedAppStore.decodeInstalled(BridgedAppStore.encodeInstalled(listOf(colored))))
        val edit = colored.editing("Renamed", "https://app.example/home")!!
        assertEquals("teal", edit.tileColorId)
        assertEquals("#ABC", edit.themeColor)
        val discarded = colored.editing("Unsaved", colored.startUrl, "plum")!!
        assertEquals("plum", discarded.tileColorId)
        assertEquals("teal", colored.tileColorId)
        assertEquals("teal", BridgedAppStore.decodeInstalled(BridgedAppStore.encodeInstalled(listOf(colored))).single().tileColorId)
        assertNull(colored.editing("Automatic", colored.startUrl, null)!!.tileColorId)
        val old = """[{"origin":"https://app.example","name":"Old","startUrl":"https://app.example/"}]"""
        val loaded = BridgedAppStore.decodeInstalled(old).single()
        assertNull(loaded.tileColorId)
        assertNull(loaded.themeColor)
        val unknown = old.replace("\"name\":", "\"tileColorId\":\"future\",\"themeColor\":\"red\",\"name\":")
        assertNull(BridgedAppStore.decodeInstalled(unknown).single().tileColorId)
        assertNull(BridgedAppStore.decodeInstalled(unknown).single().themeColor)
    }

    @Test
    fun `declared icon then same origin favicon then monogram on failures`() {
        val declared = app.copy(iconUrl = "https://app.example/icon.png")
        assertEquals(listOf(declared.iconUrl, "https://app.example/favicon.ico"), BridgedAppAppearance.iconCandidates(declared))
        assertEquals(declared.iconUrl, BridgedAppAppearance.selectIcon(declared) { it })
        assertEquals("https://app.example/favicon.ico", BridgedAppAppearance.selectIcon(declared) { it.takeUnless { it == declared.iconUrl } })
        assertNull(BridgedAppAppearance.selectIcon<String>(declared) { null })
        assertEquals("S", BridgedAppAppearance.monogram(app.name))
        assertEquals("É", BridgedAppAppearance.monogram("éclair"))
        assertEquals("?", BridgedAppAppearance.monogram("  "))
        for (url in listOf("https://other.example/icon.png", "http://app.example/icon.png", "https://user:pass@app.example/icon", "data:image/png;base64,AA", "javascript:void(0)")) {
            assertEquals(listOf("https://app.example/favicon.ico"), BridgedAppAppearance.iconCandidates(app.copy(iconUrl = url)))
        }
        val local = BridgedApp("http://192.168.1.23:5182", "Local", "http://192.168.1.23:5182/", "http://192.168.1.23:5182/touch.png")
        assertEquals(2, BridgedAppAppearance.iconCandidates(local).size)
        assertFalse(BridgedAppAppearance.iconCandidates(declared).any { it.contains("google.com") })
    }
}
