import Combine
import Foundation
import PlayBridgeProtocol

/// Playback states that are common across all engines.
public enum PlaybackState: String {
    case idle
    case loading
    case playing
    case paused
    case buffering
    case stopped
    case ended
    case error
}

/// Common interface for video playback engines (AVPlayer, VLC, etc.)
protocol PlaybackEngine: AnyObject {
    /// Publisher for current playback state updates
    var state: AnyPublisher<PlaybackState, Never> { get }

    /// Publisher for current position (seconds) updates
    var position: AnyPublisher<TimeInterval, Never> { get }

    /// Total duration of the current media (seconds)
    var duration: TimeInterval { get }

    /// Load a new media payload
    func load(_ payload: PlayPayload) async throws

    /// Start playback
    func play()

    /// Pause playback
    func pause()

    /// Stop playback and release resources
    func stop()

    /// Seek to a specific position (seconds)
    func seek(to: TimeInterval) async

    /// Change playback rate
    func setRate(_ rate: Float)

    /// Return available audio tracks as (id, displayName) pairs.
    /// Returns an empty array if the engine or current media has none.
    func audioTracks() async -> [(id: String, name: String)]

    /// Return available subtitle tracks as (id, displayName) pairs.
    /// Returns an empty array if the engine or current media has none.
    func subtitleTracks() async -> [(id: String, name: String)]

    /// Select an embedded audio track by id (as returned by audioTracks()).
    /// Pass nil to reset to the default track.
    func setAudioTrack(_ id: String?)

    /// Select an embedded subtitle track by id (as returned by subtitleTracks()).
    /// Pass nil to disable embedded subtitles.
    func setSubtitleTrack(_ id: String?)

    /// Attach an external subtitle file (used by VLC via addPlaybackSlave).
    func attachExternalSubtitle(url: URL) async throws

    /// Set color filters
    func setFilter(_ settings: ColorFilterSettings)
}
