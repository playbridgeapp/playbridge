#if canImport(UIKit)
import UIKit
#endif
import AVFoundation

/// Extracts still frames without starting playback or activating an audio session.
/// HLS uses a bounded local media sample, following Android's segment-based approach.
enum Thumbnailer {
    private static let cache = NSCache<NSString, UIImage>()

    static func thumbnail(url: String, headers: [String: String], isHLS: Bool = false) async -> UIImage? {
        guard !Task.isCancelled else { return nil }
        if let cached = cache.object(forKey: url as NSString) { StreamDebugTrace.record("Thumbnail cache hit"); return cached }
        guard let u = URL(string: url) else { return nil }

        let hls = isHLS || u.path.lowercased().hasSuffix(".m3u8")
        let image = hls
            ? await hlsThumbnail(url: u, headers: headers)
            : await generatorThumbnail(url: u, headers: headers)
        guard !Task.isCancelled else { return nil }
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
        let duration = try? await asset.load(.duration)
        let seconds = duration?.seconds ?? 0
        let target = seconds.isFinite && seconds > 0 ? min(5, seconds / 2) : 0
        let time = CMTime(seconds: target, preferredTimescale: 600)

        generator.maximumSize = CGSize(width: 640, height: 360)
        return await withTaskCancellationHandler {
            guard !Task.isCancelled else { return nil }
            let trace = StreamDebugTrace.current
            return await withCheckedContinuation { continuation in
                generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, cgImage, _, result, error in
                    StreamDebugTrace.$current.withValue(trace) {
                        if let error = error as NSError? {
                            StreamDebugTrace.record("Image generator failure: \(error.domain) code \(error.code)")
                        } else {
                            StreamDebugTrace.record("Image generator result: \(result.rawValue)")
                        }
                    }
                    continuation.resume(returning: cgImage.map { UIImage(cgImage: $0) })
                }
                if Task.isCancelled { generator.cancelAllCGImageGeneration() }
            }
        } onCancel: {
            generator.cancelAllCGImageGeneration()
        }
    }

    // MARK: - HLS streams

    private static func hlsThumbnail(url: URL, headers: [String: String]) async -> UIImage? {
        guard let sample = await HLSPreviewSample.download(from: url, headers: headers) else { return nil }
        defer { try? FileManager.default.removeItem(at: sample) }
        guard !Task.isCancelled else { return nil }
        StreamDebugTrace.record("Decoder: \(sample.pathExtension == "ts" ? "VideoToolbox AVC/TS" : "AVAssetImageGenerator")")
        if sample.pathExtension == "ts" {
            return TransportStreamThumbnail.thumbnail(file: sample)
        }
        return await generatorThumbnail(url: sample, headers: [:])
    }
}
