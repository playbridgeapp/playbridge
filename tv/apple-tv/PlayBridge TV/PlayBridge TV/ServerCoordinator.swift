import Foundation
import Observation
import PlayBridgeProtocol

enum AppRoute: Equatable {
    case home
    case prePlay(ContentPlayPayload)
    case player

    static func == (lhs: AppRoute, rhs: AppRoute) -> Bool {
        switch (lhs, rhs) {
        case (.home, .home): return true
        case (.player, .player): return true
        case (.prePlay(let l), .prePlay(let r)): return l.contentId == r.contentId
        default: return false
        }
    }
}

@Observable
class ServerCoordinator {
    var route: AppRoute = .home
    var server: WebSocketServer

    init(server: WebSocketServer) {
        self.server = server
        self.server.coordinator = self
    }

    func handlePlayContent(_ payload: ContentPlayPayload) {
        Task { @MainActor in
            self.route = .prePlay(payload)
        }
    }

    func selectStream(_ stream: ScoredStremioStream, from payload: ContentPlayPayload) {
        Task { @MainActor in
            let playPayload = PlayPayload(
                url: stream.url,
                title: payload.title + (payload.episodeTitle.map { " - \($0)" } ?? ""),
                headers: nil,  // Addons usually don't need custom headers for direct URLs
                contentType: payload.contentType,
                subtitles: nil,
                detectedBy: "TV Stremio Client",
                playerMode: payload.playerMode,
                preferredAudioLanguage: payload.preferredAudioLanguage,
                preferredSubtitleLanguage: payload.preferredSubtitleLanguage,
                defaultVideoQuality: payload.defaultVideoQuality,
                maxBitrateCapMbps: payload.maxBitrateCapMbps,
                seriesContext: nil  // We can build a SeriesContext here if needed
            )

            server.playerViewModel.load(playPayload)
            self.route = .player
        }
    }

    func exitPlayer() {
        Task { @MainActor in
            server.playerViewModel.stop()
            self.route = .home
        }
    }
}
