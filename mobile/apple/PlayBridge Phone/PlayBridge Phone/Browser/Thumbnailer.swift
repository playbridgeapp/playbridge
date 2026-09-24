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
        // Unlimited tolerance can return the same opening keyframe for every seek.
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let duration = try? await asset.load(.duration)
        let seconds = duration?.seconds ?? 0
        generator.maximumSize = CGSize(width: 640, height: 360)
        return await withTaskCancellationHandler {
            let selection = await ThumbnailFramePolicy.selectFrame(seconds: seconds) { attempt, target in
                let time = CMTime(seconds: target, preferredTimescale: 600)
                let trace = StreamDebugTrace.current
                let frame: CGImage? = await withCheckedContinuation { continuation in
                    generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, image, _, result, error in
                        StreamDebugTrace.$current.withValue(trace) {
                            if let error = error as NSError? {
                                StreamDebugTrace.record("Image generator attempt \(attempt) failed: \(error.domain) code \(error.code)")
                            } else {
                                StreamDebugTrace.record("Image generator attempt \(attempt): \(result.rawValue)")
                            }
                        }
                        continuation.resume(returning: image)
                    }
                    if Task.isCancelled { generator.cancelAllCGImageGeneration() }
                }
                return frame
            }
            StreamDebugTrace.record("Thumbnail selection: \(selection.attempts) attempts; dark fallback: \(selection.usedDarkFallback)")
            return selection.image.map { UIImage(cgImage: $0) }
        } onCancel: {
            generator.cancelAllCGImageGeneration()
        }
    }

    // MARK: - HLS streams

    private static func hlsThumbnail(url: URL, headers: [String: String]) async -> UIImage? {
        guard let sample = await HLSPreviewSample.download(from: url, headers: headers) else { return nil }
        defer { sample.removeFiles() }
        guard !Task.isCancelled else { return nil }
        let isTS = sample.segmentFiles.first?.pathExtension == "ts"
        StreamDebugTrace.record("Decoder: \(isTS ? "VideoToolbox AVC/TS" : "AVAssetImageGenerator")")
        let selected = await ThumbnailFramePolicy.selectCandidates(sample.segmentFiles) { attempt, file in
            let image = isTS
                ? TransportStreamThumbnail.thumbnail(file: file)
                : await generatorThumbnail(url: file, headers: [:])
            StreamDebugTrace.record("HLS segment attempt \(attempt): \(image == nil ? "missing" : "decoded")")
            return image?.cgImage
        }
        guard !Task.isCancelled else { return nil }
        if let image = selected.image {
            StreamDebugTrace.record("HLS selection: \(selected.attempts) segments; dark fallback: \(selected.usedDarkFallback)")
            return UIImage(cgImage: image)
        }
        // Some segments depend on preceding samples for parameter sets or timing.
        guard let combined = sample.combinedFile else { return nil }
        StreamDebugTrace.record("HLS individual segments failed; trying joined sample")
        return isTS
            ? TransportStreamThumbnail.thumbnail(file: combined)
            : await generatorThumbnail(url: combined, headers: [:])
    }
}
