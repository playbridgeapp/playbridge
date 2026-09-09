import AVFoundation

/// A proxy failure must not remove the existing direct on-phone playback path.
/// This guard retries once, only while playing locally; it does not change an
/// active AirPlay route or repeatedly retry a failing origin.
final class PhonePlaybackFallback {
    private weak var player: AVPlayer?
    private var observation: NSKeyValueObservation?
    private let originalURL: URL
    private let headers: [String: String]
    private let onFallback: () -> Void
    private var attempted = false

    init(player: AVPlayer, originalURL: URL, headers: [String: String], onFallback: @escaping () -> Void) {
        self.player = player
        self.originalURL = originalURL
        self.headers = headers
        self.onFallback = onFallback
        observation = player.currentItem?.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            DispatchQueue.main.async { [weak self] in self?.retryDirect() }
        }
    }

    static func directItem(url: URL, headers: [String: String]) -> AVPlayerItem {
        // Preserve the previously working local playback behavior. AirPlay must
        // not rely on these non-public header options reaching the receiver.
        let asset = AVURLAsset(url: url, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        return AVPlayerItem(asset: asset)
    }

    private func retryDirect() {
        guard !attempted, let player, !player.isExternalPlaybackActive else { return }
        attempted = true
        observation = nil
        let position = player.currentTime()
        onFallback()
        player.replaceCurrentItem(with: Self.directItem(url: originalURL, headers: headers))
        if position.isNumeric && position.seconds > 0 { player.seek(to: position) }
        player.play()
    }
}
