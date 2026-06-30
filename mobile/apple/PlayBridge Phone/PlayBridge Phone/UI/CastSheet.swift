import SwiftUI
import AVKit

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
    let player: AVPlayer
}

/// Unified Cast Sheet displaying all detected streams with previews, matching/exceeding Android's CastSheet UX.
struct CastSheet: View {
    let videos: [DetectedVideo]
    let tab: BrowserTab
    @ObservedObject var store: BrowserStore

    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var selectedVideo: DetectedVideo?
    @State private var selectedQuality: VideoQuality?
    @State private var attachedSubtitles = Set<String>()
    @State private var qualities: [VideoQuality] = []
    @State private var loadingQualities = false
    @State private var thumbnail: UIImage?
    @State private var isThumbnailLoading = false
    @State private var castAction = "play"
    @State private var browseUrl = ""
    @State private var selectedTab = 0
    @State private var playerMode = "tv"
    @State private var fullscreenPlayerItem: PlayerItem?

    private var streams: [DetectedVideo] { videos.filter { !$0.isSubtitle } }
    private var subtitles: [DetectedVideo] { videos.filter { $0.isSubtitle } }

    private var sendEnabled: Bool {
        if castAction == "browse" {
            return vm.isConnected && !browseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        } else {
            return vm.isConnected && selectedVideo != nil
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    capabilitySelectors
                    
                    if castAction == "browse" {
                        browseSection
                    } else {
                        tabsSection
                        
                        if selectedTab == 0 {
                            videosListSection
                        } else {
                            subtitlesListSection
                        }
                    }
                }
                .padding(.vertical, 8)
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Cast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close") { dismiss() }
                        .foregroundColor(Theme.primary)
                }
            }
            .fullScreenCover(item: $fullscreenPlayerItem) { item in
                FullScreenVideoPlayerView(player: item.player) {
                    fullscreenPlayerItem = nil
                }
            }
            .onAppear {
                if selectedVideo == nil, let firstStream = sortedStreams(streams).first {
                    selectVideo(firstStream)
                }
                if browseUrl.isEmpty {
                    browseUrl = tab.urlString
                }
                if streams.isEmpty && vm.pairedDevice?.browsers.isEmpty == false {
                    castAction = "browse"
                }
            }
            .onChange(of: castAction) { newAction in
                let options = newAction == "browse" ? TvCapabilityOptions.browserOptions(for: vm.pairedDevice) : TvCapabilityOptions.playerOptions(for: vm.pairedDevice)
                if !options.contains(where: { $0.id == playerMode }) {
                    playerMode = "tv"
                }
            }
        }
    }

    // MARK: - Subviews

    private var header: some View {
        HStack(spacing: 12) {
            Picker("Action", selection: $castAction) {
                Text("Play").tag("play")
                if vm.isConnected && vm.coordinator.activeContext == "player" {
                    Text("Queue").tag("queue")
                }
                if vm.pairedDevice?.browsers.isEmpty == false {
                    Text("Browse").tag("browse")
                }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 200)
            
            Spacer()
            
            if let device = vm.pairedDevice {
                HStack(spacing: 4) {
                    Circle()
                        .fill(vm.isConnected ? Color.green : Color.red)
                        .frame(width: 6, height: 6)
                    Text(device.name)
                        .font(.caption.bold())
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Theme.surfaceContainerHigh)
                .cornerRadius(6)
                .foregroundColor(Theme.onSurfaceVariant)
            }
            
            Button {
                sendAction()
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.body.bold())
                    .foregroundColor(sendEnabled ? Theme.onPrimary : Theme.onSurfaceVariant.opacity(0.3))
                    .padding(8)
                    .background(sendEnabled ? Theme.ctaGradient : LinearGradient(colors: [Color.clear], startPoint: .leading, endPoint: .trailing))
                    .clipShape(Circle())
            }
            .disabled(!sendEnabled)
        }
        .padding(.horizontal, 16)
    }

    private var capabilitySelectors: some View {
        HStack(spacing: 8) {
            let options = castAction == "browse" ? TvCapabilityOptions.browserOptions(for: vm.pairedDevice) : TvCapabilityOptions.playerOptions(for: vm.pairedDevice)
            let currentLabel = options.first(where: { $0.id == playerMode })?.label ?? "TV Default"
            
            Menu {
                ForEach(options, id: \.id) { opt in
                    Button(opt.label) {
                        playerMode = opt.id
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(currentLabel)
                    Image(systemName: "chevron.down")
                }
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(Theme.surfaceContainerHigh)
                .cornerRadius(8)
                .foregroundColor(Theme.onSurface)
            }
            
            if castAction == "browse" {
                Button {
                    tab.toggleDesktopMode()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: tab.isDesktopMode ? "desktopcomputer" : "iphone")
                        Text(tab.isDesktopMode ? "Desktop" : "Mobile")
                    }
                    .font(.caption)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Theme.surfaceContainerHigh)
                    .cornerRadius(8)
                    .foregroundColor(Theme.onSurface)
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var browseSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BROWSE PAGE ON TV")
                .font(.caption.bold())
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
                        .font(.caption.bold())
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
                        .font(.caption.bold())
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
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Button {
                    withAnimation { selectedTab = 0 }
                } label: {
                    VStack(spacing: 8) {
                        Text("Videos (\(streams.count))")
                            .font(.subheadline.bold())
                            .foregroundColor(selectedTab == 0 ? Theme.primary : Theme.onSurfaceVariant)
                        Rectangle()
                            .fill(selectedTab == 0 ? Theme.primary : Color.clear)
                            .frame(height: 2)
                    }
                }
                .frame(maxWidth: .infinity)
                
                Button {
                    withAnimation { selectedTab = 1 }
                } label: {
                    VStack(spacing: 8) {
                        Text("Subtitles (\(subtitles.count))")
                            .font(.subheadline.bold())
                            .foregroundColor(selectedTab == 1 ? Theme.primary : Theme.onSurfaceVariant)
                        Rectangle()
                            .fill(selectedTab == 1 ? Theme.primary : Color.clear)
                            .frame(height: 2)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
            
            Divider().background(Theme.outlineVariant)
        }
    }

    private var videosListSection: some View {
        VStack(spacing: 12) {
            if streams.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "film")
                        .font(.system(size: 48))
                        .foregroundColor(Theme.onSurfaceVariant.opacity(0.5))
                    Text("No videos detected yet")
                        .font(.headline)
                        .foregroundColor(Theme.onSurface)
                    Text("Browse a page with video content")
                        .font(.caption)
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
                        attachedSubtitles: attachedSubtitles,
                        qualities: qualities,
                        loadingQualities: loadingQualities,
                        isThumbnailLoading: isThumbnailLoading,
                        thumbnail: thumbnail,
                        onSelect: {
                            selectVideo(video)
                        },
                        onQualitySelect: { q in
                            selectQuality(q)
                        },
                        onSubtitleToggle: { subUrl in
                            if attachedSubtitles.contains(subUrl) {
                                attachedSubtitles.remove(subUrl)
                            } else {
                                attachedSubtitles.insert(subUrl)
                            }
                        },
                        onPlayOnPhone: {
                            playOnPhone(video)
                        },
                        onCopyUrl: {
                            UIPasteboard.general.string = selectedQuality?.url ?? video.url
                        }
                    )
                }
            }
        }
        .padding(.horizontal, 16)
    }

    private var subtitlesListSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if subtitles.isEmpty {
                VStack(spacing: 8) {
                    Image(systemName: "captions.bubble")
                        .font(.system(size: 40))
                        .foregroundColor(Theme.onSurfaceVariant.opacity(0.5))
                    Text("No subtitles detected")
                        .font(.subheadline)
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
                                Text(sub.displayTitle)
                                    .font(.subheadline)
                                    .foregroundColor(Theme.onSurface)
                                    .lineLimit(1)
                                Text(sub.host)
                                    .font(.caption)
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            Spacer()
                        }
                        .padding(12)
                        .background(Theme.surfaceContainer)
                        .cornerRadius(12)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(16)
    }

    // MARK: - Helper Methods

    private func selectVideo(_ video: DetectedVideo) {
        selectedVideo = video
        selectedQuality = nil
        qualities = []
        thumbnail = nil

        let headers = VideoDetector.mediaHeaders(for: video)
        isThumbnailLoading = true
        loadingQualities = true
        
        Task {
            // Show a parsed thumbnail (like Android) rather than an inline video player.
            let thumb = await Thumbnailer.thumbnail(url: video.url, headers: headers)
            await MainActor.run {
                self.thumbnail = thumb
                self.isThumbnailLoading = false
            }
            
            var loadedQualities: [VideoQuality] = []
            if video.kind == .hls {
                loadedQualities = await HLSParser.variants(masterURL: video.url, headers: headers)
            } else if video.kind == .dash {
                loadedQualities = await DASHParser.variants(mpdURL: video.url, headers: headers)
            }
            await MainActor.run {
                self.qualities = loadedQualities
                self.loadingQualities = false
            }
        }
    }

    private func selectQuality(_ q: VideoQuality?) {
        selectedQuality = q
    }

    private func playOnPhone(_ video: DetectedVideo) {
        let castURL = selectedQuality?.url ?? video.url
        if let u = URL(string: castURL) {
            let headers = VideoDetector.mediaHeaders(for: video)
            let asset = AVURLAsset(url: u, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
            let p = AVPlayer(playerItem: AVPlayerItem(asset: asset))
            fullscreenPlayerItem = PlayerItem(player: p)
        }
    }

    private func sendAction() {
        if castAction == "browse" {
            vm.browseTo(url: browseUrl, desktopMode: tab.isDesktopMode)
        } else if let video = selectedVideo {
            if castAction == "queue" {
                vm.queueStream(video, quality: selectedQuality, subtitles: Array(attachedSubtitles), playerMode: playerMode)
            } else {
                vm.castStream(video, quality: selectedQuality, subtitles: Array(attachedSubtitles), playerMode: playerMode)
                nav.navigate(to: .remote)
            }
        }
        dismiss()
    }

    private func sortedStreams(_ list: [DetectedVideo]) -> [DetectedVideo] {
        list.sorted { v1, v2 in
            let score1 = score(v1)
            let score2 = score(v2)
            if score1 != score2 {
                return score1 > score2
            }
            return v1.displayTitle.localizedCompare(v2.displayTitle) == .orderedAscending
        }
    }

    private func score(_ video: DetectedVideo) -> Int {
        if video.kind == .hls {
            return video.url.contains("master") ? 5 : 4
        }
        if video.kind == .dash { return 4 }
        if video.kind == .mp4 { return 2 }
        return 0
    }
}

// MARK: - VideoCard Component

struct VideoCard: View {
    let video: DetectedVideo
    let isSelected: Bool
    let selectedQuality: VideoQuality?
    let attachedSubtitles: Set<String>
    let qualities: [VideoQuality]
    let loadingQualities: Bool
    let isThumbnailLoading: Bool
    let thumbnail: UIImage?
    
    let onSelect: () -> Void
    let onQualitySelect: (VideoQuality?) -> Void
    let onSubtitleToggle: (String) -> Void
    let onPlayOnPhone: () -> Void
    let onCopyUrl: () -> Void
    
    @State private var localThumbnail: UIImage?
    @State private var localLoading = false
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header Row: Type badge, host/name
            HStack(spacing: 8) {
                Text(video.kind.badge)
                    .font(.caption2.bold())
                    .foregroundColor(Theme.onPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Theme.primaryDim)
                    .cornerRadius(6)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(video.displayTitle)
                        .font(.subheadline.bold())
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(1)
                    Text(video.host)
                        .font(.caption)
                        .foregroundColor(Theme.onSurfaceVariant)
                        .lineLimit(1)
                }
                
                Spacer()
                
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(Theme.primary)
                }
            }
            
            // Media Preview container
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.black.opacity(0.4))
                
                if isSelected {
                    if isThumbnailLoading {
                        ProgressView().tint(Theme.primary)
                    } else if let thumbnail = thumbnail {
                        Image(uiImage: thumbnail)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: .infinity, maxHeight: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else {
                        ProgressView().tint(Theme.primary)
                    }
                } else {
                    if let localThumbnail = localThumbnail {
                        Image(uiImage: localThumbnail)
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(maxWidth: .infinity, maxHeight: 140)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    } else if localLoading {
                        ProgressView().tint(Theme.primary)
                    } else {
                        VStack(spacing: 4) {
                            Image(systemName: "play.fill")
                                .font(.title3)
                                .foregroundColor(Theme.onSurfaceVariant.opacity(0.6))
                            Text("No preview available")
                                .font(.caption2)
                                .foregroundColor(Theme.onSurfaceVariant.opacity(0.6))
                        }
                    }
                }
            }
            .frame(height: 140)
            .frame(maxWidth: .infinity)
            .clipped()
            
            // Click target to select/expand card
            if !isSelected {
                Button {
                    onSelect()
                } label: {
                    Text("Select Stream")
                        .font(.caption.bold())
                        .foregroundColor(Theme.primary)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(Theme.surfaceContainerHigh)
                        .cornerRadius(8)
                }
                .buttonStyle(.plain)
            }
            
            if isSelected {
                VStack(alignment: .leading, spacing: 10) {
                    // Quick Action Row
                    HStack(spacing: 16) {
                        Button(action: onPlayOnPhone) {
                            Label("Play on Phone", systemImage: "play.circle.fill")
                                .font(.caption.bold())
                                .foregroundColor(Theme.primary)
                        }
                        .buttonStyle(.plain)
                        
                        Button(action: onCopyUrl) {
                            Label("Copy URL", systemImage: "doc.on.doc")
                                .font(.caption.bold())
                                .foregroundColor(Theme.primary)
                        }
                        .buttonStyle(.plain)
                        
                        Spacer()
                    }
                    .padding(.top, 4)
                    
                    // Quality Variants Selection
                    if !qualities.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("QUALITY")
                                .font(.caption2.bold())
                                .foregroundColor(Theme.onSurfaceVariant)
                            
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    Button {
                                        onQualitySelect(nil)
                                    } label: {
                                        Text("Auto")
                                            .font(.caption)
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 6)
                                            .background(selectedQuality == nil ? Theme.primaryDim : Theme.surfaceContainerHighest)
                                            .foregroundColor(selectedQuality == nil ? Theme.onPrimary : Theme.onSurface)
                                            .cornerRadius(8)
                                    }
                                    .buttonStyle(.plain)
                                    
                                    ForEach(qualities) { q in
                                        Button {
                                            onQualitySelect(q)
                                        } label: {
                                            Text(q.label)
                                                .font(.caption)
                                                .padding(.horizontal, 10)
                                                .padding(.vertical, 6)
                                                .background(selectedQuality?.id == q.id ? Theme.primaryDim : Theme.surfaceContainerHighest)
                                                .foregroundColor(selectedQuality?.id == q.id ? Theme.onPrimary : Theme.onSurface)
                                                .cornerRadius(8)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    } else if loadingQualities {
                        HStack(spacing: 8) {
                            ProgressView().tint(Theme.primary)
                            Text("Parsing qualities…")
                                .font(.caption)
                                .foregroundColor(Theme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        .padding(12)
        .background(isSelected ? Theme.surfaceContainerHigh : Theme.surfaceContainer)
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(isSelected ? Theme.primary : Color.clear, lineWidth: 2)
        )
        .task {
            if !isSelected {
                localLoading = true
                let headers = VideoDetector.mediaHeaders(for: video)
                localThumbnail = await Thumbnailer.thumbnail(url: video.url, headers: headers)
                localLoading = false
            }
        }
    }
}

// MARK: - FullScreenVideoPlayerView Component

struct FullScreenVideoPlayerView: View {
    let player: AVPlayer
    let onDismiss: () -> Void
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoPlayer(player: player)
                .ignoresSafeArea()
                .onAppear { player.play() }
                .onDisappear { player.pause() }
            
            VStack {
                HStack {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.black.opacity(0.6))
                            .clipShape(Circle())
                    }
                    Spacer()
                }
                .padding()
                Spacer()
            }
        }
    }
}
