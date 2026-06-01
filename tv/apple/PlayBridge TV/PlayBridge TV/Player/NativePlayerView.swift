import SwiftUI
import AVKit

/// Using UIViewControllerRepresentable is much more stable on tvOS for custom headers and MKVs
struct NativePlayerView: UIViewControllerRepresentable {
    let url: URL
    let headers: [String: String]?
    let initialTime: Double
    let isPreBuffering: Bool
    let onDismiss: () -> Void  // end-of-video: advance playlist or quit
    let onExit: () -> Void      // user pressed back: always quit
    let onSwitch: (Double) -> Void

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

        NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: playerItem, queue: .main
        ) { _ in
            onDismiss()
        }

        // Native AVPlayer
        let player = AVPlayer(playerItem: playerItem)
        
        // Seek to initial time if context switching occurred
        if initialTime > 0 {
            player.seek(to: CMTime(seconds: initialTime, preferredTimescale: 1))
        }

        controller.player = player
        controller.allowsPictureInPicturePlayback = true

        // Configure custom tvOS AVPlayer UI Overlays
        context.coordinator.player = player
        
        let loopAction = UIAction(
            title: "Loop",
            image: UIImage(systemName: "repeat")
        ) { [weak coordinator = context.coordinator] action in
            coordinator?.toggleLoop(action)
        }
        
        let switchAction = UIAction(
            title: "Switch to MPV",
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
        player.play()
        return controller
    }

    func updateUIViewController(_ uiViewController: AVPlayerViewController, context: Context) {
        uiViewController.player?.isMuted = isPreBuffering
        if isPreBuffering {
            uiViewController.showsPlaybackControls = false
        } else {
            uiViewController.showsPlaybackControls = true
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onDismiss: onDismiss, onExit: onExit, onSwitch: onSwitch)
    }

    class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        var isLooping = false
        weak var player: AVPlayer?
        let onDismiss: () -> Void
        let onExit: () -> Void
        let onSwitch: (Double) -> Void
        
        init(onDismiss: @escaping () -> Void, onExit: @escaping () -> Void, onSwitch: @escaping (Double) -> Void) {
            self.onDismiss = onDismiss
            self.onExit = onExit
            self.onSwitch = onSwitch
            super.init()
            
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(itemDidFinish),
                name: .AVPlayerItemDidPlayToEndTime,
                object: nil
            )
        }
        
        @objc func toggleLoop(_ action: UIAction) {
            isLooping.toggle()
            action.state = isLooping ? .on : .off
        }
        
        @objc func invokeSwitch() {
            if let currentTime = player?.currentTime().seconds {
                onSwitch(currentTime)
            }
        }
        
        @objc func itemDidFinish(notification: Notification) {
            // Ensure this notification applies to our specific player loop scope
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
    }
}
