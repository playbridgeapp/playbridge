package com.playbridge.player.player

internal object RendererProtocol {
    const val KEY_SESSION_ID = "renderer_session_id"
    const val KEY_RENDERER = "renderer"
    const val KEY_URL = "url"
    const val KEY_TITLE = "title"
    const val KEY_CONTENT_TYPE = "content_type"
    const val KEY_HEADERS = "headers"
    const val KEY_PAYLOAD_JSON = "payload_json"
    const val KEY_EVENT = "event"
    const val KEY_ERROR = "error"
    /** [ERROR_SEVERITY_STARTUP_FAILOVER] or [ERROR_SEVERITY_TERMINAL]. */
    const val KEY_ERROR_SEVERITY = "error_severity"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_HAD_FIRST_FRAME = "had_first_frame"
    const val KEY_STATE = "state"
    const val KEY_POSITION_MS = "position_ms"
    const val KEY_DURATION_MS = "duration_ms"
    const val KEY_VIDEO_WIDTH = "video_width"
    const val KEY_VIDEO_HEIGHT = "video_height"
    const val KEY_VIDEO_FPS = "video_fps"
    const val KEY_VIDEO_TRACKS = "video_tracks"
    const val KEY_AUDIO_TRACKS = "audio_tracks"
    const val KEY_SUBTITLE_TRACKS = "subtitle_tracks"
    const val KEY_TRACK_ID = "track_id"
    const val KEY_TRACK_LABEL = "track_label"
    const val KEY_TRACK_LANGUAGE = "track_language"
    const val KEY_TRACK_SELECTED = "track_selected"
    const val KEY_CUE_GROUP = "cue_group"
    const val KEY_SUBTITLE_URI = "subtitle_uri"
    const val KEY_INITIAL_SUBTITLE_URI = "initial_subtitle_uri"
    const val KEY_INITIAL_SUBTITLE_LABEL = "initial_subtitle_label"
    const val KEY_SUCCESS = "success"
    const val KEY_IS_LIVE = "is_live"
    const val KEY_IS_SEEKABLE = "is_seekable"
    const val KEY_SPEED_AVAILABLE = "speed_available"
    const val KEY_SCALING_AVAILABLE = "scaling_available"
    const val KEY_AUDIO_BOOST_AVAILABLE = "audio_boost_available"
    const val KEY_QUALITY_AVAILABLE = "quality_available"
    const val KEY_CURRENT_VIDEO_HEIGHT = "current_video_height"
    const val KEY_QUALITY_MAX_HEIGHT = "quality_max_height"

    const val EVENT_READY = "ready"
    const val EVENT_FIRST_FRAME = "first_frame"
    const val EVENT_STATE = "state"
    const val EVENT_ENDED = "ended"
    const val EVENT_VIDEO_SIZE = "video_size"
    const val EVENT_VIDEO_RATE = "video_rate"
    const val EVENT_TRACKS = "tracks"
    const val EVENT_CUES = "cues"
    const val EVENT_EXTERNAL_SUBTITLE_RESULT = "external_subtitle_result"
    const val EVENT_CAPABILITIES = "capabilities"
    const val EVENT_STOPPED = "stopped"
    const val EVENT_ERROR = "error"

    /**
     * Hard capability/format failure before first frame — host may switch engines once.
     * Mid-playback recoverable exhaustion must use [ERROR_SEVERITY_TERMINAL] instead.
     */
    const val ERROR_SEVERITY_STARTUP_FAILOVER = "startup_failover"
    /** Give up on this engine/item; do not auto-switch engines after playback has started. */
    const val ERROR_SEVERITY_TERMINAL = "terminal"
}
