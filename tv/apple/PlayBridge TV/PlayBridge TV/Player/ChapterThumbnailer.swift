import UIKit
import Libmpv

/// Frames for the mpv Chapters cards. AVPlayer draws its own chapter thumbnails;
/// mpv has none, so a second, headless libmpv core opens the same stream once,
/// pauses, and takes a small `screenshot-raw` after a keyframe seek per chapter.
/// It never outputs audio or video and is torn down when the item changes.
final class ChapterThumbnailer {
    /// Card width in pixels; the frame keeps its aspect ratio.
    static let frameWidth = 480
    private static let stepTimeout = 12.0

    private let queue = DispatchQueue(label: "app.playbridge.tv.chapter-thumbnails", qos: .utility)
    private let lock = NSLock()
    private var handle: OpaquePointer?
    private var cancelled = false

    /// Delivers each frame on the main queue, keyed by chapter start.
    func start(url: URL, headers: [String: String]?, times: [(startMs: Int, seconds: Double)],
               onFrame: @escaping (Int, UIImage) -> Void) {
        guard !times.isEmpty else { return }
        queue.async { [weak self] in self?.run(url: url, headers: headers, times: times, onFrame: onFrame) }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        if let handle { mpv_wakeup(handle) }
        lock.unlock()
    }

    private var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return cancelled
    }

    private func run(url: URL, headers: [String: String]?, times: [(startMs: Int, seconds: Double)],
                     onFrame: @escaping (Int, UIImage) -> Void) {
        guard !isCancelled, let core = mpv_create() else { return }
        lock.lock(); handle = core; lock.unlock()
        defer {
            lock.lock(); handle = nil; lock.unlock()
            mpv_terminate_destroy(core)
        }

        let ua = headers?.first { $0.key.caseInsensitiveCompare("user-agent") == .orderedSame }?.value
        var options: [(String, String)] = [
            ("config", "no"), ("terminal", "no"), ("load-scripts", "no"), ("ytdl", "no"),
            ("vo", "null"), ("ao", "null"), ("aid", "no"), ("sid", "no"),
            ("hwdec", "videotoolbox-copy"), ("hwdec-software-fallback", "yes"),
            ("vf", "scale=w=\(Self.frameWidth):h=-2"),
            ("hr-seek", "no"), ("pause", "yes"), ("keep-open", "always"),
            ("cache", "no"), ("demuxer-max-bytes", "16MiB"), ("demuxer-readahead-secs", "1"),
            ("network-timeout", "10"),
            ("http-header-fields", MPVHTTPHeaders.fields(from: headers)),
            ("start", String(format: "%.2f", times[0].seconds)),
        ]
        if let ua { options.append(("user-agent", ua)) }
        for (name, value) in options { mpv_set_option_string(core, name, value) }
        mpv_request_log_messages(core, "no")
        guard mpv_initialize(core) >= 0 else { return }

        let path = url.isFileURL ? url.path : url.absoluteString
        guard command(core, ["loadfile", path, "replace"]) >= 0 else { return }

        var delivered = 0
        for (index, time) in times.enumerated() {
            if index > 0 {
                guard command(core, ["seek", String(format: "%.2f", time.seconds), "absolute+keyframes"]) >= 0 else { break }
            }
            guard waitForFrame(core), !isCancelled else { break }
            guard let image = screenshot(core) else { continue }
            delivered += 1
            let startMs = time.startMs
            DispatchQueue.main.async { onFrame(startMs, image) }
        }
        #if DEBUG
        NSLog("[ChapterThumbnailer] \(delivered)/\(times.count) frames")
        #endif
    }

    /// Waits for the first displayed frame after the load or seek.
    private func waitForFrame(_ core: OpaquePointer) -> Bool {
        let deadline = Date().addingTimeInterval(Self.stepTimeout)
        while Date() < deadline, !isCancelled {
            guard let event = mpv_wait_event(core, 0.5)?.pointee else { continue }
            switch event.event_id {
            case MPV_EVENT_PLAYBACK_RESTART: return true
            case MPV_EVENT_END_FILE, MPV_EVENT_SHUTDOWN: return false
            default: continue
            }
        }
        return false
    }

    private func screenshot(_ core: OpaquePointer) -> UIImage? {
        var result = mpv_node()
        guard command(core, ["screenshot-raw", "video"], result: &result) >= 0 else { return nil }
        defer { mpv_free_node_contents(&result) }
        guard result.format == MPV_FORMAT_NODE_MAP, let list = result.u.list?.pointee else { return nil }

        var width = 0, height = 0, stride = 0
        var format = ""
        var bytes: mpv_byte_array?
        for index in 0..<Int(list.num) {
            guard let key = list.keys?[index].map({ String(cString: $0) }) else { continue }
            let value = list.values[index]
            switch key {
            case "w": width = Int(value.u.int64)
            case "h": height = Int(value.u.int64)
            case "stride": stride = Int(value.u.int64)
            case "format": format = value.u.string.map { String(cString: $0) } ?? ""
            case "data": bytes = value.u.ba?.pointee
            default: break
            }
        }
        // mpv returns bgr0: bytes B, G, R, unused.
        guard format == "bgr0", width > 0, height > 0, stride >= width * 4,
              let bytes, let data = bytes.data, bytes.size >= stride * height,
              let provider = CGDataProvider(data: Data(bytes: data, count: stride * height) as CFData),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
                  bytesPerRow: stride, space: CGColorSpaceCreateDeviceRGB(),
                  bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue
                      | CGImageAlphaInfo.noneSkipFirst.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        else { return nil }
        return UIImage(cgImage: image)
    }

    @discardableResult
    private func command(_ core: OpaquePointer, _ args: [String], result: UnsafeMutablePointer<mpv_node>? = nil) -> Int32 {
        var cArgs = args.map { strdup($0) }
        cArgs.append(nil)
        defer { cArgs.forEach { if let p = $0 { free(p) } } }
        return cArgs.withUnsafeMutableBufferPointer { buf in
            buf.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buf.count) { args in
                if let result { return mpv_command_ret(core, args, result) }
                return mpv_command(core, args)
            }
        }
    }
}
