import CoreGraphics
import CoreImage

/// Ambient mode's source: a tiny copy of the current video frame. Only the colours matter
/// (the view blurs it heavily), so frames are shrunk to 48 pixels wide right away and the
/// full-size pixels are never kept.
enum PhoneAmbientFrame {
    static let width = 48

    /// Shrinks a BGRX/BGRA frame (mpv's `screenshot-raw`, or an AVPlayer pixel buffer).
    /// The pixels are read during the call only.
    static func downscale(bgrx: UnsafeRawPointer, width: Int, height: Int, stride: Int) -> CGImage? {
        guard width > 0, height > 0, stride >= width * 4 else { return nil }
        let info = CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)
        guard let provider = CGDataProvider(dataInfo: nil, data: bgrx, size: stride * height, releaseData: { _, _, _ in }),
              let source = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: stride,
                                   space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: info, provider: provider,
                                   decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        return downscale(source)
    }

    /// Moves the glow part of the way toward a new frame. Fading straight between two very
    /// different frames dims the screen halfway (red and blue mix to a dark purple), so the
    /// glow follows the video in small steps instead. Without a glow yet, the frame is used as is.
    static func blend(_ glow: CGImage?, toward frame: CGImage, weight: Double = 0.35) -> CGImage? {
        guard let glow, glow.width == frame.width, glow.height == frame.height else { return frame }
        guard let context = CGContext(data: nil, width: frame.width, height: frame.height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return frame }
        let rect = CGRect(x: 0, y: 0, width: frame.width, height: frame.height)
        context.draw(glow, in: rect)
        context.setAlpha(min(1, max(0, weight)))
        context.draw(frame, in: rect)
        return context.makeImage()
    }

    private static let ciContext = CIContext(options: [.useSoftwareRenderer: false])

    /// The glow as shown: the tiny frame blurred once here, so the view only stretches it.
    static func soften(_ image: CGImage, sigma: Double = 2.5) -> CGImage? {
        let input = CIImage(cgImage: image)
        let blurred = input.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: input.extent)
        return ciContext.createCGImage(blurred, from: input.extent)
    }

    /// Average brightness, 0...1.
    static func meanLuma(_ image: CGImage) -> Double {
        guard let context = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue),
              let data = context.data else { return 0 }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        let bytes = data.assumingMemoryBound(to: UInt8.self)
        return (0.2126 * Double(bytes[0]) + 0.7152 * Double(bytes[1]) + 0.0722 * Double(bytes[2])) / 255
    }

    /// A frame grab can come back black while the picture isn't. A single near-black frame is
    /// ignored while the glow has colour; a second one in a row is a real dark scene.
    static let darkLuma = 0.03
    static func accepts(frameLuma: Double, glowLuma: Double?, previousWasDark: Bool) -> Bool {
        guard frameLuma < darkLuma, let glowLuma, glowLuma >= darkLuma else { return true }
        return previousWasDark
    }

    static func downscale(_ image: CGImage) -> CGImage? {
        guard image.width > 0, image.height > 0 else { return nil }
        let height = max(1, Int((Double(width) * Double(image.height) / Double(image.width)).rounded()))
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }
}
