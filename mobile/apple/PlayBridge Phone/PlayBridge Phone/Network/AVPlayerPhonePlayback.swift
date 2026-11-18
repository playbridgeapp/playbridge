#if os(iOS)
import UIKit
import AVFoundation

/// Apple's player as the phone's second engine, chosen per session. Streams with request
/// headers, and plain-HTTP streams, play through a loopback proxy registration because
/// AVPlayer can't send headers on every HLS request and ATS blocks plain HTTP. Ticked
/// subtitle files are drawn as an overlay caption; the stream's own subtitles use AVPlayer's
/// renderer with the phone's subtitle style.
@MainActor final class AVPlayerPhonePlayback: PhoneAlternativePlaybackEngine {
    typealias LoopbackRouter = (URL, [String: String], String?) async throws -> RoutedStream

    var onState: ((PhonePlaybackState) -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: ((Int32) -> Void)?
    private(set) var failureContext = "playback"
    private(set) var networkIssue: PhonePlaybackNetworkIssue?
    private(set) var failureMessage: String?

    let player = AVPlayer()
    /// Reads frames for ambient mode.
    private var videoOutput: AVPlayerItemVideoOutput?
    private weak var layer: AVPlayerLayer?
    private let loopbackRouter: LoopbackRouter
    private var options = PhonePlayerOptions()
    private var state = PhonePlaybackState()
    private var lastEmitted: PhonePlaybackState?
    private var ticksSinceEmit = 0
    private var contentType: String?
    private var desiredPaused = true
    private var background = false
    /// In the background playback pauses, unless Keep playing is chosen.
    private var pausedByBackground: Bool { background && !options.keepPlayingInBackground }
    private var closed = false
    private var failed = false
    private var ended = false
    private var generation = 0
    private var speed = 1.0
    private var pendingSeek: Double?
    private var seekTarget: Double?
    private var loadTask: Task<Void, Never>?
    private var tracksTask: Task<Void, Never>?
    private var subtitleTask: Task<Void, Never>?
    /// Keeps the loopback proxy registration alive while the item can still request media.
    private var loopback: PhoneProxyRegistration?
    private var itemObservations: [NSKeyValueObservation] = []
    private var itemObservers: [NSObjectProtocol] = []
    private var appObservers: [NSObjectProtocol] = []
    private var timeObserver: Any?
    private var audioGroup: AVMediaSelectionGroup?
    private var legibleGroup: AVMediaSelectionGroup?
    private var embeddedSubtitles: [PhonePlaybackTrack] = []
    private var overlay = PhoneOverlaySubtitles()
    private var overlaySelection: Int?

    init(loopbackRouter: LoopbackRouter? = nil) {
        self.loopbackRouter = loopbackRouter ?? { url, headers, contentType in
            try await StreamRouteService.localPlayback.prepare(url: url.absoluteString, headers: headers,
                contentType: contentType, route: .phone, configuration: .init())
        }
        // The phone player stays on the phone; AirPlay casting has its own controller.
        player.allowsExternalPlayback = false
        // The session picks tracks from the remembered languages; don't let AVPlayer override them.
        player.appliesMediaSelectionCriteriaAutomatically = false
        player.automaticallyWaitsToMinimizeStalling = true
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        let center = NotificationCenter.default
        appObservers.append(center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.setBackground(true) }
        })
        appObservers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.setBackground(false) }
        })
        appObservers.append(center.addObserver(forName: .playBridgeSubtitleStyleDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.applySubtitleStyle() }
        })
        appObservers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            guard (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
            Task { @MainActor in self?.pause() }
        })
    }

    func attach(_ layer: AVPlayerLayer) {
        guard !closed else { return }
        self.layer = layer
        if !background || !options.keepPlayingInBackground { layer.player = player }
        layer.videoGravity = options.fills ? .resizeAspectFill : .resizeAspect
    }

    func setSourceContentType(_ contentType: String?) { self.contentType = contentType }

    func configure(_ options: PhonePlayerOptions) {
        guard !closed else { return }
        self.options = options
        layer?.videoGravity = options.fills ? .resizeAspectFill : .resizeAspect
        setSpeed(options.preferences.speed)
        refreshCaption(); emit(force: true)
    }

    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        guard !closed else { return }
        generation += 1
        let token = generation
        loadTask?.cancel(); tracksTask?.cancel()
        teardownItem()
        failureContext = "playback"; networkIssue = nil; failureMessage = nil
        failed = false; ended = false; seekTarget = nil
        desiredPaused = !autoplay
        let start = resume.isFinite ? max(0, resume) : 0
        pendingSeek = start > 0 ? start : nil
        state = PhonePlaybackState(position: start, paused: true, buffering: true, speed: speed)
        state.subtitleTracks = overlay.tracks
        emit(force: true)
        let hint = PhoneAVPlayerSupport.contentTypeHint(url: url, contentType: contentType)
        guard PhoneAVPlayerSupport.needsLoopback(url: url, headers: headers) else {
            PhonePlaybackLog.log("avplayer load \(PhonePlaybackLog.describe(url)) resume=\(start) paused=\(desiredPaused)")
            open(url, token: token)
            return
        }
        PhonePlaybackLog.log("avplayer load via loopback \(PhonePlaybackLog.describe(url)) headers=\(PhonePlaybackLog.headerNames(headers)) resume=\(start)")
        failureContext = "loopback"
        loadTask = Task { [weak self, loopbackRouter] in
            do {
                let routed = try await loopbackRouter(url, headers, hint)
                guard let self, !self.closed, self.generation == token, !Task.isCancelled else { return }
                self.loopback = routed.registration
                self.failureContext = "playback"
                self.open(routed.url, token: token)
            } catch {
                guard let self, !self.closed, self.generation == token, !Task.isCancelled else { return }
                PhonePlaybackLog.log("avplayer loopback registration failed: \(type(of: error))")
                self.fail(error: nil, code: -1, context: "loopback", message: PhoneAVPlayerSupport.loopbackUnavailableMessage)
            }
        }
    }

    private func open(_ url: URL, token: Int) {
        let asset = AVURLAsset(url: url)
        let item = AVPlayerItem(asset: asset)
        item.textStyleRules = SubtitleStyleStore.shared.style.avTextStyleRules()
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        item.add(output)
        videoOutput = output
        itemObservations.append(item.observe(\.status, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.statusChanged(token: token) }
        })
        let center = NotificationCenter.default
        itemObservers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.playedToEnd(token: token) }
        })
        itemObservers.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
            Task { @MainActor in
                guard let self, self.generation == token else { return }
                self.fail(error: error, code: Int32(clamping: error?.code ?? -1), context: "playback")
            }
        })
        itemObservers.append(center.addObserver(forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.readErrorLog(token: token) }
        })
        player.replaceCurrentItem(with: item)
    }

    private func statusChanged(token: Int) {
        guard !closed, generation == token, let item = player.currentItem else { return }
        switch item.status {
        case .readyToPlay:
            guard !state.loaded else { return }
            state.loaded = true
            PhonePlaybackLog.log("avplayer ready: duration=\(item.duration.isNumeric ? String(format: "%.1f", item.duration.seconds) : "indefinite")")
            loadTracks(item.asset, token: token)
            if let pendingSeek {
                self.pendingSeek = nil
                seekLoaded(to: pendingSeek)
            }
            if !desiredPaused && !pausedByBackground { player.play() }
            tick()
        case .failed:
            fail(error: item.error, code: Int32(clamping: (item.error as NSError?)?.code ?? -1), context: "item")
        default: break
        }
    }

    private func loadTracks(_ asset: AVAsset, token: Int) {
        tracksTask = Task { [weak self] in
            let audio = try? await asset.loadMediaSelectionGroup(for: .audible)
            let legible = try? await asset.loadMediaSelectionGroup(for: .legible)
            guard let self, !self.closed, self.generation == token, !Task.isCancelled else { return }
            self.audioGroup = audio; self.legibleGroup = legible
            self.state.audioTracks = Self.tracks(audio)
            self.embeddedSubtitles = Self.tracks(legible)
            self.state.subtitleTracks = self.embeddedSubtitles + self.overlay.tracks
            if let legible { self.player.currentItem?.select(nil, in: legible) }
            PhonePlaybackLog.log("avplayer tracks: audio=\(self.state.audioTracks.count) subtitles=\(self.embeddedSubtitles.count) "
                + "overlay=\(self.overlay.tracks.count) languages=\((self.state.audioTracks + self.embeddedSubtitles).map { $0.language ?? "?" })")
            self.updateSelection(); self.emit(force: true)
        }
    }

    private static func tracks(_ group: AVMediaSelectionGroup?) -> [PhonePlaybackTrack] {
        guard let group else { return [] }
        return group.options.enumerated().map { index, option in
            let language = PhoneAVPlayerSupport.languageCode(extendedTag: option.extendedLanguageTag,
                                                             localeCode: option.locale?.language.languageCode?.identifier)
            var label = option.displayName
            if option.hasMediaCharacteristic(.describesMusicAndSoundForAccessibility) { label += " · SDH" }
            if option.hasMediaCharacteristic(.containsOnlyForcedSubtitles) { label += " · Forced" }
            return PhonePlaybackTrack(id: index + 1, label: label.isEmpty ? "Track \(index + 1)" : label, language: language)
        }
    }

    private func updateSelection() {
        guard let item = player.currentItem else { return }
        let selection = item.currentMediaSelection
        if let audioGroup, let option = selection.selectedMediaOption(in: audioGroup),
           let index = audioGroup.options.firstIndex(of: option) {
            state.selectedAudio = index + 1
        } else { state.selectedAudio = nil }
        if let overlaySelection {
            state.selectedSubtitle = overlaySelection
        } else if let legibleGroup, let option = selection.selectedMediaOption(in: legibleGroup),
                  let index = legibleGroup.options.firstIndex(of: option) {
            state.selectedSubtitle = index + 1
        } else { state.selectedSubtitle = nil }
    }

    private func playedToEnd(token: Int) {
        guard !closed, generation == token, !ended else { return }
        ended = true
        PhonePlaybackLog.log("avplayer end of file")
        tick(); state.paused = true; emit(force: true)
        onEnd?()
    }

    private func readErrorLog(token: Int) {
        guard !closed, generation == token, let event = player.currentItem?.errorLog()?.events.last else { return }
        // Only the domain and status code are logged; the comment and URI may carry addresses.
        PhonePlaybackLog.log("avplayer error log: domain=\(event.errorDomain) status=\(event.errorStatusCode)")
        if let issue = PhoneAVPlayerSupport.networkIssue(logStatusCode: event.errorStatusCode, domain: event.errorDomain, comment: event.errorComment),
           networkIssue != .certificate {
            networkIssue = issue
        }
    }

    private func fail(error: Error?, code: Int32, context: String, message: String? = nil) {
        guard !closed, !failed else { return }
        failed = true
        let nsError = error as NSError?
        if let issue = PhoneAVPlayerSupport.networkIssue(from: error), networkIssue != .certificate { networkIssue = issue }
        failureContext = context
        failureMessage = message ?? PhoneAVPlayerSupport.failureMessage(error: error, networkIssue: networkIssue)
        PhonePlaybackLog.log("avplayer failed: stage=\(context) domain=\(nsError?.domain ?? "none") code=\(nsError?.code ?? Int(code)) "
            + "network=\(networkIssue?.summary ?? "none")")
        player.pause()
        state.paused = true; state.buffering = false; state.loaded = false
        emit(force: true)
        onFailure?(code)
    }

    func play() {
        guard !closed else { return }
        desiredPaused = false
        if ended, let item = player.currentItem {
            ended = false
            item.seek(to: .zero, completionHandler: nil)
        }
        if state.loaded && !pausedByBackground { player.play() }
        state.paused = pausedByBackground || !state.loaded
        emit(force: true)
    }

    func pause() {
        guard !closed else { return }
        desiredPaused = true
        player.pause()
        state.paused = true
        if seekTarget == nil { state.buffering = false }
        emit(force: true)
    }

    func seek(to seconds: Double) {
        guard !closed, seconds.isFinite else { return }
        let position = max(0, seconds)
        state.position = position
        if state.loaded { seekLoaded(to: position) } else { pendingSeek = position }
        emit(force: true)
    }

    private func seekLoaded(to position: Double) {
        let token = generation
        seekTarget = position; ended = false
        state.position = position; state.buffering = true
        player.seek(to: CMTime(seconds: position, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.generation == token, self.seekTarget == position else { return }
                self.seekTarget = nil
                self.tick()
            }
        }
    }

    func setSpeed(_ speed: Double) {
        guard !closed, speed.isFinite, speed > 0 else { return }
        self.speed = speed
        player.defaultRate = Float(speed)
        if player.rate != 0 { player.rate = Float(speed) }
        state.speed = speed
    }

    func selectAudio(_ id: Int?) {
        guard !closed, let item = player.currentItem, let audioGroup else { return }
        if let id, audioGroup.options.indices.contains(id - 1) {
            item.select(audioGroup.options[id - 1], in: audioGroup)
        } else {
            item.select(audioGroup.defaultOption ?? audioGroup.options.first, in: audioGroup)
        }
        updateSelection(); emit(force: true)
    }

    func selectSubtitle(_ id: Int?) {
        guard !closed else { return }
        let item = player.currentItem
        if let id, PhoneOverlaySubtitles.isOverlay(id) {
            overlaySelection = overlay.tracks.contains { $0.id == id } ? id : nil
            if let legibleGroup { item?.select(nil, in: legibleGroup) }
        } else {
            overlaySelection = nil
            if let legibleGroup {
                if let id, legibleGroup.options.indices.contains(id - 1) {
                    item?.select(legibleGroup.options[id - 1], in: legibleGroup)
                } else {
                    item?.select(nil, in: legibleGroup)
                }
            }
        }
        updateSelection(); refreshCaption(); emit(force: true)
    }

    func addExternalSubtitles(_ subtitles: [PhoneExternalSubtitle]) {
        guard !closed else { return }
        subtitleTask?.cancel()
        subtitleTask = Task { [weak self] in
            // Downloaded files are at most 8 MB and already UTF-8; read them off the main actor.
            let contents = await Task.detached(priority: .userInitiated) {
                subtitles.map { (try? String(contentsOf: $0.url, encoding: .utf8)) ?? "" }
            }.value
            guard let self, !self.closed, !Task.isCancelled else { return }
            var overlay = PhoneOverlaySubtitles()
            for (subtitle, text) in zip(subtitles, contents) where !overlay.add(subtitle, contents: text) {
                PhonePlaybackLog.log("avplayer: ticked subtitle .\(subtitle.url.pathExtension) has no SRT/WebVTT cues; left out")
            }
            self.overlay = overlay
            self.state.subtitleTracks = self.embeddedSubtitles + overlay.tracks
            PhonePlaybackLog.log("avplayer: \(overlay.tracks.count) ticked subtitle(s) drawn as overlay")
            self.emit(force: true)
        }
    }

    private func setBackground(_ value: Bool) {
        guard !closed, background != value else { return }
        background = value
        if value && options.keepPlayingInBackground {
            // Keep playing: a player still attached to a layer pauses in the background,
            // so detach it; the sound carries on.
            layer?.player = nil
        } else if value {
            // Like mpv: pause while in the background and keep the user's pause choice.
            player.pause()
            state.paused = true
        } else {
            if layer?.player !== player { layer?.player = player }
            if !desiredPaused, state.loaded, !failed { player.play() }
        }
        emit(force: true)
    }

    private func applySubtitleStyle() {
        guard !closed else { return }
        player.currentItem?.textStyleRules = SubtitleStyleStore.shared.style.avTextStyleRules()
    }

    private func tick() {
        guard !closed, let item = player.currentItem, state.loaded else { return }
        let current = player.currentTime()
        if let seekTarget { state.position = seekTarget }
        else if current.isNumeric { state.position = max(0, current.seconds) }
        if item.duration.isNumeric, item.duration.seconds.isFinite {
            state.duration = max(0, item.duration.seconds)
        } else if let range = item.seekableTimeRanges.last?.timeRangeValue, range.end.isNumeric {
            // Live and event playlists: the end of the seekable window.
            state.duration = max(0, range.end.seconds)
        }
        state.paused = player.timeControlStatus == .paused
        state.buffering = seekTarget != nil || player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        state.speed = speed
        // Keep the last size through seeks; a new item starts from a fresh state.
        let size = item.presentationSize
        if size.width.isFinite, size.height.isFinite, size.width >= 1, size.height >= 1 {
            state.videoWidth = Int(size.width); state.videoHeight = Int(size.height)
        }
        refreshCaption()
        emit(force: false)
    }

    private func refreshCaption() {
        state.overlayCaption = overlay.caption(id: overlaySelection, position: state.position, delay: options.subtitleDelay)
    }

    /// Ticks run ten times a second for the caption; the rest of the state is sent when it
    /// changes or every 0.3 s for the position.
    private func emit(force: Bool) {
        ticksSinceEmit += 1
        var comparable = state; comparable.position = lastEmitted?.position ?? 0
        guard force || ticksSinceEmit >= 3 || comparable != lastEmitted else { return }
        ticksSinceEmit = 0; lastEmitted = state
        onState?(state)
    }

    private func teardownItem() {
        itemObservations.forEach { $0.invalidate() }; itemObservations.removeAll()
        itemObservers.forEach(NotificationCenter.default.removeObserver); itemObservers.removeAll()
        player.replaceCurrentItem(with: nil)
        videoOutput = nil
        audioGroup = nil; legibleGroup = nil; embeddedSubtitles = []; overlaySelection = nil
        // Release the old registration only after the item that used it is gone.
        loopback = nil
    }

    func ambientFrame(_ completion: @escaping @MainActor (CGImage?) -> Void) {
        guard !closed, !background, let output = videoOutput,
              let buffer = output.copyPixelBuffer(forItemTime: output.itemTime(forHostTime: CACurrentMediaTime()), itemTimeForDisplay: nil)
        else { completion(nil); return }
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { completion(nil); return }
        completion(PhoneAmbientFrame.downscale(bgrx: base, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer),
                                               stride: CVPixelBufferGetBytesPerRow(buffer)))
    }

    func close() {
        guard !closed else { return }
        closed = true
        loadTask?.cancel(); tracksTask?.cancel(); subtitleTask?.cancel()
        appObservers.forEach(NotificationCenter.default.removeObserver); appObservers.removeAll()
        if let timeObserver { player.removeTimeObserver(timeObserver); self.timeObserver = nil }
        onState = nil; onEnd = nil; onFailure = nil
        player.pause()
        teardownItem()
        layer?.player = nil
    }

    deinit {
        appObservers.forEach(NotificationCenter.default.removeObserver)
        itemObservers.forEach(NotificationCenter.default.removeObserver)
        itemObservations.forEach { $0.invalidate() }
        loadTask?.cancel(); tracksTask?.cancel(); subtitleTask?.cancel()
    }
}
#endif
