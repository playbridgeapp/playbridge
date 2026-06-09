import SwiftUI
import AVKit

/// Rich preview of a detected stream: AVPlayer preview (with the stream's headers), poster
/// thumbnail, HLS/DASH quality selection, subtitle attach, then cast to the TV.
struct StreamPreviewSheet: View {
    let video: DetectedVideo
    /// All detections on the page (used to offer subtitle tracks to attach).
    let detected: [DetectedVideo]

    @EnvironmentObject private var vm: ConnectionViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var qualities: [VideoQuality] = []
    @State private var selectedQuality: VideoQuality?   // nil = Auto (master / original URL)
    @State private var thumbnail: UIImage?
    @State private var player: AVPlayer?
    @State private var attachedSubtitles = Set<String>()
    @State private var loadingQualities = false

    private var subtitles: [DetectedVideo] { detected.filter { $0.isSubtitle } }
    private var castURL: String { selectedQuality?.url ?? video.url }
    private var previewable: Bool { video.kind == .hls || video.kind == .mp4 }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    preview
                    qualitySection
                    subtitleSection
                    castButton
                }
                .padding(16)
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Cast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Close") { dismiss() } } }
        }
        .task { await load() }
        .onDisappear { player?.pause() }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(video.kind.badge).font(.caption2.bold()).foregroundColor(Theme.onPrimary)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Theme.primaryDim).cornerRadius(6)
                Text(video.displayTitle).font(.headline).foregroundColor(Theme.onSurface).lineLimit(2)
            }
            Text(video.host).font(.caption).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
        }
    }

    @ViewBuilder private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14).fill(Color.black)
            if previewable, let player {
                VideoPlayer(player: player)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            } else if let thumbnail {
                Image(uiImage: thumbnail).resizable().aspectRatio(contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            } else {
                VStack(spacing: 6) {
                    Image(systemName: "film").font(.title).foregroundColor(Theme.onSurfaceVariant)
                    Text(previewable ? "Loading preview…" : "Preview unavailable — can still cast")
                        .font(.caption).foregroundColor(Theme.onSurfaceVariant)
                }
            }
        }
        .frame(height: 200)
    }

    @ViewBuilder private var qualitySection: some View {
        if !qualities.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("QUALITY").font(.caption.bold()).foregroundColor(Theme.onSurfaceVariant)
                Menu {
                    Button("Auto") { select(nil) }
                    ForEach(qualities) { q in
                        Button("\(q.label)  ·  \(bitrate(q.bandwidth))") { select(q) }
                    }
                } label: {
                    HStack {
                        Text(selectedQuality.map { "\($0.label)  ·  \(bitrate($0.bandwidth))" } ?? "Auto")
                            .foregroundColor(Theme.onSurface)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down").foregroundColor(Theme.onSurfaceVariant)
                    }
                    .padding(12).background(Theme.surfaceContainer).cornerRadius(10)
                }
            }
        } else if loadingQualities {
            HStack(spacing: 8) { ProgressView().tint(Theme.primary); Text("Loading qualities…").font(.caption).foregroundColor(Theme.onSurfaceVariant) }
        }
    }

    @ViewBuilder private var subtitleSection: some View {
        if !subtitles.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("SUBTITLES").font(.caption.bold()).foregroundColor(Theme.onSurfaceVariant)
                ForEach(subtitles) { sub in
                    Button {
                        if attachedSubtitles.contains(sub.url) { attachedSubtitles.remove(sub.url) }
                        else { attachedSubtitles.insert(sub.url) }
                    } label: {
                        HStack {
                            Image(systemName: attachedSubtitles.contains(sub.url) ? "checkmark.circle.fill" : "circle")
                                .foregroundColor(attachedSubtitles.contains(sub.url) ? Theme.primary : Theme.onSurfaceVariant)
                            Text(sub.displayTitle).foregroundColor(Theme.onSurface).lineLimit(1)
                            Spacer()
                        }
                        .padding(10).background(Theme.surfaceContainer).cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder private var castButton: some View {
        if vm.isConnected {
            Button {
                vm.castStream(video, quality: selectedQuality, subtitles: Array(attachedSubtitles))
                dismiss()
            } label: {
                Label("Cast to TV", systemImage: "play.tv.fill")
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .foregroundColor(Theme.onPrimary).background(Theme.ctaGradient).cornerRadius(14)
            }
        } else {
            Text("Connect to a TV first (Cast tab)")
                .font(.subheadline).foregroundColor(Theme.onSurfaceVariant)
                .frame(maxWidth: .infinity).padding(.vertical, 14)
                .background(Theme.surfaceContainerLow).cornerRadius(14)
        }
    }

    // MARK: - Logic

    private func select(_ q: VideoQuality?) {
        selectedQuality = q
        rebuildPlayer()
    }

    private func rebuildPlayer() {
        player?.pause()
        guard previewable, let u = URL(string: castURL) else { player = nil; return }
        let headers = VideoDetector.mediaHeaders(for: video)
        let asset = AVURLAsset(url: u, options: headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": headers])
        player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
    }

    private func load() async {
        rebuildPlayer()
        let headers = VideoDetector.mediaHeaders(for: video)
        async let thumb = Thumbnailer.thumbnail(url: video.url, headers: headers)

        if video.kind == .hls {
            loadingQualities = true
            qualities = await HLSParser.variants(masterURL: video.url, headers: headers)
            loadingQualities = false
        } else if video.kind == .dash {
            loadingQualities = true
            qualities = await DASHParser.variants(mpdURL: video.url, headers: headers)
            loadingQualities = false
        }
        thumbnail = await thumb
    }

    private func bitrate(_ bps: Int64) -> String {
        guard bps > 0 else { return "" }
        return String(format: "%.1f Mbps", Double(bps) / 1_000_000)
    }
}
