import Foundation
import VideoToolbox
import CoreImage
#if canImport(UIKit)
import UIKit
#endif

/// iOS AVAssetImageGenerator cannot open raw MPEG-TS files. Extract an AVC
/// random-access picture from a short TS sample and decode it with VideoToolbox.
/// No player, audio track, or audio session is created.
enum TransportStreamThumbnail {
    struct AVCFrame {
        let sps: Data
        let pps: Data
        let slices: [Data]
    }

    static func avcFrame(in transport: Data) -> AVCFrame? {
        let bytes = [UInt8](transport)
        guard bytes.count >= 188 else { StreamDebugTrace.record("TS: sample shorter than one packet"); return nil }
        // Accept the common 188-byte TS transport layout; reject corrupt framing.
        guard let start = (0..<min(188, bytes.count)).first(where: {
            bytes[$0] == 0x47 && ($0 + 188 >= bytes.count || bytes[$0 + 188] == 0x47)
        }) else { StreamDebugTrace.record("TS: 188-byte sync framing not found"); return nil }
        var pid: Int?
        var elementary = [UInt8]()
        var previousCounter: Int?
        var previousPacket: ArraySlice<UInt8>?
        var waitingForPES = false
        var discontinuities = 0
        for packet in stride(from: start, through: bytes.count - 188, by: 188) {
            guard bytes[packet] == 0x47 else { StreamDebugTrace.record("TS: lost packet synchronization at offset \(packet)"); return nil }
            let packetPID = Int(bytes[packet + 1] & 0x1f) << 8 | Int(bytes[packet + 2])
            let control = (bytes[packet + 3] >> 4) & 3
            guard control == 1 || control == 3 else { continue }
            var payload = packet + 4
            let discontinuity = control == 3 && bytes[payload] > 0 && bytes[payload + 1] & 0x80 != 0
            if control == 3 { payload += 1 + Int(bytes[payload]) }
            guard payload < packet + 188 else { continue }
            let begins = bytes[packet + 1] & 0x40 != 0
            if pid == nil, begins, payload + 9 <= packet + 188,
               bytes[payload] == 0, bytes[payload + 1] == 0, bytes[payload + 2] == 1,
               (0xe0...0xef).contains(bytes[payload + 3]) {
                pid = packetPID
            }
            guard packetPID == pid else { continue }
            guard bytes[packet + 1] & 0x80 == 0, bytes[packet + 3] & 0xc0 == 0 else {
                StreamDebugTrace.record("TS: transport error or scrambling on video PID \(packetPID)")
                return nil
            }
            let counter = Int(bytes[packet + 3] & 0x0f)
            let currentPacket = bytes[packet..<(packet + 188)]
            if !discontinuity, counter == previousCounter, currentPacket.elementsEqual(previousPacket ?? []) {
                continue // Only byte-identical packets are retransmissions.
            }
            if discontinuity || previousCounter.map({ counter != ($0 + 1) % 16 }) == true {
                discontinuities += 1
                StreamDebugTrace.record("TS: continuity reset on video PID \(packetPID), counter \(previousCounter ?? -1) -> \(counter)")
                // Independent HLS segments may restart counters. Keep an already
                // complete picture; never concatenate a broken picture across a gap.
                if let frame = avcFrame(fromAnnexB: elementary, requireComplete: true) { return frame }
                elementary.removeAll(keepingCapacity: true)
                waitingForPES = true
            }
            previousCounter = counter
            previousPacket = currentPacket
            if waitingForPES && !begins { continue }
            if begins { waitingForPES = false }
            if begins {
                guard payload + 9 <= packet + 188,
                      bytes[payload] == 0, bytes[payload + 1] == 0, bytes[payload + 2] == 1 else {
                    StreamDebugTrace.record("TS: invalid or split PES header")
                    return nil
                }
                payload += 9 + Int(bytes[payload + 8])
            }
            guard payload <= packet + 188 else { StreamDebugTrace.record("TS: PES header spans packet boundary"); return nil }
            elementary.append(contentsOf: bytes[payload..<(packet + 188)])
        }
        StreamDebugTrace.record("TS: video PID \(pid.map(String.init) ?? "not found"), elementary bytes \(elementary.count), continuity resets \(discontinuities)")
        return avcFrame(fromAnnexB: elementary)
    }

    static func avcFrame(fromAnnexB bytes: [UInt8], requireComplete: Bool = false) -> AVCFrame? {
        var starts: [(prefix: Int, payload: Int)] = []
        var index = 0
        while index + 3 < bytes.count {
            if bytes[index] == 0, bytes[index + 1] == 0 {
                if bytes[index + 2] == 1 {
                    starts.append((index, index + 3)); index += 3; continue
                }
                if bytes[index + 2] == 0, bytes[index + 3] == 1 {
                    starts.append((index, index + 4)); index += 4; continue
                }
            }
            index += 1
        }
        var sps: Data?, pps: Data?
        var slices: [Data] = []
        var complete = false
        for (offset, start) in starts.enumerated() {
            let end = offset + 1 < starts.count ? starts[offset + 1].prefix : bytes.count
            guard start.payload < end else { continue }
            let nal = Array(bytes[start.payload..<end])
            let type = nal[0] & 0x1f
            if !slices.isEmpty, type == 9 || type == 1 || type == 7 || type == 8 { complete = true; break }
            if type == 5, !slices.isEmpty, nal.count > 1, nal[1] & 0x80 != 0 { complete = true; break }
            switch type {
            case 7: sps = Data(nal)
            case 8: pps = Data(nal)
            case 5:
                // first_mb_in_slice is ue(v): a leading 1 means zero (new picture).
                guard sps != nil, pps != nil else { continue }
                slices.append(Data(nal))
            default: continue
            }
            if type == 5, !slices.isEmpty, offset + 1 == starts.count { break }
        }
        guard let sps, let pps, !slices.isEmpty, !requireComplete || complete else {
            StreamDebugTrace.record("AVC: SPS \(sps != nil), PPS \(pps != nil), IDR slices \(slices.count), complete picture \(complete)")
            return nil
        }
        return AVCFrame(sps: sps, pps: pps, slices: slices)
    }

    private final class Output {
        private let lock = NSLock()
        private var pixelBuffer: CVPixelBuffer?
        func set(_ value: CVPixelBuffer) { lock.lock(); defer { lock.unlock() }; pixelBuffer = value }
        func get() -> CVPixelBuffer? { lock.lock(); defer { lock.unlock() }; return pixelBuffer }
    }

    static func thumbnail(file: URL) -> UIImage? {
        guard !Task.isCancelled, let data = try? Data(contentsOf: file), let frame = avcFrame(in: data) else {
            StreamDebugTrace.record("TS extraction failed: no supported AVC keyframe/parameter sets, invalid transport, or cancellation")
            return nil
        }
        StreamDebugTrace.record("AVC keyframe: \(frame.slices.count) slices, SPS \(frame.sps.count) bytes, PPS \(frame.pps.count) bytes")
        var format: CMFormatDescription?
        let status = frame.sps.withUnsafeBytes { sps in
            frame.pps.withUnsafeBytes { pps in
                let pointers = [sps.bindMemory(to: UInt8.self).baseAddress!, pps.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [frame.sps.count, frame.pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2,
                    parameterSetPointers: pointers, parameterSetSizes: sizes,
                    nalUnitHeaderLength: 4, formatDescriptionOut: &format
                )
            }
        }
        guard status == noErr, let format else { StreamDebugTrace.record("AVC format creation failed: \(status)"); return nil }
        var accessUnit = Data()
        for slice in frame.slices {
            var size = UInt32(slice.count).bigEndian
            withUnsafeBytes(of: &size) { accessUnit.append(contentsOf: $0) }
            accessUnit.append(slice)
        }
        var block: CMBlockBuffer?
        guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil,
            blockLength: accessUnit.count, blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
            offsetToData: 0, dataLength: accessUnit.count, flags: 0, blockBufferOut: &block) == noErr,
            let block else { return nil }
        let copied = accessUnit.withUnsafeBytes {
            CMBlockBufferReplaceDataBytes(with: $0.baseAddress!, blockBuffer: block, offsetIntoDestination: 0, dataLength: accessUnit.count)
        }
        guard copied == noErr else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .zero, decodeTimeStamp: .invalid)
        var size = accessUnit.count
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: 1, sampleTimingEntryCount: 1, sampleTimingArray: &timing,
            sampleSizeEntryCount: 1, sampleSizeArray: &size, sampleBufferOut: &sample) == noErr,
            let sample else { return nil }
        let output = Output()
        var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { pointer, _, status, _, image, _, _ in
            guard status == noErr, let pointer, let image else { return }
            Unmanaged<Output>.fromOpaque(pointer).takeUnretainedValue().set(image)
        }, decompressionOutputRefCon: Unmanaged.passUnretained(output).toOpaque())
        var session: VTDecompressionSession?
        guard VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format,
            decoderSpecification: nil, imageBufferAttributes: [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA] as CFDictionary,
            outputCallback: &callback, decompressionSessionOut: &session) == noErr,
            let session else { return nil }
        defer { VTDecompressionSessionInvalidate(session) }
        return withExtendedLifetime(output) {
            guard !Task.isCancelled,
                  VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [], frameRefcon: nil, infoFlagsOut: nil) == noErr else { return nil }
            VTDecompressionSessionFinishDelayedFrames(session)
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            guard !Task.isCancelled, let buffer = output.get() else { StreamDebugTrace.record("VideoToolbox returned no decoded frame"); return nil }
            let image = CIImage(cvPixelBuffer: buffer)
            let scale = min(1, 640 / image.extent.width, 360 / image.extent.height)
            let scaled = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
            return UIImage(cgImage: cgImage)
        }
    }
}
