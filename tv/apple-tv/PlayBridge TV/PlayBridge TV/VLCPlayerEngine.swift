import Combine
import Foundation
import PlayBridgeProtocol
import TVVLCKit

class VLCPlayerEngine: NSObject, PlaybackEngine, VLCMediaPlayerDelegate {
    let mediaPlayer: VLCMediaPlayer
    private var timer: DispatchSourceTimer?

    private let stateSubject = CurrentValueSubject<PlaybackState, Never>(.idle)
    private let positionSubject = CurrentValueSubject<TimeInterval, Never>(0)

    var state: AnyPublisher<PlaybackState, Never> { stateSubject.eraseToAnyPublisher() }
    var position: AnyPublisher<TimeInterval, Never> { positionSubject.eraseToAnyPublisher() }

    var duration: TimeInterval {
        let time = mediaPlayer.media?.length.intValue ?? 0
        return time > 0 ? TimeInterval(time) / 1000.0 : 0
    }

    override init() {
        self.mediaPlayer = VLCMediaPlayer()
        super.init()
        mediaPlayer.delegate = self
        setupTimer()
    }

    private func setupTimer() {
        timer = DispatchSource.makeTimerSource(queue: .main)
        timer?.schedule(deadline: .now(), repeating: .milliseconds(500))
        timer?.setEventHandler { [weak self] in
            guard let self = self else { return }
            let pos = TimeInterval(self.mediaPlayer.time.intValue) / 1000.0
            if pos >= 0 {
                self.positionSubject.send(pos)
            }
        }
        timer?.resume()
    }

    func load(_ payload: PlayPayload) async throws {
        guard let url = URL(string: payload.url) else {
            throw NSError(
                domain: "VLCPlayerEngine", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }

        stateSubject.send(.loading)

        let media = VLCMedia(url: url)
        media.addOptions([":avcodec-hw": "any"])

        if let headers = payload.headers {
            for (key, value) in headers {
                media.addOptions([":http-header-fields": "\(key): \(value)"])
            }
        }

        mediaPlayer.media = media
        mediaPlayer.play()
    }

    func play() {
        mediaPlayer.play()
    }

    func pause() {
        mediaPlayer.pause()
    }

    func stop() {
        mediaPlayer.stop()
        stateSubject.send(.stopped)
    }

    func seek(to: TimeInterval) async {
        let vlcTime = VLCTime(int: Int32(to * 1000))
        mediaPlayer.time = vlcTime
    }

    func setRate(_ rate: Float) {
        mediaPlayer.rate = rate
    }

    func setAudioTrack(_ id: String?) {
        // Future Phase
    }

    func setSubtitleTrack(_ id: String?) {
        // Future Phase
    }

    func attachExternalSubtitle(url: URL) async throws {
        mediaPlayer.addPlaybackSlave(url, type: .subtitle, enforce: true)
    }

    // MARK: - VLCMediaPlayerDelegate

    func mediaPlayerStateChanged(_ aNotification: Notification) {
        DispatchQueue.main.async {
            switch self.mediaPlayer.state {
            case .playing: self.stateSubject.send(.playing)
            case .paused: self.stateSubject.send(.paused)
            case .buffering: self.stateSubject.send(.buffering)
            case .error: self.stateSubject.send(.error)
            case .stopped: self.stateSubject.send(.stopped)
            default: break
            }
        }
    }

    deinit {
        timer?.cancel()
    }
}
