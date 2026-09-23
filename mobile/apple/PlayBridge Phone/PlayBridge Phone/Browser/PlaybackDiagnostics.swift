#if DEBUG
import AVFoundation

/// Bounded, in-memory playback evidence. Never retain error descriptions, which
/// may embed credentials or complete signed URLs from upstream responses.
final class PlaybackDiagnostics {
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [NSKeyValueObservation] = []
    private var itemNotifications: [NSObjectProtocol] = []
    private var notifications: [NSObjectProtocol] = []
    private var entries: [String] = []
    private let update: (String) -> Void

    init(player: AVPlayer, update: @escaping (String) -> Void) {
        self.update = update
        observations.append(player.observe(\.isExternalPlaybackActive, options: [.initial, .new]) { [weak self] player, _ in
            self?.record("External playback active: \(player.isExternalPlaybackActive)")
        })
        observations.append(player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            self?.record("Playback state: \(player.timeControlStatus.rawValue); waiting: \(player.reasonForWaitingToPlay?.rawValue ?? "none")")
        })
        observations.append(player.observe(\.currentItem, options: [.initial, .new]) { [weak self] player, _ in
            let item = player.currentItem
            DispatchQueue.main.async { [weak self] in self?.observeItem(item) }
        })
        notifications.append(NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.recordRoute()
        })
        recordRoute()
    }

    private func observeItem(_ item: AVPlayerItem?) {
        itemObservations.removeAll()
        itemNotifications.forEach(NotificationCenter.default.removeObserver)
        itemNotifications.removeAll()
        guard let item else { return }
        if let asset = item.asset as? AVURLAsset {
            record("Player resource: \(StreamDebugTrace.safeURL(asset.url.absoluteString))")
        }
        itemObservations.append(item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            self?.record("Player item status: \(item.status.rawValue)")
            if let error = item.error { self?.recordError(error) }
        })
        itemNotifications.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemNewErrorLogEntry, object: item, queue: .main) { [weak self] _ in
            guard let event = item.errorLog()?.events.last else { return }
            self?.record("Player error: \(event.errorDomain) / \(event.errorStatusCode); resource: \(event.uri.map(StreamDebugTrace.safeURL) ?? "unknown")")
        })
        itemNotifications.append(NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] notification in
            if let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error {
                self?.recordError(error)
            }
        })
    }

    private func recordRoute() {
        let ports = AVAudioSession.sharedInstance().currentRoute.outputs.map { $0.portType.rawValue }
        record("Audio route: \(ports.joined(separator: ", "))")
    }

    private func recordError(_ error: Error) {
        var current: NSError? = error as NSError
        for _ in 0..<4 {
            guard let error = current else { break }
            record("Playback error: \(error.domain) / \(error.code)")
            current = error.userInfo[NSUnderlyingErrorKey] as? NSError
        }
    }

    private func record(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            entries.append(message)
            if entries.count > 40 { entries.removeFirst(entries.count - 40) }
            update(entries.joined(separator: "\n"))
        }
    }

    deinit {
        notifications.forEach(NotificationCenter.default.removeObserver)
        itemNotifications.forEach(NotificationCenter.default.removeObserver)
    }
}
#endif
