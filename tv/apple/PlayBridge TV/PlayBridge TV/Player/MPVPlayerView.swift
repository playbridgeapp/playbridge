import SwiftUI
import AVFoundation
import AVKit
import MPVKit

// MARK: - SwiftUI representable

struct MPVPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void
    let onExit: () -> Void
    let onSwitch: (Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void

    func makeUIViewController(context: Context) -> MPVViewController {
        let vc = MPVViewController()
        vc.url = url
        vc.headers = headers
        vc.subtitles = subtitles
        vc.initialTime = initialTime
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
        if uiViewController.url != url {
            uiViewController.loadNewItem(
                url: url,
                headers: headers,
                subtitles: subtitles,
                initialTime: initialTime,
                title: title
            )
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
    var mediaTitle: String?
    var onDismiss: (() -> Void)?
    var onExit: (() -> Void)?
    var onSwitch: ((Double) -> Void)?
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
    /// Opaque pointer to a +1-retained `self` handed to mpv's wakeup callback. Keeping self
    /// alive while the callback is installed means the callback never forms a weak reference
    /// to a deallocating object. Balanced (released) once in `teardown()`.
    private var callbackSelfPtr: UnsafeMutableRawPointer?

    // MARK: Rendering — AVSampleBufferDisplayLayer fed by vo=avfoundation
    private let displayLayer = AVSampleBufferDisplayLayer()

    // MARK: UI
    private var playbackState = PlayerControlsData()
    private var hostingController: UIHostingController<PlayerControlsOverlay>?
    private var hideControlsTimer: Timer?
    private var holdTimer: Timer?
    private var virtualScrubTickTimer: Timer?
    private var ignoreTimeUpdatesUntil: Date = .distantPast
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

        displayLayer.frame = videoView.bounds
        displayLayer.videoGravity = .resizeAspect
        displayLayer.backgroundColor = UIColor.black.cgColor
        videoView.layer.addSublayer(displayLayer)

        playbackState.title = mediaTitle ?? ""
        setupHUD()
        setupMPV()
        startRemoteSync()
        showUI(autoHide: true)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = videoView.bounds
        CATransaction.commit()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        teardown()
    }

    deinit {
        teardown()
    }

    // MARK: - MPV Initialisation

    private func setupMPV() {
        // Configure the audio session BEFORE mpv initialises its audio unit. If the session is
        // still the default (non-playback) category when mpv's `ao` starts, audio is silent until
        // the route is renegotiated — the "no sound for the first ~minute" symptom.
        configureAudioSession()

        guard let handle = mpv_create() else {
            print("[MPV] mpv_create() failed")
            return
        }
        mpv = handle

        // Pass the display layer pointer so vo_avfoundation can render into it
        var widVal = Int64(Int(bitPattern: Unmanaged.passUnretained(displayLayer).toOpaque()))
        mpv_set_option(handle, "wid", MPV_FORMAT_INT64, &widVal)

        // vo=avfoundation renders video frames directly into AVSampleBufferDisplayLayer
        mpv_set_option_string(handle, "vo", "avfoundation")

        // MUST follow vo=avfoundation immediately, before any hwdec option.
        // On tvOS "yes" causes a freeze when the player exits; "no" is correct here.
        // On iOS "yes" is needed for PiP subtitle compositing (not applicable on tvOS).
        mpv_set_option_string(handle, "avfoundation-composite-osd", "no")

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

        // Use dav1d for AV1 — the fast, well-threaded software decoder VLC also uses. This only
        // takes effect now that av1 is out of hwdec-codecs (above). dav1d decodes only AV1, so it
        // has no effect on H.264/HEVC, which keep their VideoToolbox hardware path.
        mpv_set_option_string(handle, "vd", "libdav1d")

        // Drop late frames at the decoder when the pipeline can't keep up, instead of letting
        // audio/video desync. This is the safety valve for software-decoded 4K AV1 (no Apple TV
        // has HW AV1, and dav1d can't always sustain 2160p in real time on these cores). It's the
        // mpv equivalent of the `--skip-frames=1 --drop-late-frames=1` we already give VLC — which
        // is the only reason VLC looks smooth on these files. Default framedrop ("vo") only drops
        // at display and doesn't relieve decode load; "decoder+vo" lets mpv skip decoding frames
        // it's already late for. Harmless for HW-decoded HEVC/H.264 (it triggers only when behind).
        mpv_set_option_string(handle, "framedrop", "decoder+vo")

        // Buffering for high-bitrate remuxes (4K HEVC, ~80–100 Mbps). mpv's defaults read only
        // ~1s ahead, so a momentary network/NAS shortfall drains the buffer and audio/video drop
        // out — VLC rides over this with its 15s cache. Widen the demuxer read-ahead (bounded to
        // 256 MiB so tvOS doesn't jetsam-kill us on a 56 GB file) and grow the audio output buffer
        // from its ~200ms default to 1s so a brief audio-decode/scheduling stall can't underrun.
        mpv_set_option_string(handle, "cache", "yes")
        mpv_set_option_string(handle, "demuxer-max-bytes", "256MiB")
        mpv_set_option_string(handle, "demuxer-max-back-bytes", "64MiB")
        mpv_set_option_string(handle, "demuxer-readahead-secs", "30")
        mpv_set_option_string(handle, "audio-buffer", "1.0")

        // Signal content colourspace to the display system so tvOS can switch HDR modes
        mpv_set_option_string(handle, "target-colorspace-hint", "yes")

        // Force the iOS/tvOS audio output. Auto-detection can probe an output that blocks and
        // only fall back after a ~minute timeout — that's the "audio appears after ~1 min" symptom.
        mpv_set_option_string(handle, "ao", "audiounit")

        // Subtitle defaults
        mpv_set_option_string(handle, "sub-scale-with-window", "no")
        mpv_set_option_string(handle, "sub-use-margins", "no")
        mpv_set_option_string(handle, "subs-match-os-language", "yes")
        mpv_set_option_string(handle, "subs-fallback", "yes")

        #if DEBUG
        mpv_request_log_messages(handle, "v")  // verbose: capture cache/underrun/sync events
        #else
        mpv_request_log_messages(handle, "no")
        #endif

        guard mpv_initialize(handle) >= 0 else {
            print("[MPV] mpv_initialize() failed")
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

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback, policy: .longFormAudio, options: [])
            try session.setActive(true)
        } catch {
            print("[MPV] audio session config failed: \(error)")
        }
    }

    private func activateAudioSession() {
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    /// Swap in the next item on the live mpv core (episode advance). All per-item
    /// state is reset; the handle, render context, and demuxer caches survive.
    func loadNewItem(url: URL, headers: [String: String]?, subtitles: [String]?,
                     initialTime: Double, title: String?) {
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

        loadFile(url)
    }

    private func loadFile(_ url: URL) {
        guard mpv != nil else { return }   // re-bound to the live handle inside mpvQueue below
        pendingExternalSubtitles = subtitles ?? []
        // Clear buffered-ahead state so a looped/next file doesn't flash the prior buffer.
        cacheAheadSec = 0
        playbackState.bufferedTime = 0
        loadStartTime = Date()

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
            let path = url.isFileURL ? url.path : url.absoluteString
            self.mpvCommand(handle, ["loadfile", path, "replace"])
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
                // `reason` is a plain int in the C struct; compare against the
                // enum's raw values.
                let reason = UInt32(bitPattern: efPtr.pointee.reason)
                guard reason == MPV_END_FILE_REASON_EOF.rawValue
                        || reason == MPV_END_FILE_REASON_ERROR.rawValue else {
                    break
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
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

        detectAndApplyHDR(handle: handle)
        if isPreBuffering {
            mpv_set_property_string(handle, "mute", "yes")
        }

        // Surface embedded audio/subtitle tracks right away. (mpvQueue context; updateTracks
        // hops its UI assignment to main.)
        updateTracks()

        // Attach only the FIRST external subtitle. Senders like Stremio attach dozens of subtitle
        // URLs; adding them all makes mpv open each over the network and bloats the track list, so
        // switching audio/subtitle tracks lags badly. The sender orders them by preference, so the
        // first is the best match; the media's own embedded tracks remain selectable. Async so the
        // one fetch never blocks this serial event queue.
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
                guard let self, Date() > self.ignoreTimeUpdatesUntil else { return }
                self.playbackState.currentTime = val
                self.playbackState.bufferedTime = val + self.cacheAheadSec
            }

        case "demuxer-cache-duration":
            guard prop.format == MPV_FORMAT_DOUBLE,
                  let val = prop.data?.assumingMemoryBound(to: Double.self).pointee else { break }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.cacheAheadSec = val
                self.playbackState.bufferedTime = self.playbackState.currentTime + val
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
            }

        case "current-ao":
            // mpv's audio output just came up — make sure our session is active so it isn't muted.
            if let handle = mpv {
                let ao = stringProperty(handle, "current-ao") ?? "nil"
                let elapsed = String(format: "%.1f", Date().timeIntervalSince(loadStartTime))
                print("[MPV] audio output up: \(ao) at +\(elapsed)s")
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

    /// Enumerate tracks. MUST be called on `mpvQueue` (never the main thread): this MPVKit
    /// build's vo=avfoundation does work on the main thread, so a synchronous mpv_* call from
    /// main can deadlock against the video output while it holds mpv's core lock.
    private func updateTracks() {
        dispatchPrecondition(condition: .notOnQueue(.main))
        guard let handle = mpv else { return }

        var count: Int64 = 0
        mpv_get_property(handle, "track-list/count", MPV_FORMAT_INT64, &count)

        var audioTracks: [(id: Int, name: String)] = []
        var subtitleTracks: [(id: Int, name: String)] = []

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
            case "sub":   subtitleTracks.append((id: Int(trackId), name: displayName))
            default:      break
            }
        }

        var currentAid: Int64 = 0
        var currentSid: Int64 = 0
        mpv_get_property(handle, "aid", MPV_FORMAT_INT64, &currentAid)
        mpv_get_property(handle, "sid", MPV_FORMAT_INT64, &currentSid)

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.playbackState.audioTracks = audioTracks
            self.playbackState.subtitleTracks = subtitleTracks
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
            onSwitchEngine: { [weak self] in
                guard let self else { return }
                self.onSwitch?(self.playbackState.currentTime)
            },
            onTogglePlaylist: {
                NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
            },
            engineLabel: "MPV"
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
        if isPreBuffering {
            setPropertyAsync("mute", value: "yes")
            playbackState.showUI = false
            hideControlsTimer?.invalidate()
        } else {
            setPropertyAsync("mute", value: "no")
            showUI(autoHide: true)
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
        // mpv synchronously — a main-thread mpv_* call can deadlock against vo=avfoundation.
        if playbackState.isPlaying {
            setPropertyAsync("pause", value: "yes")
            playbackState.userPaused = true
            mpvQueue.async { [weak self] in self?.updateTracks() }
            showUI(autoHide: false)
        } else {
            setPropertyAsync("pause", value: "no")
            playbackState.userPaused = false
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
        guard let handle = mpv else { return }
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
        if isPreBuffering { super.pressesBegan(presses, with: event); return }
        guard let type = presses.first?.type else { super.pressesBegan(presses, with: event); return }

        if type == .menu {
            if playbackState.showSubtitleMenu  { playbackState.showSubtitleMenu = false; return }
            if playbackState.showAudioMenu     { playbackState.showAudioMenu = false; return }
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
            onExit?()
            return
        }

        if playbackState.showSubtitleMenu || playbackState.showAudioMenu {
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
        // User-Agent is handled separately via the "user-agent" option; exclude it here.
        let filtered = headers?.filter {
            $0.key.caseInsensitiveCompare("user-agent") != .orderedSame
        }

        guard let filtered, !filtered.isEmpty else {
            mpv_set_property(handle, "http-header-fields", MPV_FORMAT_NONE, nil)
            return
        }

        // http-header-fields is OPT_STRINGLIST in mpv: comma-separated entries.
        // Literal commas and backslashes inside values must be escaped.
        let str = filtered.map { key, value in
            let escaped = value
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: ",", with: "\\,")
            return "\(key): \(escaped)"
        }.joined(separator: ",")

        mpv_set_property_string(handle, "http-header-fields", str)
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
            self, selector: #selector(onControlNotification(_:)),
            name: WebSocketServer.controlCommand, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onRemoteNotification(_:)),
            name: WebSocketServer.remoteKey, object: nil)
        NotificationCenter.default.addObserver(
            self, selector: #selector(onResyncRequest),
            name: WebSocketServer.resyncRequest, object: nil)

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
            "position": Int(max(0, playbackState.currentTime) * 1000),
            "duration": Int(max(0, playbackState.duration) * 1000),
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

    @objc private func onControlNotification(_ note: Notification) {
        guard let cmd = note.userInfo?["command"] as? String else { return }
        handleControlCommand(cmd)
    }

    @objc private func onRemoteNotification(_ note: Notification) {
        guard let key = note.userInfo?["key"] as? String else { return }
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
        switch cmd {
        case "play", "pause", "play_pause", "toggle":
            togglePlayPause()
        case "stop":
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
            // MPV's only alternative engine is AVPlayer; any non-mpv target switches to it.
            if String(c.dropFirst("switch_player:".count)) != "mpv" { onSwitch?(playbackState.currentTime) }
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

        statusTimer?.invalidate()
        statusTimer = nil
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

        // Drain pending mpvQueue work, send quit, drain remaining events
        mpvQueue.sync { [weak self] in
            guard let self else { return }
            self.mpvCommand(handle, ["quit"])
            var n = 0
            while n < 100, let evPtr = mpv_wait_event(handle, 0.1) {
                let id = evPtr.pointee.event_id
                if id == MPV_EVENT_NONE || id == MPV_EVENT_SHUTDOWN { break }
                n += 1
            }
        }

        mpv = nil

        // mpv_terminate_destroy cannot be called while blocking the main thread on tvOS —
        // AVFoundation cleanup inside mpv needs the main thread and will deadlock otherwise.
        DispatchQueue.global(qos: .userInitiated).async {
            mpv_terminate_destroy(handle)
        }

        resetDisplayCriteria()

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if #available(tvOS 17.0, *) {
                self.displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
            } else {
                self.displayLayer.flushAndRemoveImage()
            }
        }
    }

    /// Balances the `passRetained(self)` from `setupMPV`. Called exactly once during teardown.
    private func releaseCallbackSelf() {
        guard let ptr = callbackSelfPtr else { return }
        callbackSelfPtr = nil
        Unmanaged<MPVViewController>.fromOpaque(ptr).release()
    }
}
