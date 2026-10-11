package com.playbridge.sender.cast.remote

import playbridge.SubtitleResource

enum class RemoteKey(val wireValue: String) {
    Up("dpad_up"), Down("dpad_down"), Left("dpad_left"), Right("dpad_right"), Ok("dpad_center"),
    Back("back"), Home("home"), Enter("key_enter"), Delete("key_delete"), Mute("mute"), Power("power"),
}
enum class BrowserAction(val wireValue: String) { Forward("forward"), Reload("refresh"), CycleSource("video_target_cycle"), Unmute("video_unmute") }
enum class PlayerSetting { Quality, Speed, Scaling, AudioBoost, SubtitleOffset, Engine, Loop }
enum class ScalingMode(val wireValue: String) { Fit("Fit"), Zoom("Zoom"), Fill("Fill") }

sealed interface RemoteAction {
    data class Unavailable(val reason: String) : RemoteAction
    data object TogglePlayPause : RemoteAction
    data class SetPlaying(val playing: Boolean) : RemoteAction
    data class SeekTo(val positionMs: Long) : RemoteAction
    data class SeekBy(val deltaMs: Long) : RemoteAction
    data object StopPlayback : RemoteAction
    data class JumpToEpisode(val index: Int, val itemId: String? = null) : RemoteAction
    data class RemoveQueueItem(val itemId: String) : RemoteAction
    data class PlayNext(val itemId: String) : RemoteAction
    data class SetLoop(val enabled: Boolean) : RemoteAction
    data class SelectAudio(val id: String) : RemoteAction
    data class SelectSubtitle(val id: String) : RemoteAction
    data class SetVideoQuality(val maxHeight: Int?) : RemoteAction
    data class SetSpeed(val speed: Float) : RemoteAction
    data class SetScaling(val mode: ScalingMode) : RemoteAction
    data object ToggleAudioBoost : RemoteAction
    data class AdjustSubtitleOffset(val deltaMs: Long) : RemoteAction
    data class SwitchEngine(val engineId: String) : RemoteAction
    data class AddSubtitle(val resource: SubtitleResource, val legacyUrlOnly: Boolean = false) : RemoteAction
    data class Key(val key: RemoteKey) : RemoteAction
    data class AdjustVolume(val steps: Int) : RemoteAction
    data class SetVolume(val percent: Int) : RemoteAction
    data class ReplaceText(val text: String) : RemoteAction
    data object DeleteText : RemoteAction
    data class PointerMove(val dx: Float, val dy: Float) : RemoteAction
    data object PointerClick : RemoteAction
    data class PointerScroll(val dx: Float, val dy: Float) : RemoteAction
    data object PointerDown : RemoteAction
    data object PointerUp : RemoteAction
    data object PointerGestureEnd : RemoteAction
    data class ImageZoom(val factor: Float) : RemoteAction
    data class ImageRotate(val degrees: Float) : RemoteAction
    data class ImageAnchor(val x: Float, val y: Float) : RemoteAction
    data object ResetImageTransform : RemoteAction
    data class Browser(val action: BrowserAction) : RemoteAction
    data class SetBrowserFullscreen(val fullscreen: Boolean) : RemoteAction
    data object BackToOrigin : RemoteAction
    data class SelectInputMode(val mode: RemoteInputMode) : RemoteAction
    data class OpenSheet(val sheet: RemoteSheet) : RemoteAction
    data object CloseSheet : RemoteAction
    data object OpenConnections : RemoteAction
    data object RetryConnection : RemoteAction
    data object BrowseLibrary : RemoteAction
    data object OpenPhoneBrowser : RemoteAction
    data class OpenTvBrowser(val url: String, val engine: String? = null) : RemoteAction
    data object ManageUserScripts : RemoteAction
    data object ManageUserAgent : RemoteAction
    data object UnlinkWebsite : RemoteAction
    data object RequestWholeTvControl : RemoteAction
    data class SetTimeDisplay(val display: RemoteTimeDisplay) : RemoteAction
    data class MarkGestureLearned(val gesture: String) : RemoteAction
    data object Resync : RemoteAction
}

sealed interface RemoteEffect {
    data class Navigate(val destination: Destination) : RemoteEffect {
        enum class Destination { Origin, Connections, Library, PhoneBrowser }
    }
    data class OpenManager(val manager: Manager) : RemoteEffect {
        enum class Manager { UserScripts, UserAgent }
    }
    data class Notice(val message: String, val subtitle: Boolean = false) : RemoteEffect
    data object UnlinkWebsite : RemoteEffect
    data object RetryConnection : RemoteEffect
}

sealed interface RemoteCommandResult {
    data object Submitted : RemoteCommandResult
    data object Confirmed : RemoteCommandResult
    data class Unsupported(val reason: String) : RemoteCommandResult
    data class Failed(val message: String) : RemoteCommandResult
}
