import SwiftUI
import AVKit

/// Using UIViewControllerRepresentable is much more stable on tvOS for custom headers and MKVs
struct NativePlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let title: String?
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
    let onSwitch: (Double) -> Void
    /// Sends a now-playing JSON message (status/tracks) to connected phones.
    let onBroadcast: ([String: Any]) -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
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

        let loopAction = UIAction(
            title: "Loop",
            image: UIImage(systemName: "repeat")
        ) { [weak coordinator = context.coordinator] action in
            coordinator?.toggleLoop(action)
        }
        
        let switchAction = UIAction(
            title: "Switch Player",
            image: UIImage(systemName: "arrow.triangle.2.circlepath")
        ) { [weak coordinator = context.coordinator] _ in
            coordinator?.invokeSwitch()
        }
        
        let playlistAction = UIAction(
            title: "Playlist",
            image: UIImage(systemName: "list.bullet")
        ) { _ in
            NotificationCenter.default.post(name: NSNotification.Name("TogglePlaylist"), object: nil)
        }
        
        controller.transportBarCustomMenuItems = [loopAction, switchAction, playlistAction]

        player.isMuted = isPreBuffering
        context.coordinator.attach(player: player)
        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        uiViewController.player?.isMuted = isPreBuffering
        uiViewController.showsPlaybackControls = !isPreBuffering
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(title: title, onDismiss: onDismiss, onExit: onExit, onSwitch: onSwitch, onBroadcast: onBroadcast)
    }

    static func dismantleUIViewController(_ uiViewController: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.teardown()
    }

    class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        var isLooping = false
        weak var player: AVPlayer?
        let title: String?
        let onDismiss: () -> Void
        let onExit: () -> Void
        let onSwitch: (Double) -> Void
        let onBroadcast: ([String: Any]) -> Void

        private var timeObserver: Any?
        private var didBroadcastTracks = false

        init(title: String?, onDismiss: @escaping () -> Void, onExit: @escaping () -> Void,
             onSwitch: @escaping (Double) -> Void, onBroadcast: @escaping ([String: Any]) -> Void) {
            self.title = title
            self.onDismiss = onDismiss
            self.onExit = onExit
            self.onSwitch = onSwitch
            self.onBroadcast = onBroadcast
            super.init()

            NotificationCenter.default.addObserver(
                self, selector: #selector(itemDidFinish),
                name: .AVPlayerItemDidPlayToEndTime, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onControl(_:)),
                name: WebSocketServer.controlCommand, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onRemote(_:)),
                name: WebSocketServer.remoteKey, object: nil)
            NotificationCenter.default.addObserver(
                self, selector: #selector(onResync),
                name: WebSocketServer.resyncRequest, object: nil)
        }

        /// Called once the player exists: drive periodic now-playing status to the phone.
        func attach(player: AVPlayer) {
            self.player = player
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 1, preferredTimescale: 1), queue: .main
            ) { [weak self] _ in self?.broadcastStatus() }
        }

        func teardown() {
            if let token = timeObserver { player?.removeTimeObserver(token); timeObserver = nil }
            NotificationCenter.default.removeObserver(self)
        }

        deinit { teardown() }

        @objc func toggleLoop(_ action: UIAction) {
            isLooping.toggle()
            action.state = isLooping ? .on : .off
        }

        @objc func invokeSwitch() {
            onSwitch(player?.currentTime().seconds ?? 0)
        }

        @objc func itemDidFinish(notification: Notification) {
            guard let finishedItem = notification.object as? AVPlayerItem,
                  finishedItem == player?.currentItem else { return }
            if isLooping {
                player?.seek(to: .zero)
                player?.play()
            } else {
                onDismiss()
            }
        }

        func playerViewController(
            _ playerViewController: AVPlayerViewController,
            willEndFullScreenPresentation interactivelyDismissed: Bool
        ) {
            // User pressed Menu/Back on the Siri Remote
            onExit()
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
                "position": Int(max(0, pos) * 1000),
                "duration": Int(max(0, dur) * 1000),
            ]
            if let t = title, !t.isEmpty { json["title"] = t }
            onBroadcast(json)

            // Track lists become available once the item is ready; push them once.
            if !didBroadcastTracks, player.currentItem?.status == .readyToPlay {
                didBroadcastTracks = true
                broadcastTracks()
            }
        }

        private func broadcastTracks() {
            guard let item = player?.currentItem else { return }
            let asset = item.asset
            func encode(_ characteristic: AVMediaCharacteristic) -> [[String: Any]] {
                guard let group = asset.mediaSelectionGroup(forMediaCharacteristic: characteristic) else { return [] }
                let selected = item.currentMediaSelection.selectedMediaOption(in: group)
                return group.options.enumerated().map { index, option in
                    ["id": String(index), "name": option.displayName, "selected": option == selected]
                }
            }
            onBroadcast([
                "type": "tracks",
                "audio": encode(.audible),
                "subtitle": encode(.legible),
            ])
        }

        @objc private func onResync() {
            broadcastStatus()
            broadcastTracks()
        }

        @objc private func onControl(_ note: Notification) {
            guard let cmd = note.userInfo?["command"] as? String, let player else { return }
            switch cmd {
            case "play": player.play()
            case "pause": player.pause()
            case "play_pause", "toggle":
                if player.timeControlStatus == .playing { player.pause() } else { player.play() }
            case "stop": onExit()
            case "loop_on": isLooping = true
            case "loop_off": isLooping = false
            case "seek_forward": seek(by: 15)
            case "seek_back": seek(by: -15)
            case let c where c.hasPrefix("seek_to:"):
                if let ms = Double(c.dropFirst("seek_to:".count)) { seek(to: ms / 1000) }
            case let c where c.hasPrefix("audio_track:"):
                selectTrack(.audible, id: String(c.dropFirst("audio_track:".count)))
            case let c where c.hasPrefix("sub_track:"):
                selectTrack(.legible, id: String(c.dropFirst("sub_track:".count)))
            case let c where c.hasPrefix("switch_player:"):
                if String(c.dropFirst("switch_player:".count)) != "avplayer" { invokeSwitch() }
            default:
                break  // speed/scaling/filter/audio_boost/sub_offset: not supported on AVPlayer
            }
            broadcastStatus()
        }

        @objc private func onRemote(_ note: Notification) {
            guard let key = note.userInfo?["key"] as? String, let player else { return }
            switch key {
            case "dpad_center":
                if player.timeControlStatus == .playing { player.pause() } else { player.play() }
            case "dpad_left":  seek(by: -15)
            case "dpad_right": seek(by: 15)
            default: break
            }
        }

        private func seek(by delta: Double) {
            guard let player else { return }
            seek(to: max(0, player.currentTime().seconds + delta))
        }

        private func seek(to seconds: Double) {
            player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        }

        private func selectTrack(_ characteristic: AVMediaCharacteristic, id: String) {
            guard let item = player?.currentItem,
                  let group = item.asset.mediaSelectionGroup(forMediaCharacteristic: characteristic) else { return }
            if id == "none" || id == "-1" {
                item.select(nil, in: group)  // deselect (e.g. subtitles off)
            } else if let index = Int(id), group.options.indices.contains(index) {
                item.select(group.options[index], in: group)
            }
            broadcastTracks()
        }
    }
}
