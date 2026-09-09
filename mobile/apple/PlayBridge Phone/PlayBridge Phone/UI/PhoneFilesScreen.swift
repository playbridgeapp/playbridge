import SwiftUI
import PhotosUI
import AVKit
import QuickLookThumbnailing

struct PhoneFilesScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var library: PhoneMediaLibrary
    @EnvironmentObject private var collections: CollectionsStore
    @EnvironmentObject private var browser: BrowserStore
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("pb_library_category") private var category = "Videos"
    @State private var query = ""
    @State private var sortByName = false
    @State private var source = "All"
    @State private var showImporter = false
    @State private var importing = false
    @State private var selected: PhoneMedia?
    @State private var collectionItem: PhoneMedia?
    @State private var deleteItem: PhoneMedia?
    @State private var selectedCollection: MediaCollection?
    @State private var newCollection = false
    @State private var collectionName = ""
    @State private var showDevices = false
    private let categories = ["Videos", "Audio", "Images", "Collections"]

    private var filtered: [PhoneMedia] {
        library.items.filter { item in
            item.kind.title == category && (source == "All" || item.source.title == source) &&
                (query.isEmpty || item.title.localizedCaseInsensitiveContains(query))
        }.sorted { sortByName ? $0.title.localizedStandardCompare($1.title) == .orderedAscending : $0.addedAt > $1.addedAt }
    }
    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Button { nav.navigate(to: .dashboard) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel("Back to dashboard")
                Text("Media Library").font(Theme.font(.title2).bold())
                Spacer()
                Button { showDevices = true } label: { Image(systemName: vm.isConnected ? "tv.fill" : "tv") }
                    .accessibilityLabel("Connect to TV")
                Menu {
                    Button("Import from Files", systemImage: "folder") { showImporter = true }
                    Button("New Collection", systemImage: "folder.badge.plus") { newCollection = true }
                } label: { Image(systemName: "plus") }.accessibilityLabel("Add media")
            }.padding(.horizontal, 16).padding(.top, 12)
            categoryTabs
            HStack {
                Image(systemName: "magnifyingglass")
                TextField("Search library", text: $query).autocorrectionDisabled()
                if !query.isEmpty { Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear search") }
                Menu {
                    Picker("Sort", selection: $sortByName) { Text("Recently added").tag(false); Text("Name").tag(true) }
                    Picker("Source", selection: $source) {
                        ForEach(["All", "Photos", "Files", "Downloads"], id: \.self) { Text($0).tag($0) }
                    }
                } label: { Image(systemName: "line.3.horizontal.decrease.circle") }.accessibilityLabel("Sort and filter")
            }.padding(12).background(Theme.surfaceContainer).cornerRadius(12).padding(.horizontal, 12)
            if importing { HStack { ProgressView(); Text("Importing media…") }.font(Theme.font(.caption)) }
            if category == "Collections" { collectionGrid }
            else {
                if category != "Audio" { photosAccess }
                if library.isScanning { ProgressView("Loading Photos…").font(Theme.font(.caption)) }
                ScrollView {
                    if filtered.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: category == "Audio" ? "music.note.list" : "photo.on.rectangle").font(Theme.font(.largeTitle))
                            Text(query.isEmpty ? "No \(category.lowercased()) yet" : "No matching media").font(Theme.font(.headline))
                            Text(category == "Audio" ? "Import audio from Files. Completed audio downloads also appear here." : "Import from Files or browse the Photos you’ve allowed. Completed media downloads also appear here.")
                                .font(Theme.font(.subheadline)).multilineTextAlignment(.center).foregroundColor(Theme.onSurfaceVariant)
                            Button("Import from Files") { showImporter = true }.buttonStyle(.borderedProminent)
                        }.padding(28)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 145), spacing: 12)], spacing: 16) {
                        ForEach(filtered) { item in
                            Button { selected = item } label: { PhoneMediaCard(item: item, url: library.localURL(item)) }
                                .buttonStyle(.plain)
                                .contextMenu {
                                    Button("Open", systemImage: "play") { selected = item }
                                    Button("Add to Collection", systemImage: "folder.badge.plus") { collectionItem = item }
                                    if item.source == .imported {
                                        Button("Remove imported copy", systemImage: "trash", role: .destructive) { deleteItem = item }
                                    }
                                }
                        }
                    }.padding(12)
                }
            }
        }
        .foregroundColor(Theme.onSurface)
        .background(Theme.surface.ignoresSafeArea())
        .task { refresh() }
        .onChange(of: scenePhase) { phase in if phase == .active { refresh() } }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls):
                importing = true
                Task { await library.importFiles(urls); importing = false }
            case .failure: library.error = "Couldn’t open the selected files. Please try again."
            }
        }
        .sheet(item: $selected) { item in PhoneMediaDetail(item: item) }
        .sheet(item: $collectionItem) { item in AddPhoneMediaToCollection(item: item) }
        .sheet(item: $selectedCollection) { collection in
            NavigationStack { CollectionDetailScreen(collectionId: collection.id, embedded: true).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { selectedCollection = nil } } } }
        }
        .sheet(isPresented: $showDevices) { DeviceConnectionSheet() }
        .alert("New Collection", isPresented: $newCollection) {
            TextField("Collection name", text: $collectionName)
            Button("Create") { let name = collectionName.trimmingCharacters(in: .whitespacesAndNewlines); if !name.isEmpty { collections.createCollection(name: name) }; collectionName = "" }
            Button("Cancel", role: .cancel) { collectionName = "" }
        }
        .alert("Remove imported copy?", isPresented: Binding(get: { deleteItem != nil }, set: { if !$0 { deleteItem = nil } })) {
            Button("Remove", role: .destructive) { if let item = deleteItem { library.removeImport(item) }; deleteItem = nil }
            Button("Cancel", role: .cancel) { deleteItem = nil }
        } message: { Text("The original file stays where it was. Collection references to this copy will become unavailable.") }
        .alert("Media library", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("OK") { library.error = nil }
        } message: { Text(library.error ?? "") }
    }
    private var categoryTabs: some View {
        let counts = Dictionary(grouping: library.items, by: { $0.kind.title }).mapValues { $0.count }
        return ScrollViewReader { reader in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(categories, id: \.self) { title in
                        let count = title == "Collections" ? collections.collections.count : counts[title, default: 0]
                        Button {
                            withAnimation(.easeInOut(duration: 0.18)) { category = title }
                        } label: {
                            Text("\(title) (\(count))")
                                .font(Theme.font(.callout).weight(.medium)).fixedSize(horizontal: true, vertical: false)
                                .foregroundColor(category == title ? Theme.primary : Theme.onSurfaceVariant)
                                .padding(.horizontal, 8).frame(minWidth: 88, minHeight: 48)
                                .overlay(alignment: .bottom) {
                                    if category == title {
                                        Capsule().fill(Theme.primary).frame(height: 3).padding(.horizontal, 16)
                                    }
                                }
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain).id(title)
                        .accessibilityAddTraits(category == title ? .isSelected : [])
                    }
                }
            }
            .overlay(alignment: .bottom) { Rectangle().fill(Theme.outlineVariant.opacity(0.4)).frame(height: 0.5) }
            .onChange(of: category) { value in
                withAnimation(.easeInOut(duration: 0.18)) { reader.scrollTo(value, anchor: .center) }
            }
            .onAppear { reader.scrollTo(category, anchor: .center) }
        }
    }

    private func refresh() { library.refreshDownloads(browser.downloads.items); library.refreshPhotos() }
    @ViewBuilder private var photosAccess: some View {
        if library.authorization == .notDetermined {
            Button("Connect Photos") { Task { await library.requestPhotos() } }.buttonStyle(.bordered)
        } else if library.authorization == .limited {
            HStack {
                Text("Showing selected Photos").font(Theme.font(.caption))
                Button("Manage access") { managePhotos() }.font(Theme.font(.caption).bold())
            }
        } else if library.authorization == .denied || library.authorization == .restricted {
            Button("Photos access is off · Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }.font(Theme.font(.caption))
        }
    }
    private func managePhotos() {
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              var controller = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let presented = controller.presentedViewController { controller = presented }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: controller)
    }
    private var collectionGrid: some View {
        ScrollView {
            if collections.collections.isEmpty {
                VStack(spacing: 12) {
                    Text("Organize your media").font(Theme.font(.headline))
                    Text("Create a collection, then add videos, images or audio from their menus.").font(Theme.font(.subheadline))
                    Button("New Collection") { newCollection = true }.buttonStyle(.borderedProminent)
                }.padding(28)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 145))], spacing: 12) {
                ForEach(collections.collections.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) }) { collection in
                    Button { selectedCollection = collection } label: {
                        VStack(alignment: .leading, spacing: 12) {
                            Image(systemName: "rectangle.stack.fill").font(Theme.font(.largeTitle)).foregroundColor(Theme.primary)
                            Text(collection.name).font(Theme.font(.headline)).lineLimit(2)
                            Text("\(collection.itemCount) items").font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                        }.frame(maxWidth: .infinity, minHeight: 120, alignment: .leading).padding(16)
                            .background(Theme.surfaceContainer).cornerRadius(16)
                    }.buttonStyle(.plain)
                }
            }.padding(12)
        }
    }
}

struct PhoneMediaCard: View {
    let item: PhoneMedia
    let url: URL?
    @State private var thumbnail: UIImage?
    @State private var photoRequest: PHImageRequestID?
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainerHigh)
                    if let thumbnail { Image(uiImage: thumbnail).resizable().scaledToFill() }
                    else { Image(systemName: item.kind.icon).font(Theme.font(.largeTitle)).foregroundColor(Theme.primary) }
                }.frame(height: 110).clipped().cornerRadius(12)
                if let duration = item.duration, duration.isFinite {
                    Text(String(format: "%d:%02d", Int(duration) / 60, Int(duration) % 60))
                        .font(Theme.font(.caption2).monospacedDigit()).padding(4).background(.black.opacity(0.7)).cornerRadius(4).padding(6)
                }
            }
            Text(item.title).font(Theme.font(.subheadline).weight(.medium)).lineLimit(2)
            Text(item.source.title + (item.bytes.map { " · " + ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? ""))
                .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
        }
        .contentShape(Rectangle())
        .task(id: item.id) {
            if let id = item.assetIdentifier, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject {
                let options = PHImageRequestOptions(); options.deliveryMode = .opportunistic; options.isNetworkAccessAllowed = false
                photoRequest = PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 320, height: 220), contentMode: .aspectFill, options: options) { image, _ in
                    DispatchQueue.main.async { thumbnail = image }
                }
            } else if let url, item.kind == .image {
                let image = await PhoneMediaThumbnail.image(at: url, maxPixel: 320)
                if !Task.isCancelled { thumbnail = image }
            } else if let url {
                let request = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 320, height: 220), scale: 1, representationTypes: .thumbnail)
                if let result = try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: request), !Task.isCancelled { thumbnail = result.uiImage }
            }
        }
        .onDisappear { if let request = photoRequest { PHImageManager.default().cancelImageRequest(request); photoRequest = nil } }
    }
}
