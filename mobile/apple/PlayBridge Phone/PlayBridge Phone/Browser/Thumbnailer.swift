import UIKit
import AVFoundation

/// Generates a still frame for a stream URL. mp4/mov use `AVAssetImageGenerator`;
/// HLS (m3u8) can't — the generator doesn't support HTTP Live Streaming assets, so
/// those grab a frame from a muted off-screen `AVPlayer` via `AVPlayerItemVideoOutput`
/// (the iOS analogue of the Android `VideoDetector.fetchHlsThumbnail` TS-segment
/// approach). Returns nil for formats AVFoundation can't open (mkv/avi/flv…).
enum Thumbnailer {
    private static let cache = NSCache<NSString, UIImage>()

    static func thumbnail(url: String, headers: [String: String], isHLS: Bool = false) async -> UIImage? {
        if let cached = cache.object(forKey: url as NSString) { return cached }
        guard let u = URL(string: url) else { return nil }

        let hls = isHLS || u.path.lowercased().hasSuffix(".m3u8")
        let image = hls
            ? await playerThumbnail(url: u, headers: headers)
            : await generatorThumbnail(url: u, headers: headers)
        if let image { cache.setObject(image, forKey: url as NSString) }
        return image
    }

    private static func assetOptions(_ headers: [String: String]) -> [String: Any]? {
        headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers]
    }

    // MARK: - File-based assets (mp4/mov)

    private static func generatorThumbnail(url: URL, headers: [String: String]) async -> UIImage? {
        let asset = AVURLAsset(url: url, options: assetOptions(headers))
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .positiveInfinity
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let time = CMTime(seconds: 5, preferredTimescale: 600)

        return await withCheckedContinuation { continuation in
            generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, cgImage, _, _, _ in
                continuation.resume(returning: cgImage.map { UIImage(cgImage: $0) })
            }
        }
    }

    // MARK: - HLS streams

    /// Grabs a frame by briefly running a muted player: image generation doesn't
    /// work on HLS assets, but the render pipeline delivers pixel buffers to an
    /// `AVPlayerItemVideoOutput` as soon as playback starts.
    @MainActor
    private static func playerThumbnail(url: URL, headers: [String: String]) async -> UIImage? {
        let asset = AVURLAsset(url: url, options: assetOptions(headers))
        let item = AVPlayerItem(asset: asset)
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
        ])
        item.add(output)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        player.automaticallyWaitsToMinimizeStalling = false

        defer {
            player.pause()
            player.replaceCurrentItem(with: nil)
        }

        // Wait for the item to become ready (or fail / time out).
        let deadline = Date().addingTimeInterval(8)
        while item.status == .unknown {
            if Date() >= deadline { return nil }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        guard item.status == .readyToPlay else { return nil }

        // Seek a few seconds in for a non-black frame; live/short streams clamp.
        let duration = item.duration.seconds
        let target = (duration.isFinite && duration > 0) ? min(5, duration / 2) : 5
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            player.seek(to: CMTime(seconds: target, preferredTimescale: 600),
                        toleranceBefore: .positiveInfinity,
                        toleranceAfter: .positiveInfinity) { _ in cont.resume() }
        }

        player.play()
        while Date() < deadline {
            let t = item.currentTime()
            if output.hasNewPixelBuffer(forItemTime: t),
               let buffer = output.copyPixelBuffer(forItemTime: t, itemTimeForDisplay: nil) {
                let ci = CIImage(cvPixelBuffer: buffer)
                if let cg = CIContext().createCGImage(ci, from: ci.extent) {
                    return UIImage(cgImage: cg)
                }
                return nil
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return nil
    }
}
