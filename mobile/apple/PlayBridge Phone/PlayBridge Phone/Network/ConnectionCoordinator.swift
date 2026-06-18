import Foundation

/// Parses TV→phone status messages into observable now-playing state.
/// Direct port of `connection/ConnectionCoordinator.kt`. Handles the proto-defined messages
/// (context/status/playlist_status) plus the non-proto `tracks` and `player_settings`.
final class ConnectionCoordinator: ObservableObject {
    @Published var activeContext: String = "idle"   // player | browser | idle
    @Published var playback: TvPlaybackStatus?
    @Published var playlist: PlaylistUiState?
    @Published var audioTracks: [MediaTrack] = []
    @Published var subtitleTracks: [MediaTrack] = []
    @Published var playerSpeed: Float = 1.0
    @Published var playerScaling: String = "Fit"

    /// Feed every non-handshake message here (wired to `WebSocketClient.onMessage`).
    func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = json["type"] as? String else { return }

        DispatchQueue.main.async { self.dispatch(type: type, json: json) }
    }

    private func dispatch(type: String, json: [String: Any]) {
        switch type {
        case "context":
            let active = json["active"] as? String ?? "idle"
            activeContext = active
            if active == "idle" { clear() }

        case "status":
            playback = TvPlaybackStatus(
                state: json["state"] as? String ?? "paused",
                positionMs: int64(json["position"]),
                durationMs: int64(json["duration"]),
                title: (json["title"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            )

        case "playlist_status":
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
                let binge = (o["bingeGroup"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                episodes.append(PlaylistEpisode(index: index, title: title, season: season,
                                                episode: episode, imdbId: imdb, bingeGroup: binge))
            }
            playlist = PlaylistUiState(
                currentIndex: json["currentIndex"] as? Int ?? 0,
                totalCount: json["totalCount"] as? Int ?? 0,
                items: episodes
            )

        case "tracks":
            audioTracks = parseTracks(json["audio"])
            subtitleTracks = parseTracks(json["subtitle"])

        case "player_settings":
            if let speed = json["speed"] as? Double { playerSpeed = Float(speed) }
            playerScaling = json["scaling"] as? String ?? playerScaling

        default:
            break
        }
    }

    private func parseTracks(_ value: Any?) -> [MediaTrack] {
        guard let arr = value as? [[String: Any]] else { return [] }
        return arr.enumerated().map { (i, o) in
            MediaTrack(
                id: o["id"] as? String ?? "\(i)",
                name: o["name"] as? String ?? "Track \(i + 1)",
                selected: o["selected"] as? Bool ?? false
            )
        }
    }

    private func int64(_ value: Any?) -> Int64 {
        if let n = value as? Int64 { return n }
        if let n = value as? Int { return Int64(n) }
        if let n = value as? Double { return Int64(n) }
        if let n = value as? NSNumber { return n.int64Value }
        return 0
    }

    func clear() {
        playback = nil
        playlist = nil
        audioTracks = []
        subtitleTracks = []
        playerSpeed = 1.0
        playerScaling = "Fit"
    }
}
