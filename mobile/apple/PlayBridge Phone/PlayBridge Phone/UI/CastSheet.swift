import SwiftUI
import AVKit
import UniformTypeIdentifiers

/// Single source of truth for the player/browser picker options on iOS.
struct TvCapabilityOptions {
    static let playerLabels = [
        "exo": "ExoPlayer",
        "mpv": "MPV",
        "avplayer": "AVPlayer",
        "vlc": "VLC"
    ]
    static let browserLabels = [
        "webview": "System WebView",
        "gecko": "GeckoView"
    ]

    static func playerOptions(for device: PairedDevice?) -> [(id: String, label: String)] {
        var options = [("tv", "TV Default")]
        guard let device = device else { return options }
        var seen = Set<String>()
        for id in device.players {
            if let label = playerLabels[id], seen.insert(label).inserted {
                options.append((id, label))
            }
        }
        return options
    }

    static func browserOptions(for device: PairedDevice?) -> [(id: String, label: String)] {
        var options = [("tv", "TV Default")]
        guard let device = device else { return options }
        var seen = Set<String>()
        for id in device.browsers {
            if let label = browserLabels[id], seen.insert(label).inserted {
                options.append((id, label))
            }
        }
        return options
    }
}

/// Identifiable wrapper for AVPlayer to trigger SwiftUI full screen cover.
struct PlayerItem: Identifiable {
    let id = UUID()
    let session: PlaybackSession
#if DEBUG
    var report: () -> String = { "" }
    var diagnostics: PlaybackDiagnostics?
#endif
}

/// Unified Cast Sheet displaying all detected streams with previews, matching/exceeding Android's CastSheet UX.
struct CastSheet: View {
    @ObservedObject var detector: VideoDetector
    private var videos: [DetectedVideo] { detector.videos }
    let tab: BrowserTab
    @ObservedObject var store: BrowserStore

    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedVideo: DetectedVideo?
    @State private var selectedQuality: VideoQuality?
    @State private var attachedSubtitles = Set<String>()
    @State private var castAction = "play"
    @State private var browseUrl = ""
    @State private var selectedTab: CastMediaTab = .video
    @State private var tabOrder = CastMediaTab.allCases
    @State private var extraSubtitles: [DetectedVideo] = []
    @State private var showAddSubtitles = false
    @State private var browserMode = "tv"
    @AppStorage("stream_route_default") private var routePreference = StreamRoute.direct.rawValue
    @State private var proxyConfiguration = StreamProxySettingsStore.load()
    @State private var showProxySettings = false
    @State private var selectProxyAfterSave = false
    private var streamRoute: StreamRoute { StreamRoute(rawValue: routePreference) ?? .direct }
    @State private var fullscreenPlayerItem: PlayerItem?
    @State private var showDestination = false
    @State private var playbackPreparation: Task<Void, Never>?
    @State private var playbackPreparationID: UUID?
    @State private var playbackError: String?

    private var streams: [DetectedVideo] { videos.filter(\.isVideo) }
    private var audio: [DetectedVideo] { videos.filter(\.isAudio).sorted { $0.timestamp > $1.timestamp } }
    private var images: [DetectedVideo] { videos.filter(\.isImage).sorted { $0.timestamp > $1.timestamp } }
    private var subtitles: [DetectedVideo] { SubtitleOrdering.newestFirst(videos + extraSubtitles) }
    private var media: [DetectedVideo] { streams + audio + images }
    private var preferredMedia: DetectedVideo? { sortedStreams(streams).first ?? audio.first ?? images.first }

    private var sendEnabled: Bool {
        if playbackPreparationID != nil { return false }
        if castAction == "browse" {
            return vm.isConnected && !browseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        } else {
            guard let selectedVideo else { return false }
            let kind = selectedVideo.isAudio ? "audio" : selectedVideo.isImage ? "image" : "video"
            return vm.isConnected && vm.supportsNativeMediaKind(kind)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Keep casting actions and list tabs visible while results scroll.
                VStack(alignment: .leading, spacing: 16) {
                    header
                    if castAction == "browse" { capabilitySelectors }
                    if castAction != "browse" {
                        tabsSection
                        if vm.isAirPlay {
                            Text("AirPlay uses this phone for protected streams and added subtitles.")
                                .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                        }
                    }
                }
                .padding(.top, 24)
                .padding(.bottom, castAction == "browse" ? 16 : 0)
                .fixedSize(horizontal: false, vertical: true)

                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if castAction == "browse" {
                            browseSection
                        } else if selectedTab == .video {
                            videosListSection
                        } else if selectedTab == .audio {
                            simpleMediaSection(audio, kind: .audio)
                        } else if selectedTab == .image {
                            simpleMediaSection(images, kind: .image)
                        } else {
                            subtitlesListSection
                        }
                    }
                    .padding(.vertical, 8)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .background(Theme.surface.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .accessibilityAction(.escape) { dismiss() }
            .sheet(isPresented: $showDestination) { DeviceConnectionSheet() }
            .sheet(isPresented: $showAddSubtitles) {
                SubtitleSourcePickerView(detector: nil, detected: [], title: "Add subtitles",
                    onDetected: { _ in false },
                    onLocal: { url in
                        guard let served = await vm.serveLocalSubtitle(url) else {
                            playbackError = "Couldn’t share that subtitle file. Check Wi-Fi and Local Network access."
                            return false
                        }
                        addSubtitleSelection(url: served, title: url.lastPathComponent,
                                             type: LocalFileServer.mimeType(for: url), local: true)
                        return true
                    },
                    onURL: { url in
                        addSubtitleSelection(url: url, type: nil)
                        return true
                    })
                .presentationDetents([.medium, .large])
            }
            .alert("Couldn’t start playback", isPresented: Binding(
                get: { playbackError != nil },
                set: { if !$0 { playbackError = nil } }
            )) {
                Button("OK", role: .cancel) { playbackError = nil }
            } message: {
                Text(playbackError ?? "")
            }
            .overlay(alignment: .bottom) {
                if playbackPreparationID != nil {
                    ProgressView("Preparing playback…")
                        .padding()
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                        .padding()
                }
            }
            .onDisappear { playbackPreparation?.cancel() }
            .sheet(isPresented: $showProxySettings) {
                StreamProxySettingsView(configuration: proxyConfiguration) { configuration in
                    proxyConfiguration = configuration
                    if selectProxyAfterSave { routePreference = StreamRoute.proxy.rawValue }
                }
            }
            .fullScreenCover(item: $fullscreenPlayerItem) { item in
                FullScreenVideoPlayerView(session: item.session, diagnosticsReport: {
#if DEBUG
                    item.report()
#else
                    ""
#endif
                }) {
                    fullscreenPlayerItem = nil
                }
            }
            .onChange(of: vm.destinationID) { _ in
                tabOrder = CastMediaTab.prioritized(videos: videos, includeSubtitles: !vm.isExternalReceiver)
                selectedTab = tabOrder.first ?? .video
                if vm.isExternalReceiver { castAction = "play"; attachedSubtitles.removeAll() }
                if !vm.supportsBrowser && castAction == "browse" { castAction = "play" }
            }
            .onAppear {
                tabOrder = CastMediaTab.prioritized(videos: videos, includeSubtitles: !vm.isExternalReceiver)
                selectedTab = tabOrder.first ?? .video
                if vm.isExternalReceiver { castAction = "play"; attachedSubtitles.removeAll() }
                if streamRoute == .proxy, (try? proxyConfiguration.validatedURL()) == nil {
                    routePreference = StreamRoute.direct.rawValue
                }
                if selectedVideo == nil, let firstStream = preferredMedia {
                    selectVideo(firstStream)
                }
                if browseUrl.isEmpty {
                    browseUrl = tab.urlString
                }
                if media.isEmpty && vm.supportsBrowser {
                    castAction = "browse"
                }
            }
            .onChange(of: videos) { current in
                if let selectedVideo, !current.contains(where: { $0.id == selectedVideo.id }) {
                    self.selectedVideo = nil
                    selectedQuality = nil
                }
                if selectedVideo == nil, let first = preferredMedia {
                    selectVideo(first)
                }
            }
            .onChange(of: castAction) { newAction in
                if newAction == "browse", !TvCapabilityOptions.browserOptions(for: vm.pairedDevice).contains(where: { $0.id == browserMode }) {
                    browserMode = "tv"
                }
            }
        }
    }

    // MARK: - Subviews

    private var actionLabel: String {
        castAction == "browse" ? "Browse" : castAction == "queue" ? "Queue" : "Play"
    }

    private var actionIcon: String {
        castAction == "browse" ? "globe" : castAction == "queue" ? "text.badge.plus" : "play.fill"
    }

    private var destinationDescription: String {
        if vm.isConnected, let name = vm.receiverName {
            return "Connected to \(name)"
        }
        return "No receiver connected"
    }

    private var header: some View {
        HStack(spacing: 12) {
            Menu {
                Picker("Action", selection: $castAction) {
                    Label("Play", systemImage: "play.fill").tag("play")
                    if vm.isConnected && vm.supportsQueue && vm.coordinator.activeContext == "player" {
                        Label("Queue", systemImage: "text.badge.plus").tag("queue")
                    }
                    if vm.supportsBrowser {
                        Label("Browse", systemImage: "globe").tag("browse")
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: actionIcon)
                    Text(actionLabel)
                    Image(systemName: "chevron.down").font(Theme.font(.caption2).bold())
                }
                .font(Theme.font(.subheadline).weight(.medium))
                .foregroundColor(Theme.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 8))
                .frame(minHeight: 44)
            }
            .accessibilityLabel("Cast action")
            .accessibilityValue(actionLabel)

            if castAction != "browse" { routeMenu }

            Spacer(minLength: 0)

            Button { showDestination = true } label: {
                Image(systemName: "tv")
                    .font(Theme.font(size: 20))
                    .foregroundColor(vm.isConnected ? Theme.primary : Theme.onSurfaceVariant)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(destinationDescription)
            .accessibilityHint("Show cast destination")

            Button { sendAction() } label: {
                Image(systemName: "paperplane.fill")
                    .font(Theme.font(size: 20))
                    .foregroundColor(sendEnabled ? Theme.primary : Theme.onSurfaceVariant.opacity(0.38))
                    .frame(width: 44, height: 44)
            }
            .disabled(!sendEnabled)
            .accessibilityLabel("Send")
            .accessibilityHint("\(actionLabel) on the connected receiver")
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
    }

    private var routeMenu: some View {
        Menu {
            ForEach(StreamRoute.allCases) { route in
                Button {
                    if route == .proxy, (try? proxyConfiguration.validatedURL()) == nil {
                        selectProxyAfterSave = true
                        showProxySettings = true
                    } else { routePreference = route.rawValue }
                } label: {
                    if streamRoute == route {
                        Label(route.label, systemImage: "checkmark")
                    } else {
                        Text(route.label)
                    }
                }
            }
            Divider()
            Button {
                selectProxyAfterSave = false
                showProxySettings = true
            } label: {
                Label("Configure proxy", systemImage: "gearshape")
            }
        } label: {
            HStack(spacing: 6) {
                Text(streamRoute.label)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Image(systemName: "chevron.down").font(Theme.font(.caption2).bold())
            }
            .font(Theme.font(.subheadline).weight(.medium))
            .foregroundColor(Theme.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Theme.secondaryContainer, in: RoundedRectangle(cornerRadius: 8))
            .frame(minHeight: 44)
        }
        .accessibilityLabel("Stream route")
        .accessibilityValue(streamRoute.label)
    }

    private var capabilitySelectors: some View {
        HStack(spacing: 8) {
            if castAction == "browse" {
                Menu {
                    ForEach(TvCapabilityOptions.browserOptions(for: vm.pairedDevice), id: \.id) { option in
                        Button(option.label) { browserMode = option.id }
                    }
                } label: {
                    Text("Browser: " + (TvCapabilityOptions.browserOptions(for: vm.pairedDevice).first { $0.id == browserMode }?.label ?? "TV Default"))
                        .font(Theme.font(.caption)).padding(10)
                }
                Button { tab.toggleDesktopMode() } label: {
                    Label(tab.isDesktopMode ? "Desktop" : "Mobile", systemImage: tab.isDesktopMode ? "desktopcomputer" : "iphone")
                        .font(Theme.font(.caption))
                        .padding(10)
                }
            }
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
    }

    private var browseSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BROWSE PAGE ON TV")
                .font(Theme.font(.caption).bold())
                .foregroundColor(Theme.onSurfaceVariant)
            
            TextField("Website URL", text: $browseUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(12)
                .background(Theme.surfaceContainer)
                .cornerRadius(10)
                .foregroundColor(Theme.onSurface)
            
            HStack(spacing: 12) {
                Button {
                    store.newTab(loading: browseUrl)
                    dismiss()
                } label: {
                    Label("New Tab", systemImage: "plus")
                        .font(Theme.font(.caption).bold())
                        .foregroundColor(Theme.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Theme.surfaceContainerLow)
                        .cornerRadius(8)
                }
                
                Button {
                    if let url = URL(string: browseUrl) {
                        UIApplication.shared.open(url)
                        dismiss()
                    }
                } label: {
                    Label("Safari", systemImage: "safari")
                        .font(Theme.font(.caption).bold())
                        .foregroundColor(Theme.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Theme.surfaceContainerLow)
                        .cornerRadius(8)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var tabsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Detected media")
                .font(Theme.font(.caption))
                .foregroundColor(Theme.onSurfaceVariant)
                .padding(.horizontal, 16)

            VStack(spacing: 0) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(tabOrder.filter { !vm.isExternalReceiver || $0 != .subtitle }, id: \.self) { tab in
                            mediaTab(tab, count: tabCount(tab))
                        }
                    }
                    .padding(.horizontal, 8)
                }
                Divider().background(Theme.outlineVariant)
            }
        }
    }

    private func tabCount(_ tab: CastMediaTab) -> Int {
        switch tab {
        case .video: return streams.count
        case .audio: return audio.count
        case .subtitle: return subtitles.count
        case .image: return images.count
        }
    }

    private func mediaTab(_ tab: CastMediaTab, count: Int) -> some View {
        let selected = selectedTab == tab
        return Button {
            selectedTab = tab
            let candidates: [DetectedVideo] = switch tab {
            case .video: sortedStreams(streams)
            case .audio: audio
            case .image: images
            case .subtitle: []
            }
            if let first = candidates.first, !candidates.contains(where: { $0.id == selectedVideo?.id }) {
                selectVideo(first)
            }
        } label: {
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: tab.icon).font(Theme.font(size: 14))
                    Text(tab.title).font(Theme.font(.subheadline).weight(.semibold))
                    Text("\(count)")
                        .font(Theme.font(.caption2).bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Theme.primary.opacity(selected ? 0.22 : 0.12), in: Capsule())
                }
                .foregroundColor(selected ? Theme.primary : Theme.onSurfaceVariant)
                .frame(minHeight: 44)
                .padding(.horizontal, 12)
                Rectangle()
                    .fill(selected ? Theme.primary : Color.clear)
                    .frame(height: 2)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(tab.title), \(count)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var videosListSection: some View {
        VStack(spacing: 12) {
            if streams.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "film")
                        .font(Theme.font(size: 48))
                        .foregroundColor(Theme.onSurfaceVariant.opacity(0.5))
                    Text("No videos detected yet")
                        .font(Theme.font(.headline))
                        .foregroundColor(Theme.onSurface)
                    Text("Browse a page with video content")
                        .font(Theme.font(.caption))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 60)
            } else {
                ForEach(sortedStreams(streams)) { video in
                    VideoCard(
                        video: video,
                        isSelected: selectedVideo?.id == video.id,
                        selectedQuality: selectedQuality,
                        qualities: detector.qualities[video.id] ?? [],
                        loadingQualities: (video.kind == .hls || video.kind == .dash) && detector.qualities[video.id] == nil,
                        thumbnail: detector.thumbnails[video.id],
                        thumbnailLoading: detector.thumbnailStates[video.id] == .loading,
                        onSelect: {
                            selectVideo(video)
                        },
                        onQualitySelect: { q in
                            if selectedVideo?.id != video.id { selectVideo(video) }
                            selectedQuality = q
                        },
                        onPlayOnPhone: {
                            playOnPhone(video)
                        },
                        onCopyUrl: {
                            UIPasteboard.general.string = (selectedVideo?.id == video.id ? selectedQuality?.url : nil) ?? video.url
                        },
                        onCopyDiagnostics: {
#if DEBUG
                            UIPasteboard.general.setItems([["public.utf8-plain-text": detector.debugReport(for: video)]],
                                options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(900)])
#endif
                        }
                    )
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private func simpleMediaSection(_ items: [DetectedVideo], kind: CastMediaTab) -> some View {
        LazyVStack(spacing: 12) {
            if items.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: kind.icon).font(Theme.font(size: 40))
                    Text("No \(kind.title.lowercased()) detected yet").font(Theme.font(.subheadline))
                }
                    .foregroundColor(Theme.onSurfaceVariant)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 32)
            } else {
                ForEach(items) { item in
                    Button { selectVideo(item) } label: {
                        HStack(spacing: 12) {
                            if kind == .image, let url = URL(string: item.url) {
                                AsyncImage(url: url) { image in image.resizable().scaledToFill() }
                                    placeholder: { Image(systemName: kind.icon).resizable().scaledToFit().padding(15) }
                                    .frame(width: 64, height: 64).clipped().clipShape(RoundedRectangle(cornerRadius: 8))
                            } else {
                                Image(systemName: kind.icon).font(Theme.font(size: 26))
                                    .frame(width: 64, height: 64)
                                    .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 8))
                            }
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.displayTitle).font(Theme.font(.subheadline).weight(.semibold)).lineLimit(2)
                                Text(item.host).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                                Text(item.detectedBy).font(Theme.font(.caption2)).foregroundColor(Theme.onSurfaceVariant)
                            }
                            Spacer(minLength: 0)
                            if selectedVideo?.id == item.id { Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.primary) }
                        }
                        .foregroundColor(Theme.onSurface)
                        .padding(12)
                        .background(selectedVideo?.id == item.id ? Theme.secondaryContainer : Theme.surfaceContainer,
                                    in: RoundedRectangle(cornerRadius: 12))
                    }
                    .buttonStyle(.plain)
                    .contextMenu { Button("Copy URL") { UIPasteboard.general.string = item.url } }
                }
                if !vm.supportsNativeMediaKind(kind.rawValue) {
                    Text("This receiver has not reported \(kind.title.lowercased()) support.")
                        .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var subtitlesListSection: some View {
        LazyVStack(alignment: .leading, spacing: 12) {
            if subtitles.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "captions.bubble")
                        .font(Theme.font(size: 40))
                        .foregroundColor(Theme.onSurfaceVariant.opacity(0.5))
                    Text("No subtitles detected")
                        .font(Theme.font(.subheadline))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 60)
            } else {
                ForEach(subtitles) { sub in
                    Button {
                        if attachedSubtitles.contains(sub.url) {
                            attachedSubtitles.remove(sub.url)
                        } else {
                            attachedSubtitles.insert(sub.url)
                        }
                    } label: {
                        HStack {
                            Image(systemName: attachedSubtitles.contains(sub.url) ? "checkmark.square.fill" : "square")
                                .foregroundColor(attachedSubtitles.contains(sub.url) ? Theme.primary : Theme.onSurfaceVariant)
                            
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(sub.displayTitle)
                                        .font(Theme.font(.subheadline))
                                        .foregroundColor(Theme.onSurface)
                                        .lineLimit(1)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    if case .ready(_, let language) = detector.subtitlePreviews[sub.id] {
                                        Text(language.map { "Likely \($0)" } ?? "Language unknown")
                                            .font(Theme.font(.caption))
                                            .foregroundColor(language == nil ? Theme.onSurfaceVariant : Theme.primary)
                                            .lineLimit(1)
                                            .frame(maxWidth: 132, alignment: .trailing)
                                    }
                                }
                                Text("Detected \(Date(timeIntervalSince1970: Double(sub.timestamp) / 1_000).formatted(date: .omitted, time: .standard))")
                                    .font(Theme.font(.caption))
                                    .foregroundColor(Theme.onSurfaceVariant)
                                Text(sub.host)
                                    .font(Theme.font(.caption))
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .lineLimit(1)
                                switch detector.subtitlePreviews[sub.id] {
                                case .loading:
                                    Text("Loading preview…")
                                        .font(Theme.font(.caption))
                                        .foregroundColor(Theme.onSurfaceVariant)
                                        .italic()
                                case .ready(let preview, _):
                                    Text(preview)
                                        .font(Theme.font(.caption))
                                        .foregroundColor(Theme.primary)
                                        .lineLimit(2)
                                        .italic()
                                case .unavailable, .none:
                                    EmptyView()
                                }
                            }
                            Spacer()
                        }
                        .padding(12)
                        .background(Theme.surfaceContainer)
                        .cornerRadius(12)
                    }
                    .buttonStyle(.plain)
                    .task(id: sub.headers) {
                        await detector.loadSubtitlePreview(for: sub)
                    }
                }
            }
            Button { showAddSubtitles = true } label: {
                Label("Add subtitles", systemImage: "plus.circle.fill")
                    .frame(maxWidth: .infinity)
                    .padding(12)
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
    }

    // MARK: - Helper Methods

    private func selectVideo(_ video: DetectedVideo) {
        selectedVideo = video
        selectedQuality = nil
    }

    private func addSubtitleSelection(url: String, title: String? = nil, type: String?, local: Bool = false) {
        guard !subtitles.contains(where: { $0.url == url }) else {
            attachedSubtitles.insert(url)
            return
        }
        let item = DetectedVideo(url: url, contentType: type, detectedBy: local ? "local_subtitle" : "manual_subtitle",
                                 originUrl: nil, headers: [:], kind: .subtitle,
                                 timestamp: Int64(Date().timeIntervalSince1970 * 1_000), title: title)
        extraSubtitles.append(item)
        attachedSubtitles.insert(item.url)
    }

    private func playOnPhone(_ video: DetectedVideo) {
        playbackPreparation?.cancel()
        let attempt = UUID()
        playbackPreparationID = attempt
        let url = (selectedVideo?.id == video.id ? selectedQuality?.url : nil) ?? video.url
        let route = streamRoute
        let configuration = proxyConfiguration
        playbackPreparation = Task { @MainActor in
            defer { if playbackPreparationID == attempt { playbackPreparationID = nil } }
            do {
                let prepare: () async throws -> RoutedStream = {
                    try await StreamRouteService().prepare(url: url, headers: VideoDetector.mediaHeaders(for: video),
                        contentType: video.kind == .hls ? "application/vnd.apple.mpegurl" : video.contentType,
                        route: route, configuration: configuration)
                }
                let media = try await prepare()
                try Task.checkCancellation()
                guard playbackPreparationID == attempt else { return }
                let session = PlaybackSession(media: media, route: route, prepare: prepare)
                var presentation = PlayerItem(session: session)
#if DEBUG
                presentation.report = { [weak detector] in detector?.debugReport(for: video) ?? "" }
                presentation.diagnostics = PlaybackDiagnostics(player: session.player) { [weak detector] report in
                    detector?.recordPlaybackDiagnostics("Route: \(route.label)\n" + report, for: video.id)
                }
#endif
                fullscreenPlayerItem = presentation
            } catch {
                guard !Task.isCancelled, playbackPreparationID == attempt else { return }
                playbackError = error.localizedDescription
            }
        }
    }

    private func sendAction() {
        if castAction == "browse" {
            vm.browseTo(url: browseUrl, browserMode: browserMode, desktopMode: tab.isDesktopMode)
            dismiss()
            return
        }
        guard let video = selectedVideo else { return }
        playbackPreparation?.cancel()
        let attempt = UUID()
        playbackPreparationID = attempt
        let url = selectedQuality?.url ?? video.url
        let route = streamRoute
        let configuration = proxyConfiguration
        let subtitleURLs = subtitles.map(\.url).filter { attachedSubtitles.contains($0) && video.isVideo }
        let queue = castAction == "queue"
        let airPlayRequest = vm.isAirPlay ? vm.airPlay.beginRequest(queue: queue) : nil
        let destination = vm.destinationID
        playbackPreparation = Task { @MainActor in
            defer { if playbackPreparationID == attempt { playbackPreparationID = nil } }
            do {
                let router = StreamRouteService()
                let mediaType = video.contentType ?? ((video.isAudio || video.isImage)
                    ? URL(string: video.url).map(LocalFileServer.mimeType(for:)) : nil)
                let media = try await router.prepare(url: url, headers: VideoDetector.mediaHeaders(for: video),
                    contentType: video.kind == .hls ? "application/vnd.apple.mpegurl" : mediaType,
                    route: route, configuration: configuration)
                var subtitles: [RoutedStream] = []
                for url in subtitleURLs {
                    let detected = self.subtitles.first { $0.url == url }
                    subtitles.append(try await router.prepare(url: url,
                        headers: detected.map(VideoDetector.subtitleHeaders) ?? [:], contentType: detected?.contentType,
                        route: detected?.detectedBy == "local_subtitle" ? .direct : route,
                        configuration: configuration))
                }
                try Task.checkCancellation()
                guard playbackPreparationID == attempt, vm.isConnected,
                      destination == vm.destinationID else { return }
                if route == .phone, media.url.host == "127.0.0.1" {
                    throw StreamRoutingError.message("Connect to Wi-Fi to send via phone.")
                }
                let subtitleTitles = subtitleURLs.map { url in self.subtitles.first { $0.url == url }?.displayTitle ?? "Subtitle" }
                try await vm.sendRoutedStream(media, video: video.withCastTitle(pageTitle: tab.title), subtitles: subtitles, queue: queue, subtitleTitles: subtitleTitles, airPlayRequest: airPlayRequest)
                if !queue { nav.navigate(to: .remote) }
                dismiss()
            } catch {
                guard !Task.isCancelled, playbackPreparationID == attempt else { return }
                playbackError = error.localizedDescription
            }
        }
    }

    private func sortedStreams(_ list: [DetectedVideo]) -> [DetectedVideo] {
        CastStreamRanking.sorted(list, qualities: detector.qualities, thumbnails: detector.thumbnailStates, manifests: detector.manifests)
    }
}

/// The same source choices are used before casting and for late subtitle attachment.
struct SubtitleSourcePickerView: View {
    let detector: VideoDetector?
    let detected: [DetectedVideo]
    let title: String
    let onDetected: (DetectedVideo) -> Bool
    let onLocal: (URL) async -> Bool
    let onURL: (String) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var source = "Local"
    @State private var urlText = ""
    @State private var importing = false
    @State private var working = false
    @State private var error: String?

    private var sources: [String] { (detected.isEmpty ? [] : ["Detected"]) + ["Local", "URL"] }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Picker("Subtitle source", selection: $source) {
                    ForEach(sources, id: \.self) { Text($0).tag($0) }
                }
                .pickerStyle(.segmented)
                if let error {
                    Text(error).font(Theme.font(.caption)).foregroundColor(.red)
                }
                switch source {
                case "Detected":
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(SubtitleOrdering.newestFirst(detected)) { subtitle in
                                Button {
                                    if onDetected(subtitle) { dismiss() }
                                    else { error = "Couldn’t add this subtitle. Check the receiver connection." }
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(subtitle.displayTitle).font(Theme.font(.subheadline).weight(.semibold))
                                        Text("Detected \(Date(timeIntervalSince1970: Double(subtitle.timestamp) / 1_000).formatted(date: .omitted, time: .standard))")
                                            .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                                        if let detector {
                                            DetectedSubtitlePreview(detector: detector, subtitle: subtitle)
                                        }
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(12)
                                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 10))
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                case "URL":
                    TextField("Subtitle URL (.srt / .vtt)", text: $urlText)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .textFieldStyle(.roundedBorder)
                    Button("Add URL") {
                        let entered = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard let parsed = URL(string: entered), ["http", "https"].contains(parsed.scheme?.lowercased() ?? ""),
                              parsed.host != nil else { error = "Enter a valid HTTP or HTTPS URL."; return }
                        if onURL(entered) { dismiss() }
                        else { error = "Couldn’t add this subtitle. Check the receiver connection." }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                default:
                    Text("Choose a .srt or .vtt file from this phone. The receiver will load it through the phone.")
                        .font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
                    Button { importing = true } label: {
                        if working { ProgressView() } else { Label("Choose local file", systemImage: "folder") }
                    }
                    .buttonStyle(.borderedProminent).disabled(working)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .onAppear { source = sources.first ?? "Local" }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
                guard case .success(let file) = result else { return }
                guard ["srt", "vtt"].contains(file.pathExtension.lowercased()) else {
                    error = "Choose an SRT or WebVTT subtitle file."
                    return
                }
                working = true
                Task { @MainActor in
                    let scoped = file.startAccessingSecurityScopedResource()
                    let sent = await onLocal(file)
                    if scoped { file.stopAccessingSecurityScopedResource() }
                    working = false
                    if sent { dismiss() }
                    else { error = "Couldn’t share this subtitle. Check Wi-Fi and the receiver connection." }
                }
            }
        }
    }
}

private struct DetectedSubtitlePreview: View {
    @ObservedObject var detector: VideoDetector
    let subtitle: DetectedVideo

    var body: some View {
        Group {
            if case .ready(let preview, let language) = detector.subtitlePreviews[subtitle.id] {
                if let language { Text("Likely \(language)").foregroundColor(Theme.primary) }
                Text(preview).lineLimit(2).foregroundColor(Theme.onSurfaceVariant)
            }
        }
        .font(Theme.font(.caption))
        .task(id: subtitle.id) { await detector.loadSubtitlePreview(for: subtitle) }
    }
}

// MARK: - VideoCard Component

struct VideoCard: View {
    let video: DetectedVideo
    let isSelected: Bool
    let selectedQuality: VideoQuality?
    let qualities: [VideoQuality]
    let loadingQualities: Bool
    let thumbnail: UIImage?
    let thumbnailLoading: Bool
    let onSelect: () -> Void
    let onQualitySelect: (VideoQuality?) -> Void
    let onPlayOnPhone: () -> Void
    let onCopyUrl: () -> Void
    let onCopyDiagnostics: () -> Void
#if DEBUG
    @State private var copiedDiagnostics = false
#endif

    private var formatColor: Color {
        switch video.kind {
        case .hls: return Theme.primary
        case .dash: return Theme.onSecondaryContainer
        default: return Theme.primary
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // A native button covers the card's media area without nesting the
            // independent quality/action buttons inside another button.
            Button {
                if !isSelected { onSelect() }
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Label(video.kind.badge, systemImage: "film")
                            .font(Theme.font(.caption2).bold())
                            .foregroundColor(formatColor)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(formatColor.opacity(0.2), in: RoundedRectangle(cornerRadius: 6))
                        Spacer()
                        if isSelected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(Theme.primary)
                        }
                    }

                    Color.black.opacity(0.25)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .overlay {
                            GeometryReader { geometry in
                                if let thumbnail {
                                    Image(uiImage: thumbnail)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: geometry.size.width, height: geometry.size.height)
                                        .clipped()
                                } else {
                                    VStack(spacing: 6) {
                                        if thumbnailLoading {
                                            ProgressView().tint(Theme.onSurfaceVariant)
                                        } else {
                                            Image(systemName: "play.fill").font(Theme.font(.title2))
                                            Text("Preview unavailable").font(Theme.font(.caption))
                                        }
                                    }
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .frame(width: geometry.size.width, height: geometry.size.height)
                                }
                            }
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 8))

                    Text(video.displayTitle)
                        .font(Theme.font(.headline).weight(.medium))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(2)
                    Text(video.url)
                        .font(Theme.font(.caption))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    HStack(spacing: 12) {
                        if let contentType = video.contentType {
                            Label(contentType, systemImage: "info.circle")
                        }
                        Label(video.detectedBy, systemImage: "magnifyingglass")
                    }
                    .font(Theme.font(.caption2))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(video.displayTitle), \(video.kind.badge), \(video.host)")
            .accessibilityHint("Select stream")
            .accessibilityAddTraits(isSelected ? .isSelected : [])

            if video.kind == .hls || video.kind == .dash {
                qualityOptions
            }

            HStack(spacing: 16) {
                Button(action: onPlayOnPhone) {
                    Label("Play on Phone", systemImage: "play.circle")
                }
                Button(action: onCopyUrl) {
                    Label("Copy URL", systemImage: "doc.on.doc")
                }
            }
            .font(Theme.font(.caption).weight(.semibold))
            .foregroundColor(Theme.primary)
            .buttonStyle(.plain)
#if DEBUG
            Button {
                onCopyDiagnostics()
                copiedDiagnostics = true
            } label: {
                Label(copiedDiagnostics ? "Diagnostics copied" : "Copy diagnostics", systemImage: "doc.on.clipboard")
                    .font(Theme.font(.caption).weight(.semibold))
                    .foregroundColor(Theme.primary)
            }
            .buttonStyle(.plain)
            .accessibilityHint("Copy this stream's preview and manifest debugging details")
#endif
        }
        .padding(12)
        .background(isSelected ? Theme.secondaryContainer : Theme.surfaceContainer)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay {
            RoundedRectangle(cornerRadius: 12)
                .stroke(isSelected ? Theme.primary : Color.clear, lineWidth: 2)
                .allowsHitTesting(false)
        }
        .background {
            // Padding and gaps select the card too; controls above consume their own taps.
            Color.clear.contentShape(Rectangle()).onTapGesture {
                if !isSelected { onSelect() }
            }
        }

    }

    private var qualityOptions: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(video.kind == .dash ? "Qualities · receiver auto-selects" : "Qualities")
                .font(Theme.font(.caption))
                .foregroundColor(Theme.onSurfaceVariant)
            if loadingQualities {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Checking manifest…").font(Theme.font(.caption))
                }
                .foregroundColor(Theme.onSurfaceVariant)
            } else if qualities.isEmpty {
                Text("No quality variants found")
                    .font(Theme.font(.caption))
                    .foregroundColor(Theme.onSurfaceVariant)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        qualityChip("Auto", quality: nil)
                        ForEach(qualities) { quality in
                            if video.kind == .dash {
                                // DASHParser returns the same MPD for each tier; these
                                // labels describe available tiers, not forced resolution.
                                Text(quality.label)
                                    .font(Theme.font(.caption))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 8)
                                    .background(Theme.surfaceContainerHigh, in: Capsule())
                            } else {
                                qualityChip(quality.label, quality: quality)
                            }
                        }
                    }
                }
            }
        }
    }

    private func qualityChip(_ title: String, quality: VideoQuality?) -> some View {
        let selected = isSelected && selectedQuality?.id == quality?.id
        return Button { onQualitySelect(quality) } label: {
            Text(title)
                .font(Theme.font(.caption).weight(.medium))
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(selected ? Theme.primary : Theme.surfaceContainerHigh, in: Capsule())
                .foregroundColor(selected ? Theme.onPrimary : Theme.onSurface)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

// MARK: - FullScreenVideoPlayerView Component

struct FullScreenVideoPlayerView: View {
    @ObservedObject var session: PlaybackSession
    let diagnosticsReport: () -> String
    let onDismiss: () -> Void
    private var player: AVPlayer { session.player }
    private var canAirPlay: Bool { session.canAirPlay }
#if DEBUG
    @State private var copiedDiagnostics = false
#endif
    @State private var audioSessionError: String?
    @State private var localAudioOwner: UUID?

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .foregroundColor(.white)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Close player")
                Spacer()
            }
            .padding(.horizontal, 8)

            if !canAirPlay {
                Text("Connect to Wi-Fi to use AirPlay.")
                    .font(Theme.font(.footnote))
                    .foregroundColor(.white)
                    .padding(.bottom, 8)
            }
            ZStack {
                VideoPlayer(player: player)
                if let failure = session.failure {
                    Color.black.opacity(0.94)
                    ScrollView {
                        VStack(spacing: 16) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(Theme.font(.largeTitle))
                            Text(player.isExternalPlaybackActive ? "AirPlay playback failed" : "Playback failed")
                                .font(Theme.font(.title2).bold())
                            Text(failure.message)
                                .multilineTextAlignment(.center)
                            Text("Route: " + session.route.label)
                                .font(Theme.font(.footnote))
                                .foregroundStyle(.secondary)
                            if session.retrying {
                                ProgressView("Retrying…").tint(.white)
                            } else {
                                Button("Try again") { session.retry() }
                                    .buttonStyle(.borderedProminent)
                            }
#if DEBUG
                            Button(copiedDiagnostics ? "Diagnostics copied" : "Copy diagnostics") {
                                UIPasteboard.general.setItems([["public.utf8-plain-text": diagnosticsReport()]],
                                    options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(900)])
                                copiedDiagnostics = true
                            }
                            .buttonStyle(.bordered)
#endif
                            Button("Close player", action: onDismiss)
                        }
                        .foregroundColor(.white)
                        .padding(24)
                        .frame(maxWidth: 460)
                        .frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .background(Color.black.ignoresSafeArea())
        .onAppear {
            do {
                if localAudioOwner == nil { localAudioOwner = try CastSystemPlayback.shared.beginLocalPlayback() }
            } catch {
                audioSessionError = error.localizedDescription
            }
            player.isMuted = false
            player.volume = 1
            player.allowsExternalPlayback = canAirPlay
            player.play()
        }
        .onDisappear {
            session.close()
            if let localAudioOwner {
                CastSystemPlayback.shared.endLocalPlayback(localAudioOwner)
                self.localAudioOwner = nil
            }
        }
        .alert("Couldn’t enable playback audio", isPresented: Binding(
            get: { audioSessionError != nil },
            set: { if !$0 { audioSessionError = nil } }
        )) {
            Button("OK", role: .cancel) { audioSessionError = nil }
        } message: {
            Text(audioSessionError ?? "")
        }
    }
}
