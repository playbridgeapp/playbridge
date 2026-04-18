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

    // Settings
    var playbackRate: Float = 1.0 {
        didSet {
            engine?.setRate(playbackRate)
        }
    }

    var filterPreset: FilterPreset = .none {
        didSet {
            applyFilterPreset()
        }
    }

    var customFilterSettings: ColorFilterSettings = .default {
        didSet {
            if filterPreset == .custom {
                engine?.setFilter(customFilterSettings)
            }
        }
    }

    // Playlist management
    var playlistItems: [PlayPayload] = []
    var currentIndex: Int = 0

    // Subtitle management
    var subtitleManager = SubtitleManager()

    private var cancellables = Set<AnyCancellable>()
    private var currentPayload: PlayPayload?

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

    func load(_ payload: PlayPayload, items: [PlayPayload] = [], index: Int = 0) {
        // Save current progress before switching if we were playing something
        if let current = currentPayload {
            ResumeStore.shared.save(
                url: current.url,
                position: Int64(position * 1000),
                duration: Int64(duration * 1000),
                rate: playbackRate,
                filterPreset: filterPreset,
                filterSettings: customFilterSettings
            )
        }

        // Stop current engine if exists
        engine?.stop()
        cancellables.removeAll()
        subtitleManager.clear()

        // Update playlist state
        if !items.isEmpty {
            self.playlistItems = items
            self.currentIndex = index
        } else if playlistItems.isEmpty {
            // Single play, create a dummy playlist of 1
            self.playlistItems = [payload]
            self.currentIndex = 0
        } else {
            // We are likely in an existing playlist, just update index
            self.currentIndex = index
        }

        self.currentPayload = payload

        // Pick engine
        let engine: any PlaybackEngine
        if payload.playerMode == "internal_vlc" {
            engine = VLCPlayerEngine()
        } else {
            engine = AVPlayerEngine()
        }

        self.engine = engine
        self.currentTitle = payload.title

        // Bind state and position
        engine.state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newState in
                self?.state = newState

                // Auto-advance if finished
                if newState == .ended {
                    self?.advanceToNext()
                }
            }
            .store(in: &cancellables)

        // Setup specialized end-of-media detection
        setupEndOfMediaDetection(engine)

        engine.position
            .receive(on: DispatchQueue.main)
            .sink { [weak self] newPos in
                self?.position = newPos
                self?.duration = self?.engine?.duration ?? 0
                self?.subtitleManager.update(currentTime: newPos)

                // Periodically save progress (every 5 seconds)
                if Int(newPos) % 5 == 0 && newPos > 0 {
                    if let current = self?.currentPayload {
                        ResumeStore.shared.save(
                            url: current.url,
                            position: Int64(newPos * 1000),
                            duration: Int64((self?.duration ?? 0) * 1000),
                            rate: self?.playbackRate,
                            filterPreset: self?.filterPreset,
                            filterSettings: self?.customFilterSettings
                        )
                    }
                }
            }
            .store(in: &cancellables)

        // Load payload and handle subtitles
        Task {
            do {
                try await engine.load(payload)

                // Restore resume position and settings
                if let info = ResumeStore.shared.getResumeInfo(for: payload.url) {
                    // Restore position
                    let positionSec = Double(info.positionMs) / 1000.0
                    let durationSec = Double(info.durationMs) / 1000.0
                    if positionSec > 5 && positionSec < (durationSec - 5) {
                        print("Resuming at \(info.positionMs)ms")
                        await engine.seek(to: positionSec)
                    }

                    // Restore settings
                    if let rate = info.playbackRate {
                        self.playbackRate = rate
                    }
                    if let preset = info.filterPreset {
                        self.filterPreset = preset
                    }
                    if let settings = info.filterSettings {
                        self.customFilterSettings = settings
                    }

                    applyFilterPreset()
                }

                // Handle external subtitles if any
                if let subUrls = payload.subtitles {
                    for subUrlString in subUrls {
                        if let url = URL(string: subUrlString) {
                            if let vlc = engine as? VLCPlayerEngine {
                                try? await vlc.attachExternalSubtitle(url: url)
                            }
                            if let cues = try? await subtitleManager.downloadSubtitle(
                                url: url, headers: payload.headers)
                            {
                                subtitleManager.loadCues(cues)
                            }
                        }
                    }
                }
            } catch {
                print("Failed to load engine: \(error)")
                self.state = .error
            }
        }
    }

    private func setupEndOfMediaDetection(_ engine: any PlaybackEngine) {
        if let avEngine = engine as? AVPlayerEngine {
            NotificationCenter.default.publisher(
                for: .AVPlayerItemDidPlayToEndTime, object: avEngine.player.currentItem
            )
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.advanceToNext()
            }
            .store(in: &cancellables)
        } else if engine is VLCPlayerEngine {
            // VLC handles .ended in VLCPlayerEngine through its delegate callbacks,
            // which updates the state subject observed in the sink above.
        }
    }

    func advanceToNext() {
        let nextIndex = currentIndex + 1
        if nextIndex < playlistItems.count {
            load(playlistItems[nextIndex], items: playlistItems, index: nextIndex)
        } else {
            stop()
        }
    }

    func queueAdd(_ item: PlayPayload) {
        playlistItems.append(item)
    }

    func jumpTo(index: Int) {
        guard index >= 0 && index < playlistItems.count else { return }
        load(playlistItems[index], items: playlistItems, index: index)
    }
    func play() { engine?.play() }
    func pause() { engine?.pause() }
    func stop() {
        engine?.stop()
        engine = nil
        state = .idle
        position = 0
        duration = 0
        currentTitle = nil
        cancellables.removeAll()
    }

    func seek(to: TimeInterval) {
        Task {
            await engine?.seek(to: to)
        }
    }

    func skipForward() {
        let newPos = min(position + 10, duration)
        seek(to: newPos)
    }

    func skipBackward() {
        let newPos = max(position - 10, 0)
        seek(to: newPos)
    }
}
