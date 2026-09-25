import AVFoundation
import Combine
import MediaPlayer

/// One player survives navigation and item changes. Route choice remains owned by iOS.
final class AirPlayController: ObservableObject {
    struct Entry: Identifiable {
        let id = UUID()
        let title: String
        let kind: String
        let media: RoutedStream
        var subtitles: [AirPlaySubtitleSource]
        var presentation: AirPlaySubtitlePresentation?
        var url: URL
    }
    struct Track: Identifiable {
        let id: Int
        let title: String
    }

    let player = AVPlayer()
    @Published private(set) var selected = false
    @Published private(set) var routeAvailable = false
    @Published private(set) var routeName = "AirPlay"
    @Published private var queueState = AirPlayQueueState<Entry>()
    var current: Entry? { queueState.current }
    var upcoming: [Entry] { queueState.upcoming }
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var playing = false
    @Published private(set) var buffering = false
    @Published private(set) var preparing = false
    @Published private(set) var tracks: [Track] = []
    @Published private(set) var selectedTrack: Int?
    @Published private(set) var audioTracks: [Track] = []
    @Published private(set) var selectedAudioTrack: Int?
    @Published var error: String?
    var onDestinationSelected: (() -> Void)?
    var onUpdate: (() -> Void)?
    var onStop: (() -> Void)?
    private var audioOwner: UUID?
    private var observers: [NSObjectProtocol] = []
    private var playerObservations: [NSKeyValueObservation] = []
    private var itemObservation: NSKeyValueObservation?
    private var timeObserver: Any?
    private var commandTargets: [(MPRemoteCommand, Any)] = []
    private var legibleGroup: AVMediaSelectionGroup?
    private var audioGroup: AVMediaSelectionGroup?
    private(set) var generation = UUID()
    private var selectingRoute = false
    private var routeSelectionID = UUID()
    private var wantsPlayback = false
    private var ownsNowPlaying = false
    private var subtitleTask: Task<Void, Never>?

    init() {
        player.allowsExternalPlayback = true
        player.audiovisualBackgroundPlaybackPolicy = .continuesIfPossible
        player.appliesMediaSelectionCriteriaAutomatically = false
        playerObservations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.update() }
        })
        playerObservations.append(player.observe(\.isExternalPlaybackActive, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshRoute() }
        })
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] _ in self?.update() }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] _ in self?.refreshRoute() })
        observers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            guard let self, let item = note.object as? AVPlayerItem, item === player.currentItem else { return }
            next()
        })
        observers.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: nil, queue: .main) { [weak self] note in
            guard let self, let item = note.object as? AVPlayerItem, item === player.currentItem else { return }
            fail(item.error)
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let self, selected,
                  let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            suspendForLocalPlayback()
        })
    }

    func beginRouteSelection() {
        selectingRoute = true
        routeSelectionID = UUID()
        do { try acquireAudio() } catch { self.error = "Couldn’t activate AirPlay audio. Try again." }
    }

    func endRouteSelection() {
        refreshRoute()
        let attempt = routeSelectionID
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            guard let self, routeSelectionID == attempt else { return }
            refreshRoute()
            selectingRoute = false
            if !selected { releaseAudio() }
        }
    }

    func refreshRoute() {
        let output = AVAudioSession.sharedInstance().currentRoute.outputs.first { $0.portType == .airPlay }
        let available = output != nil || player.isExternalPlaybackActive
        if available, selectingRoute, !selected {
            selected = true
            onDestinationSelected?()
        }
        guard selected else { return }
        routeAvailable = available
        if let output { routeName = output.portName }
        if !available {
            wantsPlayback = false
            player.pause()
        }
        update()
    }

    /// Reserve before URL/proxy preparation so a late request cannot undo Stop or a newer Play.
    func beginRequest(queue: Bool) -> UUID {
        if !queue {
            generation = UUID()
            subtitleTask?.cancel()
            subtitleTask = nil
            preparing = false
        }
        return generation
    }

    @MainActor
    func send(media: RoutedStream, title: String, kind: String, subtitles: [AirPlaySubtitleSource], queue: Bool, request: UUID) async throws {
        guard request == generation else { throw CancellationError() }
        guard selected, routeAvailable else { throw StreamRoutingError.message("Choose an AirPlay device before casting.") }
        guard kind != "image" else { throw StreamRoutingError.message("AirPlay casting currently supports video and audio.") }
        guard !preparing else { throw StreamRoutingError.message("Wait for the current item to finish preparing.") }
        let attempt = generation
        preparing = true
        defer { if generation == attempt { preparing = false } }
        var entry = Entry(title: Self.safeTitle(title), kind: kind, media: media, subtitles: subtitles, url: media.url)
        if !subtitles.isEmpty {
            let prepared = try await AirPlaySubtitleService.prepare(media: media, subtitles: subtitles)
            entry.presentation = prepared
            entry.url = prepared.url
        }
        try Task.checkCancellation()
        guard generation == attempt, selected, routeAvailable else { throw CancellationError() }
        guard entry.url.host != "127.0.0.1", entry.url.host != "localhost" else {
            throw StreamRoutingError.message("Connect to Wi-Fi so the AirPlay device can reach this stream.")
        }
        if queue, current != nil {
            queueState.enqueue(entry)
            update()
        } else {
            queueState.play(entry)
            install(entry)
        }
    }

    private func install(_ entry: Entry, resumeAt: Double = 0, autoplay: Bool = true) {
        subtitleTask?.cancel()
        queueState.replaceCurrent(entry)
        error = nil
        tracks = []
        selectedTrack = nil
        legibleGroup = nil
        audioGroup = nil
        audioTracks = []
        selectedAudioTrack = nil
        position = resumeAt
        duration = 0
        wantsPlayback = autoplay
        let item = PhonePlaybackFallback.directItem(url: entry.url, headers: entry.presentation == nil ? entry.media.headers : [:])
        itemObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async { [weak self] in
                guard let self, player.currentItem === item else { return }
                if item.status == .failed { fail(item.error) }
                if item.status == .readyToPlay { loadTracks(item) }
            }
        }
        player.replaceCurrentItem(with: item)
        do { try acquireAudio() } catch {
            wantsPlayback = false
            self.error = "Couldn’t activate AirPlay audio."
            update()
            return
        }
        installCommands()
        if resumeAt > 0 {
            player.seek(to: CMTime(seconds: resumeAt, preferredTimescale: 600)) { [weak self] finished in
                DispatchQueue.main.async { [weak self] in
                    guard let self, finished, player.currentItem === item, wantsPlayback, routeAvailable else { return }
                    player.play()
                }
            }
        } else if autoplay, routeAvailable { player.play() }
        update()
    }

    private func loadTracks(_ item: AVPlayerItem) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let group = try? await item.asset.loadMediaSelectionGroup(for: .legible)
            let audible = try? await item.asset.loadMediaSelectionGroup(for: .audible)
            guard item === player.currentItem else { return }
            legibleGroup = group
            audioGroup = audible
            audioTracks = audible?.options.enumerated().map { Track(id: $0.offset, title: $0.element.displayName) } ?? []
            if let audible, let option = item.currentMediaSelection.selectedMediaOption(in: audible) {
                selectedAudioTrack = audible.options.firstIndex(of: option)
            }
            tracks = group?.options.enumerated().map { Track(id: $0.offset, title: $0.element.displayName) } ?? []
            if let group, let selected = item.currentMediaSelection.selectedMediaOption(in: group) {
                selectedTrack = group.options.firstIndex(of: selected)
            }
            // Attached subtitles are per-item; choose the first supplied track initially.
            if let title = current?.presentation?.preferredSubtitleTitle,
               let track = tracks.first(where: { $0.title == title }) { selectSubtitle(track.id) }
            update()
        }
    }

    func selectSubtitle(_ id: Int?) {
        guard let group = legibleGroup, let item = player.currentItem else { return }
        if let id {
            guard group.options.indices.contains(id) else { return }
            item.select(group.options[id], in: group)
        } else { item.select(nil, in: group) }
        selectedTrack = id
        update()
    }

    func selectAudio(_ id: Int) {
        guard let group = audioGroup, group.options.indices.contains(id) else { return }
        player.currentItem?.select(group.options[id], in: group)
        selectedAudioTrack = id
        update()
    }

    func addSubtitle(_ source: AirPlaySubtitleSource) {
        guard let current, !preparing else { return }
        let attempt = generation
        let entryID = current.id
        preparing = true
        subtitleTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer { if generation == attempt { preparing = false } }
            do {
                var updated = current
                updated.subtitles.removeAll { $0.url == source.url }
                updated.subtitles.insert(source, at: 0)
                let presentation = try await AirPlaySubtitleService.prepare(media: current.media, subtitles: updated.subtitles)
                try Task.checkCancellation()
                guard generation == attempt, self.current?.id == entryID, selected else { return }
                updated.presentation = presentation
                updated.url = presentation.url
                let resume = position
                let autoplay = wantsPlayback
                // Detach this task before install cancels an older subtitle operation.
                subtitleTask = nil
                install(updated, resumeAt: resume, autoplay: autoplay)
            } catch is CancellationError {} catch {
                if generation == attempt, self.current?.id == entryID {
                    self.error = (error as? AirPlaySubtitleError)?.localizedDescription ?? (error as? StreamRoutingError)?.localizedDescription ?? "Couldn’t add this subtitle to AirPlay."
                }
            }
        }
    }

    func play() {
        guard selected, routeAvailable, current != nil else { return }
        do { try acquireAudio() } catch { self.error = "Couldn’t activate AirPlay audio."; return }
        wantsPlayback = true
        installCommands()
        player.play()
        update()
    }
    func pause() { wantsPlayback = false; player.pause(); update() }
    func suspendForLocalPlayback() {
        guard selected else { return }
        generation = UUID()
        subtitleTask?.cancel()
        subtitleTask = nil
        preparing = false
        pause()
        clearCommands()
    }
    func toggle() { wantsPlayback ? pause() : play() }
    func seek(_ seconds: Double) {
        guard routeAvailable, duration > 0, seconds.isFinite else { return }
        player.seek(to: CMTime(seconds: min(duration, max(0, seconds)), preferredTimescale: 600))
    }
    func next() {
        guard !upcoming.isEmpty else { stop(); return }
        generation = UUID()
        preparing = false
        if let entry = queueState.advance() { install(entry) }
    }
    func jump(to id: UUID) {
        guard let index = upcoming.firstIndex(where: { $0.id == id }) else { return }
        for _ in 0..<index { _ = queueState.advance() }
        next()
    }
    func remove(_ id: UUID) { queueState.remove(id); update() }
    func move(from offsets: IndexSet, to destination: Int) {
        queueState.move(from: offsets, to: destination)
        update()
    }
    func clearQueue() { queueState.clearUpcoming(); update() }
    func stop() {
        let hadMedia = current != nil
        generation = UUID()
        preparing = false
        subtitleTask?.cancel()
        subtitleTask = nil
        wantsPlayback = false
        player.pause()
        itemObservation = nil
        player.replaceCurrentItem(with: nil)
        queueState.stop()
        tracks = []
        selectedTrack = nil
        legibleGroup = nil
        audioGroup = nil
        audioTracks = []
        selectedAudioTrack = nil
        clearCommands()
        update()
        if hadMedia { onStop?() }
    }
    func disconnect() {
        selectingRoute = false
        routeSelectionID = UUID()
        stop()
        selected = false
        routeAvailable = false
        routeName = "AirPlay"
        error = nil
        releaseAudio()
        onUpdate?()
    }

    private func update() {
        let time = player.currentTime().seconds
        position = time.isFinite ? max(0, time) : 0
        let length = player.currentItem?.duration.seconds ?? 0
        duration = length.isFinite ? max(0, length) : 0
        playing = player.timeControlStatus == .playing
        buffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if let current, selected, !commandTargets.isEmpty {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = [
                MPMediaItemPropertyTitle: current.title,
                MPMediaItemPropertyArtist: routeName,
                MPMediaItemPropertyPlaybackDuration: duration,
                MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
                MPNowPlayingInfoPropertyPlaybackRate: playing ? player.rate : 0,
                MPNowPlayingInfoPropertyIsLiveStream: duration == 0
            ]
            ownsNowPlaying = true
            let center = MPRemoteCommandCenter.shared()
            center.nextTrackCommand.isEnabled = !upcoming.isEmpty && routeAvailable
            center.changePlaybackPositionCommand.isEnabled = duration > 0 && routeAvailable
        }
        onUpdate?()
    }

    private func fail(_ error: Error?) {
        wantsPlayback = false
        player.pause()
        self.error = PlaybackFailure.describe(error).message
        update()
    }
    private func acquireAudio() throws {
        if audioOwner == nil { audioOwner = try CastSystemPlayback.shared.beginLocalPlayback(externalAirPlay: true) }
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, policy: .longFormVideo)
        try AVAudioSession.sharedInstance().setActive(true)
    }
    private func releaseAudio() {
        if let audioOwner { CastSystemPlayback.shared.endLocalPlayback(audioOwner); self.audioOwner = nil }
    }
    private func installCommands() {
        guard commandTargets.isEmpty else { return }
        let center = MPRemoteCommandCenter.shared()
        add(center.playCommand) { [weak self] _ in self?.play() }
        add(center.pauseCommand) { [weak self] _ in self?.pause() }
        add(center.togglePlayPauseCommand) { [weak self] _ in self?.toggle() }
        add(center.stopCommand) { [weak self] _ in self?.stop() }
        add(center.nextTrackCommand) { [weak self] _ in self?.next() }
        add(center.changePlaybackPositionCommand) { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent { self?.seek(event.positionTime) }
        }
    }
    private func add(_ command: MPRemoteCommand, handle: @escaping (MPRemoteCommandEvent) -> Void) {
        command.isEnabled = true
        let target = command.addTarget { [weak self] event in
            let action: () -> MPRemoteCommandHandlerStatus = {
                guard let self, self.selected, self.routeAvailable else { return .commandFailed }
                handle(event)
                return .success
            }
            return Thread.isMainThread ? action() : DispatchQueue.main.sync(execute: action)
        }
        commandTargets.append((command, target))
    }
    private func clearCommands() {
        commandTargets.forEach { $0.0.removeTarget($0.1) }
        commandTargets.removeAll()
        if ownsNowPlaying { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }
        ownsNowPlaying = false
    }
    private static func safeTitle(_ title: String) -> String {
        guard !title.isEmpty, title.range(of: "https?://", options: [.regularExpression, .caseInsensitive]) == nil else { return "AirPlay media" }
        return String(title.prefix(512))
    }
    deinit {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        observers.forEach(NotificationCenter.default.removeObserver)
        subtitleTask?.cancel()
        clearCommands()
        releaseAudio()
    }
}
