/// Absolute play/pause commands must be idempotent; only explicit toggles depend on state.
enum PlaybackPauseCommand {
    static func targetPaused(for command: String, isPlaying: Bool) -> Bool? {
        switch command {
        case "play": return false
        case "pause": return true
        case "play_pause", "toggle": return isPlaying
        default: return nil
        }
    }
}
