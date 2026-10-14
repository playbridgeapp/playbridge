package com.playbridge.shared.browser

enum class MaximizeRoute { Hold, TargetFrame, MainFrameFallback }

enum class MaximizeSignal { Video, Site, Fill, Iframe, Held, NoVideo, Restored, NativeEnded, Unknown }

data class FullscreenFlags(val frameFill: Boolean = false, val nativeClaim: Boolean = false)

/** Enter is idempotent. A selected frame gets the command; otherwise the main-frame script runs. */
fun maximizeRoute(hasTargetFrame: Boolean, alreadyOn: Boolean): MaximizeRoute = when {
    alreadyOn -> MaximizeRoute.Hold
    hasTargetFrame -> MaximizeRoute.TargetFrame
    else -> MaximizeRoute.MainFrameFallback
}

/** Phone fullscreen follows native chrome, an in-frame fill, or a script-confirmed DOM fullscreen element. */
fun browserFullscreenReported(nativeChrome: Boolean, frameFill: Boolean, nativeClaim: Boolean = false): Boolean =
    nativeChrome || frameFill || nativeClaim

/** Logs and flag updates only accept these tokens. Anything else is page content and is dropped. */
fun maximizeResultToken(value: String): String = when (value) {
    "ok_site", "ok_video", "ok_fill", "ok_iframe", "ok_held", "no_video",
    "ok", "ok_native", "not_maximized", "pending" -> value
    else -> "unknown"
}

fun maximizeSignal(kind: String, value: String): MaximizeSignal = when (kind) {
    "maximize" -> when (maximizeResultToken(value)) {
        "ok_video" -> MaximizeSignal.Video
        "ok_site" -> MaximizeSignal.Site
        "ok_fill" -> MaximizeSignal.Fill
        "ok_iframe" -> MaximizeSignal.Iframe
        "ok_held" -> MaximizeSignal.Held
        "no_video" -> MaximizeSignal.NoVideo
        else -> MaximizeSignal.Unknown
    }
    "restore" -> when (maximizeResultToken(value)) {
        "ok_native" -> MaximizeSignal.NativeEnded
        else -> MaximizeSignal.Restored
    }
    else -> MaximizeSignal.Unknown
}

fun applyMaximizeSignal(flags: FullscreenFlags, signal: MaximizeSignal): FullscreenFlags = when (signal) {
    MaximizeSignal.Video, MaximizeSignal.Site -> flags.copy(nativeClaim = true)
    MaximizeSignal.Held -> flags
    MaximizeSignal.Fill, MaximizeSignal.Iframe -> FullscreenFlags(frameFill = true)
    MaximizeSignal.NativeEnded -> flags.copy(nativeClaim = false)
    MaximizeSignal.Restored -> FullscreenFlags()
    MaximizeSignal.NoVideo -> FullscreenFlags()
    MaximizeSignal.Unknown -> flags
}
