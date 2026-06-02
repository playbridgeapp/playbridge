import SwiftUI
import VLCKit

// MARK: - Enhanced VLC Player with Controls

struct VLCPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
    let onSwitch: (Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void

    func makeUIViewController(context: Context) -> VLCViewController {
        let controller = VLCViewController()
        controller.url = url
        controller.headers = headers
        controller.subtitles = subtitles
        controller.initialTime = initialTime
        controller.isPreBuffering = isPreBuffering
        controller.mediaTitle = title
        controller.onDismiss = onDismiss
        controller.onExit = onExit
        controller.onSwitch = onSwitch
        controller.onBroadcast = onBroadcast
        return controller
    }

    func updateUIViewController(_ uiViewController: VLCViewController, context: Context) {
        uiViewController.isPreBuffering = isPreBuffering
    }

    class VLCViewController: UIViewController, VLCMediaPlayerDelegate {
        var mediaPlayer: VLCMediaPlayer = VLCMediaPlayer()
        var url: URL?
        var headers: [String: String]?
        var subtitles: [String]?
        var initialTime: Double = 0.0
        var mediaTitle: String?
        private var slavesAttached: Bool = false
        var onDismiss: (() -> Void)?
        var onExit: (() -> Void)?
        var onSwitch: ((Double) -> Void)?
        var onBroadcast: (([String: Any]) -> Void)?
        private var statusTimer: Timer?
        /// The media we loaded, kept so we can restart it when looping (VLCKit 4.0 has no
        /// ".ended" replay — end arrives as ".stopped" and the input is gone).
        private var loadedMedia: VLCMedia?
        /// Set before we call mediaPlayer.stop() ourselves (teardown), so the ".stopped" state —
        /// which in VLCKit 4.0 covers both natural end AND explicit stop — isn't mistaken for
        /// end-of-video and doesn't fire onDismiss while we're tearing down.
        private var didRequestStop = false
        /// True once the embedded track lists have been read. Stops the per-second re-poll in
        /// mediaPlayerTimeChanged — enumerating tracks takes the libvlc player lock, and doing it
        /// every tick for the whole movie (which happened forever on files with no subtitle track)
        /// contends with the decoder and causes a periodic micro-hitch the real VLC app doesn't.
        private var didFetchTracks = false

        var isPreBuffering: Bool = false {
            didSet {
                if isPreBuffering != oldValue {
                    applyPreBufferingState()
                }
            }
        }
        
        private var initialTimeApplied: Bool = false
        private var ignoreTimeUpdatesUntil: Date = Date.distantPast

        // Custom focusable view to capture remote events
        class FocusableView: UIView {
            override var canBecomeFocused: Bool { true }
        }

        // UI State
        private let videoView = FocusableView()
        private var hostingController: UIHostingController<PlayerControlsOverlay>?

        override var preferredFocusEnvironments: [UIFocusEnvironment] {
            // Only route focus to the SwiftUI buttons when user explicitly paused
            if playbackState.userPaused, let hostingView = hostingController?.view {
                return [hostingView]
            }
            return [videoView]
        }

        // Data for HUD
        private var playbackState = PlayerControlsData()
        private var hideControlsTimer: Timer?
        private var continuousSeekTimer: Timer?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black

            view.isUserInteractionEnabled = true
            videoView.isUserInteractionEnabled = true

            // 1. Setup Video Surface
            videoView.frame = view.bounds
            videoView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            view.addSubview(videoView)

            playbackState.title = mediaTitle ?? ""

            // 2. Setup HUD Overlay (SwiftUI)
            setupHUD()

            // 3. Initialize Player
            setupPlayer()

            // 4. Now-playing sync + remote command handling for the phone
            startRemoteSync()

            // Show UI initially
            showUI(autoHide: true)
        }

        private func setupHUD() {
            let overlay = PlayerControlsOverlay(
                data: playbackState,
                onSelectSubtitle: { [weak self] trackId in
                    self?.selectSubtitleTrack(at: trackId)
                    self?.playbackState.currentSubtitleIndex = trackId
                },
                onSelectAudio: { [weak self] trackId in
                    self?.selectAudioTrack(at: trackId)
                    self?.playbackState.currentAudioIndex = trackId
                },
                onTogglePlayPause: { [weak self] in
                    self?.togglePlayPause()
                },
                onSwitchEngine: { [weak self] in
                    guard let self = self else { return }
                    self.onSwitch?(self.playbackState.currentTime)
                },
                onTogglePlaylist: {
                    NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
                },
                engineLabel: "VLC")
            let hosting = UIHostingController(rootView: overlay)
            hosting.view.backgroundColor = .clear
            hosting.view.frame = view.bounds
            hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

            addChild(hosting)
            view.addSubview(hosting.view)
            hosting.didMove(toParent: self)
            self.hostingController = hosting
        }

        // VLCKit 4.0 track model: `textTracks`/`audioTracks` are arrays of VLCMediaPlayerTrack.
        // We use the array index as the track id the overlay/phone work with (the old integer
        // index API was removed in 4.0), and select via `isSelectedExclusively`.

        private func updateSubtitleTracks() {
            let textTracks = mediaPlayer.textTracks
            let tracks = textTracks.enumerated().map { (id: $0.offset, name: $0.element.trackName) }
            let selected = textTracks.firstIndex(where: { $0.isSelected }) ?? -1
            DispatchQueue.main.async {
                self.playbackState.subtitleTracks = tracks
                self.playbackState.currentSubtitleIndex = selected
            }
        }

        /// Select a subtitle track by list index, or turn subtitles off when index < 0.
        private func selectSubtitleTrack(at index: Int) {
            if index < 0 {
                mediaPlayer.deselectAllTextTracks()
            } else {
                let textTracks = mediaPlayer.textTracks
                if textTracks.indices.contains(index) { textTracks[index].isSelectedExclusively = true }
            }
        }

        /// Select an audio track by list index.
        private func selectAudioTrack(at index: Int) {
            let audioTracks = mediaPlayer.audioTracks
            if audioTracks.indices.contains(index) { audioTracks[index].isSelectedExclusively = true }
        }

        private func updateAudioTracks() {
            let audioTracksList = mediaPlayer.audioTracks
            let tracks = audioTracksList.enumerated().map { (id: $0.offset, name: $0.element.trackName) }
            let selectedAudio = audioTracksList.firstIndex(where: { $0.isSelected }) ?? -1
            DispatchQueue.main.async {
                self.playbackState.audioTracks = tracks
                self.playbackState.currentAudioIndex = selectedAudio
            }
        }

        private var proxyServer: VLCProxyServer?

        private func setupPlayer() {
            mediaPlayer.delegate = self

            #if DEBUG
            // Route libvlc's internal logs (input/decoder/vout/display/http) to the Xcode console
            // so we can see WHY video isn't displaying. Very verbose — grep for "vout"/"display"/
            // "decoder"/"http". NOTE: .debug floods the log every frame and adds console-I/O
            // overhead that can skew playback smoothness — drop to .warning for smoothness testing.
            let vlcLogger = VLCConsoleLogger()
            vlcLogger.level = .warning
            mediaPlayer.libraryInstance.loggers = [vlcLogger]
            #endif

            mediaPlayer.drawable = videoView
            print("[VLC] setupPlayer: drawable=videoView bounds=\(videoView.bounds) view.bounds=\(view.bounds)")

            if let url = url {
                // Always route HTTP(S) through the local proxy. The proxy fetches via URLSession,
                // which follows 3xx redirects — VLCKit's HTTP/2 access does NOT follow the 307 that
                // hosts like aiostreams return to their CDN; it resets the stream and the media
                // never loads (black screen). URLSession also injects any custom headers. This
                // mirrors how MPV/FFmpeg play these streams. Non-HTTP URLs (file://, etc.) play
                // directly.
                let scheme = url.scheme?.lowercased()
                let playURL: URL
                if scheme == "http" || scheme == "https" {
                    var proxyHeaders = headers ?? [:]
                    // Some CDNs reject non-browser user agents; supply a browser UA if none was
                    // provided (matches the MPV path). A provided User-Agent is preserved.
                    if !proxyHeaders.keys.contains(where: { $0.caseInsensitiveCompare("user-agent") == .orderedSame }) {
                        proxyHeaders["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    }
                    let proxy = VLCProxyServer(targetURL: url, headers: proxyHeaders)
                    proxy.start()
                    self.proxyServer = proxy
                    playURL = proxy.localURL
                    print("VLC Proxy: \(url.absoluteString) -> \(playURL.absoluteString)")
                } else {
                    playURL = url
                }

                // VLCKit 4.0: VLCMedia(url:) is failable.
                guard let media = VLCMedia(url: playURL) else {
                    print("VLC: failed to create media for \(playURL)")
                    return
                }

                // Caching for tvOS streaming. network-caching is well above VLC's 999ms default
                // for resilience on flaky/throttled sources. skip-frames/drop-late-frames just
                // restate libvlc's defaults (both on). We intentionally do NOT set clock-jitter:
                // VLC leaves it at its 5000ms default, and forcing 0 disables jitter tolerance,
                // which can cause A/V resync stutter on network streams.
                // network-caching kept modest: a 15s pre-roll caused a ~15s black/buffering stall
                // before the first frame (the real VLC app uses 999ms). 3s balances fast start
                // with a small cushion for the proxy/CDN. drop-late-frames/skip-frames stay on
                // (libvlc defaults) so the player drops, rather than accumulates, late frames.
                media.addOptions([
                    "--network-caching": "3000",
                    "--drop-late-frames": "1",
                    "--skip-frames": "1",
                ])

                loadedMedia = media
                mediaPlayer.media = media
                applyPreBufferingState()
                mediaPlayer.play()
            }
        }
        
        private func attachSlavesIfNeeded() {
            guard !slavesAttached else { return }
            slavesAttached = true
            // Senders (e.g. Stremio) attach dozens of subtitle URLs. Adding every one as a VLC
            // slave fetches each over the network and bogs the player down — switching audio or
            // subtitle tracks then lags badly. Attach only the first (the sender orders them by
            // preference); the media's own embedded tracks remain available in the menu.
            guard let first = subtitles?.first, let url = URL(string: first) else { return }
            let rc = mediaPlayer.addPlaybackSlave(url, type: .subtitle, enforce: false)
            print("VLC: addPlaybackSlave subtitle=\(url.absoluteString) rc=\(rc)")
            // The slave arrives after the initial parse — refresh the track list so the new
            // entry appears in the Subtitles menu.
            updateSubtitleTracks()
        }

        private func applyPreBufferingState() {
            mediaPlayer.audio?.isMuted = isPreBuffering
            if isPreBuffering {
                playbackState.showUI = false
                hideControlsTimer?.invalidate()
            } else {
                showUI(autoHide: true)
            }
        }

        private static func stateName(_ s: VLCMediaPlayerState) -> String {
            switch s {
            case .stopped:   return "stopped"
            case .stopping:  return "stopping"
            case .opening:   return "opening"
            case .buffering: return "buffering"
            case .error:     return "error"
            case .playing:   return "playing"
            case .paused:    return "paused"
            @unknown default: return "unknown(\(s.rawValue))"
            }
        }

        // MARK: - VLCMediaPlayerDelegate
        // VLCKit 4.0 passes the new state directly (3.x passed an NSNotification).
        func mediaPlayerStateChanged(_ newState: VLCMediaPlayerState) {
            DispatchQueue.main.async {
                // Diagnostics for the "decodes but black screen" issue. hasVideoOut is the key
                // signal: false => libvlc never built a video output (vout/drawable problem);
                // true with a zero videoSize => stream/decoder problem; true + non-zero + still
                // black => a view/layout/compositing problem on our side.
                print("[VLC] state=\(Self.stateName(newState)) playing=\(self.mediaPlayer.isPlaying) hasVideoOut=\(self.mediaPlayer.hasVideoOut) videoSize=\(self.mediaPlayer.videoSize) videoTracks=\(self.mediaPlayer.videoTracks.count) viewBounds=\(self.videoView.bounds)")
                self.playbackState.isPlaying = self.mediaPlayer.isPlaying
                self.broadcastStatus()
                // libvlc requires the input to be running before slaves can be
                // attached to the *current* playback. Hook .opening (and .playing
                // as a fallback) so external SRT/VTT URLs from the phone show up
                // in the Subtitles menu.
                if newState == .opening || newState == .playing {
                    self.attachSlavesIfNeeded()
                }
                if newState == .playing {
                    // Clear userPaused when VLC resumes
                    self.playbackState.userPaused = false
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                    self.broadcastTracks()
                }
                // 4.0 has no ".ended" — natural end arrives as ".stopped" (after ".stopping").
                // Ignore the ".stopped" we cause ourselves on teardown (didRequestStop).
                if newState == .stopped, !self.didRequestStop {
                    if self.playbackState.isLooping, let media = self.loadedMedia {
                        self.mediaPlayer.media = media   // input is gone after stop; re-arm it
                        self.mediaPlayer.play()
                    } else {
                        self.onDismiss?()
                    }
                }
                if newState == .error {
                    print("VLC Error: Playback failed")
                }
            }
        }

        func mediaPlayerTimeChanged(_ aNotification: Notification) {
            DispatchQueue.main.async {
                if !self.initialTimeApplied && self.initialTime > 0 {
                    if let media = self.mediaPlayer.media, media.length.intValue > 0 {
                        self.mediaPlayer.time = VLCTime(int: Int32(self.initialTime * 1000))
                        self.initialTimeApplied = true
                        return
                    }
                }
                
                if Date() > self.ignoreTimeUpdatesUntil {
                    self.playbackState.currentTime = Double(self.mediaPlayer.time.intValue) / 1000.0
                }
                
                if let media = self.mediaPlayer.media {
                    let length = Double(media.length.intValue) / 1000.0
                    if length > 0 {
                        self.playbackState.duration = length
                    }
                }
                // Fetch the track lists once, shortly after playback starts (they may not be
                // parsed yet at the first .playing state change). Stop as soon as audio tracks
                // appear — every playable file has audio, so this self-terminates instead of
                // re-enumerating tracks (and taking the player lock) on every tick forever.
                if !self.didFetchTracks {
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                    if !self.mediaPlayer.audioTracks.isEmpty { self.didFetchTracks = true }
                }
            }
        }

        // MARK: - Interactions
        private var holdTimer: Timer?
        private var virtualScrubTickTimer: Timer?

        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            // During pre-buffering, PrePlayView owns all input — pass everything through.
            if isPreBuffering {
                super.pressesBegan(presses, with: event)
                return
            }

            guard let type = presses.first?.type else {
                super.pressesBegan(presses, with: event)
                return
            }

            // Intercept Menu/Back button to close popups before exiting
            if type == .menu {
                if playbackState.showSubtitleMenu {
                    playbackState.showSubtitleMenu = false
                    return
                }
                if playbackState.showAudioMenu {
                    playbackState.showAudioMenu = false
                    return
                }
                if playbackState.isVirtualScrubbing {
                    // Abort scrub: cancel the tick timer and restore original position
                    virtualScrubTickTimer?.invalidate()
                    virtualScrubTickTimer = nil
                    playbackState.isVirtualScrubbing = false
                    playbackState.scrubMultiplier = 0
                    mediaPlayer.play()
                    showUI(autoHide: true)
                    return
                }
                
                // Exit the video
                onExit?()
                return
            }

            // Let menus handle their own navigation input
            if playbackState.showSubtitleMenu || playbackState.showAudioMenu {
                super.pressesBegan(presses, with: event)
                return
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
                    showUI()
                    // Already scrubbing, just increment multiplier
                    increaseScrubMultiplier(forward)
                } else if !playbackState.userPaused {
                    showUI()
                    // Start hold timer to detect Siri Remote continuous hold
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
            
            if let type = presses.first?.type, (type == .leftArrow || type == .rightArrow) {
                if !playbackState.userPaused || playbackState.isVirtualScrubbing {
                    // If hold timer is still valid, it was a click, not a hold.
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

        // MARK: - Virtual Scrubbing
        
        private func startVirtualScrub(forward: Bool) {
            playbackState.isVirtualScrubbing = true
            playbackState.virtualTime = playbackState.currentTime
            mediaPlayer.pause() // Freeze video behind UI
            
            showUI(autoHide: false) // Lock UI permanently while scrubbing
            increaseScrubMultiplier(forward)
            
            virtualScrubTickTimer?.invalidate()
            virtualScrubTickTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                DispatchQueue.main.async {
                    // Each scale tick represents multiple seconds per real second
                    let speedFactor = 12.0 
                    let delta = Double(self.playbackState.scrubMultiplier) * speedFactor * 0.05
                    
                    var nextTime = self.playbackState.virtualTime + delta
                    if self.playbackState.duration > 0 {
                        nextTime = max(0, min(nextTime, self.playbackState.duration))
                    } else {
                        nextTime = max(0, nextTime)
                    }
                    self.playbackState.virtualTime = nextTime
                }
            }
        }
        
        private func increaseScrubMultiplier(_ forward: Bool) {
            let dir = forward ? 1 : -1
            
            if playbackState.scrubMultiplier == 0 {
                playbackState.scrubMultiplier = dir
            } else if (playbackState.scrubMultiplier > 0 && forward) || (playbackState.scrubMultiplier < 0 && !forward) {
                // Increment magnitude up to 8x
                let magnitude = min(abs(playbackState.scrubMultiplier) + 1, 8)
                playbackState.scrubMultiplier = magnitude * dir
            } else {
                // Changing directions resets
                playbackState.scrubMultiplier = dir
            }
        }
        
        private func commitVirtualScrub() {
            virtualScrubTickTimer?.invalidate()
            virtualScrubTickTimer = nil
            
            // Preemptively snap the UI to the correct timeline
            playbackState.currentTime = playbackState.virtualTime
            ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75) // Lock out stale VLC ticks
            
            playbackState.isVirtualScrubbing = false
            playbackState.scrubMultiplier = 0
            
            mediaPlayer.time = VLCTime(int: Int32(playbackState.virtualTime * 1000))
            mediaPlayer.play()
            showUI(autoHide: true)
        }



        @objc private func togglePlayPause() {
            if mediaPlayer.isPlaying {
                mediaPlayer.pause()
                playbackState.userPaused = true
                // Refresh tracks right before showing controls
                updateSubtitleTracks()
                updateAudioTracks()
                // Pausing: show controls permanently and give focus to buttons
                showUI(autoHide: false)
            } else {
                mediaPlayer.play()
                playbackState.userPaused = false
                // Resuming: show controls briefly then auto-hide
                showUI(autoHide: true)
            }
        }

        @objc private func skipForward() {
            guard !playbackState.userPaused else { return }
            let currentTime = mediaPlayer.time.intValue
            let duration = mediaPlayer.media?.length.intValue ?? 0

            var target = currentTime + 15000
            if duration > 0 {
                target = min(target, duration - 2000)
            }

            if target > currentTime {
                // Optimistic visual update
                playbackState.currentTime = Double(target) / 1000.0
                ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
                
                mediaPlayer.time = VLCTime(int: target)
            }
            showUI()
        }

        @objc private func skipBackward() {
            guard !playbackState.userPaused else { return }
            let currentTime = mediaPlayer.time.intValue
            let target = max(0, currentTime - 15000)

            // Optimistic visual update
            playbackState.currentTime = Double(target) / 1000.0
            ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
            
            mediaPlayer.time = VLCTime(int: target)
            showUI()
        }

        private func showUI(autoHide: Bool = true) {
            DispatchQueue.main.async {
                self.playbackState.showUI = true
                self.setNeedsFocusUpdate()
                
                self.hideControlsTimer?.invalidate()
                if autoHide && !self.playbackState.isVirtualScrubbing {
                    self.hideControlsTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false)
                    { [weak self] _ in
                        DispatchQueue.main.async {
                            withAnimation {
                                self?.playbackState.showUI = false
                                self?.setNeedsFocusUpdate()
                            }
                        }
                    }
                }
            }
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
                "state": mediaPlayer.isPlaying ? "playing" : "paused",
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
            broadcastStatus()
        }

        @objc private func onRemoteNotification(_ note: Notification) {
            guard let key = note.userInfo?["key"] as? String else { return }
            switch key {
            case "dpad_center": togglePlayPause()
            case "dpad_left":   seek(toMs: max(0, mediaPlayer.time.intValue - 15000))
            case "dpad_right":  seek(toMs: mediaPlayer.time.intValue + 15000)
            default:            break
            }
        }

        /// Map a phone `control` command to VLC. Runs on main (posted from the WS server).
        /// Speed/scaling/filter/audio_boost/sub_offset are not yet supported on VLC → ignored.
        private func handleControlCommand(_ cmd: String) {
            switch cmd {
            case "play":
                if !mediaPlayer.isPlaying { togglePlayPause() }
            case "pause":
                if mediaPlayer.isPlaying { togglePlayPause() }
            case "play_pause", "toggle":
                togglePlayPause()
            case "stop":
                onExit?()
            case "loop_on":
                playbackState.isLooping = true
            case "loop_off":
                playbackState.isLooping = false
            case "seek_forward":
                seek(toMs: mediaPlayer.time.intValue + 15000)
            case "seek_back":
                seek(toMs: max(0, mediaPlayer.time.intValue - 15000))
            case let c where c.hasPrefix("seek_to:"):
                if let ms = Int32(c.dropFirst("seek_to:".count)) { seek(toMs: ms) }
            case let c where c.hasPrefix("audio_track:"):
                if let id = Int(c.dropFirst("audio_track:".count)) {
                    selectAudioTrack(at: id)
                    playbackState.currentAudioIndex = id
                    updateAudioTracks()
                    broadcastTracks()
                }
            case let c where c.hasPrefix("sub_track:"):
                let raw = String(c.dropFirst("sub_track:".count))
                if raw == "none" || raw == "-1" {
                    selectSubtitleTrack(at: -1)
                    playbackState.currentSubtitleIndex = -1
                } else if let id = Int(raw) {
                    selectSubtitleTrack(at: id)
                    playbackState.currentSubtitleIndex = id
                }
                updateSubtitleTracks()
                broadcastTracks()
            case let c where c.hasPrefix("add_subtitle:"):
                let urlStr = String(c.dropFirst("add_subtitle:".count))
                guard let url = URL(string: urlStr) else { break }
                _ = mediaPlayer.addPlaybackSlave(url, type: .subtitle, enforce: true)
                updateSubtitleTracks()
                broadcastTracks()
            case let c where c.hasPrefix("switch_player:"):
                // Any non-vlc target hands off to PlayerView's engine cycle.
                if String(c.dropFirst("switch_player:".count)) != "vlc" { onSwitch?(playbackState.currentTime) }
            default:
                break
            }
        }

        /// Seek with the same optimistic-update + stale-tick lockout used by the on-screen skip.
        private func seek(toMs ms: Int32) {
            let clamped = max(0, ms)
            playbackState.currentTime = Double(clamped) / 1000.0
            ignoreTimeUpdatesUntil = Date().addingTimeInterval(0.75)
            mediaPlayer.time = VLCTime(int: clamped)
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            statusTimer?.invalidate()
            statusTimer = nil
            NotificationCenter.default.removeObserver(self)
            didRequestStop = true   // suppress end-of-video handling for our own stop (4.0 ".stopped")
            mediaPlayer.stop()
            proxyServer?.stop()
            proxyServer = nil
        }
    }
}
