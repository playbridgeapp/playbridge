import AVFoundation
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

/// Owns mpv playback attempts and their registrations. Retry re-prepares the
/// selected route; a missing decoder fails explicitly rather than using AVPlayer.
@MainActor final class PlaybackSession: ObservableObject {
    let engineKind = PhonePlaybackEngineKind.mpv
    @Published private(set) var mpvState = PhonePlaybackState()
    private(set) var alternativeEngine: PhoneAlternativePlaybackEngine?
    var onPlaybackEnd: (() -> Void)?
    @Published var websiteSubtitleTracks: [(url: String, label: String)] = []
    var websiteSubtitleLanguages: [String?] = []
    var onSubtitleTimingChange: (() -> Void)?
    @Published private(set) var preferences: PhonePlayerPreferences
    @Published private(set) var subtitleDelay = 0.0
    private let preferencesStore: UserDefaults
    private var manualAudio = false
    private var manualSubtitle = false
    private var appliedAudio: Int?
    private var appliedSubtitle: String?
    private var skipTarget: Double?
    private var lastSkip = Date.distantPast
    @Published var websiteSubtitleIndex: Int?
    @Published var websiteCaption = ""
    @Published var websiteSubtitleError: String?
    var onWebsiteSubtitleSelection: ((Int?) -> Void)?
    var onWebsiteClose: (() -> Void)?
    @Published var websiteQueueTitles: [String] = []
    @Published var websiteQueueIndex = 0
    @Published var websiteQueueChangingItem = false
    @Published var websiteWaitingForNext = false
    @Published var websiteQueueError: String?
    var onWebsiteJump: ((Int) -> Void)?
    let route: StreamRoute
    let initialOrientation: String?
    @Published private(set) var failure: PlaybackFailure?
    @Published private(set) var retrying = false
    private(set) var registration: PhoneProxyRegistration?
    private var prepare: () async throws -> RoutedStream
    private var retryTask: Task<Void, Never>?
    private var closed = false
    private var generation = 0
    private var sourceURL: URL
    private var contentType: String?
    private var headerCount: Int
    private let alternativeFactory: () -> PhoneAlternativePlaybackEngine?
    private var lastMPVError: Int32?

    var positionSeconds: Double { mpvState.position }
    var durationSeconds: Double { mpvState.duration }
    var isPlaying: Bool { alternativeEngine != nil && !mpvState.paused }

    init(media: RoutedStream, route: StreamRoute, contentType: String? = nil,
         initialOrientation: String? = nil, preferencesStore: UserDefaults = .standard,
         alternativeFactory: (() -> PhoneAlternativePlaybackEngine?)? = nil, prepare: @escaping () async throws -> RoutedStream) {
        self.route = route
        self.initialOrientation = initialOrientation
        self.preferencesStore = preferencesStore
        preferences = PhonePlayerPreferences.load(from: preferencesStore)
        self.prepare = prepare
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
        install(media, resume: 0, autoplay: false)
    }

    private func install(_ media: RoutedStream, resume: Double, autoplay: Bool) {
        let previous = alternativeEngine
        alternativeEngine = nil
        previous?.close()
        failure = nil; lastMPVError = nil
        mpvState = PhonePlaybackState(position: resume)
        manualAudio = false; manualSubtitle = false; appliedAudio = nil; appliedSubtitle = nil; skipTarget = nil
        guard let engine = alternativeFactory() else {
            failure = .init(message: "The mpv playback engine is unavailable. Reopen the player or update PlayBridge.")
            return
        }
        alternativeEngine = engine
        engine.onState = { [weak self, weak engine] state in
            guard let self, let engine, !self.closed, self.alternativeEngine === engine else { return }
            self.mpvState = state
            self.applyPreferredTracks()
        }
        engine.onEnd = { [weak self, weak engine] in
            guard let self, let engine, !self.closed, self.alternativeEngine === engine else { return }
            self.onPlaybackEnd?()
        }
        engine.onFailure = { [weak self, weak engine] code in
            guard let self, let engine, !self.closed, self.alternativeEngine === engine else { return }
            self.lastMPVError = code
            self.failure = PlaybackFailure.describeMPV(code, networkIssue: engine.networkIssue)
            self.alternativeEngine?.pause()
        }
        engine.configure(PhonePlayerOptions(preferences: preferences, subtitleDelay: subtitleDelay))
        engine.load(url: media.url, headers: media.headers, resume: resume, autoplay: autoplay)
    }

    func play() {
        guard !closed else { return }
        alternativeEngine?.play()
    }
    func pause() {
        guard !closed else { return }
        alternativeEngine?.pause()
    }
    func seek(to seconds: Double) {
        guard !closed, seconds.isFinite else { return }
        skipTarget = nil
        alternativeEngine?.seek(to: max(0, durationSeconds > 0 ? min(durationSeconds, seconds) : seconds))
    }

    /// Consecutive taps accumulate even before the decoder reports its seek.
    func skip(by seconds: Double) {
        guard !closed, seconds.isFinite else { return }
        let now = Date()
        let base = now.timeIntervalSince(lastSkip) < 0.8 ? (skipTarget ?? positionSeconds) : positionSeconds
        let target = max(0, durationSeconds > 0 ? min(durationSeconds, base + seconds) : base + seconds)
        seek(to: target); skipTarget = target; lastSkip = now
    }

    func updatePreferences(applyTracks: Bool = true, _ update: (inout PhonePlayerPreferences) -> Void) {
        guard !closed else { return }
        var next = preferences; update(&next); next = next.sanitized()
        if next.audioLanguage != preferences.audioLanguage { manualAudio = false; appliedAudio = nil }
        if next.subtitleLanguage != preferences.subtitleLanguage || next.subtitlesEnabled != preferences.subtitlesEnabled {
            manualSubtitle = false; appliedSubtitle = nil
        }
        preferences = next; preferences.save(to: preferencesStore)
        alternativeEngine?.configure(PhonePlayerOptions(preferences: preferences, subtitleDelay: subtitleDelay))
        if applyTracks { applyPreferredTracks() }
    }
    func setSubtitleDelay(_ seconds: Double) {
        guard !closed, seconds.isFinite else { return }
        subtitleDelay = min(10, max(-10, seconds))
        alternativeEngine?.configure(PhonePlayerOptions(preferences: preferences, subtitleDelay: subtitleDelay))
        onSubtitleTimingChange?()
    }
    func setPreferredAudioLanguage(_ language: String?) {
        manualAudio = false; appliedAudio = nil
        updatePreferences { $0.audioLanguage = language }
    }
    func setPreferredSubtitleLanguage(_ language: String?, enabled: Bool) {
        manualSubtitle = false; appliedSubtitle = nil
        updatePreferences { $0.subtitlesEnabled = enabled; $0.subtitleLanguage = language }
    }
    func selectAudio(_ id: Int) {
        guard !closed else { return }
        if let language = mpvState.audioTracks.first(where: { $0.id == id }).flatMap({ PhonePlayerPreferences.languageCode($0.language) }) {
            updatePreferences(applyTracks: false) { $0.audioLanguage = language }
        }
        manualAudio = true; alternativeEngine?.selectAudio(id)
    }
    func selectEmbeddedSubtitle(_ id: Int?) {
        guard !closed else { return }
        let language = id.flatMap { selected in mpvState.subtitleTracks.first(where: { $0.id == selected })?.language }
        updatePreferences(applyTracks: false) { $0.subtitlesEnabled = id != nil; $0.subtitleLanguage = PhonePlayerPreferences.languageCode(language) }
        manualSubtitle = true
        onWebsiteSubtitleSelection?(nil); alternativeEngine?.selectSubtitle(id)
    }
    func selectWebsiteSubtitle(_ index: Int) {
        guard !closed, websiteSubtitleTracks.indices.contains(index) else { return }
        let language = websiteSubtitleLanguages.indices.contains(index) ? websiteSubtitleLanguages[index] : nil
        updatePreferences(applyTracks: false) { $0.subtitlesEnabled = true; $0.subtitleLanguage = PhonePlayerPreferences.languageCode(language) }
        manualSubtitle = true
        alternativeEngine?.selectSubtitle(nil); onWebsiteSubtitleSelection?(index)
    }
    func applyPreferredTracks() {
        guard !closed, let engine = alternativeEngine else { return }
        if !manualAudio, preferences.audioLanguage == nil, appliedAudio != -1 {
            appliedAudio = -1; engine.selectAudio(nil)
        }
        if !manualAudio, let language = preferences.audioLanguage {
            if let track = PhonePlayerPreferences.preferredTrack(mpvState.audioTracks, language: language) {
                if appliedAudio != track.id { appliedAudio = track.id; engine.selectAudio(track.id) }
            } else if appliedAudio != -1 { appliedAudio = -1; engine.selectAudio(nil) }
        }
        guard !manualSubtitle else { return }
        if !preferences.subtitlesEnabled {
            if appliedSubtitle != "off" {
                appliedSubtitle = "off"; engine.selectSubtitle(nil); onWebsiteSubtitleSelection?(nil)
            }
        } else if let track = PhonePlayerPreferences.preferredTrack(mpvState.subtitleTracks, language: preferences.subtitleLanguage) {
            let key = "embedded:\(track.id)"
            if appliedSubtitle != key { appliedSubtitle = key; onWebsiteSubtitleSelection?(nil); engine.selectSubtitle(track.id) }
        } else if let track = PhonePlayerPreferences.preferredTrack(websiteSubtitleTracks.indices.map { index in
            PhonePlaybackTrack(id: index, label: "", language: websiteSubtitleLanguages.indices.contains(index) ? websiteSubtitleLanguages[index] : nil)
        }, language: preferences.subtitleLanguage), onWebsiteSubtitleSelection != nil {
            let key = "website:\(track.id)"
            if appliedSubtitle != key { appliedSubtitle = key; engine.selectSubtitle(nil); onWebsiteSubtitleSelection?(track.id) }
        } else if appliedSubtitle != "unavailable" {
            appliedSubtitle = "unavailable"; engine.selectSubtitle(nil); onWebsiteSubtitleSelection?(nil)
        }
    }

    func replaceWebsiteMedia(_ media: RoutedStream, title: String, resumeMs: Int, contentType: String? = nil, prepare: (() async throws -> RoutedStream)? = nil, autoplay: Bool = true) async {
        guard !closed, !Task.isCancelled else { return }
        generation += 1; retryTask?.cancel(); retryTask = nil; retrying = false
        subtitleDelay = 0
        if let prepare { self.prepare = prepare }
        sourceURL = media.sourceURL.flatMap(URL.init(string:)) ?? media.url
        self.contentType = contentType
        headerCount = media.sourceHeaders.isEmpty ? media.headers.count : media.sourceHeaders.count
        registration = media.registration
        install(media, resume: Double(resumeMs) / 1000, autoplay: autoplay)
    }

    private var isMatroska: Bool {
        let mime = contentType?.split(separator: ";").first?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return sourceURL.pathExtension.lowercased() == "mkv" ||
            ["video/x-matroska", "video/matroska", "audio/x-matroska"].contains(mime ?? "")
    }

    /// Omit paths, queries, header values and decoder logs from copied diagnostics.
    func diagnosticsReport() -> String {
        let knownExtensions = ["mkv", "mp4", "m4v", "mov", "m3u8", "mpd", "webm", "ts", "aac", "mp3"]
        let fileExtension = sourceURL.pathExtension.lowercased()
        var lines = ["PlayBridge native playback diagnostics", "Engine: \(engineKind.rawValue)", "Route: \(route.label)",
                     "Source host: \(sourceURL.host ?? "unknown")", "Source format: \(isMatroska ? "Matroska (MKV)" : knownExtensions.contains(fileExtension) ? fileExtension : "unknown")",
                     "Request header count: \(headerCount)", "MPVKit: 1.0.0",
                     "Position: \(mpvState.position)", "Duration: \(mpvState.duration)",
                     "Paused: \(mpvState.paused)", "Buffering: \(mpvState.buffering)"]
        if let failure { lines.append("Failure: \(failure.message)") }
        if let lastMPVError {
            lines.append("mpv error code: \(lastMPVError)")
            lines.append("mpv stage: \(alternativeEngine?.failureContext ?? "playback")")
        }
        if let issue = alternativeEngine?.networkIssue { lines.append("Network: " + issue.summary) }
        return lines.joined(separator: "\n")
    }

    func retry() {
        guard !closed, !retrying else { return }
        retrying = true
        generation += 1
        let attempt = generation
        let position = positionSeconds
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
                install(media, resume: position.isFinite ? max(0, position) : 0, autoplay: true)
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
        onWebsiteJump = nil; onPlaybackEnd = nil
        onWebsiteSubtitleSelection = nil; onSubtitleTimingChange = nil
        closed = true
        generation += 1
        retryTask?.cancel(); retryTask = nil
        let engine = alternativeEngine
        alternativeEngine = nil
        engine?.close()
        registration = nil
    }

    deinit { retryTask?.cancel() }
}
