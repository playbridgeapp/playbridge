import AVFoundation
import Combine

struct PlaybackFailure: Equatable {
    let message: String

    static func describe(_ error: Error?, httpStatus: Int? = nil) -> PlaybackFailure {
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
    let route: StreamRoute
    @Published private(set) var failure: PlaybackFailure?
    @Published private(set) var retrying = false
    private(set) var registration: PhoneProxyRegistration?
    private let prepare: () async throws -> RoutedStream
    private var observations: [NSKeyValueObservation] = []
    private var notifications: [NSObjectProtocol] = []
    private var retryTask: Task<Void, Never>?
    private var closed = false
    private var generation = 0

    var canAirPlay: Bool { registration?.url.host != "127.0.0.1" }

    init(media: RoutedStream, route: StreamRoute, prepare: @escaping () async throws -> RoutedStream) {
        self.route = route
        self.prepare = prepare
        registration = media.registration
        player = AVPlayer(playerItem: PhonePlaybackFallback.directItem(url: media.url, headers: media.headers))
        observeItem()
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
    }

    private func failed(_ item: AVPlayerItem, error: Error?) {
        guard !closed, player.currentItem === item else { return }
        let status = item.errorLog()?.events.last?.errorStatusCode
        failure = PlaybackFailure.describe(error, httpStatus: status)
        player.pause()
    }

    func retry() {
        guard !closed, !retrying else { return }
        retrying = true
        generation += 1
        let attempt = generation
        let position = player.currentTime()
        retryTask = Task { [weak self] in
            guard let self else { return }
            defer { if generation == attempt { retrying = false } }
            do {
                let media = try await prepare()
                try Task.checkCancellation()
                guard !closed, generation == attempt else { return }
                player.replaceCurrentItem(with: PhonePlaybackFallback.directItem(url: media.url, headers: media.headers))
                registration = media.registration
                failure = nil
                observeItem()
                player.allowsExternalPlayback = canAirPlay
                if position.isNumeric && position.seconds > 0 { await player.seek(to: position) }
                guard !closed, !Task.isCancelled, generation == attempt else { return }
                player.play()
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
        closed = true
        generation += 1
        retryTask?.cancel()
        retryTask = nil
        observations.removeAll()
        notifications.forEach(NotificationCenter.default.removeObserver)
        notifications.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        registration = nil
    }

    deinit {
        retryTask?.cancel()
        notifications.forEach(NotificationCenter.default.removeObserver)
    }
}
