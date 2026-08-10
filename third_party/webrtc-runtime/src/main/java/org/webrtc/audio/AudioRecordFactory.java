/*
 * Copyright 2026 The PlayBridge project authors.
 *
 * Licensed under the same BSD-style terms as the vendored WebRTC runtime.
 */
package org.webrtc.audio;

import android.media.AudioRecord;
import androidx.annotation.Nullable;

/** Creates the AudioRecord consumed by WebRTC's Java audio device module. */
public interface AudioRecordFactory {
  /**
   * Returns an initialized AudioRecord for the requested WebRTC input format, or {@code null} when
   * the requested source is unavailable.
   */
  @Nullable
  AudioRecord createAudioRecord(
      int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes);
}
