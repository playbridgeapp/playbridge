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
    let skipMetadata: SkipSegmentMetadata
    let initialTime: Double
    let mediaIdentity: Int
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void
    let onExit: () -> Void
    let onBack: () -> Void
    let onSwitch: (PlaybackEngine, Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void
    let onLoopChanged: (Bool, PlayerReportIdentity) -> Bool
    let onEndOfFile: (PlayerReportIdentity) -> PlayerEndDecision
    let initialLoop: Bool
    /// Stored app-volume level, 0–1. Applied to mpv as 0–100.
    let appLevel: Double
    let appMuted: Bool
    let engineChrome: PlayerEngineChrome
    /// More than one queue item; a one-item queue hides the Playlist button.
    let hasPlaylist: Bool
    let playbackSpeed: Double
    let subtitleOffsetMs: Int
    let scaling: PlayerScaling
    let hasNextItem: Bool

    func makeUIViewController(context: Context) -> MPVViewController {
        let vc = MPVViewController()
        vc.url = url
        vc.headers = headers
        vc.subtitles = subtitles
        vc.skipMetadata = skipMetadata
        vc.initialTime = initialTime
        vc.mediaIdentity = mediaIdentity
        vc.isPreBuffering = isPreBuffering
        vc.mediaTitle = title
        vc.onDismiss = onDismiss
        vc.onExit = onExit
        vc.onBack = onBack
        vc.onSwitch = onSwitch
        vc.onBroadcast = onBroadcast
        vc.onLoopChanged = onLoopChanged
        vc.onEndOfFile = onEndOfFile
        vc.initialLoop = initialLoop
        vc.appLevel = appLevel
        vc.appMuted = appMuted
        vc.bindEngineChrome(engineChrome)
        vc.hasPlaylist = hasPlaylist
        vc.playbackSpeed = playbackSpeed
        vc.subtitleOffsetMs = subtitleOffsetMs
        vc.scaling = scaling
        vc.hasNextItem = hasNextItem
        return vc
    }

    func updateUIViewController(_ uiViewController: MPVViewController, context: Context) {
        uiViewController.isPreBuffering = isPreBuffering
        // Keep callbacks current (they're cheap value assignments).
        uiViewController.onDismiss = onDismiss
        uiViewController.onExit = onExit
        uiViewController.onBack = onBack
        uiViewController.onSwitch = onSwitch
        uiViewController.onBroadcast = onBroadcast
        uiViewController.onLoopChanged = onLoopChanged
        uiViewController.onEndOfFile = onEndOfFile
        uiViewController.appLevel = appLevel
        uiViewController.appMuted = appMuted
        uiViewController.bindEngineChrome(engineChrome)
        uiViewController.hasPlaylist = hasPlaylist
        uiViewController.playbackSpeed = playbackSpeed
        uiViewController.subtitleOffsetMs = subtitleOffsetMs
        uiViewController.scaling = scaling
        uiViewController.hasNextItem = hasNextItem
        // Episode advance on the LIVE mpv core: the controller (and its initialised
        // handle, render layer, caches) is reused — `loadfile replace` swaps the
        // media. This is what makes back-to-back episodes start fast and gapless
        // instead of paying a full mpv re-init per item.
        if uiViewController.url != url || uiViewController.mediaIdentity != mediaIdentity {
            uiViewController.invalidateFailure()
            uiViewController.skipLoader.cancel()
            uiViewController.skipMetadata = skipMetadata
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
    var skipMetadata = SkipSegmentMetadata()
    fileprivate let skipLoader = SkipSegmentLoader()
    var mediaTitle: String?
    var onDismiss: (() -> Void)?
    var onExit: (() -> Void)?
    var onBack: (() -> Void)?
    var onSwitch: ((PlaybackEngine, Double) -> Void)?
    var onBroadcast: (([String: Any]) -> Void)?
    var onLoopChanged: ((Bool, PlayerReportIdentity) -> Bool)?
    var onEndOfFile: ((PlayerReportIdentity) -> PlayerEndDecision)?
    var initialLoop = false
    var hasNextItem = false {
        didSet {
            guard hasNextItem != oldValue else { return }
            playbackState.failureHasNextItem = hasNextItem
            if !hasNextItem, playbackState.failureHighlight == .next {
                playbackState.failureHighlight = PlaybackFailureModel.defaultAction
            }
        }
    }
    private var failureSession = PlaybackFailureSession()
    private var failureTimer: Timer?
    /// Assigned on mpvQueue when START_FILE arrives, including files that fail
    /// before FILE_LOADED. Retries share the item identity but get a new token.
    private var pendingLoadedAttempt: UUID?
    private var loadedAttempt: UUID?
    /// Item generation of the file mpv has actually opened. Touched only on `mpvQueue`.
    /// Stays at the previous item until START_FILE, so a late EOF cannot adopt the next item.
    private var loadedIdentity = -1
    private var pendingLoadedIdentity = -1
    private var engineChrome: PlayerEngineChrome?
    private var statusTimer: Timer?
    var isPreBuffering: Bool = false {
        didSet { if isPreBuffering != oldValue { applyPreBufferingState() } }
    }
    /// Shared session level. Writes are absolute and run on `mpvQueue`, so a
    /// burst of steps cannot interleave a read-modify-write on the core.
    var appLevel: Double = PlayerAppVolume.defaultLevel {
        didSet { if appLevel != oldValue { applyAppVolumeProperty() } }
    }
    var hasPlaylist = false {
        didSet { if hasPlaylist != oldValue { playbackState.hasPlaylist = hasPlaylist } }
    }
    var appMuted: Bool = false {
        didSet { if appMuted != oldValue { applyAudibleMute() } }
    }

    var playbackSpeed = 1.0 {
        didSet {
            if playbackSpeed != oldValue { setPropertyAsync("speed", value: PlayerTuning.mpvNumber(playbackSpeed)) }
        }
    }
    var subtitleOffsetMs = 0 {
        didSet {
            if subtitleOffsetMs != oldValue { setPropertyAsync("sub-delay", value: PlayerTuning.mpvSubDelay(ms: subtitleOffsetMs)) }
        }
    }
    var scaling = PlayerScaling.fit {
        didSet {
            if scaling != oldValue {
                for (name, value) in PlayerTuning.mpvScalingProperties(scaling) { setPropertyAsync(name, value: value) }
            }
        }
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
    /// Chapter markers of the open file (mpv `chapter-list`). Main-thread only.
    private var chapters: [TimelineChapter] = [] {
        didSet { playbackState.chapters = ChapterNavigation.sorted(chapters) }
    }
    private var chapterToastTimer: Timer?
    /// When the current file started loading — used to log time-to-audio for diagnosing delays.
    private var loadStartTime = Date()

    // Custom focusable view that receives Siri Remote presses
    private class FocusableView: UIView {
        override var canBecomeFocused: Bool { true }
    }
    private let videoView = FocusableView()

    override var preferredFocusEnvironments: [UIFocusEnvironment] {
        if !isPreBuffering, !StillWatchingGate.isPrompting,
           (playbackState.failureSentence != nil || playbackState.userPaused || playbackState.activeSkipSegment != nil), let hv = hostingController?.view { return [hv] }
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
        playbackState.currentTime = initialTime
        playbackState.prebuffering = isPreBuffering
        playbackState.isLooping = initialLoop
        externalSubtitleCatalog = ExternalSubtitleCatalog(urls: subtitles ?? [])
        playbackState.externalSubtitleTracks = externalSubtitleCatalog.unloadedTracks(excluding: [])
        setupHUD()
        startSkipSegments()
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
            initializationFailed(operation: "mpv_create", code: MPV_ERROR_NOMEM.rawValue)
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

        // Session tuning applies before initialization and survives loadfile replace.
        mpv_set_option_string(handle, "speed", PlayerTuning.mpvNumber(playbackSpeed))
        mpv_set_option_string(handle, "sub-delay", PlayerTuning.mpvSubDelay(ms: subtitleOffsetMs))
        for (name, value) in PlayerTuning.mpvScalingProperties(scaling) {
            mpv_set_option_string(handle, name, value)
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
        // JSON `[{"title","time"}]`; fires again when the next file loads.
        mpv_observe_property(handle, 0, "chapter-list", MPV_FORMAT_STRING)

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
        // No wakeup callback is installed before successful initialization. Drop
        // only this partial core: keep the controller/HUD alive for Try Again.
        if let handle = mpv {
            mpv = nil
            let outputLayer = metalLayer
            mpvQueue.async {
                mpv_destroy(handle)
                withExtendedLifetime(outputLayer) {}
            }
        }
        presentFailure(code: code, generation: mediaIdentity, attempt: failureSession.attempt)
    }

    fileprivate func invalidateFailure() {
        failureTimer?.invalidate()
        failureTimer = nil
        _ = failureSession.beginAttempt()
    }

    private func clearFailure() {
        invalidateFailure()
        playbackState.failureSentence = nil
        playbackState.cacheStalled = false
        playbackState.failureHighlight = PlaybackFailureModel.defaultAction
    }

    private func presentFailure(code: Int32, generation: Int, attempt: UUID) {
        guard !isMpvStopped, generation == mediaIdentity,
              failureSession.present(code: code, attempt: attempt) else { return }
        hideControlsTimer?.invalidate()
        holdTimer?.invalidate()
        virtualScrubTickTimer?.invalidate()
        playbackState.isVirtualScrubbing = false
        playbackState.scrubMultiplier = 0
        playbackState.showUI = false
        playbackState.showSubtitleMenu = false
        playbackState.showAudioMenu = false
        playbackState.showEngineMenu = false
        playbackState.activeSkipSegment = nil
        playbackState.cacheStalled = false
        playbackState.isPlaying = false
        playbackState.failureSentence = failureSession.sentence
        playbackState.failureHighlight = PlaybackFailureModel.defaultAction
        setPropertyAsync("pause", value: "yes")
        broadcastStatus()
        failureTimer?.invalidate()
        failureTimer = Timer.scheduledTimer(withTimeInterval: PlaybackFailureModel.autoCloseSeconds, repeats: false) { [weak self] _ in
            guard let self, self.mediaIdentity == generation, self.failureSession.matches(attempt) else { return }
            self.closeFailure(event: .autoClose)
        }
        setNeedsFocusUpdate()
    }

    private func closeFailure(event: PlaybackFailureModel.Event = .close) {
        guard !isMpvStopped,
              PlaybackFailureModel.phoneReport(for: event) == .idleContext,
              failureSession.close(attempt: failureSession.attempt) else { return }
        failureTimer?.invalidate()
        failureTimer = nil
        // The existing owner exit sends idle, flushes progress and dismisses once.
        onExit?()
    }

    private func handleFailureAction(_ action: PlaybackFailureAction) {
        guard !isMpvStopped, !isPreBuffering, !StillWatchingGate.isPrompting,
              failureSession.sentence != nil, failureSession.matches(failureSession.attempt),
              PlaybackFailureModel.actions(hasNextItem: hasNextItem).contains(action) else { return }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
        switch action {
        case .close: closeFailure()
        case .next:
            clearFailure()
            onDismiss?() // Explicit Next bypasses repeat-one, unlike natural EOF.
        case .tryAgain:
            let position = playbackState.currentTime
            clearFailure()
            initialTime = position.isFinite && position >= 0 ? position : 0
            playbackState.userPaused = false
            playbackState.isPlaying = false
            if let url, mpv != nil {
                loadFile(url)
                setPropertyAsync("pause", value: "no")
                showUI(autoHide: true)
            } else {
                setupAfterCleanup()
            }
        }
    }

    private func setupAfterCleanup() {
        // A first layout still owns initial setup; a failed init can retry only
        // after its partial core has finished destruction on the serial queue.
        guard didSetupMPV else { return }
        let attempt = failureSession.attempt
        mpvQueue.async { [weak self] in
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mpv == nil,
                      self.failureSession.matches(attempt) else { return }
                self.setupMPV()
            }
        }
    }

    private func handleFailureKey(_ key: String) -> Bool {
        guard !isMpvStopped, !isPreBuffering, !StillWatchingGate.isPrompting,
              failureSession.sentence != nil, failureSession.matches(failureSession.attempt) else { return false }
        let actions = PlaybackFailureModel.actions(hasNextItem: hasNextItem)
        switch key {
        case "back", "home": closeFailure()
        case "dpad_center": handleFailureAction(playbackState.failureHighlight)
        case "dpad_left", "dpad_up", "dpad_right", "dpad_down":
            let index = actions.firstIndex(of: playbackState.failureHighlight) ?? 0
            let delta = key == "dpad_left" || key == "dpad_up" ? -1 : 1
            playbackState.failureHighlight = actions[min(max(index + delta, 0), actions.count - 1)]
        default: return false
        }
        return true
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

        chapters = []
        clearChapterToast()
        playbackState.seekStripActive = false
        playbackState.activeSkipSegment = nil
        startSkipSegments()

        // Per-item resets (mirrors what a fresh controller would start with).
        didApplyTrackPreferences = false
        ignoreTimeUpdatesUntil = .distantPast
        playbackState.title = title ?? ""
        playbackState.currentTime = initialTime
        playbackState.duration = 0
        playbackState.userPaused = false
        playbackState.audioTracks = []
        playbackState.subtitleTracks = []
        externalSubtitleCatalog = ExternalSubtitleCatalog(urls: subtitles ?? [])
        playbackState.externalSubtitleTracks = externalSubtitleCatalog.unloadedTracks(excluding: [])

        clearFailure()
        if mpv == nil { setupAfterCleanup() } else { loadFile(url) }
        mpvQueue.async {
            oldSubtitleFiles.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    private func loadFile(_ url: URL, fromStart: Bool = false) {
        guard mpv != nil else { return }   // re-bound to the live handle inside mpvQueue below
        clearFailure()
        let attempt = failureSession.attempt
        pendingExternalSubtitles = subtitles ?? []
        timelineUpdateGate.reset()
        // Clear buffered-ahead state so a looped/next file doesn't flash the prior buffer.
        cacheAheadSec = 0
        playbackState.bufferedTime = 0
        loadStartTime = Date()
        configureAudioSession()
        let path = url.isFileURL ? url.path : url.absoluteString
        let volume = PlayerAppVolume.mpvVolumeString(level: appLevel)
        let loadArguments = MPVAudioPolicy.loadArguments(
            path: path, isPreBuffering: isPreBuffering, appMuted: appMuted)
        let startTime = fromStart ? 0 : initialTime
        let itemIdentity = mediaIdentity

        mpvQueue.async { [weak self] in
            guard let self, let handle = self.mpv else { return }
            self.pendingLoadedIdentity = itemIdentity
            self.pendingLoadedAttempt = attempt

            // User-Agent must go via the dedicated "user-agent" option, not http-header-fields.
            // Fall back to a browser UA — some servers reject mpv's default "Lavf/..." agent.
            let ua = self.headers?
                .first(where: { $0.key.caseInsensitiveCompare("user-agent") == .orderedSame })?
                .value ?? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            mpv_set_property_string(handle, "user-agent", ua)

            self.setHTTPHeaders(self.headers, on: handle)

            if startTime > 0 {
                mpv_set_property_string(handle, "start", String(format: "%.2f", startTime))
            } else {
                // The handle persists across episodes now — clear a previous item's
                // resume point or the next file would start there too.
                mpv_set_property_string(handle, "start", "none")
            }
            mpv_set_property_string(handle, "volume", volume)
            let result = self.mpvCommand(handle, loadArguments)
            if result >= 0 { mpv_set_property_string(handle, "pause", "no") }
            if result < 0 {
                DispatchQueue.main.async { [weak self] in
                    self?.presentFailure(code: result, generation: itemIdentity, attempt: attempt)
                }
            }
        }
    }

    /// Absolute mpv volume, 0–100, queued behind any load already on `mpvQueue`.
    private func applyAppVolumeProperty() {
        setPropertyAsync("volume", value: PlayerAppVolume.mpvVolumeString(level: appLevel))
    }

    private func applyAudibleMute() {
        setPropertyAsync("mute", value: MPVAudioPolicy.muteValue(
            isPreBuffering: isPreBuffering, appMuted: appMuted))
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
        case MPV_EVENT_START_FILE:
            loadedIdentity = pendingLoadedIdentity
            loadedAttempt = pendingLoadedAttempt

        case MPV_EVENT_FILE_LOADED:
            onFileLoaded()

        case MPV_EVENT_END_FILE:
            // STOP/REDIRECT are caused by our own loadfile replace. Only EOF
            // advances or loops; an error stays on its own screen until action.
            guard let end = event.data?.assumingMemoryBound(to: mpv_event_end_file.self).pointee,
                  let attempt = loadedAttempt else { break }
            let reason = end.reason
            let code = end.error
            let generation = loadedIdentity
            let identity = PlayerReportIdentity(engine: PlaybackEngine.mpv.rawValue, generation: generation)
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      self.failureSession.matches(attempt) else { return }
                if reason == MPV_END_FILE_REASON_ERROR {
                    self.presentFailure(code: code, generation: generation, attempt: attempt)
                    return
                }
                guard reason == MPV_END_FILE_REASON_EOF, self.playbackState.failureSentence == nil else { return }
                switch self.onEndOfFile?(identity) ?? .ignored {
                case .repeatCurrentItem:
                    if let url = self.url { self.loadFile(url, fromStart: true) }
                case .advanceOrLeave: self.onDismiss?()
                case .ignored: break
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

        // Initial observer samples can precede START_FILE. Seed both flags
        // once loaded, otherwise a normal unpaused file may never emit pause again.
        var paused: Int32 = 0
        var stalled: Int32 = 0
        mpv_get_property(handle, "pause", MPV_FORMAT_FLAG, &paused)
        mpv_get_property(handle, "paused-for-cache", MPV_FORMAT_FLAG, &stalled)
        let isPaused = paused != 0
        let cacheStalled = stalled != 0
        let attempt = loadedAttempt
        let generation = loadedIdentity
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                  let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil else { return }
            self.playbackState.isPlaying = !isPaused
            self.playbackState.cacheStalled = cacheStalled
            self.applyAudibleMute()
            self.applyAppVolumeProperty()
            self.activateAudioSession()
            self.broadcastStatus()
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
        let attempt = loadedAttempt
        let generation = loadedIdentity
        switch name {
        case "time-pos":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee else { break }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil,
                      Date() > self.ignoreTimeUpdatesUntil,
                      self.timelineUpdateGate.shouldUpdate(at: CACurrentMediaTime()) else { return }
                // UI progress does not need a SwiftUI redraw for every decoded frame.
                self.playbackState.currentTime = val
                self.playbackState.bufferedTime = val + self.cacheAheadSec
                self.updateActiveSkipSegment()
            }

        case "chapter-list":
            guard prop.format == MPV_FORMAT_STRING,
                  let cString = prop.data?.assumingMemoryBound(to: UnsafePointer<CChar>?.self).pointee else { break }
            let parsed = TimelineReport.parseMpvChapterList(String(cString: cString))
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil,
                      parsed != self.chapters else { return }
                self.chapters = parsed
                self.broadcastTimeline()
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
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil else { return }
                // Store every cache sample, but publish the buffer bar with the throttled
                // time-pos update instead of triggering another per-frame HUD redraw.
                self.cacheAheadSec = val
            }

        case "paused-for-cache":
            guard prop.format == MPV_FORMAT_FLAG,
                  let val = prop.data?.assumingMemoryBound(to: Int32.self).pointee else { break }
            let stalled = val != 0
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil else { return }
                self.playbackState.cacheStalled = stalled
                self.broadcastStatus()
            }

        case "duration":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee,
                  val > 0 else { break }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil else { return }
                self.playbackState.duration = val
                self.updateActiveSkipSegment()
            }

        case "pause":
            guard prop.format == MPV_FORMAT_FLAG,
                  let val = prop.data?.assumingMemoryBound(to: Int32.self).pointee else { break }
            let isPaused = val != 0
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.isMpvStopped, self.mediaIdentity == generation,
                      let attempt, self.failureSession.matches(attempt), self.playbackState.failureSentence == nil else { return }
                self.playbackState.isPlaying = !isPaused
                self.broadcastStatus()
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
                self?.selectSubtitleTrack(trackId)
            },
            onSelectAudio: { [weak self] trackId in
                self?.selectAudioTrack(trackId)
            },
            onTogglePlayPause: { [weak self] in self?.togglePlayPause() },
            onSwitchEngine: { [weak self] target in
                guard let self else { return }
                self.onSwitch?(target, self.playbackState.currentTime)
            },
            onTogglePlaylist: {
                NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
            },
            onLoopToggle: { [weak self] in
                guard let self else { return }
                self.setLooping(!self.playbackState.isLooping)
            },
            onSkipSegment: { [weak self] in self?.skipActiveSegment() },
            onFailureAction: { [weak self] in self?.handleFailureAction($0) },
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
        applyAudibleMute()
        playbackState.prebuffering = isPreBuffering
        playbackState.skipControlsEnabled = !isPreBuffering
        if isPreBuffering {
            playbackState.showUI = false
            hideControlsTimer?.invalidate()
        } else {
            activateAudioSession()
            showUI(autoHide: true)
            NotificationCenter.default.post(
                name: .playBridgePlaybackActivity, object: nil,
                userInfo: ["isPlaying": playbackState.failureSentence == nil && playbackState.surfaceState == .playing])
        }
    }

    /// `seekStrip` marks the HUD as the strip a seek or chapter jump opened, in
    /// which Up/Down jump chapters. Any other reason to show the HUD clears it.
    private func showUI(autoHide: Bool = true, seekStrip: Bool = false) {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isMpvStopped, self.playbackState.failureSentence == nil else { return }
            self.playbackState.showUI = true
            self.playbackState.seekStripActive = seekStrip
            self.setNeedsFocusUpdate()
            self.hideControlsTimer?.invalidate()
            guard autoHide, !self.playbackState.isVirtualScrubbing else { return }
            self.hideControlsTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { [weak self] _ in
                DispatchQueue.main.async {
                    withAnimation {
                        self?.playbackState.showUI = false
                        self?.playbackState.seekStripActive = false
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
        seekRelative(by: 15)
    }

    private func skipBackward() {
        guard !playbackState.userPaused else { return }
        seekRelative(by: -15)
    }

    /// Network Left/Right seek while paused. The physical skip helpers above stay
    /// idle in that state so a Siri arrow still moves focus along the button row.
    private func seekRelative(by delta: Double) {
        let target: Double
        if delta >= 0 {
            let cap = playbackState.duration > 0 ? playbackState.duration - 2.0 : Double.infinity
            target = min(playbackState.currentTime + delta, cap)
        } else {
            target = max(0, playbackState.currentTime + delta)
        }
        playbackState.currentTime = target
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        seekAsync(to: target)
        let menuOpen = playbackState.showSubtitleMenu || playbackState.showAudioMenu
            || playbackState.showEngineMenu
        if !menuOpen { showUI(autoHide: !playbackState.userPaused, seekStrip: !playbackState.userPaused) }
    }

    // MARK: - Chapters

    /// Up/Down in the seek strip, or during a scrub (where the ghost moves instead).
    /// Repeated presses chain from the pending target: seeks set `currentTime`
    /// optimistically and hold off mpv's stale `time-pos`.
    private func jumpChapter(forward: Bool) {
        let list = playbackState.chapters
        guard ChapterNavigation.canJump(list) else { return }
        let scrubbing = playbackState.isVirtualScrubbing
        let base = scrubbing ? playbackState.virtualTime : playbackState.currentTime
        let target = ChapterNavigation.jumpTarget(
            chapters: list, positionMs: PlaybackTime.milliseconds(base), forward: forward)
        showChapterToast(ChapterNavigation.toast(target))
        guard let target else {
            if scrubbing { showUI(autoHide: false) } else { showUI(autoHide: true, seekStrip: true) }
            return
        }
        var seconds = Double(target.chapter.startMs) / 1000
        if playbackState.duration > 0 { seconds = min(seconds, playbackState.duration) }
        if scrubbing {
            // Stop the ghost on the chapter; Select commits, Left/Right scrub on from there.
            playbackState.scrubMultiplier = 0
            playbackState.virtualTime = seconds
            showUI(autoHide: false)
            return
        }
        playbackState.currentTime = seconds
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        seekAsync(to: seconds)
        updateActiveSkipSegment()
        showUI(autoHide: true, seekStrip: true)
    }

    private func showChapterToast(_ text: String) {
        playbackState.chapterToast = text
        chapterToastTimer?.invalidate()
        chapterToastTimer = Timer.scheduledTimer(
            withTimeInterval: ChapterNavigation.toastSeconds, repeats: false
        ) { [weak self] _ in
            DispatchQueue.main.async { self?.playbackState.chapterToast = nil }
        }
    }

    private func clearChapterToast() {
        chapterToastTimer?.invalidate()
        chapterToastTimer = nil
        playbackState.chapterToast = nil
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
        showUI(autoHide: true, seekStrip: true)
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

        if playbackState.failureSentence != nil {
            if type == .menu { closeFailure() }
            else { super.pressesBegan(presses, with: event) }
            return
        }

        if type == .menu {
            // The player owner also knows about the playlist. Use the same Back
            // levels as the phone so only the top overlay closes on each press.
            broadcastStatus()
            onBack?()
            return
        }

        let menuOpen = playbackState.showSubtitleMenu || playbackState.showAudioMenu || playbackState.showEngineMenu
        if type != .playPause && menuOpen {
            super.pressesBegan(presses, with: event); return
        }

        switch type {
        case .upArrow where playbackState.chapterKeysActive,
             .downArrow where playbackState.chapterKeysActive:
            // Ahead of the Skip branches: the Skip pill is the only focus target,
            // so Up/Down have no focus meaning while it shows.
            jumpChapter(forward: type == .upArrow)

        case .select where playbackState.activeSkipSegment != nil && !playbackState.isVirtualScrubbing:
            super.pressesBegan(presses, with: event)
        case .playPause, .select:
            switch PlayerRemoteNavigation.physicalPlaybackEffect(
                for: type == .playPause ? .playPause : .select,
                userPaused: playbackState.userPaused,
                scrubbing: playbackState.isVirtualScrubbing,
                menuOpen: menuOpen
            ) {
            case .commitScrub:
                commitVirtualScrub()
            case .togglePlayback:
                togglePlayPause()
            default:
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
        if playbackState.failureSentence != nil { super.pressesEnded(presses, with: event); return }
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
            guard let self, self.playbackState.failureSentence == nil else { return }
            self.broadcastStatus()
        }
    }

    /// Wire format mirrors the Android receiver: `status` carries ms positions.
    private func broadcastStatus() {
        guard !isMpvStopped else { return }
        if playbackState.failureSentence != nil, !failureSession.matches(failureSession.attempt) { return }
        let state = playbackState.failureSentence == nil ? playbackState.surfaceState.rawValue : "error"
        var json: [String: Any] = [
            "type": "status",
            "state": state,
            "position": PlaybackTime.milliseconds(playbackState.currentTime),
            "duration": PlaybackTime.milliseconds(playbackState.duration),
        ]
        if let t = mediaTitle, !t.isEmpty { json["title"] = t }
        if let sentence = playbackState.failureSentence { json["error"] = sentence }
        NotificationCenter.default.post(name: .playBridgePlaybackActivity, object: nil,
            userInfo: ["isPlaying": state == "playing"])
        if let buffered = TimelineReport.buffered(bufferedSeconds: playbackState.bufferedTime,
            positionSeconds: playbackState.currentTime, durationSeconds: playbackState.duration) {
            json["buffered"] = buffered
        }
        onBroadcast?(json)
    }

    /// Sent when the chapters change and on resync; the server adds the item ids.
    private func broadcastTimeline() {
        onBroadcast?(TimelineReport.frame(chapters: chapters, segments: skipLoader.segments))
    }

    private func startSkipSegments() {
        let identity = mediaIdentity
        playbackState.skipSegments = []
        skipLoader.start(skipMetadata) { [weak self] in
            guard let self, !self.isMpvStopped, self.mediaIdentity == identity else { return }
            self.playbackState.skipSegments = self.skipLoader.segments
            self.updateActiveSkipSegment()
            self.broadcastTimeline()
        }
        broadcastTimeline()
    }

    private func updateActiveSkipSegment() {
        guard playbackState.failureSentence == nil else { return }
        let active = skipLoader.segments.first {
            $0.contains(seconds: playbackState.currentTime)
                && $0.targetSeconds(duration: playbackState.duration) != nil
        }
        if playbackState.activeSkipSegment != active {
            playbackState.activeSkipSegment = active
            setNeedsFocusUpdate()
        }
    }

    private func skipActiveSegment() {
        guard !isPreBuffering, !StillWatchingGate.isPrompting,
              let segment = playbackState.activeSkipSegment,
              skipLoader.segments.contains(segment), segment.contains(seconds: playbackState.currentTime),
              let target = segment.targetSeconds(duration: playbackState.duration) else { return }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
        playbackState.activeSkipSegment = nil
        if segment.endsPlayback(duration: playbackState.duration) {
            playbackState.currentTime = target
            broadcastStatus()
            onDismiss?()
            return
        }
        playbackState.currentTime = target
        ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
        seekAsync(to: target)
        broadcastStatus()
        setNeedsFocusUpdate()
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
        broadcastTimeline()
    }

    @objc private func onStillWatchingPause() { setPropertyAsync("pause", value: "yes") }
    @objc private func onStillWatchingResume() {
        if playbackState.failureSentence == nil { setPropertyAsync("pause", value: "no") }
    }

    @objc private func onControlNotification(_ note: Notification) {
        guard let cmd = note.userInfo?["command"] as? String else { return }
        if playbackState.failureSentence != nil {
            if cmd == "stop" { closeFailure(event: .phoneStop) }
            (note.userInfo?["subtitleCompletion"] as? ((Bool) -> Void))?(false)
            return
        }
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
        if cmd == "loop_on" || cmd == "loop_off" {
            setLooping(cmd == "loop_on")
            return
        }
        if StillWatchingGate.isPrompting { return }
        handleControlCommand(cmd)
    }

    func bindEngineChrome(_ chrome: PlayerEngineChrome) {
        engineChrome = chrome
        chrome.handleFailureKey = { [weak self] key in self?.handleFailureKey(key) ?? false }
        chrome.snapshot = { [weak self] in
            guard let self else { return PlayerEngineChromeSnapshot() }
            return PlayerEngineChromeSnapshot(
                subtitleMenu: self.playbackState.showSubtitleMenu,
                audioMenu: self.playbackState.showAudioMenu,
                engineMenu: self.playbackState.showEngineMenu,
                settingsSheet: false,
                scrubbing: self.playbackState.isVirtualScrubbing,
                controlsVisible: self.playbackState.showUI && !self.isPreBuffering,
                chapterCount: self.playbackState.chapters.count,
                seekStripVisible: !self.isPreBuffering && self.playbackState.seekStripActive
                    && self.playbackState.showUI && !self.playbackState.userPaused
            )
        }
        chrome.apply = { [weak self] effect in
            self?.applyRemoteEffect(effect) ?? false
        }
    }

    /// Network Back closes one MPV overlay. Scrub cancel matches the physical Menu path.
    /// Directional keys move the same menus the focus ring uses.
    private func applyRemoteEffect(_ effect: PlayerRemoteEffect) -> Bool {
        if playbackState.failureSentence != nil { return true }
        switch effect {
        case .dismissSubtitleMenu:
            guard playbackState.showSubtitleMenu else { return false }
            playbackState.showSubtitleMenu = false
            return true
        case .dismissAudioMenu:
            guard playbackState.showAudioMenu else { return false }
            playbackState.showAudioMenu = false
            return true
        case .dismissEngineMenu:
            guard playbackState.showEngineMenu else { return false }
            playbackState.showEngineMenu = false
            return true
        case .dismissScrub:
            guard playbackState.isVirtualScrubbing else { return false }
            cancelVirtualScrub()
            return true
        case .dismissControls:
            return hideRemoteControls()
        case .moveHighlight(let delta):
            return moveOpenMenu(delta)
        case .activateHighlight:
            return activateOpenMenu()
        case .togglePlayback:
            togglePlayPause()
            return true
        case .seekBackward:
            seekRelative(by: -15)
            return true
        case .seekForward:
            seekRelative(by: 15)
            return true
        case .scrubBackward:
            guard playbackState.isVirtualScrubbing else { return false }
            showUI(autoHide: false)
            increaseScrubMultiplier(false)
            return true
        case .scrubForward:
            guard playbackState.isVirtualScrubbing else { return false }
            showUI(autoHide: false)
            increaseScrubMultiplier(true)
            return true
        case .commitScrub:
            guard playbackState.isVirtualScrubbing else { return false }
            commitVirtualScrub()
            return true
        case .chapterNext, .chapterPrevious:
            guard !isPreBuffering, ChapterNavigation.canJump(playbackState.chapters) else { return false }
            jumpChapter(forward: effect == .chapterNext)
            return true
        default:
            return false
        }
    }

    private func hideRemoteControls() -> Bool {
        guard playbackState.showUI, !isPreBuffering else { return false }
        hideControlsTimer?.invalidate()
        hideControlsTimer = nil
        playbackState.showUI = false
        setNeedsFocusUpdate()
        return true
    }

    private func subtitleRowIDs() -> [Int] {
        PlayerRemoteNavigation.menuRowIDs(
            trackIDs: playbackState.subtitleTracks.map(\.id) + playbackState.externalSubtitleTracks.map(\.id),
            includeOff: true
        )
    }

    private func audioRowIDs() -> [Int] {
        PlayerRemoteNavigation.menuRowIDs(trackIDs: playbackState.audioTracks.map(\.id), includeOff: false)
    }

    private func engineRowIDs() -> [Int] {
        PlayerRemoteNavigation.menuRowIDs(
            trackIDs: PlaybackEngine.menuOrder(current: .mpv).map(\.menuID),
            includeOff: false
        )
    }

    private func moveOpenMenu(_ delta: Int) -> Bool {
        if playbackState.showSubtitleMenu {
            let count = subtitleRowIDs().count
            guard count > 0 else { return false }
            playbackState.subtitleHighlight = PlayerHighlightList(
                count: count, index: playbackState.subtitleHighlight
            ).moving(delta).index
            return true
        }
        if playbackState.showAudioMenu {
            let count = audioRowIDs().count
            guard count > 0 else { return false }
            playbackState.audioHighlight = PlayerHighlightList(
                count: count, index: playbackState.audioHighlight
            ).moving(delta).index
            return true
        }
        if playbackState.showEngineMenu {
            let count = engineRowIDs().count
            guard count > 0 else { return false }
            playbackState.engineHighlight = PlayerHighlightList(
                count: count, index: playbackState.engineHighlight
            ).moving(delta).index
            return true
        }
        return false
    }

    private func activateOpenMenu() -> Bool {
        if playbackState.showSubtitleMenu {
            let ids = subtitleRowIDs()
            let index = PlayerHighlightList(count: ids.count, index: playbackState.subtitleHighlight).moving(0).index
            guard ids.indices.contains(index) else { return false }
            playbackState.subtitleHighlight = index
            selectSubtitleTrack(ids[index])
            playbackState.showSubtitleMenu = false
            return true
        }
        if playbackState.showAudioMenu {
            let ids = audioRowIDs()
            let index = PlayerHighlightList(count: ids.count, index: playbackState.audioHighlight).moving(0).index
            guard ids.indices.contains(index) else { return false }
            playbackState.audioHighlight = index
            selectAudioTrack(ids[index])
            playbackState.showAudioMenu = false
            return true
        }
        if playbackState.showEngineMenu {
            let ids = engineRowIDs()
            let index = PlayerHighlightList(count: ids.count, index: playbackState.engineHighlight).moving(0).index
            guard ids.indices.contains(index) else { return false }
            playbackState.engineHighlight = index
            let id = ids[index]
            playbackState.showEngineMenu = false
            if let target = PlaybackEngine.allCases.first(where: { $0.menuID == id }), target != .mpv {
                onSwitch?(target, playbackState.currentTime)
            }
            return true
        }
        return false
    }

    private func selectSubtitleTrack(_ trackId: Int) {
        if let option = externalSubtitleCatalog.option(for: trackId) {
            // `cached` selects an existing file if the user taps again while loading.
            // The track-list observer replaces this menu entry once loading succeeds.
            mpvQueue.async { [weak self] in
                guard let self, let handle = self.mpv else { return }
                self.mpvCommandAsync(handle, ["sub-add", option.url, "cached", option.name])
            }
            TrackPreferences.shared.subtitlesOff = false
            TrackPreferences.shared.subtitleName = option.name
            return
        }
        setPropertyAsync("sid", value: trackId < 0 ? "no" : String(trackId))
        playbackState.currentSubtitleIndex = trackId
        recordSubtitlePreference(id: trackId)
    }

    private func selectAudioTrack(_ trackId: Int) {
        setPropertyAsync("aid", value: String(trackId))
        playbackState.currentAudioIndex = trackId
        recordAudioPreference(id: trackId)
    }

    private func cancelVirtualScrub() {
        virtualScrubTickTimer?.invalidate()
        virtualScrubTickTimer = nil
        playbackState.isVirtualScrubbing = false
        playbackState.scrubMultiplier = 0
        setPropertyAsync("pause", value: "no")
        playbackState.userPaused = false
        showUI(autoHide: true)
    }

    /// Repeat-one for this item. A report from a replaced engine or item is ignored.
    private func setLooping(_ enabled: Bool) {
        guard !isMpvStopped else { return }
        let identity = PlayerReportIdentity(engine: PlaybackEngine.mpv.rawValue, generation: mediaIdentity)
        guard onLoopChanged?(enabled, identity) == true else { return }
        playbackState.isLooping = enabled
    }

    /// Map a phone `control` command to the player. Runs on main (posted from the WS server).
    /// Session speed/scaling/subtitle offset are handled by PlayerView; filter/audio_boost remain unsupported.
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
        case "seek_forward":
            seekRelative(by: 15)
        case "seek_back":
            seekRelative(by: -15)
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
        invalidateFailure()
        skipLoader.cancel()
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
        chapterToastTimer?.invalidate()
        chapterToastTimer = nil
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
