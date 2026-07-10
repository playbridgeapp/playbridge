import Cocoa
import FlutterMacOS
import MediaPlayer

/// Native Now Playing + remote command center for Bluetooth / keyboard media keys.
///
/// The pub `flutter_media_session` plugin registers play/pause but never sets
/// `MPNowPlayingInfoCenter.playbackState` on macOS, and it skips
/// `togglePlayPauseCommand` — which is what most BT headsets actually send.
/// Without those, macOS routes headset buttons elsewhere.
final class MediaRemoteHandler: NSObject {
  static let shared = MediaRemoteHandler()

  private var eventSink: FlutterEventSink?
  private var commandTargets: [Any] = []
  private var registered = false

  private override init() {
    super.init()
  }

  func register(with messenger: FlutterBinaryMessenger) {
    let method = FlutterMethodChannel(
      name: "com.playbridge.desktop/media_remote",
      binaryMessenger: messenger
    )
    method.setMethodCallHandler { [weak self] call, result in
      self?.handle(call, result: result)
    }

    let events = FlutterEventChannel(
      name: "com.playbridge.desktop/media_remote_events",
      binaryMessenger: messenger
    )
    events.setStreamHandler(self)
  }

  private func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "activate":
      activate()
      result(nil)
    case "deactivate":
      deactivate()
      result(nil)
    case "update":
      if let args = call.arguments as? [String: Any] {
        update(args)
      }
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private func activate() {
    guard !registered else { return }
    registered = true
    let center = MPRemoteCommandCenter.shared()

    // Most Bluetooth headsets send toggle, not separate play/pause.
    register(center.togglePlayPauseCommand, action: "toggle")
    register(center.playCommand, action: "play")
    register(center.pauseCommand, action: "pause")
    register(center.stopCommand, action: "stop")
    register(center.nextTrackCommand, action: "skipToNext")
    register(center.previousTrackCommand, action: "skipToPrevious")
    register(center.skipForwardCommand, action: "fastForward")
    register(center.skipBackwardCommand, action: "rewind")
    center.skipForwardCommand.preferredIntervals = [10]
    center.skipBackwardCommand.preferredIntervals = [10]

    let seekTarget = center.changePlaybackPositionCommand.addTarget { [weak self] event in
      guard let e = event as? MPChangePlaybackPositionCommandEvent else {
        return .commandFailed
      }
      self?.emit(["action": "seekTo", "positionMs": Int(e.positionTime * 1000)])
      return .success
    }
    commandTargets.append(seekTarget)
    center.changePlaybackPositionCommand.isEnabled = true

    // Begin as paused idle so the system knows we own the remote center.
    if #available(macOS 10.12.2, *) {
      MPNowPlayingInfoCenter.default().playbackState = .paused
    }
  }

  private func deactivate() {
    let center = MPRemoteCommandCenter.shared()
    for t in commandTargets {
      center.togglePlayPauseCommand.removeTarget(t)
      center.playCommand.removeTarget(t)
      center.pauseCommand.removeTarget(t)
      center.stopCommand.removeTarget(t)
      center.nextTrackCommand.removeTarget(t)
      center.previousTrackCommand.removeTarget(t)
      center.skipForwardCommand.removeTarget(t)
      center.skipBackwardCommand.removeTarget(t)
      center.changePlaybackPositionCommand.removeTarget(t)
    }
    commandTargets.removeAll()
    registered = false
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    if #available(macOS 10.12.2, *) {
      MPNowPlayingInfoCenter.default().playbackState = .stopped
    }
  }

  private func register(_ command: MPRemoteCommand, action: String) {
    command.isEnabled = true
    let target = command.addTarget { [weak self] _ in
      self?.emit(["action": action])
      return .success
    }
    commandTargets.append(target)
  }

  private func update(_ args: [String: Any]) {
    let title = args["title"] as? String ?? "PlayBridge"
    let artist = args["artist"] as? String ?? "PlayBridge"
    let status = args["status"] as? String ?? "paused"
    let positionMs = (args["positionMs"] as? NSNumber)?.intValue ?? 0
    let durationMs = (args["durationMs"] as? NSNumber)?.intValue ?? 0
    let speed = (args["speed"] as? NSNumber)?.doubleValue ?? 1.0

    var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [String: Any]()
    info[MPMediaItemPropertyTitle] = title
    info[MPMediaItemPropertyArtist] = artist
    info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.video.rawValue
    if durationMs > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000.0
    }
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(positionMs) / 1000.0
    info[MPNowPlayingInfoPropertyPlaybackRate] = status == "playing" ? speed : 0.0
    info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = speed
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info

    // Critical on macOS: without playbackState, remote events are not delivered.
    if #available(macOS 10.12.2, *) {
      switch status {
      case "playing":
        MPNowPlayingInfoCenter.default().playbackState = .playing
      case "buffering":
        MPNowPlayingInfoCenter.default().playbackState = .playing
      case "ended", "idle":
        MPNowPlayingInfoCenter.default().playbackState = .stopped
      default:
        MPNowPlayingInfoCenter.default().playbackState = .paused
      }
    }
  }

  private func emit(_ event: [String: Any]) {
    DispatchQueue.main.async { [weak self] in
      self?.eventSink?(event)
    }
  }
}

extension MediaRemoteHandler: FlutterStreamHandler {
  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink)
    -> FlutterError?
  {
    eventSink = events
    return nil
  }

  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }
}
