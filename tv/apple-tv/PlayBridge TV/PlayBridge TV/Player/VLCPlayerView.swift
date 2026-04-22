import SwiftUI
import TVVLCKit

// MARK: - Enhanced VLC Player with Controls

struct VLCPlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let initialTime: Double
    let onDismiss: () -> Void
    let onSwitch: (Double) -> Void

    func makeUIViewController(context: Context) -> VLCViewController {
        let controller = VLCViewController()
        controller.url = url
        controller.headers = headers
        controller.initialTime = initialTime
        controller.onDismiss = onDismiss
        controller.onSwitch = onSwitch
        return controller
    }

    func updateUIViewController(_ uiViewController: VLCViewController, context: Context) {}

    class VLCViewController: UIViewController, VLCMediaPlayerDelegate {
        var mediaPlayer: VLCMediaPlayer = VLCMediaPlayer()
        var url: URL?
        var headers: [String: String]?
        var initialTime: Double = 0.0
        var onDismiss: (() -> Void)?
        var onSwitch: ((Double) -> Void)?
        
        private var initialTimeApplied: Bool = false

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
                mediaPlayer.play()
            }
        }

        // MARK: - VLCMediaPlayerDelegate
        func mediaPlayerStateChanged(_ aNotification: Notification) {
            DispatchQueue.main.async {
                self.playbackState.isPlaying = self.mediaPlayer.isPlaying
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
                
                self.playbackState.currentTime = Double(self.mediaPlayer.time.intValue) / 1000.0
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

        override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
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
                if !mediaPlayer.isPlaying && playbackState.showUI {
                    // If paused with controls visible, resume playback first
                    togglePlayPause()
                    return
                }
                
                // If nothing else caught it, exit the video entirely
                onDismiss?()
                return
            }

            // Let menus handle their own navigation input
            if playbackState.showSubtitleMenu || playbackState.showAudioMenu {
                super.pressesBegan(presses, with: event)
                return
            }

            if mediaPlayer.isPlaying {
                // PLAYING MODE: arrows seek, center pauses
                switch type {
                case .playPause, .select:
                    togglePlayPause()
                case .leftArrow:
                    showUI()
                    startContinuousSeek(forward: false)
                case .rightArrow:
                    showUI()
                    startContinuousSeek(forward: true)
                default:
                    super.pressesBegan(presses, with: event)
                }
            } else {
                // PAUSED MODE: arrows navigate buttons, center activates focused button
                switch type {
                case .playPause:
                    togglePlayPause()
                default:
                    // Let the focus engine handle all navigation and selection
                    super.pressesBegan(presses, with: event)
                }
            }
        }

        override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            stopContinuousSeek()
            if presses.first?.type == .menu { return }
            super.pressesEnded(presses, with: event)
        }

        override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
            stopContinuousSeek()
            if presses.first?.type == .menu { return }
            super.pressesCancelled(presses, with: event)
        }

        private func startContinuousSeek(forward: Bool) {
            // Initial skip (one-time)
            if forward { skipForward() } else { skipBackward() }

            continuousSeekTimer?.invalidate()
            // After 0.4s hold, start repeating fast
            continuousSeekTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) {
                [weak self] _ in
                DispatchQueue.main.async {
                    self?.continuousSeekTimer?.invalidate()
                    self?.continuousSeekTimer = Timer.scheduledTimer(
                        withTimeInterval: 0.2, repeats: true
                    ) { [weak self] _ in
                        DispatchQueue.main.async {
                            if forward { self?.skipForward() } else { self?.skipBackward() }
                        }
                    }
                }
            }
        }

        private func stopContinuousSeek() {
            continuousSeekTimer?.invalidate()
            continuousSeekTimer = nil
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
                mediaPlayer.time = VLCTime(int: target)
            }
            showUI()
        }

        @objc private func skipBackward() {
            guard !playbackState.userPaused else { return }
            let currentTime = mediaPlayer.time.intValue
            let target = max(0, currentTime - 15000)

            mediaPlayer.time = VLCTime(int: target)
            showUI()
        }

        private func showUI(autoHide: Bool = true) {
            DispatchQueue.main.async {
                self.playbackState.showUI = true
                self.setNeedsFocusUpdate()
                
                self.hideControlsTimer?.invalidate()
                if autoHide {
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
