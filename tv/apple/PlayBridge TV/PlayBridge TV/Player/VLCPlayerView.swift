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
        /// Last state we acted on. VLCKit fires mediaPlayerStateChanged repeatedly with the same
        /// state during buffering (once per % update); we de-dupe so the broadcast/print/track
        /// work only runs on real transitions — that per-tick work competes with the decoder.
        private var lastReportedState: VLCMediaPlayerState?
        // Reload bookkeeping (GLES2 switch / proxy fallback). resolvedPlayURL/-Headers are the
        // values startMedia last used, so a reload can recreate the media identically.
        private var didSwitchVout = false
        private var resolvedPlayURL: URL?
        private var resolvedUseNativeHeaders = false
        private var originalURL: URL?              // the cast URL — re-resolved via proxy on fallback
        private var isPlayingViaProxy = false      // current playback goes through the relay
        private var didFallbackToProxy = false     // only fall back from direct → proxy once
        private var redirectResolver: RedirectResolver?  // retained during the resolve pre-flight

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
        /// Records the pick in [TrackPreferences] so the next episode (fresh player)
        /// re-applies it by track name.
        private func selectSubtitleTrack(at index: Int) {
            if index < 0 {
                mediaPlayer.deselectAllTextTracks()
                TrackPreferences.shared.subtitlesOff = true
                TrackPreferences.shared.subtitleName = nil
            } else {
                let textTracks = mediaPlayer.textTracks
                if textTracks.indices.contains(index) {
                    textTracks[index].isSelectedExclusively = true
                    TrackPreferences.shared.subtitlesOff = false
                    TrackPreferences.shared.subtitleName = textTracks[index].trackName
                }
            }
        }

        /// Select an audio track by list index. Records the pick (see above).
        private func selectAudioTrack(at index: Int) {
            let audioTracks = mediaPlayer.audioTracks
            if audioTracks.indices.contains(index) {
                audioTracks[index].isSelectedExclusively = true
                TrackPreferences.shared.audioName = audioTracks[index].trackName
            }
        }

        /// Re-apply the session's track preferences (by display name) once tracks are
        /// enumerated, so picks carry across episodes. Selects directly — bypassing the
        /// recording setters — so applying never overwrites the stored preference.
        private func applyPreferredTracks() {
            let prefs = TrackPreferences.shared
            if let name = prefs.audioName,
               let idx = mediaPlayer.audioTracks.firstIndex(where: { $0.trackName == name }) {
                mediaPlayer.audioTracks[idx].isSelectedExclusively = true
            }
            if prefs.subtitlesOff {
                mediaPlayer.deselectAllTextTracks()
            } else if let name = prefs.subtitleName,
                      let idx = mediaPlayer.textTracks.firstIndex(where: { $0.trackName == name }) {
                mediaPlayer.textTracks[idx].isSelectedExclusively = true
            }
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

        private static let browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private func setupPlayer() {
            // Start on the default vout (samplebufferdisplay): correct + efficient for hardware-
            // decoded frames (already CVPixelBuffers) and it does proper HDR/Dolby Vision passthrough
            // on tvOS. Only software-decoded AV1 (dav1d) suffers there — it needs a CPU swscale of
            // every frame — so once we detect AV1 we reload the player onto the GLES2 vout (GPU
            // color-convert). Non-AV1 content is never touched. See switchToGLESVout().
            mediaPlayer.delegate = self

            #if DEBUG
            // Route libvlc's internal logs (input/decoder/vout/display/http) to the Xcode console.
            // .warning for normal use — .debug floods every frame and its console I/O skews
            // playback smoothness. Bump to .debug to diagnose a specific failure.
            let vlcLogger = VLCConsoleLogger()
            vlcLogger.level = .warning
            mediaPlayer.libraryInstance.loggers = [vlcLogger]
            #endif

            mediaPlayer.drawable = videoView
            print("[VLC] setupPlayer: drawable=videoView bounds=\(videoView.bounds) view.bounds=\(view.bounds)")

            guard let url = url else { return }
            originalURL = url
            let scheme = url.scheme?.lowercased()
            guard scheme == "http" || scheme == "https" else {
                startMedia(playURL: url, useNativeHeaders: false, viaProxy: false)   // file:// etc.
                return
            }

            // The local proxy (URLSession) must relay the whole stream when headers beyond
            // User-Agent/Referer are needed (VLC can't inject those itself). Otherwise we only use
            // URLSession to RESOLVE the redirect chain once, then let VLC play the final URL
            // DIRECTLY (native UA/Referer) — no relay, which is lighter and matches the VLC app.
            // If the resolved URL later errors (e.g. expired token), we fall back to the relay.
            let complexHeaders = (headers ?? [:]).keys.contains { k in
                let l = k.lowercased(); return l != "user-agent" && l != "referer"
            }
            if complexHeaders {
                startViaProxy(url)
            } else {
                resolveFinalURL(for: url) { [weak self] finalURL in
                    guard let self, !self.didRequestStop else { return }
                    if let finalURL = finalURL {
                        if finalURL != url { print("[VLC] resolved \(url.host ?? "?") -> \(finalURL.host ?? "?") (direct)") }
                        self.startMedia(playURL: finalURL, useNativeHeaders: true, viaProxy: false)
                    } else {
                        // Couldn't resolve — relay through the proxy (handles redirects robustly).
                        self.startViaProxy(url)
                    }
                }
            }
        }

        /// Relay through the local proxy: URLSession follows redirects and injects all headers.
        private func startViaProxy(_ url: URL) {
            var proxyHeaders = headers ?? [:]
            if !proxyHeaders.keys.contains(where: { $0.caseInsensitiveCompare("user-agent") == .orderedSame }) {
                proxyHeaders["User-Agent"] = Self.browserUA
            }
            let proxy = VLCProxyServer(targetURL: url, headers: proxyHeaders)
            proxy.start()
            self.proxyServer = proxy
            print("VLC Proxy: \(url.absoluteString) -> \(proxy.localURL.absoluteString)")
            startMedia(playURL: proxy.localURL, useNativeHeaders: false, viaProxy: true)
        }

        /// Create the media and start playback. `useNativeHeaders` adds VLC's native UA/Referer
        /// options (direct path only); `viaProxy` records whether playback is relayed (so an error
        /// on the direct path can fall back to the relay, but a relay error doesn't loop).
        private func startMedia(playURL: URL, useNativeHeaders: Bool, viaProxy: Bool) {
            resolvedPlayURL = playURL
            resolvedUseNativeHeaders = useNativeHeaders
            isPlayingViaProxy = viaProxy
            guard let media = VLCMedia(url: playURL) else {   // VLCKit 4.0: VLCMedia(url:) is failable
                print("VLC: failed to create media for \(playURL)")
                return
            }
            // network-caching kept modest: a 15s pre-roll caused a long black/buffering stall before
            // the first frame (the real VLC app uses 999ms). 3s = fast start + small cushion.
            // drop-late-frames/skip-frames stay on (libvlc defaults) so late frames are dropped.
            media.addOptions([
                "--network-caching": "3000",
                "--drop-late-frames": "1",
                "--skip-frames": "1",
            ])
            if useNativeHeaders, let headers = headers {
                if let ua = headers.first(where: { $0.key.caseInsensitiveCompare("user-agent") == .orderedSame })?.value {
                    media.addOption(":http-user-agent=\(ua)")
                }
                if let ref = headers.first(where: { $0.key.caseInsensitiveCompare("referer") == .orderedSame })?.value {
                    media.addOption(":http-referrer=\(ref)")
                }
            }
            loadedMedia = media
            mediaPlayer.media = media
            applyPreBufferingState()
            mediaPlayer.play()
        }

        /// Resolve `url` through its redirect chain via URLSession (which follows 3xx) and return
        /// the final URL — what VLC should play directly. Uses a 1-byte ranged GET cancelled right
        /// after the response headers, so it mimics VLC's own GET (rather than a HEAD some servers
        /// treat differently) without downloading the body. Completion runs on main; nil = failure.
        private func resolveFinalURL(for url: URL, completion: @escaping (URL?) -> Void) {
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.setValue("bytes=0-1", forHTTPHeaderField: "Range")
            req.timeoutInterval = 8
            if let headers = headers {
                for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
            }
            redirectResolver = RedirectResolver(request: req) { [weak self] finalURL in
                self?.redirectResolver = nil
                completion(finalURL)   // nil on failure → caller relays through the proxy
            }
        }

        // MARK: - AV1 → GLES2 vout reload

        /// Is the current video track AV1? (Checked once playback starts and tracks are known.)
        private func currentVideoIsAV1() -> Bool {
            func isAV1(_ codecFourCC: UInt32) -> Bool {
                Self.fourCCString(codecFourCC).lowercased() == "av01"
            }
            return mediaPlayer.videoTracks.contains { isAV1($0.codec) || isAV1($0.fourcc) }
        }

        private static func fourCCString(_ f: UInt32) -> String {
            let bytes: [UInt8] = [UInt8(f & 0xff), UInt8((f >> 8) & 0xff), UInt8((f >> 16) & 0xff), UInt8((f >> 24) & 0xff)]
            return (String(bytes: bytes, encoding: .ascii) ?? "")
                .trimmingCharacters(in: CharacterSet(charactersIn: "\0 "))
        }

        /// Reload the current media onto the GLES2 vout so dav1d's software I420 frames are
        /// color-converted on the GPU instead of via a CPU swscale. Done once, only for AV1.
        private func switchToGLESVout() {
            guard !didSwitchVout, let playURL = resolvedPlayURL else { return }
            didSwitchVout = true
            let resumeSec = max(0, Double(mediaPlayer.time.intValue) / 1000.0)
            print("[VLC] AV1 detected — reloading on GLES2 vout (resume @ \(String(format: "%.1f", resumeSec))s)")
            reloadPlayer(playURL: playURL, useNativeHeaders: resolvedUseNativeHeaders,
                         viaProxy: isPlayingViaProxy, forceGLES: true, resumeSec: resumeSec)
        }

        /// Direct playback errored (e.g. an expired/invalid resolved-redirect token). Re-resolve
        /// the original URL through the proxy relay, which fetches fresh on every connection.
        private func fallbackToProxy() {
            guard !didFallbackToProxy, !isPlayingViaProxy, let url = originalURL else { return }
            didFallbackToProxy = true
            let resumeSec = max(0, Double(mediaPlayer.time.intValue) / 1000.0)
            print("[VLC] direct playback errored — falling back to proxy relay (resume @ \(String(format: "%.1f", resumeSec))s)")

            var proxyHeaders = headers ?? [:]
            if !proxyHeaders.keys.contains(where: { $0.caseInsensitiveCompare("user-agent") == .orderedSame }) {
                proxyHeaders["User-Agent"] = Self.browserUA
            }
            let proxy = VLCProxyServer(targetURL: url, headers: proxyHeaders)
            proxy.start()
            self.proxyServer = proxy
            // Keep GLES2 if we'd already switched (AV1); the relay just changes where bytes come from.
            reloadPlayer(playURL: proxy.localURL, useNativeHeaders: false,
                         viaProxy: true, forceGLES: didSwitchVout, resumeSec: resumeSec)
        }

        /// Tear down the current player and recreate it (optionally on the GLES2 vout), then resume
        /// `playURL` at `resumeSec`. Shared by the GLES2 switch and the proxy fallback.
        private func reloadPlayer(playURL: URL, useNativeHeaders: Bool, viaProxy: Bool,
                                  forceGLES: Bool, resumeSec: Double) {
            // Detach + stop the old player so its async ".stopped"/".error" can't be mistaken for
            // end-of-video or re-trigger a fallback.
            let old = mediaPlayer
            old.delegate = nil
            old.stop()

            mediaPlayer = forceGLES ? VLCMediaPlayer(options: ["--vout=gles2"]) : VLCMediaPlayer()
            mediaPlayer.delegate = self
            #if DEBUG
            let vlcLogger = VLCConsoleLogger()
            vlcLogger.level = .warning
            mediaPlayer.libraryInstance.loggers = [vlcLogger]
            #endif
            mediaPlayer.drawable = videoView

            // Reset per-playback state so the new player re-attaches slaves/tracks and resumes.
            lastReportedState = nil
            slavesAttached = false
            didFetchTracks = false
            initialTime = resumeSec
            initialTimeApplied = false

            startMedia(playURL: playURL, useNativeHeaders: useNativeHeaders, viaProxy: viaProxy)
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
                // De-dupe: VLCKit re-fires this with the same state on every buffering % tick.
                // Only do the (lock-taking / broadcasting) work on real transitions.
                let changed = newState != self.lastReportedState
                self.lastReportedState = newState
                guard changed else { return }

                print("[VLC] state=\(Self.stateName(newState)) playing=\(self.mediaPlayer.isPlaying) hasVideoOut=\(self.mediaPlayer.hasVideoOut) videoSize=\(self.mediaPlayer.videoSize) videoTracks=\(self.mediaPlayer.videoTracks.count)")
                if self.playbackState.isPlaying != self.mediaPlayer.isPlaying {
                    self.playbackState.isPlaying = self.mediaPlayer.isPlaying
                }
                self.broadcastStatus()

                // libvlc needs the input running before slaves attach to the current playback;
                // hook .opening (with .playing as fallback). attachSlavesIfNeeded is idempotent.
                if newState == .opening || newState == .playing {
                    self.attachSlavesIfNeeded()
                }
                if newState == .playing {
                    self.playbackState.userPaused = false   // clear when VLC resumes
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                    self.broadcastTracks()
                    // Software-decoded AV1 is the only case that benefits from the GPU vout; reload
                    // onto GLES2 once. Other codecs stay on samplebufferdisplay (HDR-correct).
                    if !self.didSwitchVout, self.currentVideoIsAV1() {
                        self.switchToGLESVout()
                        return
                    }
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
                    // If a directly-played (resolved) URL failed — e.g. an expired token — retry
                    // once through the proxy relay, which re-resolves fresh on each connection.
                    if !self.isPlayingViaProxy, !self.didFallbackToProxy {
                        self.fallbackToProxy()
                    }
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
                    if !self.mediaPlayer.audioTracks.isEmpty {
                        self.didFetchTracks = true
                        // Carry the session's track picks into this episode before the
                        // lists are published to the overlay/phone.
                        self.applyPreferredTracks()
                    }
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
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

            // Periodic position broadcast. 2s (was 1s) and only while actually playing — the JSON
            // build + WebSocket send is wasted work while paused/buffering (state transitions are
            // broadcast separately), and trimming it frees a little CPU for the decoder.
            statusTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
                guard let self, self.mediaPlayer.isPlaying else { return }
                self.broadcastStatus()
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

/// One-shot redirect resolver. Starts a request, lets URLSession follow the redirect chain, and
/// reports the FINAL URL as soon as the response headers arrive — cancelling before the body
/// downloads (so a server that ignores our Range and returns 200 doesn't pull the whole file).
/// `nil` means a hard failure (no response). Completion is delivered on the main thread.
private final class RedirectResolver: NSObject, URLSessionDataDelegate {
    private let completion: (URL?) -> Void
    private var finished = false
    private var session: URLSession!

    init(request: URLRequest, completion: @escaping (URL?) -> Void) {
        self.completion = completion
        super.init()
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = request.timeoutInterval
        cfg.timeoutIntervalForResource = request.timeoutInterval
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: nil)
        session.dataTask(with: request).resume()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                    didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        finish(response.url)               // response.url is the final URL after any redirects
        completionHandler(.cancel)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(task.response?.url)         // hard failure before a response → task.response is nil
    }

    private func finish(_ url: URL?) {
        guard !finished else { return }
        finished = true
        session.invalidateAndCancel()      // breaks the session↔delegate retain cycle
        DispatchQueue.main.async { self.completion(url) }
    }
}
