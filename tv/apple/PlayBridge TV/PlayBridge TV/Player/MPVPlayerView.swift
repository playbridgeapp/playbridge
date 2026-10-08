import SwiftUI
import AVFoundation
import AVKit
import Metal
import Libmpv

// MARK: - SwiftUI representable

struct MPVPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [String]?
    let initialTime: Double
    let mediaIdentity: Int
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void
    let onExit: () -> Void
    let onSwitch: (PlaybackEngine, Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void

    func makeUIViewController(context: Context) -> MPVViewController {
        let vc = MPVViewController()
        vc.url = url
        vc.headers = headers
        vc.subtitles = subtitles
        vc.initialTime = initialTime
        vc.mediaIdentity = mediaIdentity
        vc.isPreBuffering = isPreBuffering
        vc.mediaTitle = title
        vc.onDismiss = onDismiss
        vc.onExit = onExit
        vc.onSwitch = onSwitch
        vc.onBroadcast = onBroadcast
        return vc
    }

    func updateUIViewController(_ uiViewController: MPVViewController, context: Context) {
        uiViewController.isPreBuffering = isPreBuffering
        // Keep callbacks current (they're cheap value assignments).
        uiViewController.onDismiss = onDismiss
        uiViewController.onExit = onExit
        uiViewController.onSwitch = onSwitch
        uiViewController.onBroadcast = onBroadcast
        // Episode advance on the LIVE mpv core: the controller (and its initialised
        // handle, render layer, caches) is reused — `loadfile replace` swaps the
        // media. This is what makes back-to-back episodes start fast and gapless
        // instead of paying a full mpv re-init per item.
        if uiViewController.url != url || uiViewController.mediaIdentity != mediaIdentity {
            uiViewController.mediaIdentity = mediaIdentity
            uiViewController.url = url
            // Mark the request synchronously, but publish the per-item HUD reset only
            // after SwiftUI's update pass. The identity guard cancels superseded loads.
            DispatchQueue.main.async { [weak uiViewController] in
                guard let controller = uiViewController,
                      controller.mediaIdentity == mediaIdentity, controller.url == url else { return }
                controller.loadNewItem(
                    url: url,
                    headers: headers,
                    subtitles: subtitles,
                    initialTime: initialTime,
                    title: title
                )
            }
        }
    }

    /// Deterministic teardown. SwiftUI calls this when it removes the representable's
    /// controller — unlike `viewWillDisappear`, it's guaranteed to fire. Tearing mpv down
    /// here (while the controller is still alive) clears the wakeup callback before
    /// deallocation, so the callback can never run against a half-dead object.
    static func dismantleUIViewController(_ uiViewController: MPVViewController, coordinator: ()) {
        uiViewController.teardown()
    }
}

// MARK: - View Controller

class MPVViewController: UIViewController {

    // MARK: Configuration (set by representable before viewDidLoad)
    var url: URL?
    var headers: [String: String]?
    var subtitles: [String]?
    var initialTime: Double = 0.0
    var mediaIdentity = 0
    var mediaTitle: String?
    var onDismiss: (() -> Void)?
    var onExit: (() -> Void)?
    var onSwitch: ((PlaybackEngine, Double) -> Void)?
    var onBroadcast: (([String: Any]) -> Void)?
    private var statusTimer: Timer?
    var isPreBuffering: Bool = false {
        didSet { if isPreBuffering != oldValue { applyPreBufferingState() } }
    }

    // MARK: MPV
    private var mpv: OpaquePointer?
    private let mpvQueue = DispatchQueue(label: "mpv.playbridge.tvos", qos: .userInitiated)
    private var isMpvStopped = false
    private var pendingExternalSubtitles: [String] = []
    private var externalSubtitleCatalog = ExternalSubtitleCatalog(urls: [])
    private var lateSubtitleDownloads: [UUID: ScopedSubtitleDownload] = [:]
    private var lateSubtitleFiles: [URL] = []
    private var lateSubtitleSelectionID: UUID?
    /// Opaque pointer to a +1-retained `self` handed to mpv's wakeup callback. Keeping self
    /// alive while the callback is installed means the callback never forms a weak reference
    /// to a deallocating object. Balanced (released) once in `teardown()`.
    private var callbackSelfPtr: UnsafeMutableRawPointer?

    // MARK: Rendering — mpv gpu-next on Metal (MoltenVK)
    private let metalLayer = MPVMetalLayer()
    private var didSetupMPV = false
    /// Video decoding is detached while backgrounded; Metal cannot present then.
    private var videoDetachedForBackground = false

    // MARK: UI
    private var playbackState = PlayerControlsData()
    private var hostingController: UIHostingController<PlayerControlsOverlay>?
    private var hideControlsTimer: Timer?
    private var holdTimer: Timer?
    private var virtualScrubTickTimer: Timer?
    private var ignoreTimeUpdatesUntil: Date = .distantPast
    private var timelineUpdateGate = PlaybackUIUpdateGate()
    /// Seconds buffered ahead of the play head (from mpv `demuxer-cache-duration`). Main-thread only.
    private var cacheAheadSec: Double = 0
    /// When the current file started loading — used to log time-to-audio for diagnosing delays.
    private var loadStartTime = Date()

    // Custom focusable view that receives Siri Remote presses
    private class FocusableView: UIView {
        override var canBecomeFocused: Bool { true }
    }
    private let videoView = FocusableView()

    override var preferredFocusEnvironments: [UIFocusEnvironment] {
        if playbackState.userPaused, let hv = hostingController?.view { return [hv] }
        return [videoView]
    }

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        videoView.frame = view.bounds
        videoView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(videoView)

        metalLayer.device = MTLCreateSystemDefaultDevice()
        metalLayer.framebufferOnly = true
        metalLayer.backgroundColor = UIColor.black.cgColor
        updateRenderingSurface()
        videoView.layer.addSublayer(metalLayer)

        playbackState.title = mediaTitle ?? ""
        externalSubtitleCatalog = ExternalSubtitleCatalog(urls: subtitles ?? [])
        playbackState.externalSubtitleTracks = externalSubtitleCatalog.unloadedTracks(excluding: [])
        setupHUD()
        startRemoteSync()
        showUI(autoHide: true)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateRenderingSurface()
        // gpu-next sizes its swapchain from the layer, so start mpv once it has real bounds.
        if !didSetupMPV, !isMpvStopped, videoView.bounds.width > 1, videoView.bounds.height > 1 {
            didSetupMPV = true
            setupMPV()
        }
    }

    private func updateRenderingSurface() {
        let bounds = videoView.bounds
        guard bounds.width > 1, bounds.height > 1 else { return }
        let scale = view.window?.screen.scale ?? videoView.contentScaleFactor
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        metalLayer.frame = bounds
        metalLayer.contentsScale = scale
        metalLayer.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        CATransaction.commit()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Home Screen, system overlays and temporary presentations are not an exit.
        // SwiftUI's dismantle hook owns removal; only actual dismissal tears down here.
        if isBeingDismissed || isMovingFromParent || parent?.isBeingDismissed == true {
            teardown()
        }
    }

    @objc private func onApplicationBackground() {
        guard !isMpvStopped, !videoDetachedForBackground else { return }
        // Metal cannot present while backgrounded. Detach video decoding and restore it
        // on return; pause and audio state are untouched.
        videoDetachedForBackground = true
        setPropertyAsync("vid", value: "no")
    }

    @objc private func onApplicationActive() {
        guard !isMpvStopped else { return }
        configureAudioSession()
        updateRenderingSurface()
        if videoDetachedForBackground {
            videoDetachedForBackground = false
            setPropertyAsync("vid", value: "auto")
        }
    }

    deinit {
        teardown()
    }

    // MARK: - MPV Initialisation

    private func setupMPV() {
        guard !isMpvStopped else { return }
        // Configure the audio session BEFORE mpv initialises its audio unit. If the session is
        // still the default (non-playback) category when mpv's `ao` starts, audio is silent until
        // the route is renegotiated — the "no sound for the first ~minute" symptom.
        configureAudioSession()

        guard let handle = mpv_create() else {
            print("[MPV] mpv_create() failed")
            return
        }
        mpv = handle

        // gpu-next renders into the CAMetalLayer through MoltenVK; retained through teardown.
        var widVal = Int64(Int(bitPattern: Unmanaged.passUnretained(metalLayer).toOpaque()))
        let windowStatus = mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &widVal)
        guard windowStatus >= 0 else {
            initializationFailed(operation: "wid", code: windowStatus)
            return
        }
        // Same video output as the phone. target-colorspace-hint asks libplacebo to
        // output in the source's colorspace (PQ/HLG) once the display has switched to HDR.
        for (name, value) in [("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
                              ("target-colorspace-hint", "yes")] {
            let status = mpv_set_option_string(handle, name, value)
            guard status >= 0 else {
                initializationFailed(operation: name, code: status)
                return
            }
        }

        // VideoToolbox hardware decoding, with software fallback enabled. Fallback is required for
        // codecs Apple TV has no HW decoder for — notably AV1 (no shipping Apple TV decodes AV1 in
        // hardware); without it those files play audio-only with a black screen. The decoder mpv
        // actually selects is logged via the "hwdec-current" observer below, so a drop to software
        // (e.g. on a 4K HEVC file VideoToolbox bails on) is visible rather than silent.
        mpv_set_option_string(handle, "hwdec", "videotoolbox")
        // Only the codecs Apple TV actually has hardware decoders for. AV1 is deliberately
        // excluded: no Apple TV decodes AV1 in hardware, and leaving it in this list makes mpv
        // pick FFmpeg's *native* av1 decoder (the only one with a VideoToolbox hwaccel path) to
        // attempt HW. When VideoToolbox then rejects AV1, that same native decoder limps along on
        // its slow software path — overriding the `vd=libdav1d` preference below. Dropping av1
        // here means av1 is never HW-selected, so the fast dav1d software decoder wins instead.
        mpv_set_option_string(handle, "hwdec-codecs", "h264,hevc,vp9")
        mpv_set_option_string(handle, "hwdec-software-fallback", "yes")

        // Use dav1d for AV1, a threaded software decoder. This only
        // takes effect now that av1 is out of hwdec-codecs (above). dav1d decodes only AV1, so it
        // has no effect on H.264/HEVC, which keep their VideoToolbox hardware path.
        mpv_set_option_string(handle, "vd", "libdav1d")

        // Allow late-frame dropping at both decoder and output when mpv can detect
        // lateness. This is best-effort load relief, not a guarantee of real-time AV1
        // on the tested A15 Apple TV. In particular, video-only playback may not
        // increment decoder-drop counters even when the pipeline cannot keep up.
        mpv_set_option_string(handle, "framedrop", "decoder+vo")

        // Buffering for high-bitrate remuxes (4K HEVC, ~80–100 Mbps). mpv's defaults read only
        // ~1s ahead, so a momentary network/NAS shortfall drains the buffer and audio/video drop
        // out. Widen the demuxer read-ahead (bounded to
        // 256 MiB so tvOS doesn't jetsam-kill us on a 56 GB file) and grow the audio output buffer
        // from its ~200ms default to 1s so a brief audio-decode/scheduling stall can't underrun.
        mpv_set_option_string(handle, "cache", "yes")
        mpv_set_option_string(handle, "demuxer-max-bytes", "256MiB")
        mpv_set_option_string(handle, "demuxer-max-back-bytes", "64MiB")
        mpv_set_option_string(handle, "demuxer-readahead-secs", "30")
        mpv_set_option_string(handle, "audio-buffer", "1.0")

        // Prefer AVFoundation audio (as on the phone): it avoids AudioUnit's channel-layout
        // query that fails on some HDMI routes. AudioUnit remains the fallback. Check option
        // acceptance; actual output initialization is reported by current-ao.
        let audioOutputStatus = mpv_set_option_string(handle, "ao", "avfoundation,audiounit")
        guard audioOutputStatus >= 0 else {
            initializationFailed(operation: "ao", code: audioOutputStatus)
            return
        }

        // Subtitle defaults
        mpv_set_option_string(handle, "sub-scale-with-window", "no")
        mpv_set_option_string(handle, "sub-use-margins", "no")
        mpv_set_option_string(handle, "subs-match-os-language", "yes")
        mpv_set_option_string(handle, "subs-fallback", "yes")

        #if DEBUG
        // Verbose scaler/frame logs are delivered even when handleEvent discards them.
        // Keep Debug playback lightweight; errors and warnings remain available.
        mpv_request_log_messages(handle, "warn")
        #else
        mpv_request_log_messages(handle, "no")
        #endif

        let initializeStatus = mpv_initialize(handle)
        guard initializeStatus >= 0 else {
            initializationFailed(operation: "mpv_initialize", code: initializeStatus)
            return
        }

        mpv_observe_property(handle, 0, "duration",         MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "time-pos",         MPV_FORMAT_DOUBLE)
        mpv_observe_property(handle, 0, "pause",            MPV_FORMAT_FLAG)
        mpv_observe_property(handle, 0, "demuxer-cache-duration", MPV_FORMAT_DOUBLE)
        // Observe the whole list (FORMAT_NONE = notify-only): mpv reliably fires this when
        // tracks are discovered or a sub is added, unlike the indexed "track-list/count".
        mpv_observe_property(handle, 0, "track-list", MPV_FORMAT_NONE)
        mpv_observe_property(handle, 0, "paused-for-cache", MPV_FORMAT_FLAG)
        // Re-assert the audio session the moment mpv's audio output actually comes up.
        mpv_observe_property(handle, 0, "current-ao", MPV_FORMAT_STRING)
        // Which decoder mpv actually selected ("videotoolbox" = HW, "no"/empty = software).
        mpv_observe_property(handle, 0, "hwdec-current", MPV_FORMAT_STRING)
        // Decoded colorimetry only exists once the first frame is out, well after
        // FILE_LOADED, so HDR display switching waits for this instead.
        mpv_observe_property(handle, 0, "video-params/gamma", MPV_FORMAT_STRING)

        // Retain self for the callback's lifetime (released in teardown()). The callback fires
        // on mpv's own thread, so self must outlive the installed callback.
        let selfPtr = Unmanaged.passRetained(self).toOpaque()
        callbackSelfPtr = selfPtr
        mpv_set_wakeup_callback(handle, { ctx in
            guard let ctx else { return }
            Unmanaged<MPVViewController>.fromOpaque(ctx).takeUnretainedValue().drainEvents()
        }, selfPtr)

        if let url { loadFile(url) }
    }

    private func initializationFailed(operation: String, code: Int32) {
        print("[MPV] initialization failed at \(operation): \(code)")
        // Do not leave a failed renderer on a black player screen or advance the queue.
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isMpvStopped else { return }
            self.teardown()
            self.onExit?()
        }
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            // Match the working AVPlayer and local phone playback policy. Long-form
            // audio routing is not required for movie playback on the TV.
            try session.setCategory(.playback, mode: .moviePlayback, policy: .default, options: [])
            try session.setActive(true)
            #if DEBUG
            logAudioSession(reason: "configured")
            #endif
        } catch {
            print("[MPV] audio session config failed: \(error)")
        }
    }

    private func activateAudioSession() {
        guard !isMpvStopped else { return }
        do {
            try AVAudioSession.sharedInstance().setActive(true)
            #if DEBUG
            logAudioSession(reason: "activated")
            #endif
        } catch {
            print("[MPV] audio session activation failed: \(error)")
        }
    }

    #if DEBUG
    private func logAudioSession(reason: String) {
        let session = AVAudioSession.sharedInstance()
        // Port types only: never include device names, IDs, URLs or credentials.
        let ports = session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ",")
        print("[MPV audio-session] reason=\(reason) category=\(session.category.rawValue) "
              + "mode=\(session.mode.rawValue) policy=\(session.routeSharingPolicy.rawValue) "
              + "ports=\(ports) sampleRate=\(session.sampleRate) "
              + "outputChannels=\(session.outputNumberOfChannels) "
              + "maxOutputChannels=\(session.maximumOutputNumberOfChannels) "
              + "outputLatency=\(session.outputLatency) ioBufferSeconds=\(session.ioBufferDuration) "
              + "systemVolume=\(session.outputVolume) preplay=\(isPreBuffering)")
    }
    #endif

    /// Swap in the next item on the live mpv core (episode advance). All per-item
    /// state is reset; the handle, render context, and demuxer caches survive.
    func loadNewItem(url: URL, headers: [String: String]?, subtitles: [String]?,
                     initialTime: Double, title: String?) {
        guard !isMpvStopped else { return }
        lateSubtitleDownloads.values.forEach { $0.cancel() }
        lateSubtitleDownloads.removeAll()
        lateSubtitleSelectionID = nil
        let oldSubtitleFiles = lateSubtitleFiles
        lateSubtitleFiles.removeAll()
        debugLogNetworkRequest("MPV playback", url: url, headers: headers)
        self.url = url
        self.headers = headers
        self.subtitles = subtitles
        self.initialTime = initialTime
        self.mediaTitle = title

        // Per-item resets (mirrors what a fresh controller would start with).
        didApplyTrackPreferences = false
        ignoreTimeUpdatesUntil = .distantPast
        playbackState.title = title ?? ""
        playbackState.currentTime = 0
        playbackState.duration = 0
        playbackState.userPaused = false
        playbackState.audioTracks = []
        playbackState.subtitleTracks = []
        externalSubtitleCatalog = ExternalSubtitleCatalog(urls: subtitles ?? [])
        playbackState.externalSubtitleTracks = externalSubtitleCatalog.unloadedTracks(excluding: [])

        loadFile(url)
        mpvQueue.async {
            oldSubtitleFiles.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    private func loadFile(_ url: URL) {
        guard mpv != nil else { return }   // re-bound to the live handle inside mpvQueue below
        pendingExternalSubtitles = subtitles ?? []
        timelineUpdateGate.reset()
        // Clear buffered-ahead state so a looped/next file doesn't flash the prior buffer.
        cacheAheadSec = 0
        playbackState.bufferedTime = 0
        loadStartTime = Date()
        configureAudioSession()
        let path = url.isFileURL ? url.path : url.absoluteString
        let loadArguments = MPVAudioPolicy.loadArguments(path: path, isPreBuffering: isPreBuffering)

        mpvQueue.async { [weak self] in
            guard let self, let handle = self.mpv else { return }

            // User-Agent must go via the dedicated "user-agent" option, not http-header-fields.
            // Fall back to a browser UA — some servers reject mpv's default "Lavf/..." agent.
            let ua = self.headers?
                .first(where: { $0.key.caseInsensitiveCompare("user-agent") == .orderedSame })?
                .value ?? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            mpv_set_property_string(handle, "user-agent", ua)

            self.setHTTPHeaders(self.headers, on: handle)

            if self.initialTime > 0 {
                mpv_set_property_string(handle, "start", String(format: "%.2f", self.initialTime))
            } else {
                // The handle persists across episodes now — clear a previous item's
                // resume point or the next file would start there too.
                mpv_set_property_string(handle, "start", "none")
            }
            self.mpvCommand(handle, loadArguments)
        }
    }

    // MARK: - Event Loop

    private func drainEvents() {
        mpvQueue.async { [weak self] in
            guard let self else { return }
            while true {
                guard let handle = self.mpv,
                      let evPtr = mpv_wait_event(handle, 0) else { break }
                let event = evPtr.pointee
                if event.event_id == MPV_EVENT_NONE { break }
                self.handleEvent(event)
                if event.event_id == MPV_EVENT_SHUTDOWN { break }
            }
        }
    }

    private func handleEvent(_ event: mpv_event) {
        switch event.event_id {
        case MPV_EVENT_FILE_LOADED:
            onFileLoaded()

        case MPV_EVENT_END_FILE:
            // The handle is reused across episodes: our own `loadfile replace`
            // (advance/loop) also emits END_FILE, with reason STOP/REDIRECT.
            // Only a natural EOF or a hard error may advance the playlist —
            // otherwise the replace that *performs* an advance would immediately
            // trigger another one and skip episodes.
            if let efPtr = event.data?.assumingMemoryBound(to: mpv_event_end_file.self) {
                let reason = efPtr.pointee.reason
                guard reason == MPV_END_FILE_REASON_EOF || reason == MPV_END_FILE_REASON_ERROR else {
                    break
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped else { return }
                if self.playbackState.isLooping {
                    if let url = self.url { self.loadFile(url) }
                } else {
                    self.onDismiss?()
                }
            }

        case MPV_EVENT_PROPERTY_CHANGE:
            guard let propPtr = event.data?.assumingMemoryBound(to: mpv_event_property.self),
                  let nameCStr = propPtr.pointee.name else { break }
            handlePropertyChange(name: String(cString: nameCStr), prop: propPtr.pointee)

        case MPV_EVENT_LOG_MESSAGE:
            #if DEBUG
            if let logPtr = event.data?.assumingMemoryBound(to: mpv_event_log_message.self) {
                let text = String(cString: logPtr.pointee.text)
                let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                // Drop the per-frame software-scaler flood so cache/audio events stay readable.
                if !trimmed.isEmpty, !trimmed.contains("swscaler") {
                    print("[MPV] \(text)", terminator: "")
                }
            }
            #endif

        default:
            break
        }
    }

    private func onFileLoaded() {
        guard let handle = mpv else { return }

        // Reconcile mute with current main-thread state even if preplay ended while
        // the asynchronous load was in flight. Reused cores must clear mute too.
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isMpvStopped else { return }
            self.setPropertyAsync("mute", value: MPVAudioPolicy.muteValue(isPreBuffering: self.isPreBuffering))
            self.activateAudioSession()
        }

        // Surface embedded audio/subtitle tracks right away. (mpvQueue context; updateTracks
        // hops its UI assignment to main.)
        updateTracks()

        // Attach only the sender's first/preferred external subtitle. Others remain in
        // the picker and load only on selection, avoiding many simultaneous fetches.
        if let firstSub = pendingExternalSubtitles.first {
            mpvCommandAsync(handle, ["sub-add", firstSub, "auto"])
        }
        pendingExternalSubtitles = []
    }

    private func handlePropertyChange(name: String, prop: mpv_event_property) {
        switch name {
        case "time-pos":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee else { break }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, Date() > self.ignoreTimeUpdatesUntil,
                      self.timelineUpdateGate.shouldUpdate(at: CACurrentMediaTime()) else { return }
                // UI progress does not need a SwiftUI redraw for every decoded frame.
                self.playbackState.currentTime = val
                self.playbackState.bufferedTime = val + self.cacheAheadSec
            }

        case "video-params/gamma":
            guard prop.format == MPV_FORMAT_STRING,
                  let cString = prop.data?.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee,
                  let handle = mpv else { break }
            if !String(cString: cString).isEmpty { detectAndApplyHDR(handle: handle) }

        case "demuxer-cache-duration":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee else { break }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped else { return }
                // Store every cache sample, but publish the buffer bar with the throttled
                // time-pos update instead of triggering another per-frame HUD redraw.
                self.cacheAheadSec = val
            }

        case "paused-for-cache":
            // The decode-vs-network discriminator. If this fires "STALLED" repeatedly during
            // playback, mpv is starved for data (network-bound — the proxy hypothesis). If it
            // never fires but playback is still choppy/desynced, the demuxer is keeping up and
            // the bottleneck is decode (4K AV1 software — a hardware limit the proxy can't fix).
            guard prop.format == MPV_FORMAT_FLAG,
                  let val = prop.data?.assumingMemoryBound(to: Int32.self).pointee else { break }
            let stalled = val != 0
            var cacheDur: Double = 0
            if let handle = mpv {
                mpv_get_property(handle, "demuxer-cache-duration", MPV_FORMAT_DOUBLE, &cacheDur)
            }
            print("[MPV] paused-for-cache: \(stalled ? "STALLED (network can't keep up)" : "resumed") — demuxer cache ahead = \(String(format: "%.1f", cacheDur))s")
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                NotificationCenter.default.post(
                    name: .playBridgePlaybackActivity, object: nil,
                    userInfo: ["isPlaying": self.playbackState.isPlaying && !stalled])
            }

        case "duration":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee,
                  val > 0 else { break }
            DispatchQueue.main.async { [weak self] in self?.playbackState.duration = val }

        case "pause":
            guard prop.format == MPV_FORMAT_FLAG,
                  let val = prop.data?.assumingMemoryBound(to: Int32.self).pointee else { break }
            let isPaused = val != 0
            DispatchQueue.main.async { [weak self] in
                self?.playbackState.isPlaying = !isPaused
                self?.broadcastStatus()
                NotificationCenter.default.post(
                    name: .playBridgePlaybackActivity, object: nil,
                    userInfo: ["isPlaying": !isPaused])
            }

        case "current-ao":
            // mpv's audio output just came up — make sure our session is active so it isn't muted.
            if let handle = mpv {
                let ao = stringProperty(handle, "current-ao") ?? "nil"
                let elapsed = String(format: "%.1f", Date().timeIntervalSince(loadStartTime))
                print("[MPV] audio output=\(ao) at +\(elapsed)s")
            }
            DispatchQueue.main.async { [weak self] in self?.activateAudioSession() }

        case "hwdec-current":
            // Reports the decoder mpv settled on. With software fallback off this should read
            // "videotoolbox"; "no"/empty here means the file isn't HW-decodable on this device.
            if let handle = mpv {
                let dec = stringProperty(handle, "hwdec-current") ?? "nil"
                let elapsed = String(format: "%.1f", Date().timeIntervalSince(loadStartTime))
                print("[MPV] hwdec-current: \(dec) at +\(elapsed)s")
            }

        case "track-list":
            updateTracks()  // already on mpvQueue (event loop); hops UI assignment to main

        default:
            break
        }
    }

    // MARK: - Track Selection

    /// One-shot guard so session track preferences are applied once per item; after that
    /// the user's live changes win.
    private var didApplyTrackPreferences = false

    /// Remember an audio pick (by display name) so the next episode — a fresh mpv
    /// instance — re-applies it. Call on main (reads playbackState).
    private func recordAudioPreference(id: Int) {
        if let t = playbackState.audioTracks.first(where: { $0.id == id }) {
            TrackPreferences.shared.audioName = t.name
        }
    }

    /// Remember a subtitle pick or an explicit "off" (see above).
    private func recordSubtitlePreference(id: Int) {
        let prefs = TrackPreferences.shared
        if id < 0 {
            prefs.subtitlesOff = true
            prefs.subtitleName = nil
        } else if let t = playbackState.subtitleTracks.first(where: { $0.id == id }) {
            prefs.subtitlesOff = false
            prefs.subtitleName = t.name
        }
    }

    /// Enumerate tracks. MUST be called on `mpvQueue` (never the main thread): synchronous
    /// mpv_* calls wait on mpv's core lock and would stall the UI.
    private func updateTracks() {
        dispatchPrecondition(condition: .notOnQueue(.main))
        guard let handle = mpv else { return }
        let itemIdentity = mediaIdentity

        var count: Int64 = 0
        mpv_get_property(handle, "track-list/count", MPV_FORMAT_INT64, &count)

        var audioTracks: [(id: Int, name: String)] = []
        var subtitleTracks: [(id: Int, name: String)] = []
        var loadedExternalURLs = Set<String>()

        for i in 0..<count {
            let prefix = "track-list/\(i)"
            guard let type = stringProperty(handle, "\(prefix)/type") else { continue }

            var trackId: Int64 = 0
            mpv_get_property(handle, "\(prefix)/id", MPV_FORMAT_INT64, &trackId)

            let rawTitle = stringProperty(handle, "\(prefix)/title")
            let lang = stringProperty(handle, "\(prefix)/lang")
            let displayName: String
            if let t = rawTitle, !t.isEmpty {
                displayName = t
            } else if let l = lang, !l.isEmpty {
                displayName = Locale.current.localizedString(forLanguageCode: l) ?? l
            } else {
                displayName = "Track \(trackId)"
            }

            switch type {
            case "audio": audioTracks.append((id: Int(trackId), name: displayName))
            case "sub":
                subtitleTracks.append((id: Int(trackId), name: displayName))
                if let filename = stringProperty(handle, "\(prefix)/external-filename") {
                    loadedExternalURLs.insert(filename)
                }
            default:      break
            }
        }

        var currentAid: Int64 = 0
        var currentSid: Int64 = 0
        mpv_get_property(handle, "aid", MPV_FORMAT_INT64, &currentAid)
        mpv_get_property(handle, "sid", MPV_FORMAT_INT64, &currentSid)

        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isMpvStopped, self.mediaIdentity == itemIdentity else { return }
            self.playbackState.audioTracks = audioTracks
            self.playbackState.subtitleTracks = subtitleTracks
            self.playbackState.externalSubtitleTracks = self.externalSubtitleCatalog.unloadedTracks(excluding: loadedExternalURLs)
            self.playbackState.currentAudioIndex = Int(currentAid)
            self.playbackState.currentSubtitleIndex = Int(currentSid)

            // Carry the session's track picks (made on a previous episode's instance)
            // into this item, once, as soon as tracks are known.
            if !self.didApplyTrackPreferences && !audioTracks.isEmpty {
                self.didApplyTrackPreferences = true
                let prefs = TrackPreferences.shared
                if let name = prefs.audioName,
                   let t = audioTracks.first(where: { $0.name == name }),
                   t.id != Int(currentAid) {
                    self.setPropertyAsync("aid", value: String(t.id))
                    self.playbackState.currentAudioIndex = t.id
                }
                if prefs.subtitlesOff {
                    if currentSid > 0 {
                        self.setPropertyAsync("sid", value: "no")
                        self.playbackState.currentSubtitleIndex = -1
                    }
                } else if let name = prefs.subtitleName,
                          let t = subtitleTracks.first(where: { $0.name == name }),
                          t.id != Int(currentSid) {
                    self.setPropertyAsync("sid", value: String(t.id))
                    self.playbackState.currentSubtitleIndex = t.id
                }
            }

            self.broadcastTracks()
        }
    }

    // MARK: - HDR (tvOS display criteria)

    private enum HDRMode { case sdr, hdr10, hlg }

    private func detectAndApplyHDR(handle: OpaquePointer) {
        let primaries = stringProperty(handle, "video-params/primaries")
        let gamma     = stringProperty(handle, "video-params/gamma")

        var fps: Double = 24.0
        mpv_get_property(handle, "container-fps", MPV_FORMAT_DOUBLE, &fps)
        if fps <= 0 { fps = 24.0 }

        let mode: HDRMode
        if primaries == "bt.2020" || primaries == "bt.2020-ncl" {
            mode = (gamma == "hlg") ? .hlg : .hdr10
        } else {
            mode = .sdr
        }

        DispatchQueue.main.async { [weak self] in
            self?.applyDisplayCriteria(mode, fps: Float(fps))
        }
        // HDR check: once frames flow, report what mpv decoded and what it outputs.
        mpvQueue.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, let handle = self.mpv, !self.isMpvStopped else { return }
            func params(_ prefix: String) -> String {
                ["primaries", "gamma"].map { self.stringProperty(handle, "\(prefix)/\($0)") ?? "?" }
                    .joined(separator: "/")
            }
            print("[MPV] video output=\(self.stringProperty(handle, "current-vo") ?? "nil") "
                  + "source=\(params("video-params")) target=\(params("video-target-params")) display=\(mode)")
        }
    }

    private func applyDisplayCriteria(_ mode: HDRMode, fps: Float) {
        guard #available(tvOS 17.0, *), let window = view.window else { return }
        let manager = window.avDisplayManager

        guard mode != .sdr else {
            manager.preferredDisplayCriteria = nil
            return
        }

        var ext: [String: Any] = [kCMFormatDescriptionExtension_FullRangeVideo as String: true]
        switch mode {
        case .hdr10:
            ext[kCMFormatDescriptionExtension_ColorPrimaries as String] = kCMFormatDescriptionColorPrimaries_ITU_R_2020
            ext[kCMFormatDescriptionExtension_TransferFunction as String] = kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ
            ext[kCMFormatDescriptionExtension_YCbCrMatrix as String] = kCMFormatDescriptionYCbCrMatrix_ITU_R_2020
        case .hlg:
            ext[kCMFormatDescriptionExtension_ColorPrimaries as String] = kCMFormatDescriptionColorPrimaries_ITU_R_2020
            ext[kCMFormatDescriptionExtension_TransferFunction as String] = kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG
            ext[kCMFormatDescriptionExtension_YCbCrMatrix as String] = kCMFormatDescriptionYCbCrMatrix_ITU_R_2020
        case .sdr:
            break
        }

        var formatDesc: CMFormatDescription?
        let status = CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: kCMVideoCodecType_HEVC,
            width: 3840, height: 2160,
            extensions: ext as CFDictionary,
            formatDescriptionOut: &formatDesc
        )
        guard status == noErr, let desc = formatDesc else { return }
        manager.preferredDisplayCriteria = AVDisplayCriteria(refreshRate: fps, formatDescription: desc)
    }

    private func resetDisplayCriteria() {
        guard #available(tvOS 17.0, *) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.view.window?.avDisplayManager.preferredDisplayCriteria = nil
        }
    }

    // MARK: - HUD

    private func setupHUD() {
        let overlay = PlayerControlsOverlay(
            data: playbackState,
            onSelectSubtitle: { [weak self] trackId in
                guard let self else { return }
                if let option = self.externalSubtitleCatalog.option(for: trackId) {
                    // `cached` selects an existing file if the user taps again while loading.
                    // The track-list observer replaces this menu entry once loading succeeds.
                    self.mpvQueue.async { [weak self] in
                        guard let self, let handle = self.mpv else { return }
                        self.mpvCommandAsync(handle, ["sub-add", option.url, "cached", option.name])
                    }
                    TrackPreferences.shared.subtitlesOff = false
                    TrackPreferences.shared.subtitleName = option.name
                    return
                }
                self.setPropertyAsync("sid", value: trackId < 0 ? "no" : String(trackId))
                self.playbackState.currentSubtitleIndex = trackId
                self.recordSubtitlePreference(id: trackId)
            },
            onSelectAudio: { [weak self] trackId in
                guard let self else { return }
                self.setPropertyAsync("aid", value: String(trackId))
                self.playbackState.currentAudioIndex = trackId
                self.recordAudioPreference(id: trackId)
            },
            onTogglePlayPause: { [weak self] in self?.togglePlayPause() },
            onSwitchEngine: { [weak self] target in
                guard let self else { return }
                self.onSwitch?(target, self.playbackState.currentTime)
            },
            onTogglePlaylist: {
                NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
            },
            engine: .mpv
        )
        let hosting = UIHostingController(rootView: overlay)
        hosting.view.backgroundColor = .clear
        hosting.view.frame = view.bounds
        hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addChild(hosting)
        view.addSubview(hosting.view)
        hosting.didMove(toParent: self)
        hostingController = hosting
    }

    private func applyPreBufferingState() {
        setPropertyAsync("mute", value: MPVAudioPolicy.muteValue(isPreBuffering: isPreBuffering))
        if isPreBuffering {
            playbackState.showUI = false
            hideControlsTimer?.invalidate()
        } else {
            activateAudioSession()
            showUI(autoHide: true)
            NotificationCenter.default.post(
                name: .playBridgePlaybackActivity, object: nil,
                userInfo: ["isPlaying": !playbackState.userPaused])
        }
    }

    private func showUI(autoHide: Bool = true) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.playbackState.showUI = true
            self.setNeedsFocusUpdate()
            self.hideControlsTimer?.invalidate()
            guard autoHide, !self.playbackState.isVirtualScrubbing else { return }
            self.hideControlsTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { [weak self] _ in
                DispatchQueue.main.async {
                    withAnimation {
                        self?.playbackState.showUI = false
                        self?.setNeedsFocusUpdate()
                    }
                }
            }
        }
    }

    // MARK: - Playback Controls

    private func togglePlayPause() {
        // Use cached state (kept current by the "pause" property observer) instead of querying
        // mpv synchronously, which would block the main thread on mpv's core lock.
        setPlaybackPaused(playbackState.isPlaying)
    }

    private func setPlaybackPaused(_ paused: Bool) {
        setPropertyAsync("pause", value: paused ? "yes" : "no")
        playbackState.userPaused = paused
        if paused {
            mpvQueue.async { [weak self] in self?.updateTracks() }
            showUI(autoHide: false)
        } else {
            showUI(autoHide: true)
        }
    }

    private func skipForward() {
        guard !playbackState.userPaused else { return }
        let cap = playbackState.duration > 0 ? playbackState.duration - 2.0 : Double.infinity
        let target = min(playbackState.currentTime + 15.0, cap)
        playbackState.currentTime = target
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        seekAsync(to: target)
        showUI()
    }

    private func skipBackward() {
        guard !playbackState.userPaused else { return }
        let target = max(0, playbackState.currentTime - 15.0)
        playbackState.currentTime = target
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        seekAsync(to: target)
        showUI()
    }

    private func seekAsync(to seconds: Double) {
        guard seconds.isFinite, let handle = mpv else { return }
        let pos = String(format: "%.2f", max(0, seconds))
        mpvQueue.async { self.mpvCommand(handle, ["seek", pos, "absolute"]) }
    }

    // MARK: - Virtual Scrubbing

    private func startVirtualScrub(forward: Bool) {
        playbackState.isVirtualScrubbing = true
        playbackState.virtualTime = playbackState.currentTime
        setPropertyAsync("pause", value: "yes")
        showUI(autoHide: false)
        increaseScrubMultiplier(forward)

        virtualScrubTickTimer?.invalidate()
        virtualScrubTickTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async {
                let delta = Double(self.playbackState.scrubMultiplier) * 12.0 * 0.05
                var next = self.playbackState.virtualTime + delta
                if self.playbackState.duration > 0 {
                    next = max(0, min(next, self.playbackState.duration))
                } else {
                    next = max(0, next)
                }
                self.playbackState.virtualTime = next
            }
        }
    }

    private func increaseScrubMultiplier(_ forward: Bool) {
        let dir = forward ? 1 : -1
        if playbackState.scrubMultiplier == 0 {
            playbackState.scrubMultiplier = dir
        } else if (playbackState.scrubMultiplier > 0) == forward {
            playbackState.scrubMultiplier = min(abs(playbackState.scrubMultiplier) + 1, 8) * dir
        } else {
            playbackState.scrubMultiplier = dir
        }
    }

    private func commitVirtualScrub() {
        virtualScrubTickTimer?.invalidate()
        virtualScrubTickTimer = nil

        let target = playbackState.virtualTime
        playbackState.currentTime = target
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        playbackState.isVirtualScrubbing = false
        playbackState.scrubMultiplier = 0

        seekAsync(to: target)
        setPropertyAsync("pause", value: "no")
        playbackState.userPaused = false
        showUI(autoHide: true)
    }

    // MARK: - Siri Remote

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if StillWatchingGate.isPrompting {
            NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
            return
        }
        if isPreBuffering { super.pressesBegan(presses, with: event); return }
        guard let type = presses.first?.type else { super.pressesBegan(presses, with: event); return }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)

        if type == .menu {
            if playbackState.showSubtitleMenu  { playbackState.showSubtitleMenu = false; return }
            if playbackState.showAudioMenu     { playbackState.showAudioMenu = false; return }
            if playbackState.showEngineMenu    { playbackState.showEngineMenu = false; return }
            if playbackState.isVirtualScrubbing {
                virtualScrubTickTimer?.invalidate()
                virtualScrubTickTimer = nil
                playbackState.isVirtualScrubbing = false
                playbackState.scrubMultiplier = 0
                setPropertyAsync("pause", value: "no")
                playbackState.userPaused = false
                showUI(autoHide: true)
                return
            }
            broadcastStatus()
            onExit?()
            return
        }

        if playbackState.showSubtitleMenu || playbackState.showAudioMenu || playbackState.showEngineMenu {
            super.pressesBegan(presses, with: event); return
        }

        switch type {
        case .playPause, .select:
            if playbackState.isVirtualScrubbing {
                commitVirtualScrub()
            } else if !playbackState.userPaused {
                togglePlayPause()
            } else {
                super.pressesBegan(presses, with: event)
            }

        case .leftArrow, .rightArrow:
            let forward = type == .rightArrow
            if playbackState.isVirtualScrubbing {
                showUI(); increaseScrubMultiplier(forward)
            } else if !playbackState.userPaused {
                showUI()
                holdTimer?.invalidate()
                holdTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: false) { [weak self] _ in
                    self?.startVirtualScrub(forward: forward)
                }
            } else {
                super.pressesBegan(presses, with: event)
            }

        default:
            super.pressesBegan(presses, with: event)
        }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.first?.type == .menu { return }
        if let type = presses.first?.type, type == .leftArrow || type == .rightArrow {
            if !playbackState.userPaused || playbackState.isVirtualScrubbing {
                if let timer = holdTimer, timer.isValid {
                    timer.invalidate()
                    if !playbackState.isVirtualScrubbing {
                        if type == .rightArrow { skipForward() } else { skipBackward() }
                    }
                }
            }
        }
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if presses.first?.type == .menu { return }
        holdTimer?.invalidate()
        super.pressesCancelled(presses, with: event)
    }

    // MARK: - MPV Helpers

    private func setPropertyAsync(_ name: String, value: String) {
        guard let handle = mpv else { return }
        mpvQueue.async { mpv_set_property_string(handle, name, value) }
    }

    private func setHTTPHeaders(_ headers: [String: String]?, on handle: OpaquePointer) {
        let fields = MPVHTTPHeaders.fields(from: headers)
        // An empty string clears the string-list property on the reused core.
        // MPV_FORMAT_NONE is not a value format for setting this property.
        mpv_set_property_string(handle, "http-header-fields", fields)
    }

    @discardableResult
    private func mpvCommand(_ handle: OpaquePointer, _ args: [String]) -> Int32 {
        var cArgs = args.map { strdup($0) }
        cArgs.append(nil)
        defer { cArgs.forEach { if let p = $0 { free(p) } } }
        return cArgs.withUnsafeMutableBufferPointer { buf in
            buf.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buf.count) {
                mpv_command(handle, $0)
            }
        }
    }

    /// Fire-and-forget command. Unlike `mpvCommand`, this does NOT block the calling thread (our
    /// serial event-draining queue) — essential for `sub-add` of remote subtitles, which mpv
    /// otherwise opens synchronously (a network fetch each). mpv copies the args, so freeing
    /// them right after the call is safe.
    private func mpvCommandAsync(_ handle: OpaquePointer, _ args: [String]) {
        var cArgs = args.map { strdup($0) }
        cArgs.append(nil)
        defer { cArgs.forEach { if let p = $0 { free(p) } } }
        _ = cArgs.withUnsafeMutableBufferPointer { buf in
            buf.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buf.count) {
                mpv_command_async(handle, 0, $0)
            }
        }
    }

    private func stringProperty(_ handle: OpaquePointer, _ name: String) -> String? {
        guard let cstr = mpv_get_property_string(handle, name) else { return nil }
        defer { mpv_free(cstr) }
        return String(cString: cstr)
    }

    // MARK: - Phone Now-Playing Sync & Remote Commands

    private func startRemoteSync() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(onApplicationBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onApplicationActive),
            name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onControlNotification(_:)),
            name: WebSocketServer.controlCommand, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onRemoteNotification(_:)),
            name: WebSocketServer.remoteKey, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onResyncRequest),
            name: WebSocketServer.resyncRequest, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onStillWatchingPause),
            name: .playBridgeStillWatchingPause, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onStillWatchingResume),
            name: .playBridgeStillWatchingResume, object: nil)

        // Periodic status (covers live position) — 1s cadence matches the Android receiver.
        statusTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.broadcastStatus()
        }
    }

    /// Wire format mirrors the Android receiver: `status` carries ms positions.
    private func broadcastStatus() {
        var json: [String: Any] = [
            "type": "status",
            "state": playbackState.isPlaying ? "playing" : "paused",
            "position": PlaybackTime.milliseconds(playbackState.currentTime),
            "duration": PlaybackTime.milliseconds(playbackState.duration),
        ]
        if let t = mediaTitle, !t.isEmpty { json["title"] = t }
        onBroadcast?(json)
    }

    private func broadcastTracks() {
        func encode(_ tracks: [(id: Int, name: String)], selected: Int) -> [[String: Any]] {
            tracks.map { ["id": String($0.id), "name": $0.name, "selected": $0.id == selected] }
        }
        onBroadcast?([
            "type": "tracks",
            "audio": encode(playbackState.audioTracks, selected: playbackState.currentAudioIndex),
            "subtitle": encode(playbackState.subtitleTracks, selected: playbackState.currentSubtitleIndex),
        ])
    }

    @objc private func onResyncRequest() {
        broadcastStatus()
        broadcastTracks()
    }

    @objc private func onStillWatchingPause() { setPropertyAsync("pause", value: "yes") }
    @objc private func onStillWatchingResume() { setPropertyAsync("pause", value: "no") }

    @objc private func onControlNotification(_ note: Notification) {
        guard let cmd = note.userInfo?["command"] as? String else { return }
        if cmd == "add_subtitle" {
            guard let resource = note.userInfo?["subtitleResource"] as? Playbridge_SubtitleResource,
                  let completion = note.userInfo?["subtitleCompletion"] as? ((Bool) -> Void),
                  !isMpvStopped, mpv != nil else {
                (note.userInfo?["subtitleCompletion"] as? ((Bool) -> Void))?(false)
                return
            }
            lateSubtitleDownloads.values.forEach { $0.cancel() }
            lateSubtitleDownloads.removeAll()
            let id = UUID()
            lateSubtitleSelectionID = id
            let download = ScopedSubtitleDownload(resource: resource)
            lateSubtitleDownloads[id] = download
            download.start { [weak self] result in
                guard let self else { completion(false); return }
                guard self.lateSubtitleDownloads.removeValue(forKey: id) != nil else {
                    if case .success(let file) = result { try? FileManager.default.removeItem(at: file) }
                    completion(false)
                    return
                }
                guard !self.isMpvStopped, let handle = self.mpv else {
                    if case .success(let file) = result { try? FileManager.default.removeItem(at: file) }
                    completion(false)
                    return
                }
                switch result {
                case .failure: completion(false)
                case .success(let file):
                    self.mpvQueue.async { [weak self] in
                        guard let self, self.mpv == handle,
                              self.lateSubtitleSelectionID == id else {
                            try? FileManager.default.removeItem(at: file)
                            DispatchQueue.main.async { completion(false) }
                            return
                        }
                        let label = resource.hasLabel && !resource.label.isEmpty
                            ? resource.label : "External subtitle"
                        let ok = self.mpvCommand(handle, ["sub-add", file.path, "select", label]) >= 0
                        if ok { self.updateTracks() }
                        DispatchQueue.main.async {
                            guard self.lateSubtitleSelectionID == id else {
                                if ok { self.lateSubtitleFiles.append(file) }
                                else { try? FileManager.default.removeItem(at: file) }
                                completion(false)
                                return
                            }
                            if ok { self.lateSubtitleFiles.append(file) }
                            else { try? FileManager.default.removeItem(at: file) }
                            completion(ok)
                        }
                    }
                }
            }
            return
        }
        if StillWatchingGate.isPrompting { return }
        handleControlCommand(cmd)
    }

    @objc private func onRemoteNotification(_ note: Notification) {
        guard let key = note.userInfo?["key"] as? String else { return }
        if StillWatchingGate.isPrompting { return }
        switch key {
        case "dpad_center": togglePlayPause()
        case "dpad_left":   skipBackward()
        case "dpad_right":  skipForward()
        default:            break
        }
    }

    /// Map a phone `control` command to the player. Runs on main (posted from the WS server).
    /// Speed/scaling/filter/audio_boost/sub_offset are not yet supported on Apple MPV → ignored.
    private func handleControlCommand(_ cmd: String) {
        if let paused = PlaybackPauseCommand.targetPaused(
            for: cmd, isPlaying: playbackState.isPlaying) {
            setPlaybackPaused(paused)
            return
        }
        switch cmd {
        case "stop":
            broadcastStatus()
            onExit?()
        case "loop_on":
            playbackState.isLooping = true
        case "loop_off":
            playbackState.isLooping = false
        case "seek_forward":
            skipForward()
        case "seek_back":
            skipBackward()
        case let c where c.hasPrefix("seek_to:"):
            if let ms = Double(c.dropFirst("seek_to:".count)) {
                let secs = ms / 1000
                playbackState.currentTime = secs
                ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
                seekAsync(to: secs)
            }
        case let c where c.hasPrefix("audio_track:"):
            let id = String(c.dropFirst("audio_track:".count))
            setPropertyAsync("aid", value: id)
            if let i = Int(id) {
                playbackState.currentAudioIndex = i
                recordAudioPreference(id: i)
            }
            mpvQueue.async { [weak self] in self?.updateTracks() }
        case let c where c.hasPrefix("sub_track:"):
            let id = String(c.dropFirst("sub_track:".count))
            if id == "none" || id == "-1" {
                setPropertyAsync("sid", value: "no")
                playbackState.currentSubtitleIndex = -1
                recordSubtitlePreference(id: -1)
            } else {
                setPropertyAsync("sid", value: id)
                if let i = Int(id) {
                    playbackState.currentSubtitleIndex = i
                    recordSubtitlePreference(id: i)
                }
            }
            mpvQueue.async { [weak self] in self?.updateTracks() }
        case let c where c.hasPrefix("add_subtitle:"):
            let urlStr = String(c.dropFirst("add_subtitle:".count))
            guard let handle = mpv, !urlStr.isEmpty else { break }
            // Async: a remote sub-add opens a network fetch; never block the event queue.
            mpvQueue.async { [weak self] in self?.mpvCommandAsync(handle, ["sub-add", urlStr, "select"]) }
        case let c where c.hasPrefix("switch_player:"):
            if let target = PlaybackEngine(command: String(c.dropFirst("switch_player:".count))),
               target != .mpv {
                onSwitch?(target, playbackState.currentTime)
            }
        default:
            break
        }
    }

    // MARK: - Shutdown

    /// Idempotent. Safe to call from `dismantleUIViewController` (primary), `viewWillDisappear`,
    /// and `deinit`. The first call does the work; later calls are no-ops via `isMpvStopped`.
    func teardown() {
        guard !isMpvStopped else { return }
        isMpvStopped = true
        lateSubtitleDownloads.values.forEach { $0.cancel() }
        lateSubtitleDownloads.removeAll()
        lateSubtitleSelectionID = nil
        let stagedSubtitles = lateSubtitleFiles
        lateSubtitleFiles.removeAll()

        statusTimer?.invalidate()
        statusTimer = nil
        hideControlsTimer?.invalidate()
        hideControlsTimer = nil
        holdTimer?.invalidate()
        holdTimer = nil
        virtualScrubTickTimer?.invalidate()
        virtualScrubTickTimer = nil
        NotificationCenter.default.removeObserver(self)

        guard let handle = mpv else {
            // mpv never finished initialising — still balance the callback retain if taken.
            releaseCallbackSelf()
            return
        }

        // Stop new wakeup callbacks before draining the queue, then release the retain that
        // kept self alive for the callback. The caller (SwiftUI/UIKit) still holds a ref, so
        // this won't deallocate self mid-teardown.
        mpv_set_wakeup_callback(handle, nil, nil)
        releaseCallbackSelf()

        mpv = nil

        // Serialize destruction after pending commands without blocking the main thread.
        // Keep the output layer alive until mpv has released the native window pointer.
        let outputLayer = metalLayer
        mpvQueue.async {
            mpv_terminate_destroy(handle)
            stagedSubtitles.forEach { try? FileManager.default.removeItem(at: $0) }
            withExtendedLifetime(outputLayer) {}
        }

        resetDisplayCriteria()
    }

    /// Balances the `passRetained(self)` from `setupMPV`. Called exactly once during teardown.
    private func releaseCallbackSelf() {
        guard let ptr = callbackSelfPtr else { return }
        callbackSelfPtr = nil
        Unmanaged<MPVViewController>.fromOpaque(ptr).release()
    }
}

/// MoltenVK can request a 1x1 drawable during presentation; keep the real viewport.
private final class MPVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if newValue.width > 1 && newValue.height > 1 { super.drawableSize = newValue } }
    }
}
