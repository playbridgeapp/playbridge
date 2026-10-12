package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.remote.PlayerSetting
import com.playbridge.sender.cast.remote.RemoteTrack
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.Support

internal fun RemoteTrack.screenTrack() = MediaTrack(id, name, selected, type)

/** Presentation defaults do not claim that nullable settings were reported by the receiver. */
internal fun RemoteUiState.screenSettings(): TvPlayerSettings = TvPlayerSettings(
    speed = settings.speed ?: 1f,
    scaling = settings.scaling?.wireValue ?: "Fit",
    audioBoost = settings.audioBoost ?: false,
    subtitleOffsetMs = settings.subtitleOffsetMs ?: 0L,
    engine = settings.engine.orEmpty(),
    qualityMaxHeight = settings.qualityMaxHeight ?: 0,
    currentVideoHeight = settings.currentVideoHeight ?: 0,
    isLive = playback?.isLive == true,
    isSeekable = playback?.seekable != Support.Unsupported,
    speedAvailable = settings.availability[PlayerSetting.Speed] != Support.Unsupported,
    scalingAvailable = settings.availability[PlayerSetting.Scaling] != Support.Unsupported,
    audioBoostAvailable = settings.availability[PlayerSetting.AudioBoost] != Support.Unsupported,
    qualityAvailable = settings.availability[PlayerSetting.Quality] == Support.Supported,
)
