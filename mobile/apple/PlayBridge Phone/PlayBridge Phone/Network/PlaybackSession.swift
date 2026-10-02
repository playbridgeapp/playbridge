import AVFoundation
import AVKit
import Combine

struct PlaybackFailure: Equatable {
    let message: String

    static func describeMPV(_ code: Int32, networkIssue: PhonePlaybackNetworkIssue?) -> PlaybackFailure {
        if let networkIssue {
            switch networkIssue {
            case .certificate: return .init(message: "mpv could not verify the stream server’s TLS certificate. The video has not been opened.")
            case .tls: return .init(message: "mpv could not establish a secure connection to the stream server.")
            case .http(let status): return describe(nil, httpStatus: status)
            case .dns: return .init(message: "mpv could not resolve the stream server’s hostname.")
            case .timeout: return .init(message: "The stream server took too long to respond. Try again.")
            case .connection: return .init(message: "mpv could not connect to the stream server. Check your network and try again.")
            }
        }
        switch code {
        case -13: return .init(message: "mpv could not open this stream. The loading error does not identify whether the cause is the network or the server response. Try again or choose another stream.")
        case -14: return .init(message: "mpv could not initialize audio playback.")
        case -15: return .init(message: "mpv could not initialize video rendering.")
        case -17, -18: return .init(message: "mpv could not recognize or play this video format. Choose another stream.")
        default: return .init(message: "mpv could not play this stream. Try again or choose another stream.")
        }
    }

    static func describe(_ error: Error?, httpStatus: Int? = nil, isMatroska: Bool = false) -> PlaybackFailure {
        if httpStatus == 401 || httpStatus == 403 {
            return .init(message: "Access to this video was denied. Its link may have expired. Try again, or reopen the video on the website.")
        }
        if httpStatus == 404 || httpStatus == 410 {
            return .init(message: "The video or part of it is no longer available. Reopen it on the website to get a fresh link.")
        }
        if let httpStatus, (500...599).contains(httpStatus) {
            return .init(message: "The stream server could not provide the video. Please try again in a moment.")
        }
        var current = error as NSError?
        for _ in 0..<5 {
            guard let error = current else { break }
            if error.domain == NSURLErrorDomain {
                switch URLError.Code(rawValue: error.code) {
                case .notConnectedToInternet, .networkConnectionLost:
                    return .init(message: "The network connection was lost or is offline. Check your connection and try again.")
                case .timedOut:
                    return .init(message: "The stream took too long to respond. Check your connection and try again.")
                case .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                    return .init(message: "The stream server could not be reached. Check your network and the selected route.")
                case .noPermissionsToReadFile, .userAuthenticationRequired:
                    return .init(message: "The player couldn’t access this video. Its link may have expired, or access may be restricted.")
                case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
                     .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .appTransportSecurityRequiresSecureConnection:
                    return .init(message: "A secure connection to the stream could not be established. Check the server address and certificate.")
                default: break
                }
            }
            if error.domain == AVFoundationErrorDomain,
               [AVError.Code.fileFormatNotRecognized.rawValue, AVError.Code.decoderNotFound.rawValue,
                AVError.Code.operationNotSupportedForAsset.rawValue].contains(error.code) {
                if isMatroska {
                    return .init(message: "This MKV stream could not be played by Apple’s native player. Choose an MP4 or HLS stream with compatible video and audio.")
                }
                return .init(message: "This video format could not be played by the selected player or AirPlay receiver.")
            }
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return .init(message: "The video could not be played. Try again, or close the player and reopen the video on the website.")
    }
}

/// Owns playback attempts and their registrations. Retry re-prepares the same
/// selected route; stale notifications and dismissed retries cannot replace it.
@MainActor final class PlaybackSession: ObservableObject {
    let player: AVPlayer
    @Published private(set) var engineKind = PhonePlaybackEngineKind.avplayer
    @Published private(set) var mpvState = PhonePlaybackState()
    private(set) var alternativeEngine: PhoneAlternativePlaybackEngine?
    var onPlaybackEnd: (() -> Void)?
    @Published var websiteSubtitleTracks: [(url: String, label: String)] = []
    @Published var websiteSubtitleIndex: Int?
    @Published var websiteCaption = ""
    @Published var websiteSubtitleError: String?
    var onWebsiteSubtitleSelection: ((Int?) -> Void)?
    var onWebsiteClose: (() -> Void)?
    let route: StreamRoute
    @Published private(set) var failure: PlaybackFailure?
    @Published private(set) var retrying = false
    private(set) var registration: PhoneProxyRegistration?
    private var prepare: () async throws -> RoutedStream
    private var observations: [NSKeyValueObservation] = []
    private var notifications: [NSObjectProtocol] = []
    private var retryTask: Task<Void, Never>?
    private var closed = false
    private var generation = 0
    private var sourceURL: URL
    private var contentType: String?
    private var headerCount: Int
    private var media: RoutedStream
    private let alternativeFactory: () -> PhoneAlternativePlaybackEngine?
    private var lastMPVError: Int32?

    var canAirPlay: Bool { engineKind == .avplayer && registration?.url.host != "127.0.0.1" }
    var positionSeconds: Double { engineKind == .mpv ? mpvState.position : player.currentTime().seconds }
    var durationSeconds: Double { engineKind == .mpv ? mpvState.duration : player.currentItem?.duration.seconds ?? 0 }
    var isPlaying: Bool { engineKind == .mpv ? !mpvState.paused : player.rate > 0 }

    init(media: RoutedStream, route: StreamRoute, contentType: String? = nil,
         alternativeFactory: (() -> PhoneAlternativePlaybackEngine?)? = nil, prepare: @escaping () async throws -> RoutedStream) {
        self.route = route
        self.prepare = prepare
        self.media = media
        self.alternativeFactory = alternativeFactory ?? {
#if os(iOS)
            return MPVPhonePlayback()
#else
            return nil
#endif
        }
        sourceURL = media.sourceURL.flatMap(URL.init(string:)) ?? media.url
        self.contentType = contentType
        headerCount = media.sourceHeaders.isEmpty ? media.headers.count : media.sourceHeaders.count
        registration = media.registration
        player = AVPlayer()
        install(media, resume: 0, autoplay: false)
    }

    private func install(_ media: RoutedStream, resume: Double, autoplay: Bool, preferred: PhonePlaybackEngineKind? = nil) {
        self.media = media
        observations.removeAll()
        notifications.forEach(NotificationCenter.default.removeObserver); notifications.removeAll()
        player.pause(); player.replaceCurrentItem(with: nil)
        alternativeEngine?.close(); alternativeEngine = nil
        failure = nil; lastMPVError = nil
        mpvState = PhonePlaybackState(position: resume)
        if (preferred ?? PhonePlaybackEngineKind.preferred(sourceURL: sourceURL, contentType: contentType)) == .mpv,
           let engine = alternativeFactory() {
            engineKind = .mpv; alternativeEngine = engine
            engine.onState = { [weak self, weak engine] state in
                guard let self, !self.closed, self.alternativeEngine === engine else { return }
                self.mpvState = state
            }
            engine.onEnd = { [weak self, weak engine] in
                guard let self, !self.closed, self.alternativeEngine === engine else { return }
                self.onPlaybackEnd?()
            }
            engine.onFailure = { [weak self, weak engine] code in
                guard let self, !self.closed, self.alternativeEngine === engine else { return }
                self.lastMPVError = code
                self.failure = PlaybackFailure.describeMPV(code, networkIssue: engine?.networkIssue)
                self.alternativeEngine?.pause()
            }
            engine.load(url: media.url, headers: media.headers, resume: resume, autoplay: autoplay)
        } else {
            engineKind = .avplayer
            player.replaceCurrentItem(with: PhonePlaybackFallback.directItem(url: media.url, headers: media.headers))
            observeItem()
        }
    }

    func play() {
        guard !closed else { return }
        if engineKind == .mpv { alternativeEngine?.play() } else { player.play() }
    }
    func pause() {
        guard !closed else { return }
        if engineKind == .mpv { alternativeEngine?.pause() } else { player.pause() }
    }
    func seek(to seconds: Double) {
        guard !closed, seconds.isFinite else { return }
        if engineKind == .mpv { alternativeEngine?.seek(to: max(0, seconds)) }
        else { player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 1000)) }
    }

    func tryWithMPV() {
        guard !closed, engineKind == .avplayer, !retrying else { return }
        let position = positionSeconds
        install(media, resume: position.isFinite ? max(0, position) : 0, autoplay: true, preferred: .mpv)
    }

    func replaceWebsiteMedia(_ media: RoutedStream, title: String, resumeMs: Int, contentType: String? = nil, prepare: (() async throws -> RoutedStream)? = nil, autoplay: Bool = true) async {
        guard !closed else { return }
        generation += 1; retryTask?.cancel(); retryTask = nil; retrying = false
        if let prepare { self.prepare = prepare }
        sourceURL = media.sourceURL.flatMap(URL.init(string:)) ?? media.url
        self.contentType = contentType
        headerCount = media.sourceHeaders.isEmpty ? media.headers.count : media.sourceHeaders.count
        registration = media.registration
        install(media, resume: Double(resumeMs) / 1000, autoplay: autoplay)
#if os(iOS) || os(tvOS)
        let metadata = AVMutableMetadataItem()
        metadata.identifier = .commonIdentifierTitle
        metadata.value = title as NSString
        player.currentItem?.externalMetadata = [metadata]
#endif
        guard engineKind == .avplayer else { return }
        if resumeMs > 0 {
            let player = player
            _ = await withTaskCancellationHandler {
                await player.seek(to: CMTime(value: Int64(resumeMs), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
            } onCancel: {
                DispatchQueue.main.async { player.currentItem?.cancelPendingSeeks() }
            }
        }
        guard !closed, !Task.isCancelled else { return }
        if autoplay { play() }
    }

    private func observeItem() {
        observations.removeAll()
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications.removeAll()
        guard let item = player.currentItem else { return }
        observations.append(item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async { [weak self] in self?.failed(item, error: item.error) }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item, queue: .main) { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor [weak self] in self?.failed(item, error: error ?? item.error) }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime,
            object: item, queue: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, !self.closed, self.engineKind == .avplayer, self.player.currentItem === item else { return }
                self.onPlaybackEnd?()
            }
        })
    }

    private func failed(_ item: AVPlayerItem, error: Error?) {
        guard !closed, engineKind == .avplayer, player.currentItem === item else { return }
        let status = item.errorLog()?.events.last?.errorStatusCode
        failure = PlaybackFailure.describe(error, httpStatus: status, isMatroska: isMatroska)
        player.pause()
    }

    private var isMatroska: Bool {
        PhonePlaybackEngineKind.preferred(sourceURL: sourceURL, contentType: contentType) == .mpv
    }

    /// A useful report even before observer callbacks arrive. Omit paths, queries,
    /// header values and error descriptions because they can contain credentials.
    func diagnosticsReport() -> String {
        let item = player.currentItem
        let knownExtensions = ["mkv", "mp4", "m4v", "mov", "m3u8", "mpd", "webm", "ts", "aac", "mp3"]
        let fileExtension = sourceURL.pathExtension.lowercased()
        var lines = ["PlayBridge native playback diagnostics", "Engine: \(engineKind.rawValue)", "Route: \(route.label)",
                     "Source host: \(sourceURL.host ?? "unknown")", "Source format: \(isMatroska ? "Matroska (MKV)" : knownExtensions.contains(fileExtension) ? fileExtension : "unknown")",
                     "Request header count: \(headerCount)"]
        if engineKind == .avplayer {
            lines += ["Player item status: \(item?.status.rawValue ?? -1)", "Playback state: \(player.timeControlStatus.rawValue)",
                      "External playback: \(player.isExternalPlaybackActive)"]
        }
        if let failure { lines.append("Failure: \(failure.message)") }
        if engineKind == .mpv {
            lines += ["MPVKit: 1.0.0", "Position: \(mpvState.position)", "Duration: \(mpvState.duration)",
                      "Paused: \(mpvState.paused)", "Buffering: \(mpvState.buffering)"]
            if let lastMPVError { lines.append("mpv error code: \(lastMPVError)") }
            if lastMPVError != nil { lines.append("mpv stage: \(alternativeEngine?.failureContext ?? "playback")") }
            if let issue = alternativeEngine?.networkIssue { lines.append("Network: " + issue.summary) }
        }
        var error = item?.error as NSError?
        for _ in 0..<5 {
            guard let current = error else { break }
            lines.append("Playback error: \(current.domain) / \(current.code)")
            error = current.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        for event in item?.errorLog()?.events.suffix(5) ?? [] {
            lines.append("Player error log: \(event.errorDomain) / \(event.errorStatusCode)")
        }
        if isMatroska && engineKind == .avplayer { lines.append("Compatibility: direct MKV requires a compatible playback engine or conversion to an Apple-compatible format. Proxy forwarding does not convert media.") }
        return lines.joined(separator: "\n")
    }

    func retry() {
        guard !closed, !retrying else { return }
        retrying = true
        generation += 1
        let attempt = generation
        let position = positionSeconds
        let previousEngine = engineKind
        retryTask = Task { [weak self] in
            guard let self else { return }
            defer { if generation == attempt { retrying = false } }
            do {
                let media = try await prepare()
                try Task.checkCancellation()
                guard !closed, generation == attempt else { return }
                sourceURL = media.sourceURL.flatMap(URL.init(string:)) ?? media.url
                headerCount = media.sourceHeaders.isEmpty ? media.headers.count : media.sourceHeaders.count
                registration = media.registration
                install(media, resume: position.isFinite ? max(0, position) : 0, autoplay: true, preferred: previousEngine)
                player.allowsExternalPlayback = canAirPlay
                if engineKind == .avplayer, position.isFinite && position > 0 {
                    await player.seek(to: CMTime(seconds: position, preferredTimescale: 1000))
                }
                guard !closed, !Task.isCancelled, generation == attempt else { return }
                play()
            } catch {
                guard !closed, !Task.isCancelled, generation == attempt else { return }
                if let error = error as? StreamRoutingError {
                    failure = .init(message: error.localizedDescription)
                } else {
                    failure = PlaybackFailure.describe(error)
                }
            }
        }
    }

    func close() {
        guard !closed else { return }
        onWebsiteClose?(); onWebsiteClose = nil
        onPlaybackEnd = nil
        closed = true
        generation += 1
        retryTask?.cancel()
        retryTask = nil
        observations.removeAll()
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        alternativeEngine?.close(); alternativeEngine = nil
        registration = nil
    }

    deinit {
        retryTask?.cancel()
        notifications.forEach(NotificationCenter.default.removeObserver)
    }
}
