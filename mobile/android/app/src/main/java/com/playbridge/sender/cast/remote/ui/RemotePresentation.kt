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
    engine = if (nativePolicy == null || PlayerSetting.Engine in capabilities.settings) settings.engine.orEmpty() else "",
    qualityMaxHeight = settings.qualityMaxHeight ?: 0,
    currentVideoHeight = settings.currentVideoHeight ?: 0,
    isLive = playback?.isLive == true,
    isSeekable = playback?.seekable != Support.Unsupported,
    speedAvailable = if (nativePolicy != null) PlayerSetting.Speed in capabilities.settings else settings.availability[PlayerSetting.Speed] != Support.Unsupported,
    scalingAvailable = if (nativePolicy != null) PlayerSetting.Scaling in capabilities.settings else settings.availability[PlayerSetting.Scaling] != Support.Unsupported,
    audioBoostAvailable = if (nativePolicy != null) PlayerSetting.AudioBoost in capabilities.settings else settings.availability[PlayerSetting.AudioBoost] != Support.Unsupported,
    qualityAvailable = if (nativePolicy != null) PlayerSetting.Quality in capabilities.settings else settings.availability[PlayerSetting.Quality] == Support.Supported,
)
