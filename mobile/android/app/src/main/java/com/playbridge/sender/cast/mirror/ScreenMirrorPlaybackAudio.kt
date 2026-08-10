package com.playbridge.sender.cast.mirror

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.webrtc.audio.AudioRecordFactory

/** Creates WebRTC's audio input from the active MediaProjection, never from the microphone. */
internal class ScreenMirrorPlaybackAudioRecordFactory : AudioRecordFactory {
    @Volatile
    private var projection: MediaProjection? = null

    fun attachProjection(mediaProjection: MediaProjection) {
        projection = mediaProjection
    }

    fun clearProjection() {
        projection = null
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun createAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int,
    ): AudioRecord? {
        val activeProjection = projection ?: run {
            Log.w(TAG, "Playback audio requested before MediaProjection was ready")
            return null
        }
        return runCatching {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(activeProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Playback audio capture initialized sampleRate=$sampleRate")
                record
            } else {
                Log.w(TAG, "Playback audio capture returned an uninitialized AudioRecord")
                record.release()
                null
            }
        }.onFailure {
            Log.w(TAG, "Unable to create playback AudioRecord", it)
        }.getOrNull()
    }

    private companion object {
        private const val TAG = "ScreenMirrorAudio"
    }
}
