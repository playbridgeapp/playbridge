// SPDX-License-Identifier: MPL-2.0
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum BrowserImageError: Error { case unsupported, tooLarge, network, permission, photos }

struct BrowserImageData {
    static let maximumBytes = 50 * 1024 * 1024
    let data: Data
    let type: String
    var fileExtension: String { UTType(type)?.preferredFilenameExtension ?? "img" }

    static func allowedURL(_ url: URL) -> Bool {
        switch url.scheme?.lowercased() {
        case "http", "https": return url.host?.isEmpty == false
        case "data": return url.absoluteString.prefix(11).lowercased() == "data:image/" && url.absoluteString.utf8.count <= maximumBytes * 4 / 3 + 1024
        default: return false
        }
    }
    static func imageLink(_ url: URL) -> URL? {
        guard allowedURL(url), ["png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "avif", "tif", "tiff", "bmp", "svg"].contains(url.pathExtension.lowercased()) || url.scheme?.lowercased() == "data" else { return nil }
        return url
    }
    static func decode(_ data: Data) throws -> Self {
        guard data.count <= maximumBytes else { throw BrowserImageError.tooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0,
              CGImageSourceGetStatus(source) == .statusComplete, let type = CGImageSourceGetType(source) as String?,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
              width.doubleValue > 0, height.doubleValue > 0,
              width.doubleValue * height.doubleValue <= 40_000_000 else { throw BrowserImageError.unsupported }
        return Self(data: data, type: type)
    }
    static func dataURL(_ url: URL) throws -> Self {
        guard allowedURL(url), url.scheme?.lowercased() == "data",
              let comma = url.absoluteString.firstIndex(of: ",") else { throw BrowserImageError.unsupported }
        let header = url.absoluteString[..<comma].lowercased()
        let payload = String(url.absoluteString[url.absoluteString.index(after: comma)...])
        let data: Data?
        if header.hasSuffix(";base64") {
            data = payload.removingPercentEncoding.flatMap { Data(base64Encoded: $0, options: .ignoreUnknownCharacters) }
        } else {
            // Percent-decode as bytes, not UTF-8 text: raster data need not be UTF-8.
            let bytes = Array(payload.utf8)
            var decoded = Data(), index = 0
            while index < bytes.count {
                if bytes[index] == 37 {
                    guard index + 2 < bytes.count, let byte = UInt8(String(decoding: bytes[(index + 1)...(index + 2)], as: UTF8.self), radix: 16) else { throw BrowserImageError.unsupported }
                    decoded.append(byte); index += 3
                } else { decoded.append(bytes[index]); index += 1 }
                guard decoded.count <= maximumBytes else { throw BrowserImageError.tooLarge }
            }
            data = decoded
        }
        guard let data else { throw BrowserImageError.unsupported }
        return try decode(data)
    }
}
