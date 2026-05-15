import SwiftUI
import TVVLCKit

// MARK: - Enhanced VLC Player with Controls

struct VLCPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
    let onSwitch: (Double) -> Void

    func makeUIViewController(context: Context) -> VLCViewController {
        let controller = VLCViewController()
        controller.url = url
        controller.headers = headers
        controller.subtitles = subtitles
        controller.initialTime = initialTime
        controller.isPreBuffering = isPreBuffering
        controller.onDismiss = onDismiss
        controller.onExit = onExit
        controller.onSwitch = onSwitch
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
        private var slavesAttached: Bool = false
        var onDismiss: (() -> Void)?
        var onExit: (() -> Void)?
        var onSwitch: ((Double) -> Void)?
        
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
        private var hostingController: UIHostingController<VLCControlsOverlay>?

        override var preferredFocusEnvironments: [UIFocusEnvironment] {
            // Only route focus to the SwiftUI buttons when user explicitly paused
            if playbackState.userPaused, let hostingView = hostingController?.view {
                return [hostingView]
            }
            return [videoView]
        }

        // Data for HUD
        private var playbackState = VLCPlaybackData()
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

            // 2. Setup HUD Overlay (SwiftUI)
            setupHUD()

            // 3. Initialize Player
            setupPlayer()

            // Show UI initially
            showUI(autoHide: true)
        }

        private func setupHUD() {
            let overlay = VLCControlsOverlay(
                data: playbackState,
                onSelectSubtitle: { [weak self] trackId in
                    self?.mediaPlayer.currentVideoSubTitleIndex = Int32(trackId)
                    self?.playbackState.currentSubtitleIndex = trackId
                },
                onSelectAudio: { [weak self] trackId in
                    self?.mediaPlayer.currentAudioTrackIndex = Int32(trackId)
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
                })
            let hosting = UIHostingController(rootView: overlay)
            hosting.view.backgroundColor = .clear
            hosting.view.frame = view.bounds
            hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

            addChild(hosting)
            view.addSubview(hosting.view)
            hosting.didMove(toParent: self)
            self.hostingController = hosting
        }

        private func updateSubtitleTracks() {
            guard let indexes = mediaPlayer.videoSubTitlesIndexes as? [Int],
                let names = mediaPlayer.videoSubTitlesNames as? [String],
                indexes.count == names.count
            else { return }

            let tracks = zip(indexes, names).map { (id: $0, name: $1) }
            DispatchQueue.main.async {
                self.playbackState.subtitleTracks = tracks
                self.playbackState.currentSubtitleIndex = Int(
                    self.mediaPlayer.currentVideoSubTitleIndex)
            }
        }

        private func updateAudioTracks() {
            guard let indexes = mediaPlayer.audioTrackIndexes as? [Int],
                let names = mediaPlayer.audioTrackNames as? [String],
                indexes.count == names.count
            else { return }

            let tracks = zip(indexes, names).map { (id: $0, name: $1) }
            DispatchQueue.main.async {
                self.playbackState.audioTracks = tracks
                self.playbackState.currentAudioIndex = Int(self.mediaPlayer.currentAudioTrackIndex)
            }
        }

        private var proxyServer: VLCProxyServer?

        private func setupPlayer() {
            mediaPlayer.delegate = self
            mediaPlayer.drawable = videoView

            if let url = url {
                // Separate headers into VLC-native and those that need proxy
                var nativeOptions: [String] = []
                var needsProxy = false

                if let headers = headers {
                    for (key, value) in headers {
                        switch key.lowercased() {
                        case "user-agent":
                            nativeOptions.append(":http-user-agent=\(value)")
                        case "referer":
                            nativeOptions.append(":http-referrer=\(value)")
                        default:
                            needsProxy = true
                        }
                    }
                }

                let playURL: URL
                if needsProxy {
                    // Route through local proxy to inject custom headers
                    let proxy = VLCProxyServer(targetURL: url, headers: headers ?? [:])
                    proxy.start()
                    self.proxyServer = proxy
                    playURL = proxy.localURL
                    print("VLC Proxy: \(url.absoluteString) -> \(playURL.absoluteString)")
                } else {
                    playURL = url
                }

                let media = VLCMedia(url: playURL)

                // Optimized caching for tvOS streaming
                media.addOptions([
                    "--network-caching": "15000",
                    "--clock-jitter": "0",
                    "--drop-late-frames": "1",
                    "--skip-frames": "1",
                ])

                // Apply VLC-native header options
                for option in nativeOptions {
                    media.addOption(option)
                }

                mediaPlayer.media = media
                applyPreBufferingState()
                mediaPlayer.play()
            }
        }
        
        private func attachSlavesIfNeeded() {
            guard !slavesAttached else { return }
            guard let subtitles = subtitles, !subtitles.isEmpty else {
                slavesAttached = true
                return
            }
            for sub in subtitles {
                guard let url = URL(string: sub) else {
                    print("VLC: skipping invalid subtitle URL: \(sub)")
                    continue
                }
                let rc = mediaPlayer.addPlaybackSlave(url, type: .subtitle, enforce: false)
                print("VLC: addPlaybackSlave subtitle=\(url.absoluteString) rc=\(rc)")
            }
            slavesAttached = true
            // Slaves arrive after the initial parse — refresh the track list so
            // the new entries appear in the Subtitles menu.
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

        // MARK: - VLCMediaPlayerDelegate
        func mediaPlayerStateChanged(_ aNotification: Notification) {
            DispatchQueue.main.async {
                self.playbackState.isPlaying = self.mediaPlayer.isPlaying
                // libvlc requires the input to be running before slaves can be
                // attached to the *current* playback. Hook .opening (and .playing
                // as a fallback) so external SRT/VTT URLs from the phone show up
                // in the Subtitles menu.
                if self.mediaPlayer.state == .opening || self.mediaPlayer.state == .playing {
                    self.attachSlavesIfNeeded()
                }
                if self.mediaPlayer.state == .playing {
                    // Clear userPaused when VLC resumes
                    self.playbackState.userPaused = false
                    self.updateSubtitleTracks()
                    self.updateAudioTracks()
                }
                if self.mediaPlayer.state == .ended {
                    if self.playbackState.isLooping {
                        self.mediaPlayer.stop()
                        self.mediaPlayer.play()
                    } else {
                        self.onDismiss?()
                    }
                }
                if self.mediaPlayer.state == .error {
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
                // Keep trying to fetch tracks until they're available
                // (VLC may not have parsed them on the initial .playing state change)
                if self.playbackState.subtitleTracks.isEmpty || self.playbackState.audioTracks.isEmpty {
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

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            mediaPlayer.stop()
            proxyServer?.stop()
            proxyServer = nil
        }
    }
}
