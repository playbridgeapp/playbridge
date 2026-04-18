import AVKit
import Combine
import Foundation
import PlayBridgeProtocol

class AVPlayerEngine: NSObject, PlaybackEngine {
    let player: AVPlayer
    private var timeObserver: Any?
    private var cancellables = Set<AnyCancellable>()
    /// Retained reference — AVAssetResourceLoader holds a weak reference to the delegate.
    private var resourceLoaderDelegate: PlayBridgeResourceLoaderDelegate?

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
                userInfo: [NSLocalizedDescriptionKey: "Invalid URL: \(payload.url)"])
        }

        stateSubject.send(.loading)
        resourceLoaderDelegate = nil  // release previous delegate

        let asset: AVURLAsset

        if let headers = payload.headers, !headers.isEmpty {
            // Rewrite URL to custom scheme so AVAssetResourceLoader intercepts every request,
            // including those made after HTTP redirects — fixing Debrid link 401s.
            guard let proxyURL = PlayBridgeScheme.rewrite(url) else {
                throw NSError(
                    domain: "AVPlayerEngine", code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "Cannot rewrite URL scheme: \(url.scheme ?? "nil")"])
            }
            let loader = PlayBridgeResourceLoaderDelegate(headers: headers)
            asset = AVURLAsset(url: proxyURL)
            // Must set delegate before the asset starts loading
            asset.resourceLoader.setDelegate(loader, queue: .global(qos: .userInitiated))
            resourceLoaderDelegate = loader  // retain
        } else {
            asset = AVURLAsset(url: url)
        }

        let item = AVPlayerItem(asset: asset)
        player.replaceCurrentItem(with: item)
        player.play()
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        resourceLoaderDelegate = nil
        stateSubject.send(.stopped)
    }

    func seek(to: TimeInterval) async {
        await player.seek(
            to: CMTime(seconds: to, preferredTimescale: 600),
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
    }

    func setRate(_ rate: Float) { player.rate = rate }

    // MARK: - Track Selection

    func audioTracks() async -> [(id: String, name: String)] {
        guard let item = player.currentItem else { return [] }
        if #available(tvOS 15.0, *) {
            guard let group = try? await item.asset.loadMediaSelectionGroup(for: .audible) else {
                return []
            }
            return group.options.enumerated().map { (i, opt) in (id: "\(i)", name: opt.displayName) }
        } else {
            guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .audible) else {
                return []
            }
            return group.options.enumerated().map { (i, opt) in (id: "\(i)", name: opt.displayName) }
        }
    }

    func subtitleTracks() async -> [(id: String, name: String)] {
        guard let item = player.currentItem else { return [] }
        if #available(tvOS 15.0, *) {
            guard let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) else {
                return []
            }
            return group.options.enumerated().map { (i, opt) in (id: "\(i)", name: opt.displayName) }
        } else {
            guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .legible) else {
                return []
            }
            return group.options.enumerated().map { (i, opt) in (id: "\(i)", name: opt.displayName) }
        }
    }

    func setAudioTrack(_ id: String?) {
        guard let item = player.currentItem else { return }
        if #available(tvOS 15.0, *) {
            Task {
                guard let group = try? await item.asset.loadMediaSelectionGroup(for: .audible) else { return }
                if let id = id, let index = Int(id), index < group.options.count {
                    item.select(group.options[index], in: group)
                }
            }
        } else {
            guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .audible) else { return }
            if let id = id, let index = Int(id), index < group.options.count {
                item.select(group.options[index], in: group)
            }
        }
    }

    func setSubtitleTrack(_ id: String?) {
        guard let item = player.currentItem else { return }
        if #available(tvOS 15.0, *) {
            Task {
                guard let group = try? await item.asset.loadMediaSelectionGroup(for: .legible) else { return }
                if let id = id, let index = Int(id), index < group.options.count {
                    item.select(group.options[index], in: group)
                } else {
                    item.select(nil, in: group)  // disable embedded subtitles
                }
            }
        } else {
            guard let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: .legible) else { return }
            if let id = id, let index = Int(id), index < group.options.count {
                item.select(group.options[index], in: group)
            } else {
                item.select(nil, in: group)  // disable embedded subtitles
            }
        }
    }

    func attachExternalSubtitle(url: URL) async throws {
        // AVPlayer external subs are rendered by SubtitleOverlay using SubtitleManager cues.
        // Nothing needed here — PlayerViewModel loads cues into SubtitleManager separately.
    }

    func setFilter(_ settings: ColorFilterSettings) {
        guard let item = player.currentItem else { return }

        // Identity / no-filter case — just remove any existing composition and return.
        // This avoids unnecessarily invoking AVVideoComposition, which would try to load
        // asset tracks via the standard HTTP stack, bypassing AVAssetResourceLoaderDelegate.
        if settings == .default {
            item.videoComposition = nil
            return
        }

        // AVVideoComposition.videoComposition(with:applyingCIFiltersWithHandler:) loads
        // asset tracks internally using the standard HTTP stack and cannot go through a
        // custom AVAssetResourceLoaderDelegate. For assets with a custom scheme (pb-http /
        // pb-https — used for header-authenticated streams) this always fails with
        // "Cannot Open". Skip silently; the video plays correctly, just without the filter.
        if let urlAsset = item.asset as? AVURLAsset {
            let scheme = urlAsset.url.scheme ?? ""
            if scheme == PlayBridgeScheme.http || scheme == PlayBridgeScheme.https {
                print("[AVPlayerEngine] Skipping CIFilter — custom-scheme asset not supported by AVVideoComposition")
                return
            }
        }

        AVVideoComposition.videoComposition(
            with: item.asset,
            applyingCIFiltersWithHandler: { request in
                let source = request.sourceImage.clampedToExtent()
                let filter = CIFilter(name: "CIColorControls")
                filter?.setValue(source, forKey: kCIInputImageKey)
                filter?.setValue(settings.brightness, forKey: kCIInputBrightnessKey)
                filter?.setValue(settings.contrast, forKey: kCIInputContrastKey)
                filter?.setValue(settings.saturation, forKey: kCIInputSaturationKey)
                if let output = filter?.outputImage {
                    request.finish(
                        with: output.cropped(to: request.sourceImage.extent), context: nil)
                } else {
                    request.finish(with: request.sourceImage, context: nil)
                }
            },
            completionHandler: { composition, error in
                if let composition = composition {
                    DispatchQueue.main.async { item.videoComposition = composition }
                } else if let error = error {
                    print("[AVPlayerEngine] Filter error: \(error)")
                }
            })
    }

    deinit {
        if let timeObserver = timeObserver {
            player.removeTimeObserver(timeObserver)
        }
    }
}
