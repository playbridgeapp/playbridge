import Foundation
import CoreGraphics

// macOS shim lets the production decoder compile without UIKit.
final class UIImage: NSObject {
    let cgImage: CGImage
    init(cgImage: CGImage) { self.cgImage = cgImage }
}

@main struct TransportStreamThumbnailTests {
    static func main() {
        let annex: [UInt8] = [0,0,0,1,0x67,1,2, 0,0,1,0x68,3,
                              0,0,1,0x65,0x80,4, 0,0,1,0x65,0x40,5,
                              0,0,1,0x65,0x80,6]
        let frame = TransportStreamThumbnail.avcFrame(fromAnnexB: annex)
        precondition(frame?.sps == Data([0x67,1,2]))
        precondition(frame?.pps == Data([0x68,3]))
        precondition(frame?.slices == [Data([0x65,0x80,4]), Data([0x65,0x40,5])], "Do not combine multiple IDR pictures")
        precondition(TransportStreamThumbnail.avcFrame(fromAnnexB: [0,0,1,0x65,0x80]) == nil, "Parameter sets are required")
        let pes: [UInt8] = [0,0,1,0xe0,0,0,0x80,0,0] + annex
        let adaptation = 183 - pes.count
        let packet: [UInt8] = [0x47,0x41,0,0x30,UInt8(adaptation)] + [UInt8](repeating: 0, count: adaptation) + pes
        precondition(packet.count == 188)
        precondition(TransportStreamThumbnail.avcFrame(in: Data(packet))?.slices == frame?.slices)
        func packetFor(_ elementary: [UInt8], counter: UInt8, discontinuity: Bool = false) -> [UInt8] {
            let payload: [UInt8] = [0,0,1,0xe0,0,0,0x80,0,0] + elementary
            let length = 183 - payload.count
            var adaptation = [UInt8](repeating: 0, count: length)
            if discontinuity { adaptation[0] = 0x80 }
            return [0x47,0x41,0,0x30 | counter, UInt8(length)] + adaptation + payload
        }
        let incomplete = packetFor([0,0,1,0x67,1,2], counter: 0)
        // A new segment may reuse a counter, with different bytes. It is not a duplicate.
        precondition(TransportStreamThumbnail.avcFrame(in: Data(incomplete + packet))?.slices == frame?.slices)
        // Later counter resets must not discard a complete earlier picture.
        let reset = packetFor([0,0,1,0x41,0x80,8], counter: 9)
        precondition(TransportStreamThumbnail.avcFrame(in: Data(packet + reset))?.slices == frame?.slices)
        let explicitReset = packetFor(annex, counter: 1, discontinuity: true)
        precondition(TransportStreamThumbnail.avcFrame(in: Data(incomplete + explicitReset))?.slices == frame?.slices)
        precondition(TransportStreamThumbnail.avcFrame(in: Data(packet + packet))?.slices == frame?.slices, "Actual retransmissions remain deduplicated")
        precondition(TransportStreamThumbnail.avcFrame(fromAnnexB: Array(annex.prefix(18)), requireComplete: true) == nil, "Do not recover a truncated picture across a counter gap")
        var scrambled = packet
        scrambled[3] |= 0x80
        precondition(TransportStreamThumbnail.avcFrame(in: Data(scrambled)) == nil)
        precondition(TransportStreamThumbnail.avcFrame(in: Data([0,1,2])) == nil)
        // Optional locally generated fixture; never commit signed URLs or user media.
        if let path = CommandLine.arguments.dropFirst().first {
            guard let image = TransportStreamThumbnail.thumbnail(file: URL(fileURLWithPath: path)) else {
                fatalError("Fixture did not decode")
            }
            precondition(image.cgImage.width > 0 && image.cgImage.width <= 640)
            precondition(image.cgImage.height > 0 && image.cgImage.height <= 360)
        }
        print("PASS: TS/PES extraction, AVC parameter sets, multi-slice IDR boundaries and invalid transport")
    }
}
