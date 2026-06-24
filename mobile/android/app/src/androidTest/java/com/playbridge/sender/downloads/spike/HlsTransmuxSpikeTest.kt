package com.playbridge.sender.downloads.spike

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE-0 SPIKE — run on a device/emulator (needs network + a hardware/codec MediaCodec).
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.playbridge.sender.downloads.spike.HlsTransmuxSpikeTest
 *
 * GREEN  → Media3 transmux is viable for that stream type → ship the Muxer seam, no FFmpeg.
 * SKIP   → stream unreachable (network/CDN). Inconclusive — swap the URL and re-run.
 * RED    → Transformer genuinely can't transmux that input → reconsider FFmpeg for that case.
 *
 * The final summary line prints per-stream PASS/SKIP/FAIL. Read that, not just the JUnit
 * green/red — a SKIP shouldn't be read as success.
 *
 * NOTE: these are public test streams; availability is not guaranteed. The AES-128 entry in
 * particular should be pointed at content you control before treating its result as decisive.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class HlsTransmuxSpikeTest {

    private data class Stream(val label: String, val url: String, val expectAudio: Boolean = true)

    private val streams = listOf(
        // MPEG-TS, H.264/AAC — the most common HLS-in-the-wild case.
        Stream("TS / H.264+AAC", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        // fMP4 / CMAF with separate audio + #EXT-X-MAP init segments.
        Stream(
            "fMP4 / CMAF",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        ),
        // AES-128 encrypted (#EXT-X-KEY). Media3 fetches the key + decrypts internally.
        // Swap for your own encrypted stream before trusting this result.
        Stream("AES-128 encrypted", "https://playertest.longtailvideo.com/adaptive/oceans_aes/oceans_aes.m3u8"),
    )

    @Test
    fun transmux_hls_to_mp4_streamCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val muxer = Media3Muxer(context)
        val outDir = File(context.cacheDir, "transmux_spike").apply { mkdirs() }

        val summary = StringBuilder("\n===== HLS→MP4 transmux spike =====\n")
        var anyHardFailure = false
        var anyConclusivePass = false

        for (stream in streams) {
            val out = File(outDir, "${stream.label.hashCode()}.mp4").apply { delete() }
            Log.i(TAG, "Transmuxing ${stream.label} …")

            when (val result = muxer.transmux(MediaItem.fromUri(stream.url), out)) {
                is TransmuxResult.Success -> {
                    val (hasVideo, hasAudio) = hasVideoAndAudioTracks(out)
                    val audioOk = !stream.expectAudio || hasAudio
                    val ok = hasVideo && audioOk
                    if (ok) anyConclusivePass = true else anyHardFailure = true
                    summary.append(
                        "[%s] %s  (%.1f MB, %dms, video=%b audio=%b)\n".format(
                            if (ok) "PASS" else "FAIL", stream.label,
                            out.length() / 1_048_576.0, result.durationMs, hasVideo, hasAudio,
                        )
                    )
                }
                is TransmuxResult.Skipped -> {
                    summary.append("[SKIP] ${stream.label}  (${result.reason})\n")
                }
                is TransmuxResult.Failed -> {
                    anyHardFailure = true
                    summary.append("[FAIL] ${stream.label}  (${result.reason})\n")
                }
            }
            out.delete()
        }

        summary.append("==================================\n")
        Log.i(TAG, summary.toString())
        println(summary)

        // A genuine Transformer failure fails the build. Network SKIPs do not (inconclusive).
        assertTrue("A stream genuinely failed to transmux — see summary above.$summary", !anyHardFailure)
        // Guard against a vacuous green where every stream was network-SKIPped.
        assertTrue("No stream was reachable — spike is inconclusive, not a pass.$summary", anyConclusivePass)
    }

    private companion object {
        const val TAG = "HlsTransmuxSpike"
    }
}
