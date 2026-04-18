import Foundation

/// A single episode reference within a series
public struct SeriesEpisodeRef: Codable {
    public let season: Int
    public let episode: Int
    public let title: String?

    public init(season: Int, episode: Int, title: String? = nil) {
        self.season = season
        self.episode = episode
        self.title = title
    }
}

/// Series context attached to a PlayPayload when casting a Stremio series episode.
public struct SeriesContext: Codable {
    public let imdbId: String
    public let season: Int
    public let episode: Int
    public let seriesTitle: String?
    public let episodeTitle: String?
    public let addonBaseUrls: [String]
    public let addonNames: [String]?
    public let allEpisodes: [SeriesEpisodeRef]?
    public let preferredAddonBaseUrl: String?
    public let preferredAddonName: String?

    public init(
        imdbId: String,
        season: Int,
        episode: Int,
        seriesTitle: String? = nil,
        episodeTitle: String? = nil,
        addonBaseUrls: [String],
        addonNames: [String]? = nil,
        allEpisodes: [SeriesEpisodeRef]? = nil,
        preferredAddonBaseUrl: String? = nil,
        preferredAddonName: String? = nil
    ) {
        self.imdbId = imdbId
        self.season = season
        self.episode = episode
        self.seriesTitle = seriesTitle
        self.episodeTitle = episodeTitle
        self.addonBaseUrls = addonBaseUrls
        self.addonNames = addonNames
        self.allEpisodes = allEpisodes
        self.preferredAddonBaseUrl = preferredAddonBaseUrl
        self.preferredAddonName = preferredAddonName
    }
}

/// Play video command payload
public struct PlayPayload: Codable {
    public let url: String
    public let title: String?
    public let headers: [String: String]?
    public let contentType: String?
    public let subtitles: [String]?
    public let detectedBy: String?
    public let playerMode: String?
    public let preferredAudioLanguage: String?
    public let preferredSubtitleLanguage: String?
    public let defaultVideoQuality: String?
    public let maxBitrateCapMbps: Double?
    public let seriesContext: SeriesContext?

    public init(
        url: String,
        title: String? = nil,
        headers: [String: String]? = nil,
        contentType: String? = nil,
        subtitles: [String]? = nil,
        detectedBy: String? = nil,
        playerMode: String? = nil,
        preferredAudioLanguage: String? = nil,
        preferredSubtitleLanguage: String? = nil,
        defaultVideoQuality: String? = nil,
        maxBitrateCapMbps: Double? = nil,
        seriesContext: SeriesContext? = nil
    ) {
        self.url = url
        self.title = title
        self.headers = headers
        self.contentType = contentType
        self.subtitles = subtitles
        self.detectedBy = detectedBy
        self.playerMode = playerMode
        self.preferredAudioLanguage = preferredAudioLanguage
        self.preferredSubtitleLanguage = preferredSubtitleLanguage
        self.defaultVideoQuality = defaultVideoQuality
        self.maxBitrateCapMbps = maxBitrateCapMbps
        self.seriesContext = seriesContext
    }
}

/// Playlist command payload
public struct PlaylistPayload: Codable {
    public let items: [PlayPayload]
    public let startIndex: Int

    public init(items: [PlayPayload], startIndex: Int = 0) {
        self.items = items
        self.startIndex = startIndex
    }
}

/// Queue add command payload
public struct QueueAddPayload: Codable {
    public let item: PlayPayload

    public init(item: PlayPayload) {
        self.item = item
    }
}

/// Playlist jump command payload
public struct PlaylistJumpPayload: Codable {
    public let index: Int

    public init(index: Int) {
        self.index = index
    }
}

/// Play content metadata command payload
public struct ContentPlayPayload: Codable {
    public let contentId: String
    public let contentType: String
    public let title: String
    public let year: String?
    public let rating: String?
    public let runtime: String?
    public let overview: String?
    public let genres: [String]?
    public let cast: [String]?
    public let director: String?
    public let backdropUrl: String?
    public let posterUrl: String?
    public let logoUrl: String?
    public let season: Int?
    public let episode: Int?
    public let episodeTitle: String?
    public let allEpisodes: [SeriesEpisodeRef]?
    public let addonBaseUrls: [String]
    public let addonNames: [String]?
    public let preferredAddonBaseUrl: String?
    public let preferredAddonName: String?
    public let playerMode: String?
    public let preferredAudioLanguage: String?
    public let preferredSubtitleLanguage: String?
    public let defaultVideoQuality: String?
    public let maxBitrateCapMbps: Double?
    public let forcePicker: Bool?

    public init(
        contentId: String,
        contentType: String,
        title: String,
        year: String? = nil,
        rating: String? = nil,
        runtime: String? = nil,
        overview: String? = nil,
        genres: [String]? = nil,
        cast: [String]? = nil,
        director: String? = nil,
        backdropUrl: String? = nil,
        posterUrl: String? = nil,
        logoUrl: String? = nil,
        season: Int? = nil,
        episode: Int? = nil,
        episodeTitle: String? = nil,
        allEpisodes: [SeriesEpisodeRef]? = nil,
        addonBaseUrls: [String],
        addonNames: [String]? = nil,
        preferredAddonBaseUrl: String? = nil,
        preferredAddonName: String? = nil,
        playerMode: String? = nil,
        preferredAudioLanguage: String? = nil,
        preferredSubtitleLanguage: String? = nil,
        defaultVideoQuality: String? = nil,
        maxBitrateCapMbps: Double? = nil,
        forcePicker: Bool? = nil
    ) {
        self.contentId = contentId
        self.contentType = contentType
        self.title = title
        self.year = year
        self.rating = rating
        self.runtime = runtime
        self.overview = overview
        self.genres = genres
        self.cast = cast
        self.director = director
        self.backdropUrl = backdropUrl
        self.posterUrl = posterUrl
        self.logoUrl = logoUrl
        self.season = season
        self.episode = episode
        self.episodeTitle = episodeTitle
        self.allEpisodes = allEpisodes
        self.addonBaseUrls = addonBaseUrls
        self.addonNames = addonNames
        self.preferredAddonBaseUrl = preferredAddonBaseUrl
        self.preferredAddonName = preferredAddonName
        self.playerMode = playerMode
        self.preferredAudioLanguage = preferredAudioLanguage
        self.preferredSubtitleLanguage = preferredSubtitleLanguage
        self.defaultVideoQuality = defaultVideoQuality
        self.maxBitrateCapMbps = maxBitrateCapMbps
        self.forcePicker = forcePicker
    }
}

/// Open browser command payload
public struct BrowserPayload: Codable {
    public let url: String
    public let browserMode: String?
    public let desktopMode: Bool?

    public init(url: String, browserMode: String? = nil, desktopMode: Bool? = nil) {
        self.url = url
        self.browserMode = browserMode
        self.desktopMode = desktopMode
    }
}

/// Player control command payload
public struct ControlPayload: Codable {
    public let command: String  // pause, play, seek, stop

    public init(command: String) {
        self.command = command
    }
}

/// Remote D-pad/navigation command payload
public struct RemotePayload: Codable {
    public let key: String  // dpad_up, dpad_down, dpad_left, dpad_right, dpad_center, back

    public init(key: String) {
        self.key = key
    }
}

/// Mouse/touchpad command payload
public struct MousePayload: Codable {
    public let event: String  // move, click, scroll
    public let dx: Float
    public let dy: Float

    public init(event: String, dx: Float = 0, dy: Float = 0) {
        self.event = event
        self.dx = dx
        self.dy = dy
    }
}

/// Browser control command payload
public struct BrowserControlPayload: Codable {
    public let action: String  // refresh, toggle_ublock

    public init(action: String) {
        self.action = action
    }
}
