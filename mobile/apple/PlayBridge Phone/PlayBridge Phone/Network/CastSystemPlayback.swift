import AVFoundation
import MediaPlayer
import UIKit

/// App-wide ownership of the audio session. The silent PCM loop supports an
/// active external playback session; its timing never supplies the TV seek bar.
/// All entry points run on the main thread, including remote command callbacks.
final class CastSystemPlayback: NSObject, CastPlaybackRendering, AVAudioPlayerDelegate {
    static let shared = CastSystemPlayback()
    var onAction: ((CastRemoteAction) -> Bool)?
    var onLocalPlaybackBegan: (() -> Void)?
    private var snapshot: CastNowPlayingSnapshot?
    private var wantsKeepAlive = false
    private var silentPlayer: AVAudioPlayer?
    private var localOwners = Set<UUID>()
    private var interrupted = false
    private var ownsAudioSession = false
    private var ownsNowPlaying = false
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    private var observers: [NSObjectProtocol] = []
    private(set) var lastAudioError: String?
    private lazy var artwork: MPMediaItemArtwork? = {
        guard let image = UIImage(systemName: "play.tv.fill") else { return nil }
        return MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }()

    /// Launch with `-CastSilentAudioEnabled NO` in Debug for the audio-session-only
    /// comparison. The release implementation is explicit about using silence.
    private var silentAudioEnabled: Bool {
#if DEBUG
        return UserDefaults.standard.object(forKey: "CastSilentAudioEnabled") as? Bool ?? true
#else
        return true
#endif
    }

    override init() {
        super.init()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            self?.handleInterruption(note)
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            silentPlayer = nil
            ownsAudioSession = false
            refresh()
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.refresh()
        })
    }

    func render(_ snapshot: CastNowPlayingSnapshot, keepAlive: Bool) {
        self.snapshot = snapshot
        wantsKeepAlive = keepAlive
        refresh()
    }

    func clear() {
        snapshot = nil
        wantsKeepAlive = false
        stopSilentAudio(deactivate: localOwners.isEmpty)
        clearNowPlaying()
    }

    /// Local AVPlayer UI has priority over the remote cast's system controls.
    /// A token prevents one dismissed player from deactivating another's session.
    func beginLocalPlayback(externalAirPlay: Bool = false) throws -> UUID {
        if !externalAirPlay { onLocalPlaybackBegan?() }
        let id = UUID()
        localOwners.insert(id)
        stopSilentAudio(deactivate: false)
        clearNowPlaying()
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playback, mode: .moviePlayback, policy: externalAirPlay ? .longFormVideo : .default)
            try audio.setActive(true)
            ownsAudioSession = true
            return id
        } catch {
            localOwners.remove(id)
            refresh()
            throw error
        }
    }

    func endLocalPlayback(_ id: UUID) {
        guard localOwners.remove(id) != nil, localOwners.isEmpty else { return }
        if snapshot == nil { stopSilentAudio(deactivate: true) }
        else { refresh() }
    }

    /// Only an explicit media action may reclaim audio after another app interrupts.
    func userRequestedPlayback() {
        interrupted = false
        refresh()
    }

    private func refresh() {
        guard localOwners.isEmpty, !interrupted, let snapshot else { return }
        if wantsKeepAlive {
            do {
                if !ownsAudioSession {
                    let audio = AVAudioSession.sharedInstance()
                    try audio.setCategory(.playback, mode: .default)
                    try audio.setActive(true)
                    ownsAudioSession = true
                }
                if silentAudioEnabled, silentPlayer?.isPlaying != true {
                    if silentPlayer == nil {
                        silentPlayer = try AVAudioPlayer(data: Self.silentWAV())
                        silentPlayer?.numberOfLoops = -1
                        silentPlayer?.delegate = self
                    }
                    guard silentPlayer?.play() == true else {
                        lastAudioError = "Silent audio could not start"
                        return
                    }
                }
                lastAudioError = nil
            } catch {
                // Only domain/code are retained; Foundation error text can contain URLs.
                let error = error as NSError
                lastAudioError = "\(error.domain)/\(error.code)"
                return
            }
        } else { stopSilentAudio(deactivate: true) }
        installCommands()
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: snapshot.title,
            MPMediaItemPropertyArtist: snapshot.receiverName,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.position,
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.rate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyIsLiveStream: snapshot.isLive,
            MPNowPlayingInfoPropertyMediaType: (snapshot.isAudio ? MPNowPlayingInfoMediaType.audio : .video).rawValue
        ]
        if #available(iOS 18.0, *) { info[MPNowPlayingInfoPropertyExcludeFromSuggestions] = true }
        if snapshot.duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = snapshot.duration }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        ownsNowPlaying = true
        let commands = MPRemoteCommandCenter.shared()
        commands.playCommand.isEnabled = snapshot.isConnected
        commands.pauseCommand.isEnabled = snapshot.isConnected
        commands.togglePlayPauseCommand.isEnabled = snapshot.isConnected
        commands.stopCommand.isEnabled = snapshot.isConnected
        commands.changePlaybackPositionCommand.isEnabled = snapshot.isConnected && snapshot.canSeek
        commands.skipBackwardCommand.isEnabled = snapshot.isConnected && snapshot.canSeek
        commands.skipForwardCommand.isEnabled = snapshot.isConnected && snapshot.canSeek
    }

    private func installCommands() {
        guard commandTargets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        add(center.playCommand) { _ in .play }
        add(center.pauseCommand) { _ in .pause }
        add(center.togglePlayPauseCommand) { _ in .toggle }
        add(center.stopCommand) { _ in .stop }
        add(center.changePlaybackPositionCommand) { ($0 as? MPChangePlaybackPositionCommandEvent).map { .seek($0.positionTime) } }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.preferredIntervals = [15]
        add(center.skipBackwardCommand) { ($0 as? MPSkipIntervalCommandEvent).map { .skip(-$0.interval) } }
        add(center.skipForwardCommand) { ($0 as? MPSkipIntervalCommandEvent).map { .skip($0.interval) } }
    }

    private func add(_ command: MPRemoteCommand, action: @escaping (MPRemoteCommandEvent) -> CastRemoteAction?) {
        let target = command.addTarget { [weak self] event in
            let handle: () -> MPRemoteCommandHandlerStatus = {
                guard let self, self.localOwners.isEmpty, !self.interrupted,
                      let action = action(event) else { return .commandFailed }
                return self.onAction?(action) == true ? .success : .commandFailed
            }
            return Thread.isMainThread ? handle() : DispatchQueue.main.sync(execute: handle)
        }
        commandTargets.append((command, target))
    }

    private func clearNowPlaying() {
        for (command, target) in commandTargets { command.removeTarget(target) }
        commandTargets.removeAll()
        if ownsNowPlaying { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }
        ownsNowPlaying = false
    }

    private func stopSilentAudio(deactivate: Bool) {
        silentPlayer?.stop()
        silentPlayer = nil
        if deactivate, ownsAudioSession {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            ownsAudioSession = false
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .began {
            interrupted = true
            ownsAudioSession = false
            silentPlayer?.pause()
            clearNowPlaying()
        } else {
            let options = AVAudioSession.InterruptionOptions(rawValue: notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            if options.contains(.shouldResume) {
                interrupted = false
                refresh()
            }
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard player === silentPlayer else { return }
        lastAudioError = "Silent audio decoder failed"
        stopSilentAudio(deactivate: localOwners.isEmpty)
    }

    var diagnostics: String {
        "Cast session: \(snapshot == nil ? "idle" : "active"); silent audio: \(silentPlayer?.isPlaying == true); interrupted: \(interrupted); local player: \(!localOwners.isEmpty); audio error: \(lastAudioError ?? "none")"
    }

    /// One second of mono 16-bit PCM silence; repeated in memory, never downloaded.
    static func silentWAV() -> Data {
        let sampleRate: UInt32 = 8_000
        let byteCount = sampleRate * 2
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) {
            var little = value.littleEndian
            withUnsafeBytes(of: &little) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: "RIFF".utf8); append(UInt32(36) + byteCount)
        data.append(contentsOf: "WAVEfmt ".utf8); append(UInt32(16))
        append(UInt16(1)); append(UInt16(1)); append(sampleRate)
        append(sampleRate * 2); append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: "data".utf8); append(byteCount)
        data.append(Data(count: Int(byteCount)))
        return data
    }

    deinit { observers.forEach(NotificationCenter.default.removeObserver) }
}
