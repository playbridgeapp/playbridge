import CoreGraphics
import Foundation

@main struct ThumbnailFramePolicyTests {
    static func image(_ color: CGColor) -> CGImage {
        let side = 32
        let context = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8,
                                bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.setFillColor(color)
        context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        return context.makeImage()!
    }

    static func main() async {
        precondition(ThumbnailFramePolicy.seekTimes(seconds: 60) == [1, 5, 10])
        precondition(ThumbnailFramePolicy.seekTimes(seconds: 0.5) == [0.4])
        precondition(ThumbnailFramePolicy.seekTimes(seconds: 6) == [1, 5, 5.9])
        precondition(ThumbnailFramePolicy.seekTimes(seconds: .nan) == [1, 5, 10])
        precondition(ThumbnailFramePolicy.isNearlyBlack(image(CGColor(red: 0, green: 0, blue: 0, alpha: 1))))
        precondition(ThumbnailFramePolicy.isNearlyBlack(image(CGColor(red: 0.05, green: 0.05, blue: 0.05, alpha: 1))))
        precondition(!ThumbnailFramePolicy.isNearlyBlack(image(CGColor(red: 0.1, green: 0.1, blue: 0.1, alpha: 1))))
        precondition(!ThumbnailFramePolicy.isNearlyBlack(image(CGColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1))))
        let mixed = CGContext(data: nil, width: 32, height: 32, bitsPerComponent: 8,
                              bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        mixed.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        mixed.fill(CGRect(x: 0, y: 0, width: 32, height: 32))
        mixed.setFillColor(CGColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1))
        mixed.fill(CGRect(x: 0, y: 0, width: 16, height: 32))
        precondition(!ThumbnailFramePolicy.isNearlyBlack(mixed.makeImage()!))
        let black = image(CGColor(red: 0, green: 0, blue: 0, alpha: 1))
        let bright = image(CGColor(red: 0.5, green: 0.5, blue: 0.5, alpha: 1))
        var attempts: [Double] = []
        let selected = await ThumbnailFramePolicy.selectFrame(seconds: 60) { _, time in
            attempts.append(time)
            return time == 10 ? bright : black
        }
        precondition(attempts == [1, 5, 10] && selected.image === bright && !selected.usedDarkFallback)
        attempts = []
        let fallback = await ThumbnailFramePolicy.selectFrame(seconds: 60) { _, time in
            attempts.append(time)
            return time == 5 ? nil : black
        }
        precondition(attempts == [1, 5, 10] && fallback.image === black && fallback.usedDarkFallback)
        attempts = []
        let early = await ThumbnailFramePolicy.selectFrame(seconds: 60) { _, time in
            attempts.append(time)
            return bright
        }
        precondition(attempts == [1] && early.image === bright && early.attempts == 1)
        let missing = await ThumbnailFramePolicy.selectFrame(seconds: 0.5) { _, _ in nil }
        precondition(missing.image == nil && missing.attempts == 1 && !missing.usedDarkFallback)
        var segmentAttempts: [Int] = []
        let hls = await ThumbnailFramePolicy.selectCandidates([0, 1, 2, 3]) { _, segment in
            segmentAttempts.append(segment)
            return segment == 2 ? bright : black
        }
        precondition(segmentAttempts == [0, 1, 2] && hls.image === bright && hls.attempts == 3,
                     "HLS should seek later segments but never request more than three")
        print("PASS: thumbnail frame seek and near-black policy")
    }
}
