import AVKit
import Combine
import Foundation
import PlayBridgeProtocol

class AVPlayerEngine: NSObject, PlaybackEngine {
    let player: AVPlayer
    private var timeObserver: Any?
    private var cancellables = Set<AnyCancellable>()

    private let stateSubject = CurrentValueSubject<PlaybackState, Never>(.idle)
    private let positionSubject = CurrentValueSubject<TimeInterval, Never>(0)

    var state: AnyPublisher<PlaybackState, Never> { stateSubject.eraseToAnyPublisher() }
    var position: AnyPublisher<TimeInterval, Never> { positionSubject.eraseToAnyPublisher() }

    var duration: TimeInterval {
        guard let currentItem = player.currentItem else { return 0 }
        let duration = CMTimeGetSeconds(currentItem.duration)
        return duration.isNaN ? 0 : duration
    }

    override init() {
        self.player = AVPlayer()
        super.init()
        setupObservers()
    }

    private func setupObservers() {
        // Observe status of current item
        player.publisher(for: \.currentItem?.status)
            .sink { [weak self] status in
                guard let self = self, let status = status else { return }
                switch status {
                case .readyToPlay:
                    if self.stateSubject.value == .loading {
                        self.stateSubject.send(.playing)
                    }
                case .failed:
                    self.stateSubject.send(.error)
                default:
                    break
                }
            }
            .store(in: &cancellables)

        // Observe timeControlStatus
        player.publisher(for: \.timeControlStatus)
            .sink { [weak self] status in
                guard let self = self else { return }
                switch status {
                case .playing:
                    self.stateSubject.send(.playing)
                case .paused:
                    if self.stateSubject.value != .stopped {
                        self.stateSubject.send(.paused)
                    }
                case .waitingToPlayAtSpecifiedRate:
                    self.stateSubject.send(.buffering)
                @unknown default:
                    break
                }
            }
            .store(in: &cancellables)

        // Periodic time observer
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
        ) { [weak self] time in
            self?.positionSubject.send(CMTimeGetSeconds(time))
        }
    }

    func load(_ payload: PlayPayload) async throws {
        guard let url = URL(string: payload.url) else {
            throw NSError(
                domain: "AVPlayerEngine", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
        }

        stateSubject.send(.loading)

        // TODO: Switch to AVAssetResourceLoaderDelegate for custom headers in Phase 2/3
        let options: [String: Any] =
            payload.headers.map { ["AVURLAssetHTTPHeaderFieldsKey": $0] } ?? [:]
        let asset = AVURLAsset(url: url, options: options)
        let item = AVPlayerItem(asset: asset)

        player.replaceCurrentItem(with: item)
        player.play()
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        stateSubject.send(.stopped)
    }

    func seek(to: TimeInterval) async {
        await player.seek(to: CMTime(seconds: to, preferredTimescale: 600))
    }

    func setRate(_ rate: Float) {
        player.rate = rate
    }

    func setAudioTrack(_ id: String?) {
        // Stubs for future track selection phase
    }

    func setSubtitleTrack(_ id: String?) {
        // Stubs for future track selection phase
    }

    func attachExternalSubtitle(url: URL) async throws {
        // AVPlayer needs a bit more work for external side-loaded subs
    }

    deinit {
        if let timeObserver = timeObserver {
            player.removeTimeObserver(timeObserver)
        }
    }
}
