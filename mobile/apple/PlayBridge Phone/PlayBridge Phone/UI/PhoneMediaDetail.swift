import SwiftUI
import Photos
import AVFoundation
import QuickLookThumbnailing

/// The phone file most recently sent to the TV, for the ON TV badge.
@MainActor final class PhoneFilesNowPlaying: ObservableObject {
    static let shared = PhoneFilesNowPlaying()
    @Published var itemID: String?
}

/// Sends one phone file to the chosen receiver: resolve (export Photos items), serve, cast.
@MainActor enum PhoneMediaCasting {
    static func castsToSelectedReceiver(_ vm: ConnectionViewModel) -> Bool {
        vm.isConnected && (vm.isAirPlay || vm.isExternalReceiver || vm.playBridgeOutputSelected)
    }

    static func receiverName(_ vm: ConnectionViewModel) -> String {
        vm.receiverName.map(PlayOnCatalog.displayName) ?? "TV"
    }

    /// Returns an error message, or nil once the cast was sent.
    static func cast(_ item: PhoneMedia, url resolved: URL? = nil, subtitle: URL? = nil,
                     library: PhoneMediaLibrary, vm: ConnectionViewModel) async -> String? {
        guard castsToSelectedReceiver(vm) else { return "Pick a TV first." }
        let target = vm.destinationID
        let url: URL
        if let resolved { url = resolved } else {
            do { url = try await library.resolve(item) } catch { return error.localizedDescription }
        }
        guard let served = await LocalFileServer.shared.serve(fileURL: url, subtitle: subtitle) else {
            return "Couldn’t serve this file. Check Wi-Fi and Local Network access, then try again."
        }
        guard vm.destinationID == target, vm.isConnected else { return "The connected device changed. Try casting again." }
        vm.castLocalMedia(url: served.media, title: item.title, contentType: LocalFileServer.mimeType(for: url),
                          subtitles: served.subtitle.map { [$0] } ?? [])
        PhoneFilesNowPlaying.shared.itemID = item.id
        return nil
    }
}

/// Video, photo or song details (boards 2 and 9). With a TV chosen it leads with
/// "Play on <TV>"; with none it leads with "Play on this phone" and offers "Play on a TV…".
struct PhoneMediaDetail: View {
    let item: PhoneMedia
    @EnvironmentObject private var library: PhoneMediaLibrary
    @EnvironmentObject private var vm: ConnectionViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var url: URL?
    @State private var playback: PlaybackSession?
    @State private var localAudioOwner: UUID?
    @State private var image: UIImage?
    @State private var error: String?
    @State private var sending = false
    @State private var sent = false
    @State private var showCollections = false
    @State private var showDevices = false
    @State private var playOnPage: PlayOnPage?
    @State private var playOnPicking = true
    @State private var subtitle: URL?
    @State private var pickSubtitle = false

    private var toTV: Bool { PhoneMediaCasting.castsToSelectedReceiver(vm) }
    private var tvName: String { PhoneMediaCasting.receiverName(vm) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                preview
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title).font(Theme.font(.title3).bold()).lineLimit(2)
                    let meta = [item.duration.map(PhoneMediaDetail.clock), PhoneFilesModel.size(item.bytes),
                                item.artist ?? (item.source == .imported ? "Imported" : "Photos")].compactMap { $0 }
                    Text(meta.joined(separator: " · ")).font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
                }
                if let error {
                    Text(error).font(Theme.font(.footnote)).foregroundColor(Theme.danger)
                }
                if sent { Text("Sent to \(tvName)").font(Theme.font(.footnote)).foregroundColor(Theme.primary) }
                if toTV {
                    primaryButton("Play on \(tvName)", systemImage: "play.fill") { Task { await cast() } }
                        .disabled(sending)
                } else {
                    primaryButton("Play on this phone", systemImage: "play.fill") { playHere() }
                    Button { openPlayOn() } label: {
                        Label("Play on a TV…", systemImage: "tv").font(Theme.font(.body).weight(.medium))
                            .frame(maxWidth: .infinity, minHeight: 52)
                            .overlay(Capsule().stroke(Theme.outlineVariant, lineWidth: 1))
                    }.buttonStyle(.plain)
                }
                HStack(spacing: 8) {
                    tile("Collection", systemImage: "folder.badge.plus") { showCollections = true }
                    if let url {
                        ShareLink(item: url) { tileLabel("Share", systemImage: "square.and.arrow.up") }.buttonStyle(.plain)
                    }
                }
                if toTV {
                    VStack(spacing: 0) {
                        if item.kind == .video && vm.playBridgeOutputSelected && !vm.isAirPlay && !vm.isExternalReceiver {
                            row(subtitle?.lastPathComponent ?? "Add subtitles…",
                                detail: subtitle == nil ? "Pick an .srt or .vtt file" : "Subtitles for this video",
                                systemImage: "captions.bubble") { pickSubtitle = true }
                                .overlay(alignment: .trailing) {
                                    if subtitle != nil {
                                        Button { subtitle = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                                            .accessibilityLabel("Remove subtitles").padding(.trailing, 4)
                                    }
                                }
                            Divider().overlay(Theme.outlineVariant.opacity(0.4))
                        }
                        if item.kind != .image {
                            row("Play on this phone instead", detail: nil, systemImage: "iphone") { playHere() }
                        }
                    }
                    .background(Theme.surfaceContainerHigh).cornerRadius(16)
                }
            }
            .padding(16)
        }
        .foregroundColor(Theme.onSurface)
        .background(Theme.surfaceContainer.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .task { await prepare() }
        .onDisappear {
            playback?.close()
            releaseLocalAudio()
        }
        .sheet(isPresented: $showCollections) { AddPhoneMediaToCollection(items: [item]) }
        .playOnLocalSheet(isPresented: $showDevices, page: $playOnPage, picking: $playOnPicking)
        .fileImporter(isPresented: $pickSubtitle, allowedContentTypes: [.item]) { result in
            if case .success(let picked) = result { subtitle = Self.copySubtitle(picked) }
            if subtitle == nil, case .success = result { error = "Choose an SRT, VTT, ASS or SSA file." }
        }
    }

    @ViewBuilder private var preview: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 16).fill(Theme.surfaceContainerHigh)
            if item.kind == .image, let image {
                Image(uiImage: image).resizable().scaledToFit()
            } else if let playback {
                PhoneMediaMPVPreview(session: playback) { playing in
                    if playing, localAudioOwner == nil {
                        localAudioOwner = try? CastSystemPlayback.shared.beginLocalPlayback()
                    } else if !playing { releaseLocalAudio() }
                }
            } else {
                PhoneMediaThumbnailView(item: item, url: library.localURL(item), size: CGSize(width: 640, height: 360))
                if item.kind != .image {
                    Button { playHere() } label: {
                        Image(systemName: url == nil ? "hourglass" : "play.fill").font(.system(size: 24))
                            .foregroundColor(.white).frame(width: 56, height: 56).background(Circle().fill(.black.opacity(0.55)))
                    }
                    .buttonStyle(.plain).disabled(url == nil).accessibilityLabel("Preview on this phone")
                }
            }
        }
        .aspectRatio(item.kind == .audio ? 1.6 : 16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private func primaryButton(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if sending { ProgressView().tint(Theme.onPrimary) } else { Image(systemName: systemImage) }
                Text(title).lineLimit(1)
            }
            .font(Theme.font(.body).bold()).foregroundColor(Theme.onPrimary)
            .frame(maxWidth: .infinity, minHeight: 52).background(Capsule().fill(Theme.primary))
        }.buttonStyle(.plain)
    }

    private func tile(_ title: String, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { tileLabel(title, systemImage: systemImage) }.buttonStyle(.plain)
    }

    private func tileLabel(_ title: String, systemImage: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage).foregroundColor(Theme.primary)
            Text(title).font(Theme.font(.caption))
        }
        .frame(maxWidth: .infinity, minHeight: 72).background(Theme.surfaceContainerHigh).cornerRadius(16)
    }

    private func row(_ title: String, detail: String?, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage).foregroundColor(Theme.onSurfaceVariant).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(Theme.font(.body)).lineLimit(1)
                    if let detail { Text(detail).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant) }
                }
                Spacer()
            }
            .padding(.horizontal, 14).frame(minHeight: 58).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func openPlayOn() {
        playOnPage = nil
        playOnPicking = true
        showDevices = true
    }

    /// Starts the inline phone player (images are already shown full size).
    private func playHere() {
        guard let url, item.kind != .image else { return }
        if playback == nil {
            let media = RoutedStream(url: url, headers: [:], sourceURL: url.absoluteString)
            playback = PlaybackSession(media: media, route: .direct, contentType: LocalFileServer.mimeType(for: url)) { media }
        }
    }

    @MainActor private func prepare() async {
        error = nil
        do {
            let result = try await library.resolve(item)
            guard !Task.isCancelled else { return }
            url = result
            if item.kind == .image {
                image = await PhoneMediaThumbnail.image(at: result, maxPixel: 1600)
                if image == nil { error = "This image format can’t be previewed on this iPhone." }
            }
        } catch { self.error = error.localizedDescription }
    }

    @MainActor private func cast() async {
        sending = true; sent = false; error = nil
        defer { sending = false }
        playback?.pause()
        releaseLocalAudio()
        if let failure = await PhoneMediaCasting.cast(item, url: url, subtitle: subtitle, library: library, vm: vm) {
            error = failure
        } else {
            sent = true
            dismiss()
        }
    }

    private func releaseLocalAudio() {
        if let localAudioOwner {
            CastSystemPlayback.shared.endLocalPlayback(localAudioOwner)
            self.localAudioOwner = nil
        }
    }

    /// Copies a picked subtitle into temp storage so serving doesn't depend on the picker's access.
    static func copySubtitle(_ picked: URL) -> URL? {
        let ext = picked.pathExtension.lowercased()
        guard ["srt", "vtt", "ass", "ssa"].contains(ext) else { return nil }
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Subtitles", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let destination = folder.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
        guard (try? FileManager.default.copyItem(at: picked, to: destination)) != nil else { return nil }
        return destination
    }

    static func clock(_ seconds: Double) -> String { PhoneFilesModel.clock(seconds) }
}

/// Local files use the same mpv engine, controls, tracks and retry path as streams.
private struct PhoneMediaMPVPreview: View {
    @ObservedObject var session: PlaybackSession
    let onPlaybackChange: (Bool) -> Void

    var body: some View {
        VStack(spacing: 8) {
            MPVPhonePlayerView(session: session)
            if let failure = session.failure {
                Text(failure.message).multilineTextAlignment(.center)
                Button("Try Again") { session.retry() }.disabled(session.retrying)
            }
        }
        .onChange(of: session.isPlaying) { onPlaybackChange($0) }
        .onDisappear { session.pause(); onPlaybackChange(false) }
    }
}

/// Video frame, photo or album art; icon fallback.
struct PhoneMediaThumbnailView: View {
    let item: PhoneMedia
    let url: URL?
    let size: CGSize
    @State private var thumbnail: UIImage?
    @State private var photoRequest: PHImageRequestID?

    var body: some View {
        ZStack {
            Rectangle().fill(Theme.surfaceContainerHigh)
            if let thumbnail {
                Image(uiImage: thumbnail).resizable().scaledToFill()
            } else {
                Image(systemName: item.kind.icon).font(.system(size: 22)).foregroundColor(Theme.onSurfaceVariant)
            }
        }
        .clipped()
        .task(id: item.id) { await load() }
        .onDisappear { if let request = photoRequest { PHImageManager.default().cancelImageRequest(request); photoRequest = nil } }
    }

    private func load() async {
        if let id = item.assetIdentifier, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject {
            let options = PHImageRequestOptions(); options.deliveryMode = .opportunistic; options.isNetworkAccessAllowed = false
            photoRequest = PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFill, options: options) { image, _ in
                DispatchQueue.main.async { thumbnail = image }
            }
        } else if let url, item.kind == .image {
            let image = await PhoneMediaThumbnail.image(at: url, maxPixel: Int(max(size.width, size.height)))
            if !Task.isCancelled { thumbnail = image }
        } else if let url, item.kind == .audio {
            let metadata = (try? await AVURLAsset(url: url).load(.commonMetadata)) ?? []
            for entry in AVMetadataItem.metadataItems(from: metadata, filteredByIdentifier: .commonIdentifierArtwork) {
                if let data = try? await entry.load(.dataValue), let image = UIImage(data: data) {
                    if !Task.isCancelled { thumbnail = image }
                    return
                }
            }
        } else if let url {
            let request = QLThumbnailGenerator.Request(fileAt: url, size: size, scale: 1, representationTypes: .thumbnail)
            if let result = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request), !Task.isCancelled {
                thumbnail = result.uiImage
            }
        }
    }
}

/// Adds one or more phone files to a collection.
struct AddPhoneMediaToCollection: View {
    let items: [PhoneMedia]
    @EnvironmentObject private var collections: CollectionsStore
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    var body: some View {
        NavigationStack {
            List {
                Section("Create collection") {
                    TextField("Collection name", text: $name)
                    Button("Create and Add") {
                        let id = collections.createCollection(name: name.trimmingCharacters(in: .whitespacesAndNewlines))
                        add(id)
                    }.disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                Section("Your collections") {
                    ForEach(collections.collections) { collection in
                        Button(collection.name) { add(collection.id) }
                    }
                }
            }
            .navigationTitle(items.count == 1 ? "Add to Collection" : "Add \(items.count) to Collection")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
    private func add(_ id: UUID) {
        for item in items { collections.addLocalItem(to: id, media: item) }
        dismiss()
    }
}
