import Foundation

extension PlayRequest {
    init?(from payload: Playbridge_PlayPayload) {
        guard let url = URL(string: payload.url) else { return nil }
        self.init(
            url: url,
            title: payload.hasTitle ? payload.title : nil,
            headers: payload.headers.isEmpty ? nil : payload.headers,
            subtitles: payload.subtitles.isEmpty ? nil : payload.subtitles,
            preferredAudioLanguage: payload.hasPreferredAudioLanguage ? payload.preferredAudioLanguage : nil,
            preferredSubtitleLanguage: payload.hasPreferredSubtitleLanguage ? payload.preferredSubtitleLanguage : nil,
            visualMetadata: payload.hasVisualMetadata ? VisualMetadata(from: payload.visualMetadata) : nil
        )
    }
}

extension VisualMetadata {
    init(from meta: Playbridge_VisualMetadata) {
        self.init(
            title: meta.title,
            overview: meta.hasOverview ? meta.overview : nil,
            posterUrl: meta.hasPosterURL ? meta.posterURL : nil,
            backdropUrl: meta.hasBackdropURL ? meta.backdropURL : nil,
            logoUrl: meta.hasLogoURL ? meta.logoURL : nil,
            year: meta.hasYear ? meta.year : nil,
            rating: meta.hasRating ? meta.rating : nil,
            runtime: meta.hasRuntime ? meta.runtime : nil,
            season: meta.hasSeason ? Int(meta.season) : nil,
            episode: meta.hasEpisode ? Int(meta.episode) : nil,
            episodeTitle: meta.hasEpisodeTitle ? meta.episodeTitle : nil
        )
    }
}
