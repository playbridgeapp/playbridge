import AVFoundation
import CoreGraphics
import Foundation
import UIKit

/// Captures and stores non-blank JPEG thumbnails for bookmarked casts.
enum BookmarkThumbnailCapture {
    static func saveArtworkIfAvailable(
        artworkURL: URL?,
        for mediaURL: URL,
        completion: @escaping (String?) -> Void
    ) {
        guard let artworkURL else {
            completion(nil)
            return
        }
        URLSession.shared.dataTask(with: artworkURL) { data, response, _ in
            guard let data,
                  let http = response as? HTTPURLResponse,
                  (200..<300).contains(http.statusCode),
                  let image = UIImage(data: data),
                  let jpeg = downscaledJPEG(from: image) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let fileName = writeJPEG(jpeg, for: mediaURL)
            DispatchQueue.main.async { completion(fileName) }
        }.resume()
    }

    static func saveImage(_ image: UIImage, for mediaURL: URL) -> String? {
        guard let cgImage = image.cgImage, !isBlankCGImage(cgImage) else { return nil }
        guard let jpeg = downscaledJPEG(from: image) else { return nil }
        return writeJPEG(jpeg, for: mediaURL)
    }

    static func savePixelBuffer(_ pixelBuffer: CVPixelBuffer, for mediaURL: URL) -> String? {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let format = CVPixelBufferGetPixelFormatType(pixelBuffer)
        let isBGRA = (format == kCVPixelFormatType_32BGRA)
        let ptr = baseAddress.assumingMemoryBound(to: UInt8.self)
        if BookmarkThumbnailPolicy.isBlankFrame(
            pixelBytes: ptr,
            width: width,
            height: height,
            bytesPerRow: bytesPerRow,
            isBGRA: isBGRA
        ) {
            return nil
        }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: CGRect(x: 0, y: 0, width: width, height: height)) else {
            return nil
        }
        let uiImage = UIImage(cgImage: cgImage)
        guard let jpeg = downscaledJPEG(from: uiImage) else { return nil }
        return writeJPEG(jpeg, for: mediaURL)
    }

    static func isBlankCGImage(_ cgImage: CGImage) -> Bool {
        let width = min(cgImage.width, 160)
        let height = min(cgImage.height, 90)
        guard width > 0, height > 0 else { return true }
        let bytesPerRow = width * 4
        var buffer = [UInt8](repeating: 0, count: height * bytesPerRow)
        guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(
                  data: &buffer,
                  width: width,
                  height: height,
                  bitsPerComponent: 8,
                  bytesPerRow: bytesPerRow,
                  space: colorSpace,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              ) else {
            return false
        }
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        return buffer.withUnsafeBufferPointer { ptr in
            guard let base = ptr.baseAddress else { return true }
            return BookmarkThumbnailPolicy.isBlankFrame(
                pixelBytes: base,
                width: width,
                height: height,
                bytesPerRow: bytesPerRow,
                isBGRA: false
            )
        }
    }

    static func captureAVAssetFrame(
        asset: AVAsset,
        at time: CMTime,
        for mediaURL: URL,
        completion: @escaping (String?) -> Void
    ) {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 640, height: 360)
        generator.requestedTimeToleranceBefore = CMTime(seconds: 1, preferredTimescale: 600)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 1, preferredTimescale: 600)
        generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, cgImage, _, _, _ in
            guard let cgImage, !isBlankCGImage(cgImage) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let image = UIImage(cgImage: cgImage)
            guard let jpeg = downscaledJPEG(from: image) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let fileName = writeJPEG(jpeg, for: mediaURL)
            DispatchQueue.main.async { completion(fileName) }
        }
    }

    private static func downscaledJPEG(from image: UIImage) -> Data? {
        let maxWidth: CGFloat = 640
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let scale = min(1.0, maxWidth / size.width)
        let target = CGSize(width: round(size.width * scale), height: round(size.height * scale))
        let renderer = UIGraphicsImageRenderer(size: target)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return resized.jpegData(compressionQuality: 0.82)
    }

    private static func writeJPEG(_ data: Data, for mediaURL: URL) -> String? {
        let dir = BookmarkThumbnailPolicy.thumbnailsDirectory()
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let fileName = BookmarkThumbnailPolicy.fileName(for: mediaURL)
            let fileURL = BookmarkThumbnailPolicy.fileURL(for: fileName)
            try data.write(to: fileURL, options: .atomic)
            return fileName
        } catch {
            return nil
        }
    }
}
