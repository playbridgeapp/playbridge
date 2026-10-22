import Foundation
import Libavformat
import Libavcodec
import Libavutil

/// Remuxes a Matroska (or other libavformat-readable) source into HLS fMP4 for
/// AVPlayer without re-encoding: H.264/HEVC video (Dolby Vision configuration
/// kept) and AAC/AC-3/E-AC-3/ALAC audio are copied. Segments are cut on the
/// source's keyframe index, so any segment can be produced on demand (seeking).
final class HLSRemuxer {
    enum Failure: Error, CustomStringConvertible {
        case open(Int32), streamInfo(Int32), noVideo, unsupportedVideo(String), noKeyframeIndex
        case unsupportedDolbyVision(Int), highDynamicRange(String), mux(String, Int32), segment(Int)
        var description: String {
            switch self {
            case .open(let code): return "open failed (\(code))"
            case .streamInfo(let code): return "stream info failed (\(code))"
            case .noVideo: return "no video stream"
            case .unsupportedVideo(let name): return "video codec \(name) not playable by AVPlayer"
            case .noKeyframeIndex: return "no keyframe index (cues)"
            case .unsupportedDolbyVision(let profile): return "Dolby Vision profile \(profile) not supported"
            case .highDynamicRange(let range): return "\(range) video stays on mpv"
            case .mux(let step, let code): return "mux \(step) failed (\(code))"
            case .segment(let index): return "segment \(index) out of range"
            }
        }
    }

    struct Segment: Equatable {
        var start: Int64      // video pts (input time base), inclusive keyframe
        var end: Int64?       // next keyframe pts; nil = to end of file
        var duration: Double  // seconds
    }

    /// Minimum segment length; a segment ends at the first keyframe past it, so
    /// sparse-keyframe sources stay near one GOP per segment.
    static let targetSegmentSeconds = 4.0
    private static let copyAudio: Set<AVCodecID> = [AV_CODEC_ID_AAC, AV_CODEC_ID_AC3, AV_CODEC_ID_EAC3, AV_CODEC_ID_ALAC]

    private var input: UnsafeMutablePointer<AVFormatContext>?
    private let videoIndex: Int32
    private let audioIndex: Int32?
    private let videoTimeBase: AVRational
    /// Added to every timestamp so B-frame decode times never go negative; the
    /// same constant for every segment keeps random access consistent.
    private let shift: Int64
    private let lock = NSLock()

    let segments: [Segment]
    let duration: Double
    let masterPlaylist: String
    let mediaPlaylist: String
    let summary: String

    init(url: String, headers: [String: String] = [:]) throws {
        var options: OpaquePointer?
        let headerLines = headers.filter { $0.key.caseInsensitiveCompare("user-agent") != .orderedSame }
            .map { "\($0.key): \($0.value)\r\n" }.joined()
        if !headerLines.isEmpty { av_dict_set(&options, "headers", headerLines, 0) }
        if let agent = headers.first(where: { $0.key.caseInsensitiveCompare("user-agent") == .orderedSame })?.value {
            av_dict_set(&options, "user_agent", agent, 0)
        }
        av_dict_set(&options, "reconnect", "1", 0)
        av_dict_set(&options, "reconnect_streamed", "1", 0)
        defer { av_dict_free(&options) }

        var context: UnsafeMutablePointer<AVFormatContext>?
        let opened = avformat_open_input(&context, url, nil, &options)
        guard opened >= 0, let context else { throw Failure.open(opened) }
        input = context
        let info = avformat_find_stream_info(context, nil)
        guard info >= 0 else { avformat_close_input(&input); throw Failure.streamInfo(info) }

        let video = av_find_best_stream(context, AVMEDIA_TYPE_VIDEO, -1, -1, nil, 0)
        guard video >= 0 else { avformat_close_input(&input); throw Failure.noVideo }
        videoIndex = video
        let videoStream = context.pointee.streams[Int(video)]!
        let videoPar = videoStream.pointee.codecpar!
        guard videoPar.pointee.codec_id == AV_CODEC_ID_H264 || videoPar.pointee.codec_id == AV_CODEC_ID_HEVC else {
            let name = String(cString: avcodec_get_name(videoPar.pointee.codec_id))
            avformat_close_input(&input)
            throw Failure.unsupportedVideo(name)
        }
        videoTimeBase = videoStream.pointee.time_base

        // First copyable audio track (the default one first).
        var chosenAudio: Int32?
        let best = av_find_best_stream(context, AVMEDIA_TYPE_AUDIO, -1, video, nil, 0)
        if best >= 0, Self.copyAudio.contains(context.pointee.streams[Int(best)]!.pointee.codecpar.pointee.codec_id) {
            chosenAudio = best
        } else {
            for index in 0..<Int32(context.pointee.nb_streams) {
                let par = context.pointee.streams[Int(index)]!.pointee.codecpar!
                if par.pointee.codec_type == AVMEDIA_TYPE_AUDIO, Self.copyAudio.contains(par.pointee.codec_id) {
                    chosenAudio = index
                    break
                }
            }
        }
        audioIndex = chosenAudio

        let dovi = Self.doviConfig(videoPar)
        if let dovi, dovi.profile != 5 && dovi.profile != 8 {
            avformat_close_input(&input)
            throw Failure.unsupportedDolbyVision(dovi.profile)
        }
        // HDR and Dolby Vision stay on mpv: on tvOS AVPlayer rejected every PQ
        // variant of the remux and only played it tone-mapped to SDR.
        let transfer = Self.transfer(videoPar)
        if dovi != nil || transfer == AVCOL_TRC_SMPTE2084 || transfer == AVCOL_TRC_ARIB_STD_B67 {
            avformat_close_input(&input)
            throw Failure.highDynamicRange(dovi != nil ? "Dolby Vision" : transfer == AVCOL_TRC_ARIB_STD_B67 ? "HLG" : "HDR10")
        }

        // Matroska defers reading Cues until the first seek.
        av_seek_frame(context, video, 0, AVSEEK_FLAG_BACKWARD)
        var keyframes: [Int64] = []
        let count = avformat_index_get_entries_count(videoStream)
        for index in 0..<count {
            guard let entry = avformat_index_get_entry(videoStream, index) else { continue }
            if entry.pointee.flags & AVINDEX_KEYFRAME != 0 { keyframes.append(entry.pointee.timestamp) }
        }
        keyframes = Array(Set(keyframes)).sorted()
        guard keyframes.count >= 2 else { avformat_close_input(&input); throw Failure.noKeyframeIndex }

        let totalSeconds = context.pointee.duration > 0
            ? Double(context.pointee.duration) / Double(AV_TIME_BASE)
            : Self.seconds(keyframes.last!, videoTimeBase)
        duration = totalSeconds
        segments = Self.plan(keyframes: keyframes, timeBase: videoTimeBase, duration: totalSeconds)
        shift = av_rescale_q(1, AVRational(num: 1, den: 1), videoTimeBase)  // one second

        let audioPar = chosenAudio.map { context.pointee.streams[Int($0)]!.pointee.codecpar! }
        mediaPlaylist = Self.mediaPlaylist(segments)
        masterPlaylist = Self.masterPlaylist(video: videoPar, stream: videoStream, audio: audioPar, dovi: dovi,
                                            bitRate: context.pointee.bit_rate)
        let audioName = audioPar.map { String(cString: avcodec_get_name($0.pointee.codec_id)) } ?? "none"
        summary = "\(String(cString: avcodec_get_name(videoPar.pointee.codec_id))) \(videoPar.pointee.width)x\(videoPar.pointee.height)"
            + " audio=\(audioName) dovi=\(dovi.map { "p\($0.profile)" } ?? "no") keyframes=\(keyframes.count)"
            + " segments=\(segments.count) duration=\(String(format: "%.1f", totalSeconds))"
    }

    deinit {
        Self.release(carry)
        avformat_close_input(&input)
    }

    // MARK: Planning

    static func seconds(_ ts: Int64, _ tb: AVRational) -> Double { Double(ts) * Double(tb.num) / Double(tb.den) }

    /// Groups keyframes into segments of at least `targetSegmentSeconds`.
    static func plan(keyframes: [Int64], timeBase: AVRational, duration: Double) -> [Segment] {
        var bounds: [Int64] = [keyframes[0]]
        for keyframe in keyframes.dropFirst()
            where seconds(keyframe - bounds.last!, timeBase) >= targetSegmentSeconds {
            bounds.append(keyframe)
        }
        return bounds.enumerated().map { index, start in
            let end = index + 1 < bounds.count ? bounds[index + 1] : nil
            let endSeconds = end.map { seconds($0, timeBase) } ?? duration
            return Segment(start: start, end: end, duration: max(endSeconds - seconds(start, timeBase), 0.001))
        }
    }

    static func mediaPlaylist(_ segments: [Segment]) -> String {
        let target = Int((segments.map(\.duration).max() ?? targetSegmentSeconds).rounded(.up))
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-TARGETDURATION:\(target)", "#EXT-X-MEDIA-SEQUENCE:0",
                     "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-MAP:URI=\"init.mp4\""]
        for (index, segment) in segments.enumerated() {
            lines.append(String(format: "#EXTINF:%.6f,", segment.duration))
            lines.append("seg\(index).m4s")
        }
        lines.append("#EXT-X-ENDLIST")
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: Codec strings

    struct DoviConfig { var profile: Int; var level: Int; var compatibility: Int }

    static func doviConfig(_ par: UnsafeMutablePointer<AVCodecParameters>) -> DoviConfig? {
        guard let side = av_packet_side_data_get(par.pointee.coded_side_data, par.pointee.nb_coded_side_data,
                                                 AV_PKT_DATA_DOVI_CONF),
              side.pointee.size >= MemoryLayout<AVDOVIDecoderConfigurationRecord>.size else { return nil }
        let record = UnsafeRawPointer(side.pointee.data!).assumingMemoryBound(to: AVDOVIDecoderConfigurationRecord.self).pointee
        return DoviConfig(profile: Int(record.dv_profile), level: Int(record.dv_level),
                          compatibility: Int(record.dv_bl_signal_compatibility_id))
    }

    static func videoCodecString(_ par: UnsafeMutablePointer<AVCodecParameters>) -> String {
        let extra = par.pointee.extradata.map { Array(UnsafeBufferPointer(start: $0, count: Int(par.pointee.extradata_size))) } ?? []
        if par.pointee.codec_id == AV_CODEC_ID_H264 {
            guard extra.count >= 4, extra[0] == 1 else { return "avc1.640028" }
            return String(format: "avc1.%02X%02X%02X", extra[1], extra[2], extra[3])
        }
        // hvcC: profile_space/tier/profile_idc, compatibility flags, constraint bytes, level.
        guard extra.count >= 13, extra[0] == 1 else { return "hvc1.2.4.L153.B0" }
        let space = ["", "A", "B", "C"][Int(extra[1] >> 6)]
        let tier = (extra[1] >> 5) & 1 == 1 ? "H" : "L"
        let profile = Int(extra[1] & 0x1F)
        var flags = UInt32(extra[2]) << 24 | UInt32(extra[3]) << 16 | UInt32(extra[4]) << 8 | UInt32(extra[5])
        var reversed: UInt32 = 0
        for _ in 0..<32 { reversed = (reversed << 1) | (flags & 1); flags >>= 1 }
        var constraints = Array(extra[6...11])
        while let last = constraints.last, last == 0 { constraints.removeLast() }
        let constraintText = constraints.isEmpty ? "B0" : constraints.map { String(format: "%X", $0) }.joined(separator: ".")
        return "hvc1.\(space)\(profile).\(String(reversed, radix: 16, uppercase: true)).\(tier)\(extra[12]).\(constraintText)"
    }

    static func audioCodecString(_ par: UnsafeMutablePointer<AVCodecParameters>) -> String {
        switch par.pointee.codec_id {
        case AV_CODEC_ID_AC3: return "ac-3"
        case AV_CODEC_ID_EAC3: return "ec-3"
        case AV_CODEC_ID_ALAC: return "alac"
        default: return par.pointee.profile == 4 ? "mp4a.40.5" : par.pointee.profile == 28 ? "mp4a.40.29" : "mp4a.40.2"
        }
    }

    /// The transfer function: from the container when present, else from the
    /// SPS (opening the decoder parses the parameter sets in extradata). Matroska
    /// remuxes often carry HDR only in the bitstream; a wrong VIDEO-RANGE makes
    /// AVPlayer reject the stream.
    static func transfer(_ par: UnsafeMutablePointer<AVCodecParameters>) -> AVColorTransferCharacteristic {
        if par.pointee.color_trc != AVCOL_TRC_UNSPECIFIED && par.pointee.color_trc != AVCOL_TRC_RESERVED0 {
            return par.pointee.color_trc
        }
        guard let codec = avcodec_find_decoder(par.pointee.codec_id),
              var context = avcodec_alloc_context3(codec) as UnsafeMutablePointer<AVCodecContext>? else {
            return AVCOL_TRC_UNSPECIFIED
        }
        defer { var ref: UnsafeMutablePointer<AVCodecContext>? = context; avcodec_free_context(&ref); _ = ref }
        guard avcodec_parameters_to_context(context, par) >= 0 else { return AVCOL_TRC_UNSPECIFIED }
        context.pointee.thread_count = 1
        guard avcodec_open2(context, codec, nil) >= 0 else { return AVCOL_TRC_UNSPECIFIED }
        _ = context
        return context.pointee.color_trc
    }

    static func masterPlaylist(video: UnsafeMutablePointer<AVCodecParameters>, stream: UnsafeMutablePointer<AVStream>,
                               audio: UnsafeMutablePointer<AVCodecParameters>?, dovi: DoviConfig?, bitRate: Int64) -> String {
        var codecs = [videoCodecString(video)]
        var supplemental: String?
        if let dovi {
            let level = String(format: "%02d", dovi.level)
            if dovi.profile == 5 {
                codecs = ["dvh1.05.\(level)"]
            } else {
                // Profile 8.x: an HDR10 (or HLG/SDR) base layer with a Dolby Vision RPU.
                let brand = dovi.compatibility == 4 ? "db4h" : dovi.compatibility == 2 ? "db2g" : "db1p"
                supplemental = "dvh1.08.\(level)/\(brand)"
            }
        }
        if let audio { codecs.append(audioCodecString(audio)) }
        let trc = transfer(video)
        let range = dovi != nil || trc == AVCOL_TRC_SMPTE2084 ? "PQ" : trc == AVCOL_TRC_ARIB_STD_B67 ? "HLG" : "SDR"
        let rate = stream.pointee.avg_frame_rate
        let fps = rate.den > 0 ? Double(rate.num) / Double(rate.den) : 0
        var attributes = ["BANDWIDTH=\(max(bitRate, 1_000_000) * 12 / 10)",
                          "CODECS=\"\(codecs.joined(separator: ","))\"",
                          "RESOLUTION=\(video.pointee.width)x\(video.pointee.height)",
                          "VIDEO-RANGE=\(range)"]
        if fps > 0 { attributes.append(String(format: "FRAME-RATE=%.3f", fps)) }
        var lines = ["#EXTM3U", "#EXT-X-VERSION:7", "#EXT-X-INDEPENDENT-SEGMENTS"]
        // Dolby Vision first, then the same stream as its base layer (HDR10/HLG).
        if let supplemental {
            lines += ["#EXT-X-STREAM-INF:" + (attributes + ["SUPPLEMENTAL-CODECS=\"\(supplemental)\""])
                .joined(separator: ","), "index.m3u8"]
        }
        lines += ["#EXT-X-STREAM-INF:" + attributes.joined(separator: ","), "index.m3u8"]
        return lines.joined(separator: "\n") + "\n"
    }

    // MARK: Muxing

    private struct Muxer {
        var context: UnsafeMutablePointer<AVFormatContext>
        var videoOut: UnsafeMutablePointer<AVStream>
        var audioOut: UnsafeMutablePointer<AVStream>?
    }

    private static func tag(_ text: String) -> UInt32 {
        let bytes = Array(text.utf8)
        return UInt32(bytes[0]) | UInt32(bytes[1]) << 8 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 24
    }

    /// A fresh fragmented-MP4 muxer writing into an in-memory buffer (`pb`).
    private func makeMuxer(fragmentIndex: Int = 1) throws -> Muxer {
        guard let input else { throw Failure.mux("input", -1) }
        var output: UnsafeMutablePointer<AVFormatContext>?
        var status = avformat_alloc_output_context2(&output, nil, "mp4", nil)
        guard status >= 0, let output else { throw Failure.mux("alloc", status) }
        // dvcC/dvvC are written only below FF_COMPLIANCE_NORMAL.
        output.pointee.strict_std_compliance = -1

        func add(_ index: Int32) throws -> UnsafeMutablePointer<AVStream> {
            let source = input.pointee.streams[Int(index)]!
            guard let stream = avformat_new_stream(output, nil) else { throw Failure.mux("stream", -1) }
            avcodec_parameters_copy(stream.pointee.codecpar, source.pointee.codecpar)
            stream.pointee.time_base = source.pointee.time_base
            let par = stream.pointee.codecpar!
            switch par.pointee.codec_id {
            case AV_CODEC_ID_HEVC:
                par.pointee.codec_tag = Self.tag(Self.doviConfig(par)?.profile == 5 ? "dvh1" : "hvc1")
            case AV_CODEC_ID_H264: par.pointee.codec_tag = Self.tag("avc1")
            default: par.pointee.codec_tag = 0
            }
            return stream
        }
        let videoOut = try add(videoIndex)
        let audioOut = try audioIndex.map(add)

        var options: OpaquePointer?
        // delay_moov: AC-3/E-AC-3 need a parsed packet before moov can be written.
        av_dict_set(&options, "movflags", "frag_custom+empty_moov+delay_moov+default_base_moof+frag_discont", 0)
        av_dict_set(&options, "use_editlist", "0", 0)
        // Increasing mfhd sequence numbers across independently muxed segments.
        av_dict_set(&options, "fragment_index", String(fragmentIndex), 0)
        defer { av_dict_free(&options) }
        status = avio_open_dyn_buf(&output.pointee.pb)
        guard status >= 0 else { avformat_free_context(output); throw Failure.mux("buffer", status) }
        status = avformat_write_header(output, &options)
        guard status >= 0 else {
            _ = Self.drain(output.pointee.pb); output.pointee.pb = nil
            avformat_free_context(output)
            throw Failure.mux("header", status)
        }
        return Muxer(context: output, videoOut: videoOut, audioOut: audioOut)
    }

    private static func drain(_ pb: UnsafeMutablePointer<AVIOContext>?) -> Data {
        guard let pb else { return Data() }
        var buffer: UnsafeMutablePointer<UInt8>?
        let size = avio_close_dyn_buf(pb, &buffer)
        defer { av_free(buffer) }
        guard size > 0, let buffer else { return Data() }
        return Data(bytes: buffer, count: Int(size))
    }

    private static func free(_ muxer: Muxer) {
        var context: UnsafeMutablePointer<AVFormatContext>? = muxer.context
        if let pb = context?.pointee.pb { _ = drain(pb); context?.pointee.pb = nil }
        avformat_free_context(context)
        context = nil
    }

    private var cachedInit: Data?

    /// ftyp + moov, taken from segment 0's muxer (delay_moov writes it with the
    /// first fragment, once audio parameters are parsed).
    /// Segment 0 muxed while building the init segment, served once.
    private var firstSegment: Data?
    private static let mediaBoxes: Set<String> = ["styp", "sidx", "moof", "mdat"]

    func initSegment() throws -> Data {
        if let cachedInit = lock.withLock({ cachedInit }) { return cachedInit }
        let output = try mux(0)
        let header = Self.boxes(output, keep: ["ftyp", "moov"])
        lock.withLock {
            cachedInit = header
            firstSegment = Self.boxes(output, keep: Self.mediaBoxes)
        }
        return header
    }

    /// moof + mdat for one segment.
    func segment(_ index: Int) throws -> Data {
        if index == 0, let first = lock.withLock({ () -> Data? in defer { firstSegment = nil }; return firstSegment }) {
            return first
        }
        return Self.boxes(try mux(index), keep: Self.mediaBoxes)
    }

    /// Top-level ISO BMFF boxes of the given types, in order.
    static func boxes(_ data: Data, keep: Set<String>) -> Data {
        var ranges: [Range<Int>] = []
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let bytes = raw.bindMemory(to: UInt8.self)
            var offset = 0
            while offset + 8 <= bytes.count {
                var size = Int(bytes[offset]) << 24 | Int(bytes[offset + 1]) << 16
                    | Int(bytes[offset + 2]) << 8 | Int(bytes[offset + 3])
                let type = String(decoding: bytes[(offset + 4)..<(offset + 8)], as: UTF8.self)
                if size == 1, offset + 16 <= bytes.count {
                    size = bytes[(offset + 8)..<(offset + 16)].reduce(0) { $0 << 8 | Int($1) }
                } else if size == 0 {
                    size = bytes.count - offset
                }
                guard size >= 8, offset + size <= bytes.count else { break }
                if keep.contains(type) { ranges.append(offset..<(offset + size)) }
                offset += size
            }
        }
        // The usual case keeps one contiguous run (moof + mdat): no copy needed.
        if let first = ranges.first, let last = ranges.last,
           ranges.count == 1 || zip(ranges, ranges.dropFirst()).allSatisfy({ $0.upperBound == $1.lowerBound }) {
            return first.lowerBound == 0 && last.upperBound == data.count ? data : data.subdata(in: first.lowerBound..<last.upperBound)
        }
        var result = Data()
        for range in ranges { result.append(data.subdata(in: range)) }
        return result
    }

    /// Decode times are rebuilt per segment: Matroska stores presentation times
    /// only. Sorted presentation times minus a fixed reorder delay give monotonic
    /// decode times that never pass their presentation time and stay continuous
    /// from one independently muxed segment to the next.
    private var reorderDelay: Int64 {
        guard let input else { return 0 }
        let stream = input.pointee.streams[Int(videoIndex)]!
        let rate = stream.pointee.avg_frame_rate.num > 0 ? stream.pointee.avg_frame_rate : stream.pointee.r_frame_rate
        guard rate.num > 0, rate.den > 0 else { return av_rescale_q(1, AVRational(num: 1, den: 5), videoTimeBase) }
        let frame = av_rescale_q(1, AVRational(num: rate.den, den: rate.num), videoTimeBase)
        let frames = max(Int64(stream.pointee.codecpar.pointee.video_delay), 4)
        return frame * frames
    }

    /// Packets read past the previous segment's end (its boundary keyframe and
    /// later audio). The next segment continues from them without a seek, so a
    /// remote source keeps one connection while AVPlayer plays forward.
    private var carry: [UnsafeMutablePointer<AVPacket>] = []
    private var nextSequential: Int?

    private static func release(_ packets: [UnsafeMutablePointer<AVPacket>]) {
        for packet in packets {
            var ref: UnsafeMutablePointer<AVPacket>? = packet
            av_packet_free(&ref)
        }
    }

    private func mux(_ index: Int) throws -> Data {
        lock.lock(); defer { lock.unlock() }
        guard segments.indices.contains(index), let input else { throw Failure.segment(index) }
        let segment = segments[index]
        let videoStream = input.pointee.streams[Int(videoIndex)]!
        let started = Date()

        var carried: [UnsafeMutablePointer<AVPacket>] = []
        let sequential = index == nextSequential
        if sequential {
            carried = carry
        } else {
            Self.release(carry)
            let status = av_seek_frame(input, videoIndex, segment.start, AVSEEK_FLAG_BACKWARD)
            guard status >= 0 else { carry = []; nextSequential = nil; throw Failure.mux("seek", status) }
        }
        carry = []
        nextSequential = nil

        let startSeconds = Self.seconds(segment.start, videoTimeBase)
        let endSeconds = segment.end.map { Self.seconds($0, videoTimeBase) } ?? .infinity
        var video: [UnsafeMutablePointer<AVPacket>] = []
        var audio: [UnsafeMutablePointer<AVPacket>] = []
        var spill: [UnsafeMutablePointer<AVPacket>] = []
        defer { Self.release(video + audio) }
        var videoStarted = false, videoDone = false, audioDone = audioIndex == nil

        /// Files one packet (taking ownership of `owned`) into this segment, the
        /// next segment's carry, or drops it.
        func take(_ owned: UnsafeMutablePointer<AVPacket>) {
            let streamIndex = owned.pointee.stream_index
            if streamIndex == videoIndex, owned.pointee.pts != Int64.min {
                if videoDone { spill.append(owned); return }
                let isKey = owned.pointee.flags & AV_PKT_FLAG_KEY != 0
                if !videoStarted {
                    // Decode order starts at this segment's keyframe.
                    guard isKey, owned.pointee.pts >= segment.start else { Self.release([owned]); return }
                    videoStarted = true
                } else if isKey, let end = segment.end, owned.pointee.pts >= end {
                    videoDone = true
                    spill.append(owned)
                    return
                }
                video.append(owned)
            } else if let audioIndex, streamIndex == audioIndex, owned.pointee.pts != Int64.min {
                if audioDone { spill.append(owned); return }
                let audioStream = input.pointee.streams[Int(audioIndex)]!
                let time = Self.seconds(owned.pointee.pts, audioStream.pointee.time_base)
                if time >= endSeconds { audioDone = true; spill.append(owned); return }
                guard time >= startSeconds else { Self.release([owned]); return }
                audio.append(owned)
            } else {
                Self.release([owned])
            }
        }

        for packet in carried { take(packet) }
        guard let packet = av_packet_alloc() else { throw Failure.mux("packet", -1) }
        var packetRef: UnsafeMutablePointer<AVPacket>? = packet
        defer { av_packet_free(&packetRef) }
        var reachedEnd = false
        while !(videoDone && audioDone) {
            if av_read_frame(input, packet) < 0 { reachedEnd = true; break }  // EOF or read error
            if let copy = av_packet_clone(packet) { take(copy) }
            av_packet_unref(packet)
        }
        if reachedEnd || segment.end == nil {
            Self.release(spill)
        } else {
            carry = spill
            nextSequential = index + 1
        }

        let delay = reorderDelay
        for (packet, pts) in zip(video, video.map { $0.pointee.pts }.sorted()) { packet.pointee.dts = pts - delay }
        for packet in audio { packet.pointee.dts = packet.pointee.pts }

        let muxer = try makeMuxer(fragmentIndex: index + 1)
        defer { Self.free(muxer) }
        for packet in video { try write(packet, from: videoStream, to: muxer.videoOut, muxer: muxer) }
        if let audioIndex, let audioOut = muxer.audioOut {
            let audioStream = input.pointee.streams[Int(audioIndex)]!
            for packet in audio { try write(packet, from: audioStream, to: audioOut, muxer: muxer) }
        }
        // With delay_moov the first flush writes only moov; the second writes moof + mdat.
        av_write_frame(muxer.context, nil)
        av_write_frame(muxer.context, nil)
        let output = Self.drain(muxer.context.pointee.pb)
        muxer.context.pointee.pb = nil
        #if DEBUG
        print(String(format: "[Remux] seg%d %.1f MB in %.0f ms (%@)", index, Double(output.count) / 1_048_576,
                     Date().timeIntervalSince(started) * 1000, sequential ? "sequential" : "seek"))
        #endif
        return output
    }

    private func write(_ packet: UnsafeMutablePointer<AVPacket>, from source: UnsafeMutablePointer<AVStream>,
                       to target: UnsafeMutablePointer<AVStream>, muxer: Muxer) throws {
        let offset = av_rescale_q(shift, videoTimeBase, source.pointee.time_base)
        if packet.pointee.pts != Int64.min { packet.pointee.pts += offset }  // AV_NOPTS_VALUE
        if packet.pointee.dts != Int64.min { packet.pointee.dts += offset }
        av_packet_rescale_ts(packet, source.pointee.time_base, target.pointee.time_base)
        packet.pointee.stream_index = target.pointee.index
        packet.pointee.pos = -1
        let status = av_write_frame(muxer.context, packet)
        guard status >= 0 else { throw Failure.mux("write", status) }
    }
}
