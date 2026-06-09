import UIKit
import AVFoundation

/// Generates a still frame for a stream URL using AVFoundation (handles HLS + mp4/mov natively —
/// no TS-segment download like the Android `VideoDetector.fetchHlsThumbnail`). Returns nil for
/// formats AVFoundation can't open (mkv/avi/flv…).
enum Thumbnailer {
    private static let cache = NSCache<NSString, UIImage>()

    static func thumbnail(url: String, headers: [String: String]) async -> UIImage? {
        if let cached = cache.object(forKey: url as NSString) { return cached }
        guard let u = URL(string: url) else { return nil }

        let options = headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers]
        let asset = AVURLAsset(url: u, options: options)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .positiveInfinity
        generator.requestedTimeToleranceAfter = .positiveInfinity
        let time = CMTime(seconds: 5, preferredTimescale: 600)

        return await withCheckedContinuation { continuation in
            generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, cgImage, _, _, _ in
                if let cgImage {
                    let image = UIImage(cgImage: cgImage)
                    cache.setObject(image, forKey: url as NSString)
                    continuation.resume(returning: image)
                } else {
                    continuation.resume(returning: nil)
                }
            }
        }
    }
}
