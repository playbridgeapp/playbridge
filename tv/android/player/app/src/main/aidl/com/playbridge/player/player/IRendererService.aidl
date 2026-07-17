package com.playbridge.player.player;

import android.os.Bundle;
import android.view.Surface;
import com.playbridge.player.player.IRendererCallback;

oneway interface IRendererService {
    void setCallback(IRendererCallback callback);
    void prepare(in Bundle request, long sessionId);
    void attachSurface(in Surface surface, long sessionId);
    void detachSurface(long sessionId);
    void play(long sessionId);
    void pause(long sessionId);
    void seekTo(long positionMs, long sessionId);
    void setPlaybackSpeed(float speed, long sessionId);
    void setVideoScaling(String mode, long sessionId);
    void setLooping(boolean enabled, long sessionId);
    void setAudioBoost(boolean enabled, long sessionId);
    void setSubtitleDelay(long delayMs, long sessionId);
    void setAudioTrack(String trackId, long sessionId);
    void setSubtitleTrack(String trackId, long sessionId);
    void addExternalSubtitle(String url, String language, long sessionId);
    void stop(long sessionId);
    void release(long sessionId);
}
