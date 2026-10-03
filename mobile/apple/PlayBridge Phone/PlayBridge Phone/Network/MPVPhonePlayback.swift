#if os(iOS)
import UIKit
import AVFoundation
import Metal
import Libmpv

/// UIKit owns the surface; the serial core owns all libmpv calls. No C callback
/// points at a Swift object, and the layer stays alive until core destruction.
@MainActor final class MPVPhonePlayback: PhoneAlternativePlaybackEngine {
    var onState: ((PhonePlaybackState) -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: ((Int32) -> Void)?
    private(set) var failureContext = "playback"
    private(set) var networkIssue: PhonePlaybackNetworkIssue?
    private let core = MPVPhoneCore()
    private var closed = false
    private var backgroundObservers: [NSObjectProtocol] = []

    init() {
        core.deliver = { [weak self] event in
            guard let self, !self.closed else { return }
            switch event {
            case .state(let state): self.onState?(state)
            case .ended: self.onEnd?()
            case .failed(let code, let context, let issue):
                self.failureContext = context; self.networkIssue = issue; self.onFailure?(code)
            }
        }
        backgroundObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification,
            object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.core.setBackground(true) }
            })
        backgroundObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
            object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.core.setBackground(false) }
            })
        backgroundObservers.append(NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
            object: nil, queue: .main) { [weak self] notification in
                guard (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue else { return }
                Task { @MainActor in self?.pause() }
            })
    }

    func configure(_ options: PhonePlayerOptions) { guard !closed else { return }; core.configure(options) }
    func attach(_ layer: CAMetalLayer) { guard !closed else { return }; core.attach(layer) }
    func resize() { guard !closed else { return }; core.resize() }
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        guard !closed else { return }
        failureContext = "playback"; networkIssue = nil
        core.load(url: url, headers: headers, resume: resume, autoplay: autoplay)
    }
    func play() { core.setPaused(false) }
    func pause() { core.setPaused(true) }
    func seek(to seconds: Double) { core.seek(to: seconds) }
    func selectAudio(_ id: Int?) { core.setProperty("aid", value: id.map(String.init) ?? "auto") }
    func selectSubtitle(_ id: Int?) { core.setProperty("sid", value: id.map(String.init) ?? "no") }
    func close() {
        guard !closed else { return }
        closed = true
        backgroundObservers.forEach(NotificationCenter.default.removeObserver)
        backgroundObservers.removeAll()
        onState = nil; onEnd = nil; onFailure = nil
        core.close()
    }
    deinit {
        backgroundObservers.forEach(NotificationCenter.default.removeObserver)
        core.close()
    }
}

private enum MPVPhoneEvent {
    case state(PhonePlaybackState), ended, failed(Int32, String, PhonePlaybackNetworkIssue? = nil)
}

/// All mutable core state is confined to queue, including load identifiers.
/// Delivery checks the load generation again on the main actor to reject events
/// from replaced episodes, retries, or a dismissed player.
private final class MPVPhoneCore: @unchecked Sendable {
    var deliver: (@MainActor (MPVPhoneEvent) -> Void)?
    private let queue = DispatchQueue(label: "playbridge.phone.mpv", qos: .userInitiated)
    private var handle: OpaquePointer?
    private var surface: CAMetalLayer?
    private var timer: DispatchSourceTimer?
    private var request: (url: URL, headers: [String: String], resume: Double)?
    private var state = PhonePlaybackState()
    private var entryID: Int64?
    private var loaded = false
    private var pendingOutputResize = false
    private var options = PhonePlayerOptions()
    private var pendingSeek: Double?
    private var closed = false
    private var background = false
    private var desiredPaused = true
    private var generation = 0
    private var networkIssue: PhonePlaybackNetworkIssue?
    // Main-actor mirror used only for rejecting already queued deliveries.
    @MainActor private var deliveryGeneration = 0

    func attach(_ layer: CAMetalLayer) {
        queue.async { [self] in
            guard !closed, handle == nil else { return }
            surface = layer
            guard let context = mpv_create() else { emit(.failed(-1, "create")); return }
            handle = context
            // MPVKit's GnuTLS backend cannot use the iOS system trust store.
            // Supply bundled public roots; never bypass certificate validation.
            guard let roots = Bundle.main.url(forResource: "MozillaRootCertificates", withExtension: "pem") else {
                mpv_terminate_destroy(context); handle = nil
                emit(.failed(MPV_ERROR_LOADING_FAILED.rawValue, "TLS trust store missing", .certificate)); return
            }
            // The pinned iOS build disables Lua, so ytdl is not a registered
            // option. Disable config and script loading at the core boundary.
            let options = ["config": "no", "load-scripts": "no", "terminal": "no",
                "input-default-bindings": "no", "msg-level": "all=warn", "vo": "gpu-next", "gpu-api": "vulkan",
                "gpu-context": "moltenvk", "hwdec": "videotoolbox", "ao": "avfoundation,audiounit",
                "pause": "yes", "cache": "yes", "tls-verify": "yes", "network-timeout": "30",
                "tls-ca-file": roots.path,
                "demuxer-max-bytes": "64MiB", "demuxer-max-back-bytes": "16MiB",
                "subs-fallback": "no", "sid": "no"]
            var windowID = Int64(Int(bitPattern: Unmanaged.passUnretained(layer).toOpaque()))
            var status = mpv_set_option(context, "wid", MPV_FORMAT_INT64, &windowID)
            for (name, value) in options.sorted(by: { $0.key < $1.key }) where status >= 0 {
                status = mpv_set_option_string(context, name, value)
                if status < 0 {
                    mpv_terminate_destroy(context); handle = nil
                    emit(.failed(status, "option: " + name)); return
                }
            }
            if status >= 0 { status = mpv_initialize(context) }
            guard status >= 0 else {
                mpv_terminate_destroy(context); handle = nil
                emit(.failed(status, "initialize")); return
            }
            // Receive warnings as events, classify in memory, and discard the
            // original text. Terminal/file logging remains disabled.
            mpv_request_log_messages(context, "warn")
            let ticker = DispatchSource.makeTimerSource(queue: queue)
            ticker.schedule(deadline: .now(), repeating: .milliseconds(250))
            ticker.setEventHandler { [weak self] in self?.poll() }
            timer = ticker; ticker.resume()
            applyOptions()
            if request != nil { loadCurrent() }
        }
    }

    @MainActor func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        deliveryGeneration += 1
        let token = deliveryGeneration
        queue.async { [self] in
            guard !closed else { return }
            generation = token
            request = (url, headers, resume.isFinite ? max(0, resume) : 0)
            desiredPaused = !autoplay
            state = PhonePlaybackState(position: request!.resume, paused: desiredPaused || background)
            loaded = false; entryID = nil; pendingSeek = nil
            networkIssue = nil
            emit(.state(state))
            if handle != nil { loadCurrent() }
        }
    }

    func configure(_ options: PhonePlayerOptions) {
        queue.async { [self] in
            guard !closed else { return }
            self.options = options
            applyOptions()
        }
    }
    private func applyOptions() {
        guard let handle else { return }
        let prefs = options.preferences
        let values = ["speed": String(prefs.speed), "panscan": prefs.sizing == .fill ? "1" : "0",
                      "sub-delay": String(options.subtitleDelay), "sub-scale": String(prefs.subtitleScale),
                      "sub-color": prefs.subtitleColor == .yellow ? "#FFFF00" : "#FFFFFF",
                      "sub-back-color": prefs.subtitleBackground ? "#000000BF" : "#00000000",
                      "sub-border-size": "2", "sub-ass-override": "force",
                      "sub-border-style": prefs.subtitleBackground ? "background-box" : "outline-and-shadow"]
        for (name, value) in values.sorted(by: { $0.key < $1.key }) {
            let status = mpv_set_property_string(handle, name, value)
            if status < 0 { emit(.failed(status, "player setting: " + name)) }
        }
    }

    private func loadCurrent() {
        guard let handle, let request else { return }
        pendingOutputResize = false
        mpv_set_property_string(handle, "vo", "gpu-next")
        // A node array preserves commas in Cookie/Referer values and cannot turn
        // a header value into another header or an mpv option.
        let headers = request.headers.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }
        guard headers.allSatisfy({ !$0.contains("\r") && !$0.contains("\n") && !$0.contains("\0") }) else {
            emit(.failed(MPV_ERROR_INVALID_PARAMETER.rawValue, "headers")); return
        }
        let strings = headers.map { strdup($0)! }
        defer { strings.forEach { free($0) } }
        var nodes = strings.map { pointer -> mpv_node in
            var node = mpv_node(); node.format = MPV_FORMAT_STRING; node.u.string = pointer; return node
        }
        let headerStatus = nodes.withUnsafeMutableBufferPointer { buffer -> Int32 in
            var list = mpv_node_list(); list.num = Int32(buffer.count); list.values = buffer.baseAddress
            return withUnsafeMutablePointer(to: &list) { pointer in
                var node = mpv_node(); node.format = MPV_FORMAT_NODE_ARRAY; node.u.list = pointer
                return mpv_set_property(handle, "http-header-fields", MPV_FORMAT_NODE, &node)
            }
        }
        guard headerStatus >= 0 else { emit(.failed(headerStatus, "headers")); return }
        let userAgent = request.headers.first { $0.key.lowercased() == "user-agent" }?.value ?? "PlayBridge"
        mpv_set_property_string(handle, "user-agent", userAgent)
        mpv_set_property_string(handle, "pause", desiredPaused || background ? "yes" : "no")
        mpv_set_property_string(handle, "sid", "no")
        // mpv 0.38+ places per-file options AFTER the insertion index. Supply
        // resume as a file option rather than a best-effort global property.
        let status = command(["loadfile", request.url.absoluteString, "replace", "-1", "start=\(request.resume)"])
        if status < 0 { emit(.failed(status, "loadfile")) }
    }

    func resize() {
        queue.async { [self] in
            guard !closed, !background, loaded, !pendingOutputResize, let handle else { return }
            // The embedded iOS VO doesn't receive window resize events. Merely
            // resizing CAMetalLayer leaves mpv drawing with its old viewport.
            // Recreate only the video output; keep the decoder, clock and pause
            // choice. Restore after current-vo confirms the old output is gone.
            let status = mpv_set_property_string(handle, "vo", "null")
            if status >= 0 { pendingOutputResize = true }
            else { emit(.failed(status, "resize video output")) }
        }
    }

    func setPaused(_ paused: Bool) {
        queue.async { [self] in
            guard !closed else { return }
            desiredPaused = paused
            state.paused = paused || background
            if let handle { mpv_set_property_string(handle, "pause", paused || background ? "yes" : "no") }
            emit(.state(state))
        }
    }
    func seek(to seconds: Double) {
        guard seconds.isFinite else { return }
        queue.async { [self] in
            guard !closed else { return }
            let position = max(0, seconds)
            state.position = position
            if loaded { _ = command(["seek", String(position), "absolute+exact"]) }
            else { pendingSeek = position }
            emit(.state(state))
        }
    }
    func setProperty(_ name: String, value: String) {
        queue.async { [self] in guard !closed, let handle else { return }; mpv_set_property_string(handle, name, value) }
    }
    func setBackground(_ value: Bool) {
        queue.async { [self] in
            guard !closed, background != value else { return }
            background = value
            state.paused = value || desiredPaused
            // Metal cannot draw while backgrounded. Pause and detach decoding;
            // preserve the user's pause choice when the app returns.
            if let handle {
                mpv_set_property_string(handle, "pause", value || desiredPaused ? "yes" : "no")
                mpv_set_property_string(handle, "vid", value ? "no" : "auto")
            }
            emit(.state(state))
        }
    }

    private func poll() {
        guard !closed, let handle else { return }
        if pendingOutputResize, !background, let pointer = mpv_get_property_string(handle, "current-vo") {
            let output = String(cString: pointer)
            mpv_free(pointer)
            if output == "null" {
                pendingOutputResize = false
                let status = mpv_set_property_string(handle, "vo", "gpu-next")
                if status < 0 { emit(.failed(status, "restore video output")) }
            }
        }
        var streamFailure: Int32?
        while let event = mpv_wait_event(handle, 0), event.pointee.event_id != MPV_EVENT_NONE {
            switch event.pointee.event_id {
            case MPV_EVENT_LOG_MESSAGE:
                if let data = event.pointee.data,
                   let message = data.assumingMemoryBound(to: mpv_event_log_message.self).pointee.text,
                   let issue = PhonePlaybackNetworkIssue.classify(String(cString: message)) {
                    // A later generic TLS error must not hide certificate evidence.
                    if networkIssue != .certificate { networkIssue = issue }
                }
            case MPV_EVENT_START_FILE:
                if let data = event.pointee.data { entryID = data.assumingMemoryBound(to: mpv_event_start_file.self).pointee.playlist_entry_id }
            case MPV_EVENT_FILE_LOADED:
                loaded = true
                if let pendingSeek {
                    _ = command(["seek", String(pendingSeek), "absolute+exact"])
                    self.pendingSeek = nil
                }
            case MPV_EVENT_END_FILE:
                guard let data = event.pointee.data else { continue }
                let end = data.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                guard end.playlist_entry_id == entryID else { continue }
                if end.reason == MPV_END_FILE_REASON_EOF {
                    updateState(); emit(.state(state)); loaded = false; emit(.ended)
                } else if end.reason == MPV_END_FILE_REASON_ERROR {
                    loaded = false; state.paused = true; streamFailure = end.error
                }
            default: break
            }
        }
        // FFmpeg's last warning can be queued after END_FILE. Drain it before
        // publishing the failure so copied diagnostics retain the actual cause.
        if let streamFailure { emit(.failed(streamFailure, "stream", networkIssue)) }
        if loaded { updateState(); emit(.state(state)) }
    }
    private func updateState() {
        guard let handle else { return }
        var position = 0.0; var duration = 0.0; var paused: Int32 = 1; var buffering: Int32 = 0
        if mpv_get_property(handle, "time-pos", MPV_FORMAT_DOUBLE, &position) >= 0, position.isFinite { state.position = max(0, position) }
        if mpv_get_property(handle, "duration", MPV_FORMAT_DOUBLE, &duration) >= 0, duration.isFinite { state.duration = max(0, duration) }
        mpv_get_property(handle, "pause", MPV_FORMAT_FLAG, &paused)
        mpv_get_property(handle, "paused-for-cache", MPV_FORMAT_FLAG, &buffering)
        state.paused = paused != 0
        state.buffering = buffering != 0
        state.speed = Double(propertyString("speed") ?? "") ?? 1
        state.selectedAudio = Int(propertyString("aid") ?? "")
        state.selectedSubtitle = Int(propertyString("sid") ?? "")
        if let json = propertyString("track-list"), let data = json.data(using: .utf8),
           let tracks = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] {
            func matching(_ type: String) -> [PhonePlaybackTrack] {
                tracks.compactMap { track in
                    guard track["type"] as? String == type, let id = track["id"] as? Int else { return nil }
                    let label = [track["title"] as? String, track["lang"] as? String, track["codec"] as? String]
                        .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
                    return PhonePlaybackTrack(id: id, label: label.isEmpty ? "Track \(id)" : label, language: track["lang"] as? String)
                }
            }
            state.audioTracks = matching("audio"); state.subtitleTracks = matching("sub")
        }
    }
    private func propertyString(_ name: String) -> String? {
        guard let handle, let value = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(value) }; return String(cString: value)
    }
    private func command(_ arguments: [String]) -> Int32 {
        guard let handle else { return MPV_ERROR_UNINITIALIZED.rawValue }
        let strings = arguments.map { strdup($0)! }
        defer { strings.forEach { free($0) } }
        var pointers: [UnsafePointer<CChar>?] = strings.map { UnsafePointer($0) } + [nil]
        return mpv_command(handle, &pointers)
    }
    private func emit(_ event: MPVPhoneEvent) {
        let token = generation
        Task { @MainActor [weak self] in
            guard let self, deliveryGeneration == token else { return }
            deliver?(event)
        }
    }
    func close() {
        queue.async { [self] in
            guard !closed else { return }
            closed = true; timer?.cancel(); timer = nil
            if let handle { mpv_terminate_destroy(handle); self.handle = nil }
            request = nil; surface = nil
        }
    }
}
#endif
