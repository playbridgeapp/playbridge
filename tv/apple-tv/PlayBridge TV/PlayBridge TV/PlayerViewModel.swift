import AVFoundation
import Combine
import Foundation
import Observation
import PlayBridgeProtocol

@Observable
class PlayerViewModel {
    var engine: (any PlaybackEngine)?
    var state: PlaybackState = .idle
    var position: TimeInterval = 0
    var duration: TimeInterval = 0
    var currentTitle: String?

    // Exposed so PlayerOverlayView can show series context
    private(set) var currentPayload: PlayPayload?

    // Settings
    var playbackRate: Float = 1.0 {
        didSet { engine?.setRate(playbackRate) }
    }

    var filterPreset: FilterPreset = .none {
        didSet { applyFilterPreset() }
    }

    var customFilterSettings: ColorFilterSettings = .default {
        didSet {
            if filterPreset == .custom { engine?.setFilter(customFilterSettings) }
        }
    }

    // Playlist management
    var playlistItems: [PlayPayload] = []
    var currentIndex: Int = 0

    // Subtitle management
    var subtitleManager = SubtitleManager()

    private var cancellables = Set<AnyCancellable>()

    // MARK: - Series / Next-up

    /// Series context for the currently playing item.
    var currentSeriesContext: SeriesContext? { currentPayload?.seriesContext }

    /// Returns the next episode reference when within 60 s of the end of a series episode.
    var nextEpisodeInfo: SeriesEpisodeRef? {
        guard let sc = currentPayload?.seriesContext,
            let allEpisodes = sc.allEpisodes, !allEpisodes.isEmpty,
            duration > 0, duration - position < 60
        else { return nil }

        guard
            let currentIdx = allEpisodes.firstIndex(where: {
                $0.season == sc.season && $0.episode == sc.episode
            }),
            currentIdx + 1 < allEpisodes.count
        else { return nil }

        return allEpisodes[currentIdx + 1]
    }

    // MARK: - Filter

    private func applyFilterPreset() {
        switch filterPreset {
        case .none:
            engine?.setFilter(.default)
        case .sepia:
            engine?.setFilter(ColorFilterSettings(brightness: 0, contrast: 1.0, saturation: 0.5))
        case .grayscale:
            engine?.setFilter(ColorFilterSettings(brightness: 0, contrast: 1.0, saturation: 0.0))
        case .custom:
            engine?.setFilter(customFilterSettings)
        }
    }

    // MARK: - Load

    func load(_ payload: PlayPayload, items: [PlayPayload] = [], index: Int = 0) {
        // Persist progress for the previous item before switching
        if let current = currentPayload {
            ResumeStore.shared.save(
                url: current.url,
                position: Int64(position * 1000),
                duration: Int64(duration * 1000),
                rate: playbackRate,
                filterPreset: filterPreset,
                filterSettings: customFilterSettings)
        }

        engine?.stop()
        cancellables.removeAll()
        subtitleManager.clear()

        // Update playlist state
        if !items.isEmpty {
            self.playlistItems = items
            self.currentIndex = index
        } else if playlistItems.isEmpty {
            self.playlistItems = [payload]
            self.currentIndex = 0
        } else {
            self.currentIndex = index
        }

        self.currentPayload = payload
        self.currentTitle = payload.title

        // Pick engine — TV-side AppSettings override takes precedence over the phone's payload
        let modeOverride = AppSettings.shared.playerModeOverride
        let effectiveMode =
            modeOverride != "auto" ? modeOverride : (payload.playerMode ?? "internal")
        let newEngine: any PlaybackEngine =
            effectiveMode == "internal_vlc" ? VLCPlayerEngine() : AVPlayerEngine()
        self.engine = newEngine

        // Bind state
        newEngine.state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newState in
                self?.state = newState
                if newState == .ended { self?.advanceToNext() }
            }
            .store(in: &cancellables)

        setupEndOfMediaDetection(newEngine)

        // Bind position
        newEngine.position
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newPos in
                guard let self = self else { return }
                self.position = newPos
                self.duration = self.engine?.duration ?? 0
                self.subtitleManager.update(currentTime: newPos)

                // Periodic progress save every 5 s
                if Int(newPos) % 5 == 0, newPos > 0, let current = self.currentPayload {
                    ResumeStore.shared.save(
                        url: current.url,
                        position: Int64(newPos * 1000),
                        duration: Int64(self.duration * 1000),
                        rate: self.playbackRate,
                        filterPreset: self.filterPreset,
                        filterSettings: self.customFilterSettings)
                }
            }
            .store(in: &cancellables)

        Task {
            do {
                try await newEngine.load(payload)

                // Restore resume position and settings
                if let info = ResumeStore.shared.getResumeInfo(for: payload.url) {
                    let posSec = Double(info.positionMs) / 1000.0
                    let durSec = Double(info.durationMs) / 1000.0
                    if posSec > 5, posSec < durSec - 5 {
                        await newEngine.seek(to: posSec)
                    }
                    if let rate = info.playbackRate    { self.playbackRate = rate }
                    if let preset = info.filterPreset  { self.filterPreset = preset }
                    if let settings = info.filterSettings { self.customFilterSettings = settings }
                    applyFilterPreset()
                }

                // Load external subtitles
                if let subUrls = payload.subtitles {
                    for (i, subUrlString) in subUrls.enumerated() {
                        guard let subURL = URL(string: subUrlString) else { continue }
                        do {
                            let result = try await subtitleManager.downloadSubtitle(
                                url: subURL, headers: payload.headers, index: i)

                            // For VLC: attach via local HTTP endpoint so addPlaybackSlave gets a URL
                            if let vlc = newEngine as? VLCPlayerEngine {
                                let localFilename = result.localURL.lastPathComponent
                                let httpURL = SubtitleHTTPServer.shared.localURL(for: localFilename)
                                try? await vlc.attachExternalSubtitle(url: httpURL)
                            }

                            // AVPlayer: overlay rendered by SubtitleManager cues
                            subtitleManager.loadCues(result.cues)
                        } catch {
                            print("[PlayerViewModel] Subtitle load error: \(error)")
                        }
                    }
                }
            } catch {
                print("[PlayerViewModel] Engine load error: \(error)")
                self.state = .error
            }
        }
    }

    // MARK: - End-of-media Detection

    private func setupEndOfMediaDetection(_ engine: any PlaybackEngine) {
        if let avEngine = engine as? AVPlayerEngine {
            NotificationCenter.default.publisher(
                for: .AVPlayerItemDidPlayToEndTime,
                object: avEngine.player.currentItem
            )
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.advanceToNext() }
            .store(in: &cancellables)
        }
        // VLC: .ended state propagated through the state sink above
    }

    // MARK: - Playlist

    func advanceToNext() {
        let nextIndex = currentIndex + 1
        if nextIndex < playlistItems.count {
            load(playlistItems[nextIndex], items: playlistItems, index: nextIndex)
        } else {
            stop()
        }
    }

    func queueAdd(_ item: PlayPayload) { playlistItems.append(item) }

    func jumpTo(index: Int) {
        guard index >= 0, index < playlistItems.count else { return }
        load(playlistItems[index], items: playlistItems, index: index)
    }

    // MARK: - Controls

    func play()  { engine?.play() }
    func pause() { engine?.pause() }

    func stop() {
        engine?.stop()
        engine = nil
        state = .idle
        position = 0
        duration = 0
        currentTitle = nil
        currentPayload = nil
        cancellables.removeAll()
    }

    func seek(to: TimeInterval) {
        Task { await engine?.seek(to: to) }
    }

    func skipForward()  { seek(to: min(position + 10, duration)) }
    func skipBackward() { seek(to: max(position - 10, 0)) }
}
