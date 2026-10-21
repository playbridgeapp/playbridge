import Foundation
import Combine
import CoreFoundation

/// Parses TV→phone status messages into observable now-playing state.
/// Direct port of `connection/ConnectionCoordinator.kt`. Handles the proto-defined messages
/// (context/status/playlist_status) plus the non-proto `tracks` and `player_settings`.
final class ConnectionCoordinator: ObservableObject {
    @Published var activeContext: String = "idle" { // player | browser | idle
        didSet { if activeContext != oldValue { remoteContextGeneration = UUID() } }
    }
    private(set) var remoteContextGeneration = UUID()
    @Published var playback: TvPlaybackStatus?
    @Published private(set) var playbackFailure: ReceiverPlaybackFailure?
    private var nativePlaybackID: String?
    private var playbackSessionID: UUID?
    @Published var playlist: PlaylistUiState?
    /// Chapters and skip segments from the receiver's `timeline` frame.
    @Published var timeline: TvTimeline?
    @Published var videoTracks: [MediaTrack] = []
    @Published var audioTracks: [MediaTrack] = []
    @Published var subtitleTracks: [MediaTrack] = []
    @Published var playerSpeed: Float = 1.0
    @Published var playerScaling: String = "Fit"
    @Published var audioBoost = false
    @Published var subtitleOffsetMs: Int64 = 0
    @Published var playerEngine = ""
    @Published var contextEngine: String?
    @Published var receiverSettingsAvailability: [String: Bool] = [:]
    @Published var receiverLoop: Bool?
    @Published var receiverVolumeScope: String?
    @Published var receiverVolumeLevel: Double?
    @Published var receiverVolumeMuted: Bool?
    private var volumeIntentTask: Task<Void, Never>?
    private var volumeBaseline: (Double?, Bool?)?
    private var volumeIntentID = UUID()

    /// A successful send is intent only. The next report or a short deadline restores truth.
    @MainActor func beginVolumeIntent(key: String, step: Double) {
        guard ["volume_up", "volume_down", "mute"].contains(key) else { return }
        if key == "mute" && receiverVolumeMuted == nil || key != "mute" && receiverVolumeLevel == nil { return }
        if volumeBaseline == nil { volumeBaseline = (receiverVolumeLevel, receiverVolumeMuted) }
        if key == "mute" { receiverVolumeMuted = receiverVolumeMuted.map { !$0 } }
        else if let level = receiverVolumeLevel {
            receiverVolumeLevel = min(1, max(0, level + (key == "volume_up" ? step : -step)))
        }
        volumeIntentTask?.cancel()
        let id = UUID(); volumeIntentID = id
        volumeIntentTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard !Task.isCancelled, let self, self.volumeIntentID == id else { return }
            self.endVolumeIntent()
        }
    }
    func endVolumeIntent() {
        volumeIntentTask?.cancel(); volumeIntentTask = nil
        volumeIntentID = UUID()
        if let baseline = volumeBaseline {
            receiverVolumeLevel = baseline.0; receiverVolumeMuted = baseline.1
        }
        volumeBaseline = nil
    }
    @Published var qualityMaxHeight = 0
    @Published var currentVideoHeight = 0
    @Published var playerIsLive = false
    @Published var playerIsSeekable = true
    @Published var speedAvailable = false
    @Published var scalingAvailable = false
    @Published var audioBoostAvailable = false
    @Published var qualityAvailable = false
    @Published var mediaKind = "video"
    @Published var lastCommandResult: QueueCommandResult?
    @Published var installedUserScripts: [String] = []
    @Published var userAgentActive = ""
    @Published var savedUserAgents: [(name: String, value: String)] = []

    /// Feed every non-handshake message here (wired to `WebSocketClient.onMessage`).
    func handle(_ text: String, sessionID: UUID? = nil, isCurrentSession: @escaping () -> Bool = { true }) {
        guard let data = text.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else { return }

        DispatchQueue.main.async {
            guard isCurrentSession() else { return }
            if let sessionID { self.beginReceiverSession(sessionID) }
            self.dispatch(type: type, json: json)
        }
    }

    private func dispatch(type: String, json: [String: Any]) {
        switch type {
        case "context":
            let active = json["active"] as? String ?? "idle"
            activeContext = active
            if active == "idle" { clear(keepingReceiverVolume: true, keepingPlaybackFailure: true) }
            contextEngine = (json["engine"] as? String).flatMap { (1...64).contains($0.unicodeScalars.count) ? $0 : nil }
            if active == "browser_external", contextEngine == nil { contextEngine = "gecko" }

        case "status":
            let state = json["state"] as? String ?? "paused"
            let id = nonEmpty(json["playbackId"])
            if state == "error" {
                if let id, let current = nativePlaybackID, id != current { return }
                if let id { nativePlaybackID = id }
                playbackFailure = ReceiverPlaybackFailure(message: ReceiverPlaybackFailure.message(json["error"]))
            } else if ["playing", "buffering", "paused"].contains(state) {
                if state == "playing" || id != nil && id != nativePlaybackID { dismissPlaybackFailure() }
                if let id { nativePlaybackID = id }
            }
            mediaKind = json["mediaKind"] as? String ?? "video"
            playback = TvPlaybackStatus(
                state: json["state"] as? String ?? "paused",
                positionMs: int64(json["position"]),
                durationMs: int64(json["duration"]),
                title: (json["title"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                playbackId: nonEmpty(json["playbackId"]),
                currentItemId: nonEmpty(json["currentItemId"]),
                bufferedMs: { let ms = int64(json["buffered"]); return ms > 0 ? ms : nil }()
            )

        case "timeline":
            timeline = TvTimeline.parse(json)

        case "playlist_status":
            if let id = nonEmpty(json["playbackId"]), id != nativePlaybackID {
                nativePlaybackID = id
                dismissPlaybackFailure()
            }
            let itemsJson = json["items"] as? [[String: Any]] ?? []
            var episodes: [PlaylistEpisode] = []
            for (i, o) in itemsJson.enumerated() {
                let index: Int = (o["index"] as? Int) ?? i
                let title: String = (o["title"] as? String) ?? "Item \(i + 1)"
                var season: Int? = o["season"] as? Int
                if let s = season, s < 0 { season = nil }
                var episode: Int? = o["episode"] as? Int
                if let e = episode, e < 0 { episode = nil }
                let imdb = (o["imdbId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                let tmdb = (o["tmdbId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                let binge = (o["bingeGroup"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                episodes.append(PlaylistEpisode(
                    index: index, title: title, itemId: nonEmpty(o["itemId"]),
                    season: season, episode: episode, imdbId: imdb, tmdbId: tmdb,
                    bingeGroup: binge
                ))
            }
            playlist = PlaylistUiState(
                currentIndex: json["currentIndex"] as? Int ?? 0,
                totalCount: json["totalCount"] as? Int ?? 0,
                items: episodes,
                playbackId: nonEmpty(json["playbackId"]),
                currentItemId: nonEmpty(json["currentItemId"]),
                queueRevision: uint64(json["queueRevision"])
            )

        case "command_result":
            guard let requestId = json["requestId"] as? String,
                  let ok = json["ok"] as? Bool else { return }
            lastCommandResult = QueueCommandResult(
                requestId: requestId,
                ok: ok,
                error: nonEmpty(json["error"]),
                playbackId: nonEmpty(json["playbackId"]),
                queueRevision: json["queueRevision"].map { uint64($0) }
            )

        case "tracks":
            videoTracks = parseTracks(json["video"])
            audioTracks = parseTracks(json["audio"])
            subtitleTracks = parseTracks(json["subtitle"])

        case "player_settings":
            receiverSettingsAvailability = [:]
            for (operation, field) in ["speed": "speedAvailable", "scaling": "scalingAvailable", "audio_boost": "audioBoostAvailable", "quality": "qualityAvailable", "subtitle_offset": "subtitleOffsetAvailable", "engine": "engineAvailable", "loop": "loopAvailable"] {
                if let number = json[field] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() {
                    receiverSettingsAvailability[operation] = number.boolValue
                }
            }
            receiverLoop = json["loop"] as? Bool
            if let speed = json["speed"] as? Double { playerSpeed = Float(speed) }
            else if let speed = json["speed"] as? NSNumber { playerSpeed = speed.floatValue }
            playerScaling = json["scaling"] as? String ?? "Fit"
            audioBoost = json["audioBoost"] as? Bool ?? false
            subtitleOffsetMs = int64(json["subtitleOffsetMs"])
            playerEngine = json["engine"] as? String ?? ""
            qualityMaxHeight = intValue(json["qualityMaxHeight"])
            currentVideoHeight = intValue(json["currentVideoHeight"])
            playerIsLive = json["isLive"] as? Bool ?? false
            playerIsSeekable = json["isSeekable"] as? Bool ?? true
            speedAvailable = json["speedAvailable"] as? Bool ?? true
            scalingAvailable = json["scalingAvailable"] as? Bool ?? true
            audioBoostAvailable = json["audioBoostAvailable"] as? Bool ?? true
            qualityAvailable = json["qualityAvailable"] as? Bool ?? false

        case "volume_state":
            guard Set(json.keys).isSubset(of: ["type", "scope", "level", "muted"]),
                  let scope = json["scope"] as? String, (1...64).contains(scope.unicodeScalars.count),
                  json["level"] != nil || json["muted"] != nil else { return }
            var level: Double?
            var muted: Bool?
            if let raw = json["level"] {
                guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                      number.doubleValue.isFinite, number.decimalValue >= 0, number.decimalValue <= 1 else { return }
                level = number.doubleValue
            }
            if let raw = json["muted"] {
                guard let number = raw as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return }
                muted = number.boolValue
            }
            endVolumeIntent()
            if receiverVolumeScope != scope { receiverVolumeLevel = nil; receiverVolumeMuted = nil }
            receiverVolumeScope = scope
            if let level { receiverVolumeLevel = level }
            if let muted { receiverVolumeMuted = muted }

        case "user_scripts":
            installedUserScripts = (json["names"] as? [String]) ?? []

        case "user_agents":
            userAgentActive = json["active"] as? String ?? ""
            savedUserAgents = ((json["entries"] as? [[String: Any]]) ?? []).compactMap { entry in
                guard let name = entry["name"] as? String, let value = entry["value"] as? String else { return nil }
                return (name, value)
            }

        default:
            break
        }
    }

    private func parseTracks(_ value: Any?) -> [MediaTrack] {
        guard let arr = value as? [[String: Any]] else { return [] }
        return arr.enumerated().map { (i, o) in
            MediaTrack(
                id: (o["id"] as? String) ?? (o["id"] as? NSNumber)?.stringValue ?? "\(i)",
                name: o["name"] as? String ?? "Track \(i + 1)",
                selected: o["selected"] as? Bool ?? false,
                type: o["type"] as? String
            )
        }
    }

    private func intValue(_ value: Any?) -> Int {
        if let n = value as? Int { return n }
        if let n = value as? NSNumber { return n.intValue }
        return 0
    }

    private func int64(_ value: Any?) -> Int64 {
        if let n = value as? Int64 { return n }
        if let n = value as? Int { return Int64(n) }
        if let n = value as? Double { return n.isFinite ? (Int64(exactly: n.rounded(.towardZero)) ?? 0) : 0 }
        if let n = value as? NSNumber { return n.int64Value }
        return 0
    }

    private func uint64(_ value: Any?) -> UInt64 {
        if let n = value as? UInt64 { return n }
        if let n = value as? Int { return UInt64(max(0, n)) }
        if let n = value as? NSNumber { return n.uint64Value }
        return 0
    }

    private func nonEmpty(_ value: Any?) -> String? {
        (value as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    func beginReceiverSession(_ id: UUID) {
        guard playbackSessionID != id else { return }
        playbackSessionID = id
        beginNativePlayback()
    }

    func beginNativePlayback() {
        nativePlaybackID = nil
        dismissPlaybackFailure()
    }

    func dismissPlaybackFailure() { playbackFailure = nil }

    func clear(keepingReceiverVolume: Bool = false, keepingPlaybackFailure: Bool = false) {
        if !keepingPlaybackFailure { beginNativePlayback() }
        clearReceiverMetadata(keepingVolume: keepingReceiverVolume)
        playback = nil
        playlist = nil
        timeline = nil
        videoTracks = []
        audioTracks = []
        subtitleTracks = []
        playerSpeed = 1.0
        playerScaling = "Fit"
        audioBoost = false
        subtitleOffsetMs = 0
        playerEngine = ""
        qualityMaxHeight = 0
        currentVideoHeight = 0
        playerIsLive = false
        playerIsSeekable = true
        speedAvailable = false
        scalingAvailable = false
        audioBoostAvailable = false
        qualityAvailable = false
        mediaKind = "video"
        lastCommandResult = nil
        installedUserScripts = []
        userAgentActive = ""
        savedUserAgents = []
    }

    func clearReceiverMetadata(keepingVolume: Bool = false) {
        contextEngine = nil
        receiverSettingsAvailability = [:]
        receiverLoop = nil
        if !keepingVolume {
            endVolumeIntent()
            receiverVolumeScope = nil
            receiverVolumeLevel = nil
            receiverVolumeMuted = nil
        }
    }
}

/// In-memory UI notice only. Never include receiver-supplied text in diagnostics.
struct ReceiverPlaybackFailure: Equatable, Identifiable {
    let id = UUID()
    let message: String

    static func message(_ raw: Any?) -> String {
        guard let raw = raw as? String else { return "Playback failed on the TV" }
        let text = raw.components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }.joined(separator: " ")
        guard !text.isEmpty else { return "Playback failed on the TV" }
        return String(String.UnicodeScalarView(text.unicodeScalars.prefix(512)))
    }
}
