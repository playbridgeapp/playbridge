package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class WebFilePickerTest {
    private val extensions = mapOf("pdf" to "application/pdf", "png" to "image/png")
    private fun types(vararg accept: String) = pickerMimeTypes(arrayOf(*accept)) { extensions[it] }

    @Test
    fun `no accept list offers every file`() {
        assertEquals(listOf("*/*"), types())
        assertEquals(listOf("*/*"), types(" "))
    }

    @Test
    fun `mime types and wildcards pass through`() {
        assertEquals(listOf("image/*", "video/mp4"), types("image/*", "Video/MP4"))
    }

    @Test
    fun `extensions become mime types without duplicates`() {
        assertEquals(listOf("application/pdf", "image/png"), types(".pdf", ".PNG", "image/png"))
    }

    @Test
    fun `an unknown extension offers every file`() {
        assertEquals(listOf("*/*"), types(".pdf", ".xyz"))
    }
}
