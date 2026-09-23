import SwiftUI
import AVKit
import QuickLookThumbnailing

struct PhoneMediaDetail: View {
    let item: PhoneMedia
    @EnvironmentObject private var library: PhoneMediaLibrary
    @EnvironmentObject private var vm: ConnectionViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var url: URL?
    @State private var player: AVPlayer?
    @State private var playerObservation: NSKeyValueObservation?
    @State private var image: UIImage?
    @State private var error: String?
    @State private var sending = false
    @State private var sent = false
    @State private var showCollections = false
    @State private var showDevices = false
    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let error {
                    Text(error).multilineTextAlignment(.center).padding()
                    Button("Try Again") { Task { await prepare() } }.buttonStyle(.bordered)
                } else if url == nil { ProgressView("Preparing media…").padding() }
                else if item.kind == .image, let image {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: .infinity)
                } else if let player { VideoPlayer(player: player).frame(minHeight: 220) }
                else { Image(systemName: item.kind.icon).font(Theme.font(.largeTitle)) }
                Text(item.source.title).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                if sent { Text("Sent to your TV").font(Theme.font(.caption)).foregroundColor(Theme.primary) }
                HStack {
                    Button { showCollections = true } label: { Label("Collection", systemImage: "folder.badge.plus") }.buttonStyle(.bordered)
                    Button {
                        if vm.isConnected { Task { await cast() } } else { showDevices = true }
                    } label: {
                        if sending { ProgressView() }
                        else { Label(vm.isConnected ? "Cast to TV" : "Connect TV", systemImage: "play.tv") }
                    }.buttonStyle(.borderedProminent).disabled(url == nil || sending)
                }
                if let url { ShareLink(item: url).padding(.bottom, 8) }
            }
            .padding(16).frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle(item.title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { await prepare() }
            .onDisappear { player?.pause() }
            .sheet(isPresented: $showCollections) { AddPhoneMediaToCollection(item: item) }
            .sheet(isPresented: $showDevices) { DeviceConnectionSheet() }
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
            } else {
                try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
                try? AVAudioSession.sharedInstance().setActive(true)
                let media = AVPlayerItem(url: result)
                player = AVPlayer(playerItem: media)
                player?.isMuted = false
                playerObservation = media.observe(\.status, options: [.new]) { item, _ in
                    if item.status == .failed {
                        DispatchQueue.main.async { error = "This file can’t be played on this iPhone. You can still try casting to a device that supports its format." }
                    }
                }
            }
        } catch { self.error = error.localizedDescription }
    }
    @MainActor private func cast() async {
        guard let url, vm.isConnected else { return }
        sending = true; sent = false
        defer { sending = false }
        let target = vm.destinationID
        player?.pause()
        guard let served = await LocalFileServer.shared.serve(fileURL: url) else {
            error = "Couldn’t serve this file. Check Wi-Fi and Local Network access, then try again."; return
        }
        guard vm.destinationID == target, vm.isConnected else { error = "The connected device changed. Try casting again."; return }
        vm.castLocalMedia(url: served, title: item.title, contentType: LocalFileServer.mimeType(for: url))
        sent = true
    }
}

struct AddPhoneMediaToCollection: View {
    let item: PhoneMedia
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
            .navigationTitle("Add to Collection").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
    private func add(_ id: UUID) { collections.addLocalItem(to: id, media: item); dismiss() }
}
