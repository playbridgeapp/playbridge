package com.playbridge.sender.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalFileResponseTest {
    @Test fun suffixRangesSelectLastBytesAndClampToFileSize() {
        assertPartial("bytes=-500", 500, 500, "bytes 500-999/1000")
        assertPartial("bytes=-1500", 0, 1000, "bytes 0-999/1000")
    }

    @Test fun openEndedAndClosedRangesClampEnd() {
        assertPartial("bytes=100-", 100, 900, "bytes 100-999/1000")
        assertPartial("bytes=100-199", 100, 100, "bytes 100-199/1000")
        assertPartial("bytes=100-1500", 100, 900, "bytes 100-999/1000")
    }

    @Test fun beyondEndAndReversedRangesAre416() {
        listOf("bytes=1000-", "bytes=1001-2000", "bytes=200-100").forEach(::assertUnsatisfiable)
    }

    @Test fun malformedZeroSuffixAndMultiRangesAre416() {
        listOf("bytes=-0", "bytes=-", "bytes=0-1,2-3", "bytes=0-1junk", "garbage bytes=0-1",
            "Bytes=0-1", "bytes= 0-1", "bytes=0-1-2", "bytes=١-2").forEach(::assertUnsatisfiable)
    }

    @Test fun overflowIsRejectedWithoutThrowingAndValidUnsignedValuesClamp() {
        listOf("bytes=18446744073709551616-", "bytes=-18446744073709551616",
            "bytes=0-18446744073709551616").forEach(::assertUnsatisfiable)
        assertPartial("bytes=-18446744073709551615", 0, 1000, "bytes 0-999/1000")
        assertPartial("bytes=0-18446744073709551615", 0, 1000, "bytes 0-999/1000")
        assertPartial("bytes=+100-+199", 100, 100, "bytes 100-199/1000")
    }

    @Test fun noRangeEmptyFilesAndUnknownProviderLength() {
        assertEquals(LocalFileResponse(200, "OK", 0, 1000), LocalFileResponse.forRequest(null, 1000))
        assertEquals(LocalFileResponse(200, "OK", 0, 0), LocalFileResponse.forRequest(null, 0))
        assertEquals(LocalFileResponse(416, "Range Not Satisfiable", 0, 0, "bytes */0"),
            LocalFileResponse.forRequest("bytes=0-", 0))
        assertEquals(LocalFileResponse(200, "OK", 0, -1), LocalFileResponse.forRequest("bytes=-500", -1))
    }

    private fun assertPartial(range: String, start: Long, length: Long, contentRange: String) {
        assertEquals(LocalFileResponse(206, "Partial Content", start, length, contentRange),
            LocalFileResponse.forRequest(range, 1000))
    }

    private fun assertUnsatisfiable(range: String) {
        assertEquals(LocalFileResponse(416, "Range Not Satisfiable", 0, 0, "bytes */1000"),
            LocalFileResponse.forRequest(range, 1000))
    }
}
