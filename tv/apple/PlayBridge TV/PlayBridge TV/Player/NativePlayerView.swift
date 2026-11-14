import SwiftUI
import AVKit

final class NativePlayerContainerViewController: UIViewController {
    let playerViewController = AVPlayerViewController()
    weak var overlayController: PlayerOverlayController?
    let musicHost = MusicPresentationHost()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        playerViewController.showsPlaybackControls = false
        addChild(playerViewController)
        playerViewController.view.translatesAutoresizingMaskIntoConstraints = false
        playerViewController.view.isUserInteractionEnabled = false
        view.addSubview(playerViewController.view)
        NSLayoutConstraint.activate([
            playerViewController.view.topAnchor.constraint(equalTo: view.topAnchor),
            playerViewController.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            playerViewController.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            playerViewController.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        playerViewController.didMove(toParent: self)
    }

    override var preferredFocusEnvironments: [UIFocusEnvironment] {
        if let envs = overlayController?.preferredFocusEnvironments, !envs.isEmpty {
            return envs
        }
        return super.preferredFocusEnvironments
    }

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let overlay = overlayController {
            overlay.handlePressesBegan(presses, with: event) {
                super.pressesBegan(presses, with: event)
            }
            return
        }
        if StillWatchingGate.isPrompting {
            NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
            return
        }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
        super.pressesBegan(presses, with: event)
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let overlay = overlayController {
            overlay.handlePressesEnded(presses, with: event) {
                super.pressesEnded(presses, with: event)
            }
            return
        }
        super.pressesEnded(presses, with: event)
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if let overlay = overlayController {
            overlay.handlePressesCancelled(presses, with: event) {
                super.pressesCancelled(presses, with: event)
            }
            return
        }
        super.pressesCancelled(presses, with: event)
    }
}

/// Using UIViewControllerRepresentable is much more stable on tvOS for custom headers and MKVs
struct NativePlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [ItemSubtitle]?
    let skipMetadata: SkipSegmentMetadata
    let initialTime: Double
    let isPreBuffering: Bool
    let title: String?
    let musicPayload: Playbridge_PlayPayload?
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
    let onBack: () -> Void      // back navigation after overlay handling
    let onSwitch: (PlaybackEngine, Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void
    let onLoopChanged: (Bool, PlayerReportIdentity) -> Bool
    let onEndOfFile: (PlayerReportIdentity) -> PlayerEndDecision
    let initialLoop: Bool
    /// Stored app-volume level, 0–1. Mute is independent of this value.
    let appLevel: Double
    let appMuted: Bool
    let mediaIdentity: Int
    let engineChrome: PlayerEngineChrome
    /// More than one queue item; a one-item queue hides the Queue button.
    let hasPlaylist: Bool
    var playlistItems: [PlayerQueueEntry] = []
    var playlistIndex: Int = 0
    var onSelectPlaylistItem: ((Int) -> Void)? = nil
    let playbackSpeed: Double
    let subtitleOffsetMs: Int
    let scaling: PlayerScaling
    let hasNextItem: Bool
    let metadata: TransportMetadata
    let playlistVisible: Bool
    var senderName: String? = nil
    var isBookmarked: Bool = false
    var isWatchTogetherParty: Bool = false
    var bookmarkCaptureToken: Int = 0
    var onToggleBookmark: (() -> Void)? = nil
    var onLeaveParty: (() -> Void)? = nil
    var onBookmarkThumbnailCaptured: ((URL, String) -> Void)? = nil
    let onSidecarOverlayChanged: (Bool, PlayerReportIdentity) -> Void
    /// Speed / Scaling / Subtitle Sync menu choices. `PlayerView.applyTuning` is
    /// the only writer; applied values return as `playbackSpeed` etc.
    let onLocalTuning: (PlayerTuning.Command) -> Void

    private var tuning: PlayerTuning {
        PlayerTuning(speed: playbackSpeed, subtitleOffsetMs: subtitleOffsetMs, scaling: scaling)
    }

    private func gravity(_ scaling: PlayerScaling) -> AVLayerVideoGravity {
        switch scaling {
        case .fit: return .resizeAspect
        case .zoom: return .resizeAspectFill
        case .fill: return .resize
        }
    }

    func makeUIViewController(context: Context) -> NativePlayerContainerViewController {
        debugLogNetworkRequest("AVPlayer playback", url: url, headers: headers)
        let container = NativePlayerContainerViewController()
        let controller = container.playerViewController
        controller.delegate = context.coordinator
        controller.showsPlaybackControls = false
        container.overlayController = context.coordinator.overlay

        // Setup Audio Session for TV output
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)

        // Add Headers to the Asset
        var options: [String: Any] = [:]
        if let headers = headers {
            options["AVURLAssetHTTPHeaderFieldsKey"] = headers
        }
        let asset = AVURLAsset(url: url, options: options)
        let playerItem = AVPlayerItem(asset: asset)
        playerItem.textStyleRules = SubtitleStyleStore.shared.style.avTextStyleRules()

        if let title, !title.isEmpty {
            let item = AVMutableMetadataItem()
            item.identifier = .commonIdentifierTitle
            item.value = title as NSString
            item.extendedLanguageTag = "und"
            playerItem.externalMetadata = [item]
        }

        let player = AVPlayer(playerItem: playerItem)
        playerItem.audioTimePitchAlgorithm = .timeDomain
        player.defaultRate = Float(playbackSpeed)

        // Seek to initial time if context switching occurred
        if initialTime > 0 {
            player.seek(to: CMTime(seconds: initialTime, preferredTimescale: 1))
        }

        controller.player = player
        controller.videoGravity = gravity(scaling)
        controller.speeds = []
        controller.allowsPictureInPicturePlayback = true
        controller.loadViewIfNeeded()
        if let overlayContainer = controller.contentOverlayView {
            container.musicHost.update(musicPayload, in: controller, container: overlayContainer, behindSubviews: true)
        }
        context.coordinator.attachSubtitleOverlay(to: controller)
        context.coordinator.attachOverlay(to: container)
        context.coordinator.onToggleBookmark = onToggleBookmark
        context.coordinator.onLeaveParty = onLeaveParty
        context.coordinator.syncFromRepresentable(
            title: title,
            metadata: metadata,
            initialTime: initialTime,
            isPreBuffering: isPreBuffering,
            isLooping: initialLoop,
            hasPlaylist: hasPlaylist,
            playlistItems: playlistItems,
            playlistIndex: playlistIndex,
            hasNextItem: hasNextItem,
            playlistVisible: playlistVisible,
            senderName: senderName,
            tuning: tuning,
            isMusic: musicPayload != nil,
            onSelectPlaylistItem: onSelectPlaylistItem,
            onBack: onBack
        )

        player.volume = PlayerAppVolume.avPlayerVolume(appLevel)
        player.isMuted = isPreBuffering || appMuted
        context.coordinator.attach(player: player)
        context.coordinator.bindEngineChrome(
            engineChrome, controller: controller, isPreBuffering: isPreBuffering)
        context.coordinator.startSkipSegments(skipMetadata)
        if !isWatchTogetherParty {
            player.play()
        }
        if !isPreBuffering {
            context.coordinator.overlay.showControls(full: true, autoHide: true)
        }
        return container
    }

    func updateUIViewController(_ uiViewController: NativePlayerContainerViewController, context: Context) {
        let controller = uiViewController.playerViewController
        if let overlayContainer = controller.contentOverlayView {
            uiViewController.musicHost.update(musicPayload, in: controller, container: overlayContainer, behindSubviews: true)
        }
        controller.showsPlaybackControls = false
        if let player = controller.player, player.defaultRate != Float(playbackSpeed) {
            let wasPlaying = player.rate != 0
            player.defaultRate = Float(playbackSpeed)
            if wasPlaying { player.rate = Float(playbackSpeed) }
        }
        controller.videoGravity = gravity(scaling)
        context.coordinator.onSidecarOverlayChanged = onSidecarOverlayChanged
        context.coordinator.subtitleOffsetMs = subtitleOffsetMs
        context.coordinator.onLocalTuning = onLocalTuning
        context.coordinator.onToggleBookmark = onToggleBookmark
        context.coordinator.onBookmarkThumbnailCaptured = onBookmarkThumbnailCaptured
        context.coordinator.onToggleBookmark = onToggleBookmark
        context.coordinator.onLeaveParty = onLeaveParty
        context.coordinator.syncBookmarkState(
            mediaURL: url,
            isBookmarked: isBookmarked,
            isWatchTogetherParty: isWatchTogetherParty,
            captureToken: bookmarkCaptureToken
        )
        context.coordinator.onToggleBookmark = onToggleBookmark
        context.coordinator.onLeaveParty = onLeaveParty
        context.coordinator.syncFromRepresentable(
            title: title,
            metadata: metadata,
            initialTime: nil,
            isPreBuffering: isPreBuffering,
            isLooping: nil,
            hasPlaylist: hasPlaylist,
            playlistItems: playlistItems,
            playlistIndex: playlistIndex,
            hasNextItem: hasNextItem,
            playlistVisible: playlistVisible,
            senderName: senderName,
            tuning: tuning,
            isMusic: musicPayload != nil,
            onSelectPlaylistItem: onSelectPlaylistItem,
            onBack: onBack
        )
        context.coordinator.refreshExternalSubtitle()
        controller.player?.volume = PlayerAppVolume.avPlayerVolume(appLevel)
        controller.player?.isMuted = isPreBuffering || appMuted
        context.coordinator.bindEngineChrome(
            engineChrome, controller: controller, isPreBuffering: isPreBuffering)
        context.coordinator.mediaIdentity = mediaIdentity
        context.coordinator.onLoopChanged = onLoopChanged
        context.coordinator.onEndOfFile = onEndOfFile
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(title: title, onDismiss: onDismiss, onExit: onExit, onBack: onBack, onSwitch: onSwitch,
                    headers: headers, subtitles: subtitles, onBroadcast: onBroadcast,
                    onLoopChanged: onLoopChanged, onEndOfFile: onEndOfFile,
                    initialLoop: initialLoop, mediaIdentity: mediaIdentity,
                    subtitleOffsetMs: subtitleOffsetMs, onSidecarOverlayChanged: onSidecarOverlayChanged,
                    tuning: tuning, onLocalTuning: onLocalTuning,
                    onSelectPlaylistItem: onSelectPlaylistItem,
                    isMusicPlayback: musicPayload != nil)
    }

    static func dismantleUIViewController(_ uiViewController: NativePlayerContainerViewController, coordinator: Coordinator) {
        coordinator.teardown()
        uiViewController.musicHost.remove()
        uiViewController.playerViewController.player = nil
    }

    class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        let overlay = PlayerOverlayController(engine: .avplayer)
        var playbackState: PlayerControlsData { overlay.playbackState }
        var isLooping = false
        weak var player: AVPlayer?
        let title: String?
        let onDismiss: () -> Void
        let onExit: () -> Void
        var onBack: () -> Void
        let onSwitch: (PlaybackEngine, Double) -> Void
        let headers: [String: String]?
        var externalSubtitleCatalog: ExternalSubtitleCatalog
        let onBroadcast: ([String: Any]) -> Void
        var onLoopChanged: (Bool, PlayerReportIdentity) -> Bool
        var onEndOfFile: (PlayerReportIdentity) -> PlayerEndDecision
        var mediaIdentity: Int
        var subtitleOffsetMs: Int
        var onSidecarOverlayChanged: (Bool, PlayerReportIdentity) -> Void
        var onLocalTuning: (PlayerTuning.Command) -> Void
        var onToggleBookmark: (() -> Void)?
        var onLeaveParty: (() -> Void)?
        var onBookmarkThumbnailCaptured: ((URL, String) -> Void)?
        private var lastBookmarkCaptureToken = 0
        var onSelectPlaylistItem: ((Int) -> Void)?
        private var isMusicPlayback = false
        private var playlistVisible = false
        private var tuning: PlayerTuning
        /// Mirrors the reporter's availability: Subtitle Sync only moves the overlay.
        private var sidecarOverlayActive = false

        func attachOverlay(to controller: UIViewController) {
            overlay.isStopped = { [weak self] in self?.player == nil }
            overlay.isPreBuffering = { [weak self] in self?.controlsSuppressedByPreplay ?? false }
            overlay.isPlaylistVisible = { [weak self] in self?.playlistVisible ?? false }
            overlay.isMusic = { [weak self] in self?.isMusicPlayback ?? false }
            overlay.onSeek = { [weak self] seconds in
                guard let self else { return }
                self.playbackState.currentTime = seconds
                self.seek(to: seconds)
            }
            overlay.onSetPaused = { [weak self] paused, openControls in
                self?.setPlaybackPaused(paused, openControls: openControls)
            }
            overlay.onTogglePlayPause = { [weak self] openControls in
                self?.togglePlayPause(openControls: openControls)
            }
            overlay.onSelectSubtitle = { [weak self] trackId in
                self?.selectSubtitleFromOverlay(trackId)
            }
            overlay.onSelectAudio = { [weak self] trackId in
                self?.selectTrack(.audible, id: String(trackId))
            }
            overlay.onSelectQueueItem = { [weak self] index in
                self?.onSelectPlaylistItem?(index)
            }
            overlay.onLoopToggle = { [weak self] in
                guard let self else { return }
                self.setLooping(!self.isLooping)
            }
            overlay.onSwitchEngine = { [weak self] in
                self?.invokeSwitch(to: .mpv)
            }
            overlay.onSkipSegment = { [weak self] in
                self?.skipActiveSegment()
            }
            overlay.onLocalTuning = { [weak self] command in
                self?.onLocalTuning(command)
            }
            overlay.onToggleBookmark = { [weak self] in
                self?.onToggleBookmark?()
            }
            overlay.onLeaveParty = { [weak self] in
                self?.onLeaveParty?()
            }
            overlay.onPartyRate = { [weak self] rate in
                guard let self, let player = self.player else { return }
                let floatRate = Float(rate)
                player.defaultRate = floatRate
                if player.timeControlStatus != .paused {
                    player.rate = floatRate
                }
            }
            overlay.onPartyGoLive = { [weak self] in
                guard let self else { return }
                let item = self.player?.currentItem
                let loaded = self.itemWasReady && item?.status == .readyToPlay
                let rawDuration = item.map { CMTimeGetSeconds($0.duration) } ?? 0
                let finiteDuration = (item?.duration.isIndefinite == false)
                    && rawDuration.isFinite && rawDuration > 0 ? rawDuration : 0
                if TogetherPlaybackPolicy.shouldSeekToLiveEdge(itemLoaded: loaded, durationSeconds: finiteDuration) {
                    if let range = item?.seekableTimeRanges.last?.timeRangeValue {
                        let end = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
                        if end.isFinite && end > 0 {
                            self.seek(to: end)
                        }
                    }
                }
                self.setPlaybackPaused(false, openControls: false)
            }
            overlay.onBack = { [weak self] in
                self?.onBack()
            }
            overlay.onBroadcastStatus = { [weak self] in
                self?.broadcastStatus()
            }
            overlay.attach(to: controller, container: controller.view)
        }

        func syncFromRepresentable(
            title: String?,
            metadata: TransportMetadata,
            initialTime: Double?,
            isPreBuffering: Bool,
            isLooping: Bool?,
            hasPlaylist: Bool,
            playlistItems: [PlayerQueueEntry],
            playlistIndex: Int,
            hasNextItem: Bool,
            playlistVisible: Bool,
            senderName: String?,
            tuning: PlayerTuning,
            isMusic: Bool,
            onSelectPlaylistItem: ((Int) -> Void)?,
            onBack: @escaping () -> Void
        ) {
            playbackState.title = title ?? ""
            playbackState.metadata = metadata
            if let initialTime {
                playbackState.currentTime = initialTime
            }
            let wasPreBuffering = controlsSuppressedByPreplay
            controlsSuppressedByPreplay = isPreBuffering
            playbackState.prebuffering = isPreBuffering
            playbackState.skipControlsEnabled = !isPreBuffering
            if wasPreBuffering && !isPreBuffering {
                overlay.showControls(full: true, autoHide: true)
            } else if isPreBuffering {
                overlay.hideControls()
            }
            if let isLooping {
                self.isLooping = isLooping
                playbackState.isLooping = isLooping
            }
            playbackState.hasPlaylist = hasPlaylist
            playbackState.playlistItems = playlistItems
            playbackState.playlistIndex = playlistIndex
            self.playlistVisible = playlistVisible
            playbackState.playlistVisible = playlistVisible
            playbackState.senderName = senderName
            self.tuning = tuning
            playbackState.tuning = tuning
            self.isMusicPlayback = isMusic
            self.onSelectPlaylistItem = onSelectPlaylistItem
            self.onBack = onBack
            syncOverlayTracks()
        }

        func syncBookmarkState(mediaURL: URL, isBookmarked: Bool, isWatchTogetherParty: Bool, captureToken: Int) {
            playbackState.isBookmarked = isBookmarked
            playbackState.isWatchTogetherParty = isWatchTogetherParty
            if captureToken != lastBookmarkCaptureToken, captureToken > 0 {
                lastBookmarkCaptureToken = captureToken
                captureBookmarkFrame(mediaURL: mediaURL, expectedToken: captureToken, attempt: 0)
            }
        }

        private func captureBookmarkFrame(mediaURL: URL, expectedToken: Int, attempt: Int) {
            guard expectedToken == lastBookmarkCaptureToken,
                  let player,
                  let asset = player.currentItem?.asset else {
                if expectedToken == lastBookmarkCaptureToken, attempt + 1 < BookmarkThumbnailPolicy.maxRetryAttempts {
                    DispatchQueue.main.asyncAfter(deadline: .now() + BookmarkThumbnailPolicy.retryDelaySeconds) { [weak self] in
                        self?.captureBookmarkFrame(mediaURL: mediaURL, expectedToken: expectedToken, attempt: attempt + 1)
                    }
                }
                return
            }
            let currentTime = player.currentTime()
            BookmarkThumbnailCapture.captureAVAssetFrame(asset: asset, at: currentTime, for: mediaURL) { [weak self] saved in
                guard let self, expectedToken == self.lastBookmarkCaptureToken else { return }
                if let saved {
                    self.onBookmarkThumbnailCaptured?(mediaURL, saved)
                } else if attempt + 1 < BookmarkThumbnailPolicy.maxRetryAttempts {
                    DispatchQueue.main.asyncAfter(deadline: .now() + BookmarkThumbnailPolicy.retryDelaySeconds) { [weak self] in
                        self?.captureBookmarkFrame(mediaURL: mediaURL, expectedToken: expectedToken, attempt: attempt + 1)
                    }
                }
            }
        }

        private func setSidecarOverlay(_ active: Bool) {
            onSidecarOverlayChanged(active, PlayerReportIdentity(engine: "avplayer", generation: mediaIdentity))
            guard active != sidecarOverlayActive else { return }
            sidecarOverlayActive = active
            playbackState.hasSidecarSubtitleOverlay = active
        }

        private var timeObserver: Any?
        private var stallTimer: Timer?
        private var stallDetector = PlaybackStallDetector()
        private var stallHasFirstFrame = false
        private var subtitleTimeObserver: Any?
        private var timeControlObservation: NSKeyValueObservation?
        /// AVPlayer cannot open some containers (MKV, WebM, AVI). If the item fails
        /// before it was ever ready, hand the same position to mpv once, like the
        /// Android TV engine fallback. Later failures stay here.
        private var itemStatusObservation: NSKeyValueObservation?
        private var itemWasReady = false
        private var didFallBack = false
        private weak var playerController: AVPlayerViewController?
        private weak var subtitleCaptionView: UIView?
        private weak var subtitleLabel: UILabel?
        private var selectedExternalSubtitleID: Int?
        private var externalCues: ExternalSubtitleCues?
        private var subtitleSession: URLSession?
        private var subtitleDownloadTask: URLSessionDownloadTask?
        private var subtitleRequestID: UUID?
        private var lateSubtitleDownload: ScopedSubtitleDownload?
        private var didBroadcastTracks = false
        /// Chapter markers of the current item, loaded once it is ready to play.
        private var chapters: [TimelineChapter] = [] {
            didSet { playbackState.chapters = ChapterNavigation.sorted(chapters) }
        }
        private let skipLoader = SkipSegmentLoader()
        private var activeSkipSegment: SkipSegment?
        private var autoSkippedSegmentStarts: Set<Int> = []
        private var appliedSubtitleStyle = SubtitleStylePreferences.current()
        private var appliedFontRevision: Int = 0
        private var subtitleDefaultsObserver: NSObjectProtocol?
        private var controlsSuppressedByPreplay = false
        // Media-selection groups loaded once (async, tvOS 16+) when the item is ready, then used
        // synchronously by broadcastTracks/selectTrack. Avoids the deprecated sync accessor.
        private var audioGroup: AVMediaSelectionGroup?
        private var subtitleGroup: AVMediaSelectionGroup?

        init(title: String?, onDismiss: @escaping () -> Void, onExit: @escaping () -> Void,
             onBack: @escaping () -> Void,
             onSwitch: @escaping (PlaybackEngine, Double) -> Void,
             headers: [String: String]?, subtitles: [ItemSubtitle]?,
             onBroadcast: @escaping ([String: Any]) -> Void,
             onLoopChanged: @escaping (Bool, PlayerReportIdentity) -> Bool,
             onEndOfFile: @escaping (PlayerReportIdentity) -> PlayerEndDecision,
             initialLoop: Bool, mediaIdentity: Int,
             subtitleOffsetMs: Int, onSidecarOverlayChanged: @escaping (Bool, PlayerReportIdentity) -> Void,
             tuning: PlayerTuning, onLocalTuning: @escaping (PlayerTuning.Command) -> Void,
             onSelectPlaylistItem: ((Int) -> Void)?, isMusicPlayback: Bool) {
            self.title = title
            self.onDismiss = onDismiss
            self.onExit = onExit
            self.onBack = onBack
            self.onSwitch = onSwitch
            self.headers = headers
            self.externalSubtitleCatalog = ExternalSubtitleCatalog(subtitles: subtitles ?? [])
            self.onBroadcast = onBroadcast
            self.onLoopChanged = onLoopChanged
            self.onEndOfFile = onEndOfFile
            self.mediaIdentity = mediaIdentity
            self.subtitleOffsetMs = subtitleOffsetMs
            self.onSidecarOverlayChanged = onSidecarOverlayChanged
            self.tuning = tuning
            self.onLocalTuning = onLocalTuning
            self.onSelectPlaylistItem = onSelectPlaylistItem
            self.isMusicPlayback = isMusicPlayback
            self.isLooping = initialLoop
            super.init()

            NotificationCenter.default.addObserver(
                self, selector: #selector(itemDidFinish),
                name: .AVPlayerItemDidPlayToEndTime, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(itemFailedToPlay(_:)),
                name: .AVPlayerItemFailedToPlayToEndTime, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onControl(_:)),
                name: WebSocketServer.controlCommand, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onResync),
                name: WebSocketServer.resyncRequest, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onStillWatchingPause),
                name: .playBridgeStillWatchingPause, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onStillWatchingResume),
                name: .playBridgeStillWatchingResume, object: nil)
        }

        /// Called once the player exists: drive periodic now-playing status to the phone.
        func attach(player: AVPlayer) {
            self.player = player
            stallDetector.reset()
            stallHasFirstFrame = false
            // AVPlayer's periodic time observer stops while waiting, so use a wall timer.
            stallTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                self?.checkForStall()
            }
            timeControlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) {
                [weak self] _, _ in
                DispatchQueue.main.async {
                    if self?.player?.timeControlStatus == .playing { self?.stallHasFirstFrame = true }
                    self?.broadcastStatus()
                    self?.refreshExternalSubtitle()
                }
            }
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main
            ) { [weak self] _ in self?.broadcastStatus() }
            itemStatusObservation = player.currentItem?.observe(\.status, options: [.initial, .new]) {
                [weak self] item, _ in
                let status = item.status
                DispatchQueue.main.async { self?.itemStatusChanged(status) }
            }
        }

        private func checkForStall() {
            guard let player, !didFallBack else { return }
            if player.timeControlStatus == .playing { stallHasFirstFrame = true }
            let position = player.currentTime().seconds
            let end = player.currentItem?.loadedTimeRanges.compactMap { value -> Double? in
                let range = value.timeRangeValue
                let start = range.start.seconds
                let end = CMTimeRangeGetEnd(range).seconds
                return start <= position && end >= position ? end : nil
            }.max() ?? position
            if stallDetector.sample(now: ProcessInfo.processInfo.systemUptime,
                buffering: player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
                playbackRequested: player.timeControlStatus != .paused && !controlsSuppressedByPreplay && !StillWatchingGate.isPrompting,
                hasFirstFrame: stallHasFirstFrame, position: position,
                bufferedAhead: max(0, end - position)) {
                fallBackToMpv(reason: nil)
            }
        }

        private func itemStatusChanged(_ status: AVPlayerItem.Status) {
            switch status {
            case .readyToPlay:
                itemWasReady = true
                broadcastStatus()
            case .failed:
                // Before ready: a format AVPlayer can't open. After ready: a fatal
                // playback error. Either way mpv takes over at the same position.
                fallBackToMpv(reason: player?.currentItem?.error)
            default:
                break
            }
        }

        /// AVPlayer can load an item and then fail as soon as it decodes (posting
        /// FailedToPlayToEndTime, e.g. -11800). Without this it stayed paused at
        /// 0:00 behind a spinner; now mpv takes over.
        @objc private func itemFailedToPlay(_ note: Notification) {
            guard let item = note.object as? AVPlayerItem, item === player?.currentItem else { return }
            let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            DispatchQueue.main.async { [weak self] in self?.fallBackToMpv(reason: error) }
        }

        private func fallBackToMpv(reason: Error?) {
            guard !didFallBack else { return }
            didFallBack = true
            let position = player?.currentTime().seconds ?? 0
            #if DEBUG
            // Codes only: errors can carry request URLs.
            var chain: [String] = []
            var error = reason as NSError?
            while let current = error {
                chain.append("\(current.domain) \(current.code)")
                error = current.userInfo[NSUnderlyingErrorKey] as? NSError
            }
            print("[AVPlayer] falling back to mpv: \(chain.joined(separator: " <- "))")
            #endif
            onSwitch(.mpv, position.isFinite ? position : 0)
        }

        func teardown() {
            overlay.teardown()
            stallTimer?.invalidate()
            stallTimer = nil
            stallDetector.reset()
            skipLoader.cancel()
            lateSubtitleDownload?.cancel()
            lateSubtitleDownload = nil
            subtitleDownloadTask?.cancel()
            subtitleSession?.invalidateAndCancel()
            subtitleRequestID = nil
            if let token = subtitleTimeObserver { player?.removeTimeObserver(token); subtitleTimeObserver = nil }
            subtitleCaptionView?.removeFromSuperview()
            timeControlObservation?.invalidate()
            timeControlObservation = nil
            itemStatusObservation?.invalidate()
            itemStatusObservation = nil
            if let token = timeObserver { player?.removeTimeObserver(token); timeObserver = nil }
            NotificationCenter.default.removeObserver(self)
            if let observer = subtitleDefaultsObserver {
                NotificationCenter.default.removeObserver(observer)
                subtitleDefaultsObserver = nil
            }
            player?.pause()
            player?.replaceCurrentItem(with: nil)
            player = nil
        }

        deinit { teardown() }

        private func setLooping(_ enabled: Bool) {
            let identity = PlayerReportIdentity(engine: PlaybackEngine.avplayer.rawValue, generation: mediaIdentity)
            guard onLoopChanged(enabled, identity) else { return }
            isLooping = enabled
            playbackState.isLooping = enabled
        }

        func invokeSwitch(to target: PlaybackEngine) {
            guard target != .avplayer else { return }
            NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
            onSwitch(target, player?.currentTime().seconds ?? 0)
        }

        func selectExternalSubtitle(_ id: Int) {
            NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
            guard let option = externalSubtitleCatalog.option(for: id),
                  let url = URL(string: option.url) else { return }
            subtitleDownloadTask?.cancel()
            subtitleSession?.invalidateAndCancel()
            let requestID = UUID()
            subtitleRequestID = requestID

            if url.isFileURL {
                DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                    let result = Result { () throws -> ExternalSubtitleCues in
                        let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber
                        guard let size, size.uint64Value <= UInt64(ExternalSubtitleDownload.maximumBytes)
                        else { throw ExternalSubtitleDownloadError.tooLarge }
                        let data = try Data(contentsOf: url)
                        guard let cues = ExternalSubtitleCues(data: data), !cues.cues.isEmpty
                        else { throw ExternalSubtitleDownloadError.unsupportedFormat }
                        return cues
                    }
                    DispatchQueue.main.async { self?.finishExternalSubtitleLoad(result, id: requestID, option: option) }
                }
                return
            }

            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            let session = URLSession(configuration: configuration)
            subtitleSession = session
            let request = option.headers.map { ExternalSubtitleDownload.request(for: url, subtitleHeaders: $0) }
                ?? ExternalSubtitleDownload.request(for: url, playbackHeaders: headers)
            let task = session.downloadTask(with: request) { [weak self] temporaryFile, response, error in
                let result: Result<ExternalSubtitleCues, Error>
                if let error {
                    result = .failure(error)
                } else if let temporaryFile {
                    result = Result {
                        let file = try ExternalSubtitleDownload.prepare(file: temporaryFile, response: response)
                        defer { try? FileManager.default.removeItem(at: file) }
                        let data = try Data(contentsOf: file)
                        guard let cues = ExternalSubtitleCues(data: data), !cues.cues.isEmpty
                        else { throw ExternalSubtitleDownloadError.unsupportedFormat }
                        return cues
                    }
                } else {
                    result = .failure(ExternalSubtitleDownloadError.invalidResponse)
                }
                DispatchQueue.main.async { self?.finishExternalSubtitleLoad(result, id: requestID, option: option) }
            }
            subtitleDownloadTask = task
            task.resume()
        }

        func attachSubtitleOverlay(to controller: AVPlayerViewController) {
            playerController = controller
            guard let content = controller.contentOverlayView else { return }
            let caption = UIView()
            caption.translatesAutoresizingMaskIntoConstraints = false
            caption.isUserInteractionEnabled = false
            caption.isHidden = true
            let label = UILabel()
            label.translatesAutoresizingMaskIntoConstraints = false
            label.textAlignment = .center
            label.numberOfLines = 3
            caption.addSubview(label)
            content.addSubview(caption)
            NSLayoutConstraint.activate([
                caption.centerXAnchor.constraint(equalTo: content.centerXAnchor),
                caption.bottomAnchor.constraint(equalTo: content.safeAreaLayoutGuide.bottomAnchor, constant: -50),
                caption.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, multiplier: 0.82),
                label.leadingAnchor.constraint(equalTo: caption.leadingAnchor, constant: 20),
                label.trailingAnchor.constraint(equalTo: caption.trailingAnchor, constant: -20),
                label.topAnchor.constraint(equalTo: caption.topAnchor, constant: 10),
                label.bottomAnchor.constraint(equalTo: caption.bottomAnchor, constant: -10),
            ])
            SubtitleStyleStore.shared.style.applyToOverlay(captionView: caption, label: label)
            subtitleCaptionView = caption
            subtitleLabel = label
        }

        func clearExternalSubtitle(explicitOff: Bool) {
            lateSubtitleDownload?.cancel()
            lateSubtitleDownload = nil
            subtitleDownloadTask?.cancel()
            subtitleSession?.invalidateAndCancel()
            subtitleDownloadTask = nil
            subtitleSession = nil
            subtitleRequestID = nil
            selectedExternalSubtitleID = nil
            externalCues = nil
            setSidecarOverlay(false)
            if let token = subtitleTimeObserver { player?.removeTimeObserver(token); subtitleTimeObserver = nil }
            subtitleLabel?.text = nil
            subtitleCaptionView?.isHidden = true
            if explicitOff {
                if let subtitleGroup { player?.currentItem?.select(nil, in: subtitleGroup) }
                TrackPreferences.shared.subtitlesOff = true
                TrackPreferences.shared.subtitleLanguage = nil
                TrackPreferences.shared.subtitleName = nil
            }
            syncOverlayTracks()
            broadcastTracks()
        }

        private func finishExternalSubtitleLoad(_ result: Result<ExternalSubtitleCues, Error>,
                                                id: UUID, option: ExternalSubtitleCatalog.Option) {
            guard subtitleRequestID == id, player != nil else { return }
            subtitleSession?.finishTasksAndInvalidate()
            subtitleSession = nil
            subtitleDownloadTask = nil
            subtitleRequestID = nil
            switch result {
            case .success(let cues):
                selectedExternalSubtitleID = option.id
                externalCues = cues
                setSidecarOverlay(true)
                if let subtitleGroup { player?.currentItem?.select(nil, in: subtitleGroup) }
                TrackPreferences.shared.subtitlesOff = false
                TrackPreferences.shared.subtitleLanguage = nil
                TrackPreferences.shared.subtitleName = nil
                if subtitleTimeObserver == nil, let player {
                    subtitleTimeObserver = player.addPeriodicTimeObserver(
                        forInterval: CMTime(seconds: 0.2, preferredTimescale: 600), queue: .main
                    ) { [weak self] _ in self?.refreshExternalSubtitle() }
                }
                refreshExternalSubtitle()
                syncOverlayTracks()
                broadcastTracks()
            case .failure(let error):
                let message = (error as? ExternalSubtitleDownloadError)?.message
                    ?? "The subtitle could not be loaded. Playback will continue."
                let alert = UIAlertController(title: "Subtitle unavailable", message: message,
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "OK", style: .default))
                playerController?.present(alert, animated: true)
            }
        }

        func refreshExternalSubtitle() {
            let time = PlayerTuning.overlayCueTime(position: player?.currentTime().seconds ?? .nan,
                                                  offsetMs: subtitleOffsetMs)
            let text = externalCues?.text(at: time)
            if subtitleLabel?.text != text {
                if let text {
                    subtitleLabel?.attributedText = SubtitleStyleStore.shared.style.overlayAttributedText(text)
                } else {
                    subtitleLabel?.attributedText = nil
                    subtitleLabel?.text = nil
                }
            }
            subtitleCaptionView?.isHidden = text == nil
        }

        @objc func itemDidFinish(notification: Notification) {
            guard let finishedItem = notification.object as? AVPlayerItem,
                  finishedItem == player?.currentItem else { return }
            let identity = PlayerReportIdentity(engine: PlaybackEngine.avplayer.rawValue, generation: mediaIdentity)
            switch onEndOfFile(identity) {
            case .repeatCurrentItem:
                let loopingPlayer = player
                loopingPlayer?.seek(to: .zero, toleranceBefore: .zero, toleranceAfter: .zero) { _ in
                    guard loopingPlayer?.currentItem == finishedItem else { return }
                    loopingPlayer?.play()
                }
            case .advanceOrLeave:
                broadcastStatus()
                onDismiss()
            case .ignored:
                break
            }
        }

        func playerViewController(
            _ playerViewController: AVPlayerViewController,
            willEndFullScreenPresentation interactivelyDismissed: Bool
        ) {
            // User pressed Menu/Back on the Siri Remote
            if StillWatchingGate.isPrompting {
                NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
            } else {
                NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
                broadcastStatus()
                onExit()
            }
        }

        func playerViewController(_ playerViewController: AVPlayerViewController,
                                  didSelect mediaSelectionOption: AVMediaSelectionOption?,
                                  in mediaSelectionGroup: AVMediaSelectionGroup) {
            let prefs = TrackPreferences.shared
            if let audioGroup, mediaSelectionGroup == audioGroup {
                prefs.audioLanguage = mediaSelectionOption?.locale?.identifier
                    ?? mediaSelectionOption?.extendedLanguageTag
                prefs.audioName = mediaSelectionOption?.displayName
                broadcastTracks()
                return
            }
            guard let subtitleGroup, mediaSelectionGroup == subtitleGroup else { return }
            clearExternalSubtitle(explicitOff: false)
            prefs.subtitlesOff = mediaSelectionOption == nil
            prefs.subtitleLanguage = mediaSelectionOption?.locale?.identifier
                ?? mediaSelectionOption?.extendedLanguageTag
            prefs.subtitleName = mediaSelectionOption?.displayName
            broadcastTracks()
        }

        // MARK: - Phone Now-Playing Sync

        private func broadcastStatus() {
            guard let player else { return }
            updateSkipAction()
            let itemLoaded = itemWasReady && player.currentItem?.status == .readyToPlay
            let posRaw = player.currentTime().seconds
            let pos = posRaw.isFinite && posRaw >= 0 ? posRaw : 0
            let durRaw = player.currentItem?.duration.seconds ?? 0
            let dur = (itemLoaded && durRaw.isFinite && durRaw > 0) ? durRaw : 0
            let bufferedEndSeconds = bufferedEnd(player: player, position: pos)
            playbackState.currentTime = pos
            playbackState.duration = dur
            if let buffered = bufferedEndSeconds, buffered.isFinite {
                playbackState.bufferedTime = max(pos, buffered)
            }
            let isPlayingNow = itemLoaded && player.timeControlStatus == .playing
            let isBufferingNow = !itemLoaded || player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            playbackState.isPlaying = isPlayingNow
            playbackState.cacheStalled = isBufferingNow && !playbackState.userPaused && !controlsSuppressedByPreplay
            if isPlayingNow && playbackState.userPaused {
                playbackState.userPaused = false
            }

            let state: String
            if !itemLoaded {
                state = (player.timeControlStatus == .paused || controlsSuppressedByPreplay || playbackState.userPaused) ? "paused" : "buffering"
            } else {
                switch player.timeControlStatus {
                case .playing: state = "playing"
                case .waitingToPlayAtSpecifiedRate: state = "buffering"
                default: state = "paused"
                }
            }
            var json: [String: Any] = [
                "type": "status",
                "state": state,
                "position": PlaybackTime.milliseconds(pos),
                "duration": PlaybackTime.milliseconds(dur),
                "generation": mediaIdentity,
                "itemLoaded": itemLoaded,
            ]
            if let t = title, !t.isEmpty { json["title"] = t }
            if let buffered = bufferedEndSeconds,
               let ms = TimelineReport.buffered(bufferedSeconds: buffered, positionSeconds: pos, durationSeconds: dur) {
                json["buffered"] = ms
            }
            onBroadcast(json)
            NotificationCenter.default.post(
                name: .playBridgePlaybackActivity, object: nil,
                userInfo: ["isPlaying": isPlayingNow])

            // Track lists become available once the item is ready; load the selection groups once
            // (async on tvOS 16+), cache them, then broadcast.
            if !didBroadcastTracks, let item = player.currentItem, item.status == .readyToPlay {
                didBroadcastTracks = true
                loadSelectionGroupsAndBroadcast(asset: item.asset)
            }
        }

        /// End of the loaded range that contains the play head (AVPlayer buffers ahead in one range).
        private func bufferedEnd(player: AVPlayer, position: Double) -> Double? {
            guard let ranges = player.currentItem?.loadedTimeRanges else { return nil }
            return ranges.lazy.map(\.timeRangeValue)
                .first { $0.start.seconds <= position + 0.5 && $0.end.seconds >= position }?
                .end.seconds
        }

        /// Chapters from MP4/MOV chapter tracks. HLS rarely carries them; AVPlayer has no MKV.
        private func loadChapters(asset: AVAsset) async -> [TimelineChapter] {
            let languages = Locale.preferredLanguages
            var groups = (try? await asset.loadChapterMetadataGroups(bestMatchingPreferredLanguages: languages)) ?? []
            if groups.isEmpty,
               let firstLocale = (try? await asset.load(.availableChapterLocales))?.first {
                groups = (try? await asset.loadChapterMetadataGroups(
                    withTitleLocale: firstLocale,
                    containingItemsWithCommonKeys: [.commonKeyArtwork]
                )) ?? []
            }
            guard !groups.isEmpty else { return [] }
            var chapters: [TimelineChapter] = []
            for group in groups.prefix(TimelineReport.maxChapters) {
                let start = group.timeRange.start.seconds
                guard start.isFinite, start >= 0 else { continue }
                let titleItem = AVMetadataItem.metadataItems(from: group.items, filteredByIdentifier: .commonIdentifierTitle).first
                let title = try? await titleItem?.load(.stringValue)
                chapters.append(TimelineChapter(startMs: PlaybackTime.milliseconds(start), title: title ?? nil))
            }
            return chapters
        }

        private func broadcastTimeline() {
            onBroadcast(TimelineReport.frame(chapters: chapters, segments: skipLoader.segments))
        }

        /// Feeds the system Chapters tab of the Info panel. tvOS shows only the first
        /// group, which with a nil title is the chapter list. No custom press handling.
        private func applyNavigationMarkers(_ chapters: [TimelineChapter]) {
            guard let item = player?.currentItem else { return }
            let seconds = item.duration.seconds
            let durationMs = seconds.isFinite && seconds > 0 ? PlaybackTime.milliseconds(seconds) : nil
            let markers = ChapterNavigation.navigationMarkers(chapters: chapters, durationMs: durationMs)
            guard !markers.isEmpty else {
                item.navigationMarkerGroups = []
                return
            }
            func time(_ ms: Int) -> CMTime { CMTime(value: CMTimeValue(ms), timescale: 1000) }
            let groups = markers.map { marker -> AVTimedMetadataGroup in
                let title = AVMutableMetadataItem()
                title.identifier = .commonIdentifierTitle
                title.value = marker.title as NSString
                title.extendedLanguageTag = "und"
                let start = time(marker.startMs)
                let range = marker.endMs.map { CMTimeRange(start: start, end: time($0)) }
                    ?? CMTimeRange(start: start, duration: .positiveInfinity)
                return AVTimedMetadataGroup(items: [title], timeRange: range)
            }
            item.navigationMarkerGroups = [AVNavigationMarkersGroup(title: nil, timedNavigationMarkers: groups)]
        }

        func startSkipSegments(_ metadata: SkipSegmentMetadata) {
            if subtitleDefaultsObserver == nil {
                subtitleDefaultsObserver = NotificationCenter.default.addObserver(
                    forName: .playBridgeSubtitleStyleDidChange,
                    object: nil,
                    queue: .main
                ) { [weak self] _ in
                    Task { @MainActor [weak self] in
                        guard let self else { return }
                        let updated = SubtitleStyleStore.shared.style
                        let revision = SubtitleStyleStore.shared.fontRevision
                        guard updated != self.appliedSubtitleStyle || revision != self.appliedFontRevision else { return }
                        self.appliedSubtitleStyle = updated
                        self.appliedFontRevision = revision
                        self.player?.currentItem?.textStyleRules = updated.avTextStyleRules()
                        if let caption = self.subtitleCaptionView, let label = self.subtitleLabel {
                            updated.applyToOverlay(captionView: caption, label: label)
                        }
                    }
                }
            }
            let identity = mediaIdentity
            autoSkippedSegmentStarts = []
            skipLoader.start(metadata) { [weak self] in
                guard let self, self.player != nil, self.mediaIdentity == identity else { return }
                self.playbackState.skipSegments = self.skipLoader.segments
                self.updateSkipAction()
                self.broadcastTimeline()
            }
            playbackState.skipSegments = skipLoader.segments
            broadcastTimeline()
        }

        private func updateSkipAction() {
            guard let player else { return }
            let duration = player.currentItem?.duration.seconds ?? 0
            let segment = controlsSuppressedByPreplay || StillWatchingGate.isPrompting ? nil : skipLoader.segments.first {
                $0.contains(seconds: player.currentTime().seconds) && $0.targetSeconds(duration: duration) != nil
            }
            if let segment, !autoSkippedSegmentStarts.contains(segment.startMs) {
                let autoPrefs = AutoSkipPreferences.current()
                if autoPrefs.shouldAutoSkip(segment: segment) {
                    autoSkippedSegmentStarts.insert(segment.startMs)
                    activeSkipSegment = segment
                    playbackState.activeSkipSegment = segment
                    skipActiveSegment()
                    overlay.showNotice(autoPrefs.noticeText(for: segment), icon: "forward.end.fill", duration: 2.0)
                    return
                }
            }
            guard segment != activeSkipSegment else { return }
            let wasVisible = activeSkipSegment != nil
            activeSkipSegment = segment
            playbackState.activeSkipSegment = segment
            if (segment != nil) != wasVisible {
                overlay.updateTransportFocus()
            }
        }

        private func skipActiveSegment() {
            guard !controlsSuppressedByPreplay,
                  !StillWatchingGate.isPrompting,
                  let player,
                  let segment = activeSkipSegment,
                  skipLoader.segments.contains(segment),
                  let target = segment.targetSeconds(duration: player.currentItem?.duration.seconds ?? 0) else { return }
            NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
            activeSkipSegment = nil
            playbackState.activeSkipSegment = nil
            overlay.updateTransportFocus()
            if segment.endsPlayback(duration: player.currentItem?.duration.seconds ?? 0) {
                broadcastStatus()
                onDismiss()
                return
            }
            playbackState.currentTime = target
            seek(to: target)
        }

        private func loadSelectionGroupsAndBroadcast(asset: AVAsset) {
            if #available(tvOS 16, *) {
                Task { [weak self] in
                    guard let self else { return }
                    let identity = self.mediaIdentity
                    let audio = (try? await asset.loadMediaSelectionGroup(for: .audible)) ?? nil
                    let sub = (try? await asset.loadMediaSelectionGroup(for: .legible)) ?? nil
                    let chapters = await self.loadChapters(asset: asset)
                    await MainActor.run {
                        guard self.mediaIdentity == identity, self.player?.currentItem?.asset === asset else { return }
                        self.audioGroup = audio
                        self.subtitleGroup = sub
                        self.applyPreferredTracks()
                        self.syncOverlayTracks()
                        self.broadcastTracks()
                        self.chapters = chapters
                        self.applyNavigationMarkers(chapters)
                        self.broadcastTimeline()
                    }
                }
            } else {
                // tvOS 14/15: the only accessor is the deprecated sync one — skip track sync there.
                syncOverlayTracks()
                broadcastTracks()
            }
        }

        private func broadcastTracks() {
            guard let item = player?.currentItem else { return }
            func encode(_ group: AVMediaSelectionGroup?) -> [[String: Any]] {
                guard let group else { return [] }
                let selected = item.currentMediaSelection.selectedMediaOption(in: group)
                return group.options.enumerated().map { index, option in
                    ["id": String(index), "name": option.displayName, "selected": option == selected]
                }
            }
            onBroadcast([
                "type": "tracks",
                "audio": encode(audioGroup),
                "subtitle": encode(subtitleGroup) + externalSubtitleCatalog.options.map { option in
                    ["id": String(option.id), "name": option.name,
                     "selected": selectedExternalSubtitleID == option.id]
                },
            ])
        }

        @objc private func onResync() {
            broadcastStatus()
            broadcastTracks()
            broadcastTimeline()
        }

        @objc private func onControl(_ note: Notification) {
            guard let cmd = note.userInfo?["command"] as? String else { return }
            if cmd == "add_subtitle" {
                guard let resource = note.userInfo?["subtitleResource"] as? Playbridge_SubtitleResource,
                      let completion = note.userInfo?["subtitleCompletion"] as? ((Bool) -> Void),
                      player != nil else {
                    (note.userInfo?["subtitleCompletion"] as? ((Bool) -> Void))?(false)
                    return
                }
                lateSubtitleDownload?.cancel()
                subtitleDownloadTask?.cancel()
                subtitleSession?.invalidateAndCancel()
                subtitleDownloadTask = nil
                subtitleSession = nil
                let id = UUID()
                subtitleRequestID = id
                let download = ScopedSubtitleDownload(resource: resource)
                lateSubtitleDownload = download
                download.start { [weak self] result in
                    guard let self, self.player != nil, self.subtitleRequestID == id else {
                        if case .success(let file) = result { try? FileManager.default.removeItem(at: file) }
                        completion(false)
                        return
                    }
                    self.lateSubtitleDownload = nil
                    switch result {
                    case .failure: completion(false)
                    case .success(let file):
                        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                            let cuesResult = Result { () throws -> ExternalSubtitleCues in
                            defer { try? FileManager.default.removeItem(at: file) }
                            let data = try Data(contentsOf: file)
                            guard let cues = ExternalSubtitleCues(data: data), !cues.cues.isEmpty
                            else { throw ExternalSubtitleDownloadError.unsupportedFormat }
                            return cues
                            }
                            DispatchQueue.main.async { [weak self] in
                                guard let self, self.player != nil, self.subtitleRequestID == id else {
                                    completion(false)
                                    return
                                }
                                guard case .success = cuesResult else { completion(false); return }
                                let name = resource.hasLabel && !resource.label.isEmpty
                                    ? resource.label : "External subtitle"
                                let (catalog, option) = self.externalSubtitleCatalog.appending(
                                    url: resource.url, name: name)
                                self.externalSubtitleCatalog = catalog
                                self.finishExternalSubtitleLoad(cuesResult, id: id, option: option)
                                self.overlay.showPhoneCard(PhoneConfirmations.card(
                                    kind: .subtitles,
                                    title: PhoneConfirmations.subtitleChangeTitle(trackName: name, off: false),
                                    name: note.userInfo?["senderName"] as? String
                                ))
                                completion(true)
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
            guard let player else { return }
            if playbackState.isWatchTogetherParty {
                switch cmd {
                case "play":
                    _ = TvTogether.shared.localPlay()
                    return
                case "pause":
                    _ = TvTogether.shared.localPause()
                    return
                case "play_pause", "toggle":
                    let currentlyPlaying = !(player.timeControlStatus == .paused || playbackState.userPaused)
                    _ = currentlyPlaying ? TvTogether.shared.localPause() : TvTogether.shared.localPlay()
                    return
                case "seek_forward":
                    let current = max(0, player.currentTime().seconds)
                    let target = playbackState.duration > 0 ? min(playbackState.duration, current + 15) : current + 15
                    _ = TvTogether.shared.localSeek(positionMs: Int64(target * 1000))
                    return
                case "seek_back":
                    let target = max(0, player.currentTime().seconds - 15)
                    _ = TvTogether.shared.localSeek(positionMs: Int64(target * 1000))
                    return
                case let c where c.hasPrefix("seek_to:"):
                    if let ms = Double(c.dropFirst("seek_to:".count)), ms.isFinite, ms >= 0 {
                        _ = TvTogether.shared.localSeek(positionMs: Int64(ms))
                    }
                    return
                default:
                    break
                }
            }
            let senderName = note.userInfo?["senderName"] as? String
            switch cmd {
            case "play":
                setPlaybackPaused(false, openControls: false)
            case "pause":
                setPlaybackPaused(true, openControls: false)
                overlay.showPhonePaused(name: senderName)
            case "play_pause", "toggle":
                let willPause = !(player.timeControlStatus == .paused || playbackState.userPaused)
                setPlaybackPaused(willPause, openControls: false)
                if willPause { overlay.showPhonePaused(name: senderName) }
            case "stop":
                broadcastStatus()
                onExit()
                return
            case "seek_forward":
                let current = max(0, player.currentTime().seconds)
                let target = playbackState.duration > 0 ? min(playbackState.duration, current + 15) : current + 15
                seek(to: target)
                overlay.showPhoneSeek(name: senderName, targetSeconds: target)
            case "seek_back":
                let target = max(0, player.currentTime().seconds - 15)
                seek(to: target)
                overlay.showPhoneSeek(name: senderName, targetSeconds: target)
            case let c where c.hasPrefix("seek_to:"):
                if let ms = Double(c.dropFirst("seek_to:".count)) {
                    let target = max(0, ms / 1000)
                    seek(to: target)
                    overlay.showPhoneSeek(name: senderName, targetSeconds: target)
                }
            case let c where c.hasPrefix("audio_track:"):
                let trackID = String(c.dropFirst("audio_track:".count))
                selectTrack(.audible, id: trackID)
                let trackName = Int(trackID).flatMap { id in playbackState.audioTracks.first(where: { $0.id == id })?.name }
                overlay.showPhoneCard(PhoneConfirmations.card(
                    kind: .audio,
                    title: PhoneConfirmations.audioChangeTitle(trackName: trackName),
                    name: senderName
                ))
            case let c where c.hasPrefix("sub_track:"):
                let id = String(c.dropFirst("sub_track:".count))
                var trackName: String?
                var isOff = false
                if let externalID = Int(id), externalID < -1 {
                    selectExternalSubtitle(externalID)
                    trackName = externalSubtitleCatalog.option(for: externalID)?.name
                } else {
                    selectTrack(.legible, id: id)
                    if id == "none" || id == "-1" {
                        isOff = true
                    } else if let idx = Int(id) {
                        trackName = playbackState.subtitleTracks.first(where: { $0.id == idx })?.name
                    }
                }
                overlay.showPhoneCard(PhoneConfirmations.card(
                    kind: .subtitles,
                    title: PhoneConfirmations.subtitleChangeTitle(trackName: trackName, off: isOff),
                    name: senderName
                ))
            case let c where c.hasPrefix("add_subtitle:"):
                let rawURL = String(c.dropFirst("add_subtitle:".count))
                guard let url = URL(string: rawURL),
                      url.isFileURL || (["http", "https"].contains(url.scheme?.lowercased() ?? "")
                          && url.host != nil) else { break }
                let (catalog, option) = externalSubtitleCatalog.appending(
                    url: rawURL, name: "External subtitle")
                externalSubtitleCatalog = catalog
                selectExternalSubtitle(option.id)
            case let c where c.hasPrefix("switch_player:"):
                if let target = PlaybackEngine(command: String(c.dropFirst("switch_player:".count))) {
                    invokeSwitch(to: target)
                }
            default:
                break  // Tuning is owned by PlayerView; filter/audio_boost remain unsupported.
            }
            broadcastStatus()
        }

        func bindEngineChrome(
            _ chrome: PlayerEngineChrome,
            controller: AVPlayerViewController,
            isPreBuffering: Bool
        ) {
            playerController = controller
            controlsSuppressedByPreplay = isPreBuffering
            controller.showsPlaybackControls = false
            overlay.bindEngineChrome(chrome)
        }

        private func setPlaybackPaused(_ paused: Bool, openControls: Bool) {
            guard let player else { return }
            playbackState.userPaused = paused
            playbackState.isPlaying = !paused
            if !paused { overlay.clearPhonePaused() }
            if paused {
                player.pause()
            } else {
                player.play()
            }
            if openControls {
                if !playbackState.showUI {
                    overlay.showControls(full: true, autoHide: !paused)
                } else {
                    overlay.showControls(full: playbackState.isFullControlsVisible, autoHide: !paused)
                }
            } else if !playbackState.showUI {
                overlay.showTransportBadge(paused ? .paused : .playing)
            } else {
                overlay.showUI(autoHide: !paused)
            }
            broadcastStatus()
        }

        private func togglePlayPause(openControls: Bool) {
            guard let player else { return }
            let isPausedNow = player.timeControlStatus == .paused || playbackState.userPaused
            setPlaybackPaused(!isPausedNow, openControls: openControls)
        }

        private func selectSubtitleFromOverlay(_ trackId: Int) {
            if trackId == -1 {
                selectTrack(.legible, id: "-1")
            } else if externalSubtitleCatalog.option(for: trackId) != nil {
                selectExternalSubtitle(trackId)
            } else {
                selectTrack(.legible, id: String(trackId))
            }
        }

        private func syncOverlayTracks() {
            let item = player?.currentItem
            let audioOptions = audioGroup?.options.enumerated().map { (id: $0.offset, name: $0.element.displayName) } ?? []
            let selectedAudio: Int = {
                guard let group = audioGroup,
                      let selected = item?.currentMediaSelection.selectedMediaOption(in: group),
                      let idx = group.options.firstIndex(of: selected) else { return -1 }
                return idx
            }()

            let subtitleOptions = subtitleGroup?.options.enumerated().map { (id: $0.offset, name: $0.element.displayName) } ?? []
            let externalOptions = externalSubtitleCatalog.options.map { (id: $0.id, name: $0.name) }
            let selectedSub: Int = {
                if let extID = selectedExternalSubtitleID { return extID }
                guard let group = subtitleGroup,
                      let selected = item?.currentMediaSelection.selectedMediaOption(in: group),
                      let idx = group.options.firstIndex(of: selected) else { return -1 }
                return idx
            }()

            playbackState.audioTracks = audioOptions
            playbackState.currentAudioIndex = selectedAudio
            playbackState.subtitleTracks = subtitleOptions
            playbackState.externalSubtitleTracks = externalOptions
            playbackState.currentSubtitleIndex = selectedSub
            playbackState.hasSidecarSubtitleOverlay = (selectedExternalSubtitleID != nil)
        }

        @objc private func onStillWatchingPause() { player?.pause(); broadcastStatus() }
        @objc private func onStillWatchingResume() { player?.play(); broadcastStatus() }

        private func seek(by delta: Double) {
            guard let player else { return }
            seek(to: max(0, player.currentTime().seconds + delta))
        }

        private func seek(to seconds: Double) {
            stallDetector.reset()
            guard seconds.isFinite, seconds >= 0 else { return }
            player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        }

        private func selectTrack(_ characteristic: AVMediaCharacteristic, id: String) {
            if characteristic == .legible {
                clearExternalSubtitle(explicitOff: id == "none" || id == "-1")
            }
            guard let item = player?.currentItem else { return }
            guard let group = (characteristic == .audible) ? audioGroup : subtitleGroup else { return }
            let prefs = TrackPreferences.shared
            if id == "none" || id == "-1" {
                item.select(nil, in: group)  // deselect (e.g. subtitles off)
                if characteristic == .legible {
                    prefs.subtitlesOff = true
                    prefs.subtitleLanguage = nil
                    prefs.subtitleName = nil
                }
            } else if let index = Int(id), group.options.indices.contains(index) {
                let option = group.options[index]
                item.select(option, in: group)
                // Remember the pick so the next episode's (new) player re-applies it.
                let lang = option.locale?.identifier ?? option.extendedLanguageTag
                if characteristic == .audible {
                    prefs.audioLanguage = lang
                    prefs.audioName = option.displayName
                } else {
                    prefs.subtitlesOff = false
                    prefs.subtitleLanguage = lang
                    prefs.subtitleName = option.displayName
                }
            }
            syncOverlayTracks()
            broadcastTracks()
        }

        /// Re-apply the session's track preferences once the selection groups are loaded,
        /// so picks carry across episodes (each item gets a fresh AVPlayer).
        private func applyPreferredTracks() {
            guard let item = player?.currentItem else { return }
            let prefs = TrackPreferences.shared

            func match(in group: AVMediaSelectionGroup?, language: String?, name: String?)
                -> AVMediaSelectionOption?
            {
                guard let group else { return nil }
                if let language,
                   let m = group.options.first(where: {
                       $0.locale?.identifier == language || $0.extendedLanguageTag == language
                   }) { return m }
                if let name, let m = group.options.first(where: { $0.displayName == name }) {
                    return m
                }
                return nil
            }

            if let group = audioGroup,
               let option = match(in: group, language: prefs.audioLanguage, name: prefs.audioName) {
                item.select(option, in: group)
            }
            if let group = subtitleGroup {
                if selectedExternalSubtitleID != nil {
                    item.select(nil, in: group)
                } else if prefs.subtitlesOff {
                    item.select(nil, in: group)
                } else if let option = match(
                    in: group, language: prefs.subtitleLanguage, name: prefs.subtitleName) {
                    item.select(option, in: group)
                }
            }
        }
    }
}
