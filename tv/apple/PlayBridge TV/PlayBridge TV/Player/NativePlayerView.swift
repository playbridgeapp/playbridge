import SwiftUI
import AVKit

final class ActivityAVPlayerViewController: AVPlayerViewController {
    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        if StillWatchingGate.isPrompting {
            NotificationCenter.default.post(name: .playBridgeStillWatchingResume, object: nil)
            return
        }
        NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
        super.pressesBegan(presses, with: event)
    }
}

/// Using UIViewControllerRepresentable is much more stable on tvOS for custom headers and MKVs
struct NativePlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let subtitles: [String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
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

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        debugLogNetworkRequest("AVPlayer playback", url: url, headers: headers)
        let controller = ActivityAVPlayerViewController()
        controller.delegate = context.coordinator

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

        // Title shown in the native tvOS transport bar (read from externalMetadata).
        if let title, !title.isEmpty {
            let item = AVMutableMetadataItem()
            item.identifier = .commonIdentifierTitle
            item.value = title as NSString
            item.extendedLanguageTag = "und"
            playerItem.externalMetadata = [item]
        }

        let player = AVPlayer(playerItem: playerItem)

        // Seek to initial time if context switching occurred
        if initialTime > 0 {
            player.seek(to: CMTime(seconds: initialTime, preferredTimescale: 1))
        }

        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.loadViewIfNeeded()
        context.coordinator.attachSubtitleOverlay(to: controller)

        let loopAction = UIAction(
            title: "Loop",
            image: UIImage(systemName: "repeat")
        ) { [weak coordinator = context.coordinator] action in
            coordinator?.toggleLoop(action)
        }
        loopAction.state = context.coordinator.isLooping ? .on : .off
        context.coordinator.loopAction = loopAction
        
        let switchMenu = UIMenu(
            title: "Switch Player",
            image: UIImage(systemName: "arrow.triangle.2.circlepath"),
            children: PlaybackEngine.menuOrder(current: .avplayer).map { engine in
                UIAction(title: engine.name, state: engine == .avplayer ? .on : .off) {
                    [weak coordinator = context.coordinator] _ in
                    coordinator?.invokeSwitch(to: engine)
                }
            })
        
        let playlistAction = UIAction(
            title: "Playlist",
            image: UIImage(systemName: "list.bullet")
        ) { _ in
            NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
        }

        let subtitleOptions = ExternalSubtitleCatalog(urls: subtitles ?? []).options
        let subtitleOffAction = UIAction(title: "No External Subtitle") { [weak coordinator = context.coordinator] _ in
            coordinator?.clearExternalSubtitle(explicitOff: false)
        }
        var subtitleActions: [Int: UIAction] = [:]
        for option in subtitleOptions {
            subtitleActions[option.id] = UIAction(title: option.name) { [weak coordinator = context.coordinator] _ in
                coordinator?.selectExternalSubtitle(option.id)
            }
        }
        context.coordinator.configureSubtitleActions(off: subtitleOffAction, options: subtitleActions)
        let externalSubtitleMenu = UIMenu(
            title: "External Subtitles",
            image: UIImage(systemName: "captions.bubble"),
            children: [subtitleOffAction] + subtitleOptions.compactMap { subtitleActions[$0.id] })
        controller.transportBarCustomMenuItems = subtitleOptions.isEmpty
            ? [loopAction, switchMenu, playlistAction]
            : [loopAction, externalSubtitleMenu, switchMenu, playlistAction]

        player.volume = PlayerAppVolume.avPlayerVolume(appLevel)
        player.isMuted = isPreBuffering || appMuted
        context.coordinator.attach(player: player)
        context.coordinator.bindEngineChrome(
            engineChrome, controller: controller, isPreBuffering: isPreBuffering)
        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        uiViewController.player?.volume = PlayerAppVolume.avPlayerVolume(appLevel)
        uiViewController.player?.isMuted = isPreBuffering || appMuted
        context.coordinator.bindEngineChrome(
            engineChrome, controller: uiViewController, isPreBuffering: isPreBuffering)
        context.coordinator.mediaIdentity = mediaIdentity
        context.coordinator.onLoopChanged = onLoopChanged
        context.coordinator.onEndOfFile = onEndOfFile
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(title: title, onDismiss: onDismiss, onExit: onExit, onSwitch: onSwitch,
                    headers: headers, subtitles: subtitles, onBroadcast: onBroadcast,
                    onLoopChanged: onLoopChanged, onEndOfFile: onEndOfFile,
                    initialLoop: initialLoop, mediaIdentity: mediaIdentity)
    }

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.teardown()
        uiViewController.player = nil
    }

    class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        var isLooping = false
        weak var player: AVPlayer?
        let title: String?
        let onDismiss: () -> Void
        let onExit: () -> Void
        let onSwitch: (PlaybackEngine, Double) -> Void
        let headers: [String: String]?
        var externalSubtitleCatalog: ExternalSubtitleCatalog
        let onBroadcast: ([String: Any]) -> Void
        var onLoopChanged: (Bool, PlayerReportIdentity) -> Bool
        var onEndOfFile: (PlayerReportIdentity) -> PlayerEndDecision
        var mediaIdentity: Int
        var loopAction: UIAction?

        private var timeObserver: Any?
        private var subtitleTimeObserver: Any?
        private var timeControlObservation: NSKeyValueObservation?
        private weak var playerController: AVPlayerViewController?
        private weak var subtitleCaptionView: UIView?
        private weak var subtitleLabel: UILabel?
        private var subtitleOffAction: UIAction?
        private var subtitleActions: [Int: UIAction] = [:]
        private var selectedExternalSubtitleID: Int?
        private var externalCues: ExternalSubtitleCues?
        private var subtitleSession: URLSession?
        private var subtitleDownloadTask: URLSessionDownloadTask?
        private var subtitleRequestID: UUID?
        private var lateSubtitleDownload: ScopedSubtitleDownload?
        private var didBroadcastTracks = false
        /// Chapter markers of the current item, loaded once it is ready to play.
        private var chapters: [TimelineChapter] = []
        /// Network Back hides the system transport. Later SwiftUI updates keep it hidden
        /// until this player view is recreated for the next item.
        private var remoteControlsHidden = false
        private var controlsSuppressedByPreplay = false
        // Media-selection groups loaded once (async, tvOS 16+) when the item is ready, then used
        // synchronously by broadcastTracks/selectTrack. Avoids the deprecated sync accessor.
        private var audioGroup: AVMediaSelectionGroup?
        private var subtitleGroup: AVMediaSelectionGroup?

        init(title: String?, onDismiss: @escaping () -> Void, onExit: @escaping () -> Void,
             onSwitch: @escaping (PlaybackEngine, Double) -> Void,
             headers: [String: String]?, subtitles: [String]?,
             onBroadcast: @escaping ([String: Any]) -> Void,
             onLoopChanged: @escaping (Bool, PlayerReportIdentity) -> Bool,
             onEndOfFile: @escaping (PlayerReportIdentity) -> PlayerEndDecision,
             initialLoop: Bool, mediaIdentity: Int) {
            self.title = title
            self.onDismiss = onDismiss
            self.onExit = onExit
            self.onSwitch = onSwitch
            self.headers = headers
            self.externalSubtitleCatalog = ExternalSubtitleCatalog(urls: subtitles ?? [])
            self.onBroadcast = onBroadcast
            self.onLoopChanged = onLoopChanged
            self.onEndOfFile = onEndOfFile
            self.mediaIdentity = mediaIdentity
            self.isLooping = initialLoop
            super.init()

            NotificationCenter.default.addObserver(
                self, selector: #selector(itemDidFinish),
                name: .AVPlayerItemDidPlayToEndTime, object: nil)
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
            timeControlObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) {
                [weak self] _, _ in
                DispatchQueue.main.async {
                    self?.broadcastStatus()
                    self?.refreshExternalSubtitle()
                }
            }
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 1, preferredTimescale: 1), queue: .main
            ) { [weak self] _ in self?.broadcastStatus() }
        }

        func teardown() {
            lateSubtitleDownload?.cancel()
            lateSubtitleDownload = nil
            subtitleDownloadTask?.cancel()
            subtitleSession?.invalidateAndCancel()
            subtitleRequestID = nil
            if let token = subtitleTimeObserver { player?.removeTimeObserver(token); subtitleTimeObserver = nil }
            subtitleCaptionView?.removeFromSuperview()
            timeControlObservation?.invalidate()
            timeControlObservation = nil
            if let token = timeObserver { player?.removeTimeObserver(token); timeObserver = nil }
            NotificationCenter.default.removeObserver(self)
            player?.pause()
            player?.replaceCurrentItem(with: nil)
            player = nil
        }

        deinit { teardown() }

        @objc func toggleLoop(_ action: UIAction) {
            NotificationCenter.default.post(name: .playBridgeUserActivity, object: nil)
            setLooping(!isLooping, action: action)
        }

        private func setLooping(_ enabled: Bool, action: UIAction? = nil) {
            let identity = PlayerReportIdentity(engine: PlaybackEngine.avplayer.rawValue, generation: mediaIdentity)
            guard onLoopChanged(enabled, identity) else { return }
            isLooping = enabled
            let item = action ?? loopAction
            item?.state = enabled ? .on : .off
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
            let request = ExternalSubtitleDownload.request(for: url, playbackHeaders: headers)
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

        func configureSubtitleActions(off: UIAction, options: [Int: UIAction]) {
            subtitleOffAction = off
            subtitleActions = options
            updateSubtitleActionStates()
        }

        func attachSubtitleOverlay(to controller: AVPlayerViewController) {
            playerController = controller
            guard let content = controller.contentOverlayView else { return }
            let caption = UIView()
            caption.translatesAutoresizingMaskIntoConstraints = false
            caption.backgroundColor = UIColor.black.withAlphaComponent(0.72)
            caption.layer.cornerRadius = 12
            caption.isUserInteractionEnabled = false
            caption.isHidden = true
            let label = UILabel()
            label.translatesAutoresizingMaskIntoConstraints = false
            label.font = .systemFont(ofSize: 42, weight: .semibold)
            label.textColor = .white
            label.textAlignment = .center
            label.numberOfLines = 3
            caption.addSubview(label)
            content.addSubview(caption)
            NSLayoutConstraint.activate([
                caption.centerXAnchor.constraint(equalTo: content.centerXAnchor),
                caption.bottomAnchor.constraint(equalTo: content.safeAreaLayoutGuide.bottomAnchor, constant: -50),
                caption.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, multiplier: 0.82),
                label.leadingAnchor.constraint(equalTo: caption.leadingAnchor, constant: 22),
                label.trailingAnchor.constraint(equalTo: caption.trailingAnchor, constant: -22),
                label.topAnchor.constraint(equalTo: caption.topAnchor, constant: 12),
                label.bottomAnchor.constraint(equalTo: caption.bottomAnchor, constant: -12),
            ])
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
            if let token = subtitleTimeObserver { player?.removeTimeObserver(token); subtitleTimeObserver = nil }
            subtitleLabel?.text = nil
            subtitleCaptionView?.isHidden = true
            if explicitOff {
                if let subtitleGroup { player?.currentItem?.select(nil, in: subtitleGroup) }
                TrackPreferences.shared.subtitlesOff = true
                TrackPreferences.shared.subtitleLanguage = nil
                TrackPreferences.shared.subtitleName = nil
            }
            updateSubtitleActionStates()
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
                updateSubtitleActionStates()
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

        private func refreshExternalSubtitle() {
            let text = externalCues?.text(at: player?.currentTime().seconds ?? .nan)
            if subtitleLabel?.text != text { subtitleLabel?.text = text }
            subtitleCaptionView?.isHidden = text == nil
        }

        private func updateSubtitleActionStates() {
            subtitleOffAction?.state = selectedExternalSubtitleID == nil ? .on : .off
            for (id, action) in subtitleActions {
                action.state = id == selectedExternalSubtitleID ? .on : .off
            }
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
            guard let subtitleGroup, mediaSelectionGroup == subtitleGroup else { return }
            clearExternalSubtitle(explicitOff: false)
            let prefs = TrackPreferences.shared
            prefs.subtitlesOff = mediaSelectionOption == nil
            prefs.subtitleLanguage = mediaSelectionOption?.locale?.identifier
                ?? mediaSelectionOption?.extendedLanguageTag
            prefs.subtitleName = mediaSelectionOption?.displayName
        }

        // MARK: - Phone Now-Playing Sync

        private func broadcastStatus() {
            guard let player else { return }
            let pos = player.currentTime().seconds
            let durRaw = player.currentItem?.duration.seconds ?? 0
            let dur = durRaw.isFinite ? durRaw : 0
            let state: String
            switch player.timeControlStatus {
            case .playing: state = "playing"
            case .waitingToPlayAtSpecifiedRate: state = "buffering"
            default: state = "paused"
            }
            var json: [String: Any] = [
                "type": "status",
                "state": state,
                "position": PlaybackTime.milliseconds(pos),
                "duration": PlaybackTime.milliseconds(dur),
            ]
            if let t = title, !t.isEmpty { json["title"] = t }
            if let buffered = bufferedEnd(player: player, position: pos),
               let ms = TimelineReport.buffered(bufferedSeconds: buffered, positionSeconds: pos, durationSeconds: dur) {
                json["buffered"] = ms
            }
            onBroadcast(json)
            NotificationCenter.default.post(
                name: .playBridgePlaybackActivity, object: nil,
                userInfo: ["isPlaying": player.timeControlStatus == .playing])

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
            guard let groups = try? await asset.loadChapterMetadataGroups(bestMatchingPreferredLanguages: languages) else { return [] }
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
            onBroadcast(TimelineReport.frame(chapters: chapters))
        }

        private func loadSelectionGroupsAndBroadcast(asset: AVAsset) {
            if #available(tvOS 16, *) {
                Task { [weak self] in
                    guard let self else { return }
                    let audio = (try? await asset.loadMediaSelectionGroup(for: .audible)) ?? nil
                    let sub = (try? await asset.loadMediaSelectionGroup(for: .legible)) ?? nil
                    let chapters = await self.loadChapters(asset: asset)
                    await MainActor.run {
                        self.audioGroup = audio
                        self.subtitleGroup = sub
                        self.applyPreferredTracks()
                        self.broadcastTracks()
                        self.chapters = chapters
                        self.broadcastTimeline()
                    }
                }
            } else {
                // tvOS 14/15: the only accessor is the deprecated sync one — skip track sync there.
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
            switch cmd {
            case "play": player.play()
            case "pause": player.pause()
            case "play_pause", "toggle":
                if player.timeControlStatus == .playing { player.pause() } else { player.play() }
            case "stop":
                broadcastStatus()
                onExit()
                return
            case "seek_forward": seek(by: 15)
            case "seek_back": seek(by: -15)
            case let c where c.hasPrefix("seek_to:"):
                if let ms = Double(c.dropFirst("seek_to:".count)) { seek(to: ms / 1000) }
            case let c where c.hasPrefix("audio_track:"):
                selectTrack(.audible, id: String(c.dropFirst("audio_track:".count)))
            case let c where c.hasPrefix("sub_track:"):
                let id = String(c.dropFirst("sub_track:".count))
                if let externalID = Int(id), externalID < -1 {
                    selectExternalSubtitle(externalID)
                } else {
                    selectTrack(.legible, id: id)
                }
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
                break  // speed/scaling/filter/audio_boost/sub_offset: not supported on AVPlayer
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
            controller.showsPlaybackControls = !isPreBuffering && !remoteControlsHidden
            chrome.snapshot = { [weak self] in
                guard let self else { return PlayerEngineChromeSnapshot() }
                let presented = self.playerController?.presentedViewController != nil
                let controls = !self.controlsSuppressedByPreplay && !self.remoteControlsHidden
                    && (self.playerController?.showsPlaybackControls ?? false)
                return PlayerEngineChromeSnapshot(
                    settingsSheet: presented,
                    controlsVisible: controls
                )
            }
            chrome.apply = { [weak self] effect in
                self?.applyRemoteEffect(effect) ?? false
            }
        }

        /// Network Back closes a presented sheet, then the system transport.
        /// Track menus drawn inside that transport go away with it.
        private func applyRemoteEffect(_ effect: PlayerRemoteEffect) -> Bool {
            switch effect {
            case .dismissSettingsSheet:
                guard playerController?.presentedViewController != nil else { return false }
                playerController?.dismiss(animated: true)
                return true
            case .dismissControls:
                return hideRemoteControls()
            case .togglePlayback:
                guard let player else { return false }
                if player.timeControlStatus == .playing { player.pause() } else { player.play() }
                return true
            case .seekBackward:
                guard player != nil else { return false }
                seek(by: -15)
                return true
            case .seekForward:
                guard player != nil else { return false }
                seek(by: 15)
                return true
            default:
                return false
            }
        }

        private func hideRemoteControls() -> Bool {
            guard !remoteControlsHidden, !controlsSuppressedByPreplay,
                  playerController?.showsPlaybackControls == true else { return false }
            remoteControlsHidden = true
            playerController?.showsPlaybackControls = false
            return true
        }

        @objc private func onStillWatchingPause() { player?.pause(); broadcastStatus() }
        @objc private func onStillWatchingResume() { player?.play(); broadcastStatus() }

        private func seek(by delta: Double) {
            guard let player else { return }
            seek(to: max(0, player.currentTime().seconds + delta))
        }

        private func seek(to seconds: Double) {
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
