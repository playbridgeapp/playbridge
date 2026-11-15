import CryptoKit
import Foundation

/// Pure-Swift policy and storage helpers for bookmark thumbnails.
enum BookmarkThumbnailPolicy {
    static let blankLumaThreshold: UInt8 = 32
    static let minNonBlankPerThousand = 5
    static let retryDelaySeconds: TimeInterval = 3.0
    static let maxRetryAttempts = 3

    /// Returns true when fewer than 5 per 1,000 pixels have luma > 32.
    static func isBlankFrame(lumaSamples: [UInt8]) -> Bool {
        guard !lumaSamples.isEmpty else { return true }
        var brightCount = 0
        for luma in lumaSamples where luma > blankLumaThreshold {
            brightCount += 1
        }
        return brightCount * 1000 < lumaSamples.count * minNonBlankPerThousand
    }

    /// Evaluates an RGBA8888 / BGRA8888 pixel buffer without allocating per-pixel arrays.
    static func isBlankFrame(
        pixelBytes: UnsafePointer<UInt8>,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        isBGRA: Bool = false
    ) -> Bool {
        guard width > 0, height > 0, bytesPerRow >= width * 4 else { return true }
        let stepX = max(1, width / 64)
        let stepY = max(1, height / 64)
        var total = 0
        var bright = 0
        var y = 0
        while y < height {
            let row = pixelBytes.advanced(by: y * bytesPerRow)
            var x = 0
            while x < width {
                let px = row.advanced(by: x * 4)
                let r = Int(isBGRA ? px[2] : px[0])
                let g = Int(px[1])
                let b = Int(isBGRA ? px[0] : px[2])
                let luma = (299 * r + 587 * g + 114 * b) / 1000
                total += 1
                if luma > Int(blankLumaThreshold) {
                    bright += 1
                }
                x += stepX
            }
            y += stepY
        }
        guard total > 0 else { return true }
        return bright * 1000 < total * minNonBlankPerThousand
    }

    static func thumbnailsDirectory(baseDirectory: URL? = nil) -> URL {
        let base = baseDirectory
            ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("bookmark-thumbnails", isDirectory: true)
    }

    /// Deterministic, URL-safe filename derived from SHA-256 of the media URL.
    static func fileName(for url: URL) -> String {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
        let hex = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        return "\(hex).jpg"
    }

    static func fileURL(for fileName: String, directory: URL? = nil) -> URL {
        thumbnailsDirectory(baseDirectory: directory).appendingPathComponent(fileName, isDirectory: false)
    }

    static func deleteThumbnail(fileName: String?, directory: URL? = nil) {
        guard let fileName, !fileName.isEmpty else { return }
        let url = fileURL(for: fileName, directory: directory)
        try? FileManager.default.removeItem(at: url)
    }
}
