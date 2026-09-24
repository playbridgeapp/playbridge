import CoreGraphics
import Foundation

/// Match Android's bounded 1/5/10-second search, staying inside short clips.
enum ThumbnailFramePolicy {
    struct Selection {
        let image: CGImage?
        let attempts: Int
        let usedDarkFallback: Bool
    }

    static func seekTimes(seconds: Double) -> [Double] {
        let end = seconds.isFinite && seconds > 0 ? max(0, seconds - 0.1) : 10
        var times: [Double] = []
        for candidate in [1.0, 5.0, 10.0] {
            let time = min(candidate, end)
            if !times.contains(time) { times.append(time) }
        }
        return times
    }

    /// Reject only frames that are almost entirely black, not normal dark scenes.
    static func isNearlyBlack(_ image: CGImage) -> Bool {
        let side = 24
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        let rendered = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: side, height: side,
                                          bitsPerComponent: 8, bytesPerRow: side * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue |
                                              CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: side, height: side))
            return true
        }
        guard rendered else { return false }
        var dark = 0
        for offset in stride(from: 0, to: pixels.count, by: 4) {
            let luma = (54 * Int(pixels[offset]) + 183 * Int(pixels[offset + 1]) +
                        19 * Int(pixels[offset + 2])) / 256
            if luma < 18 { dark += 1 }
        }
        return Double(dark) / Double(side * side) >= 0.98
    }

    static func selectFrame(seconds: Double, load: (Int, Double) async -> CGImage?) async -> Selection {
        await selectCandidates(seekTimes(seconds: seconds), load: load)
    }

    static func selectCandidates<Candidate>(_ candidates: [Candidate],
                                            load: (Int, Candidate) async -> CGImage?) async -> Selection {
        var fallback: CGImage?
        var attempts = 0
        for (index, candidate) in candidates.prefix(3).enumerated() {
            guard !Task.isCancelled else { return Selection(image: nil, attempts: attempts, usedDarkFallback: false) }
            attempts += 1
            guard let frame = await load(index + 1, candidate) else { continue }
            guard !Task.isCancelled else { return Selection(image: nil, attempts: attempts, usedDarkFallback: false) }
            if !isNearlyBlack(frame) {
                return Selection(image: frame, attempts: attempts, usedDarkFallback: false)
            }
            if fallback == nil { fallback = frame }
        }
        guard !Task.isCancelled else { return Selection(image: nil, attempts: attempts, usedDarkFallback: false) }
        return Selection(image: fallback, attempts: attempts, usedDarkFallback: fallback != nil)
    }
}
