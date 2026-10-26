import SwiftUI
import PhotosUI

/// Phone Files (Phone Files canvas, boards 1–11): Videos, Music and Photos over the Photos
/// library and files imported from Files. Items play on the chosen TV, or on this phone when
/// none is chosen. The shared mini player (ContentView) shows what is playing.
struct PhoneFilesScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var library: PhoneMediaLibrary
    @EnvironmentObject private var nav: NavigationViewModel
    @ObservedObject private var nowPlaying = PhoneFilesNowPlaying.shared
    @ObservedObject private var phoneQueue = PhoneQueueCoordinator.shared
    @AppStorage("pb_phone_files_slide_seconds") private var slideSeconds = 5
    @AppStorage("pb_phone_files_slide_shuffle") private var slideShuffle = false
    @AppStorage("pb_phone_files_slide_videos") private var slideVideos = true
    @State private var slideshow: SlideshowRun?
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("pb_phone_files_tab") private var tabRaw = PhoneFilesTab.videos.rawValue
    @AppStorage("pb_phone_files_sort") private var sortRaw = PhoneFilesSort.newest.rawValue
    @AppStorage("pb_phone_files_videos_list") private var videosAsList = false
    @AppStorage("pb_phone_files_music_view") private var musicRaw = MusicView.songs.rawValue
    @State private var query = ""
    @State private var searching = false
    /// Chosen chip per tab: an album id, `importedChip`, or nil for All.
    @State private var chipByTab: [String: String] = [:]
    @State private var selection: [String] = []
    @State private var openGroupID: String?
    @State private var showImporter = false
    @State private var importing = false
    @State private var detail: PhoneMedia?
    @State private var collectionItems: CollectionTarget?
    @State private var deleteItem: PhoneMedia?
    @State private var toast: String?

    private static let importedChip = "imported"
    private var tab: PhoneFilesTab { PhoneFilesTab(rawValue: tabRaw) ?? .videos }
    private var sort: PhoneFilesSort { PhoneFilesSort(rawValue: sortRaw) ?? .newest }
    private var musicView: MusicView { MusicView(rawValue: musicRaw) ?? .songs }
    private var selecting: Bool { !selection.isEmpty }
    private var toTV: Bool { PhoneMediaCasting.castsToSelectedReceiver(vm) }
    /// PlayBridge receivers take lists; Google Cast, DLNA, Roku and AirPlay play one item at a time.
    /// Lists start on any connected PlayBridge receiver, playing or not (as on Android).
    private var queueEnabled: Bool { vm.localListSupported }
    private var onTvID: String? {
        guard vm.isConnected, vm.coordinator.activeContext == "player" || vm.isExternalReceiver || vm.isAirPlay else { return nil }
        return nowPlaying.itemID
    }

    private func items(_ kind: PhoneMedia.Kind) -> [PhoneMedia] { library.items.filter { $0.kind == kind } }

    private var chip: String? { chipByTab[tab.rawValue] }

    private var chips: [(id: String, title: String)] {
        let all = items(tab.kind)
        let ids = Set(all.compactMap(\.assetIdentifier))
        var result: [(String, String)] = []
        if all.contains(where: { $0.source == .imported }) { result.append((Self.importedChip, "Imported")) }
        for album in library.albums where !album.assetIDs.isDisjoint(with: ids) { result.append((album.id, album.title)) }
        return result
    }

    private var shown: [PhoneMedia] {
        var all = items(tab.kind)
        var album: Set<String>?
        if chip == Self.importedChip { all = all.filter { $0.source == .imported } }
        else if let chip, let match = library.albums.first(where: { $0.id == chip }) { album = match.assetIDs }
        return PhoneFilesModel.visible(all, album: album, query: query, sort: tab == .photos ? .newest : sort)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let group = openGroup {
                SongGroupPage(group: group, onTvID: onTvID, tvName: PhoneMediaCasting.receiverName(vm), selection: selection,
                              onPlay: queueEnabled && !selecting ? { songs, shuffle in playList(songs, shuffle: shuffle) } : nil,
                              onBack: { openGroupID = nil }, row: songRow)
            } else {
                header
                tabs.padding(.horizontal, 16).padding(.bottom, 12)
                if phoneQueue.needsAppRunning { queueNote.padding(.horizontal, 16).padding(.bottom, 12) }
                content
            }
        }
        .foregroundColor(Theme.onSurface)
        .background(Theme.surface.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) { if selecting { selectionBar } }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast).font(Theme.font(.subheadline)).foregroundColor(Theme.surface)
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(Capsule().fill(Theme.onSurface)).padding(.bottom, 16)
                    .transition(.opacity)
            }
        }
        .task { library.refreshPhotos() }
        .onChange(of: scenePhase) { phase in if phase == .active { library.refreshPhotos() } }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls):
                importing = true
                Task { await library.importFiles(urls); importing = false }
            case .failure: library.error = "Couldn’t open the selected files. Please try again."
            }
        }
        .sheet(item: $detail) { item in PhoneMediaDetail(item: item) }
        .sheet(item: $collectionItems) { target in AddPhoneMediaToCollection(items: target.items) }
        .sheet(item: $slideshow) { run in
            SlideshowSheet(run: run, seconds: $slideSeconds, shuffle: $slideShuffle, includeVideos: $slideVideos,
                           restart: { from in startSlideshow(run, from: from) },
                           onStop: { vm.control("stop"); phoneQueue.stop(); slideshow = nil })
        }
        .alert("Remove imported copy?", isPresented: Binding(get: { deleteItem != nil }, set: { if !$0 { deleteItem = nil } })) {
            Button("Remove", role: .destructive) { if let item = deleteItem { library.removeImport(item) }; deleteItem = nil }
            Button("Cancel", role: .cancel) { deleteItem = nil }
        } message: { Text("The original file stays where it was. Collection references to this copy will become unavailable.") }
        .alert("Phone files", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("OK") { library.error = nil }
        } message: { Text(library.error ?? "") }
    }

    // MARK: Header and tabs

    @ViewBuilder private var header: some View {
        HStack(spacing: 2) {
            if selecting {
                Button { selection = [] } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                    .accessibilityLabel("Cancel selection")
                Text("\(selection.count) selected").font(Theme.font(.headline))
                Spacer()
                Button("Select all") {
                    let visible = tab == .music ? PhoneFilesModel.sorted(items(.audio).filter { PhoneFilesModel.matches($0, query) }, by: sort) : shown
                    selection = selection + visible.map(\.id).filter { !selection.contains($0) }
                }.font(Theme.font(.subheadline).weight(.semibold)).padding(.trailing, 8)
            } else if searching {
                Image(systemName: "magnifyingglass").foregroundColor(Theme.onSurfaceVariant).frame(width: 44, height: 44)
                TextField("Search \(tab.title.lowercased())", text: $query).autocorrectionDisabled()
                Button("Cancel") { searching = false; query = "" }.padding(.horizontal, 8)
            } else {
                DashboardNavigationButton()
                Text("Phone files").font(Theme.font(.title2).bold())
                Spacer()
                iconButton("folder", label: "Import from Files") { showImporter = true }
                iconButton("magnifyingglass", label: "Search") { searching = true }
                Menu {
                    if tab != .photos {
                        Picker("Sort", selection: $sortRaw) {
                            ForEach(PhoneFilesSort.allCases) { Text($0.title).tag($0.rawValue) }
                        }
                    }
                    if tab == .videos {
                        Picker("View", selection: $videosAsList) { Text("Grid").tag(false); Text("List").tag(true) }
                    }
                    Button("Import from Files", systemImage: "folder") { showImporter = true }
                } label: {
                    Image(systemName: "line.3.horizontal.decrease").font(.system(size: 19)).frame(width: 44, height: 44)
                }.accessibilityLabel("Sort and view")
            }
        }
        .padding(.leading, 6).padding(.trailing, 4).frame(height: 56)
        .background(selecting ? Theme.surfaceContainerHigh : Color.clear)
        if importing {
            HStack { ProgressView(); Text("Importing…") }.font(Theme.font(.caption)).padding(.bottom, 6)
        }
    }

    private func iconButton(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: systemImage).font(.system(size: 19)).frame(width: 44, height: 44) }
            .accessibilityLabel(label)
    }

    private var tabs: some View {
        HStack(spacing: 4) {
            ForEach(PhoneFilesTab.allCases) { item in
                let selected = item == tab
                let count = items(item.kind).count
                let showCount = item == .music || library.authorization != .notDetermined || count > 0
                Button {
                    if tabRaw != item.rawValue { tabRaw = item.rawValue; selection = [] }
                } label: {
                    Text(showCount ? "\(item.title) · \(count)" : item.title)
                        .font(Theme.font(.subheadline).weight(selected ? .bold : .medium))
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .foregroundColor(selected ? Theme.onSecondaryContainer : Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(Capsule().fill(selected ? Theme.secondaryContainer : Color.clear))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
            }
        }
        .padding(4).background(Capsule().fill(Theme.surfaceContainer))
    }

    // MARK: Content

    @ViewBuilder private var content: some View {
        switch tab {
        case .videos, .photos:
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    photosAccess
                    if !chips.isEmpty { chipRow(chips.map { ($0.id as String?, $0.title) }, selected: chip) { chipByTab[tab.rawValue] = $0 } }
                    if tab == .videos {
                        HStack {
                            Text(sort.title).font(Theme.font(.footnote)).foregroundColor(Theme.onSurfaceVariant)
                            Spacer()
                            if queueEnabled && !selecting && !shown.isEmpty {
                                Button { playList(shown, shuffle: false) } label: {
                                    Label("Play all", systemImage: "play.fill").font(Theme.font(.footnote).weight(.medium))
                                        .padding(.horizontal, 14).frame(minHeight: 36).background(Capsule().fill(Theme.surfaceContainerHigh))
                                }.buttonStyle(.plain)
                            }
                        }
                        videoGrid
                    } else {
                        if queueEnabled && !selecting && !shown.isEmpty {
                            let name = chips.first { $0.id == chip }?.title
                            Button {
                                let run = SlideshowRun(title: name ?? "All photos", photos: shown, videos: slideshowVideos)
                                startSlideshow(run)
                            } label: {
                                Label("Slideshow of \(name ?? "all photos") on TV", systemImage: "play.rectangle.on.rectangle")
                                    .font(Theme.font(.body).bold()).foregroundColor(Theme.onPrimary).lineLimit(1)
                                    .frame(maxWidth: .infinity, minHeight: 48).background(Capsule().fill(Theme.primary))
                            }.buttonStyle(.plain)
                        }
                        photoGrid
                    }
                }.padding(.horizontal, 16).padding(.bottom, 16)
            }
        case .music:
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    importMusicCard
                    chipRow(MusicView.allCases.map { ($0.rawValue as String?, $0.title) }, selected: musicRaw, includeAll: false) {
                        musicRaw = $0 ?? MusicView.songs.rawValue
                    }
                    musicContent
                }.padding(.horizontal, 16).padding(.bottom, 16)
            }
        }
    }

    private var videoGrid: some View {
        let videos = shown
        return Group {
            if videos.isEmpty {
                empty(query.isEmpty ? "No videos here" : "No matches")
            } else if videosAsList {
                LazyVStack(spacing: 0) { ForEach(videos) { songRow($0, nil) } }
            } else {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 14) {
                    ForEach(videos) { video in
                        Button { open(video) } label: { videoCard(video) }
                            .buttonStyle(.plain)
                            .contextMenu { menu(video) }
                    }
                }
            }
        }
    }

    private func videoCard(_ video: PhoneMedia) -> some View {
        let order = selection.firstIndex(of: video.id).map { $0 + 1 }
        return VStack(alignment: .leading, spacing: 6) {
            PhoneMediaThumbnailView(item: video, url: library.localURL(video), size: CGSize(width: 320, height: 180))
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay(alignment: .bottomTrailing) {
                    if let duration = video.duration {
                        Text(PhoneFilesModel.clock(duration)).font(Theme.font(.caption2).monospacedDigit()).foregroundColor(.white)
                            .padding(.horizontal, 5).padding(.vertical, 2).background(.black.opacity(0.7)).cornerRadius(4).padding(6)
                    }
                }
                .overlay(alignment: .topLeading) {
                    if selecting { SelectionMarker(order: order).padding(8) }
                    else if video.id == onTvID { OnTvBadge().padding(6) }
                }
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(order != nil ? Theme.primary : .clear, lineWidth: 3))
            Text(video.title).font(Theme.font(.footnote).weight(.medium)).lineLimit(1)
            let meta = [video.source == .imported ? "Imported" : nil, PhoneFilesModel.size(video.bytes)].compactMap { $0 }
            if !meta.isEmpty { Text(meta.joined(separator: " · ")).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant) }
        }
        .contentShape(Rectangle())
    }

    private var photoGrid: some View {
        let sections = PhoneFilesModel.photoSections(shown)
        return VStack(alignment: .leading, spacing: 10) {
            if sections.isEmpty { empty(query.isEmpty ? "No photos here" : "No matches") }
            ForEach(sections) { section in
                Text(section.label.uppercased()).font(Theme.font(.caption).bold()).foregroundColor(Theme.onSurfaceVariant)
                    .padding(.top, 4)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 3), spacing: 4) {
                    ForEach(section.items) { photo in
                        let order = selection.firstIndex(of: photo.id).map { $0 + 1 }
                        let onTv = photo.id == onTvID
                        Button { open(photo) } label: {
                            Color.clear.aspectRatio(1, contentMode: .fit)
                                .overlay(PhoneMediaThumbnailView(item: photo, url: library.localURL(photo), size: CGSize(width: 256, height: 256)))
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                                .overlay(RoundedRectangle(cornerRadius: 6).stroke(onTv ? Color(red: 0.27, green: 0.83, blue: 0.51) : order != nil ? Theme.primary : .clear, lineWidth: 3))
                                .overlay(alignment: .topLeading) { if selecting { SelectionMarker(order: order).padding(6) } }
                                .overlay(alignment: .bottomLeading) { if onTv && !selecting { OnTvBadge().padding(5) } }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(onTv ? "Photo, showing on TV" : "Photo")
                        .contextMenu { menu(photo) }
                    }
                }
            }
        }
    }

    @ViewBuilder private var musicContent: some View {
        let songs = PhoneFilesModel.sorted(items(.audio).filter { PhoneFilesModel.matches($0, query) }, by: sort)
        switch musicView {
        case .songs:
            if queueEnabled && !selecting && !songs.isEmpty {
                PlayShuffleButtons(playTitle: "Play all", shuffleTitle: "Shuffle all",
                                   onPlay: { playList(songs, shuffle: false) }, onShuffle: { playList(songs, shuffle: true) })
            }
            if songs.isEmpty { empty(query.isEmpty ? "No music imported yet" : "No matches") }
            LazyVStack(spacing: 0) { ForEach(songs) { songRow($0, nil) } }
        case .albums:
            let albums = PhoneFilesModel.albums(songs)
            if albums.isEmpty { empty("No albums. Albums come from the tags in imported files.") }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
                ForEach(albums) { album in
                    Button { openGroupID = album.id } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Color.clear.aspectRatio(1, contentMode: .fit)
                                .overlay(PhoneMediaThumbnailView(item: album.songs[0], url: library.localURL(album.songs[0]), size: CGSize(width: 256, height: 256)))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Text(album.title).font(Theme.font(.subheadline).weight(.medium)).lineLimit(1)
                            Text([album.subtitle, PhoneFilesModel.songCount(album.songs.count)].compactMap { $0 }.joined(separator: " · "))
                                .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
        case .artists:
            let artists = PhoneFilesModel.artists(songs)
            if artists.isEmpty { empty("No artists. Artists come from the tags in imported files.") }
            LazyVStack(spacing: 0) {
                ForEach(artists) { artist in
                    Button { openGroupID = artist.id } label: {
                        HStack(spacing: 12) {
                            PhoneMediaThumbnailView(item: artist.songs[0], url: library.localURL(artist.songs[0]), size: CGSize(width: 96, height: 96))
                                .frame(width: 48, height: 48).clipShape(Circle())
                            VStack(alignment: .leading, spacing: 2) {
                                Text(artist.title).font(Theme.font(.body)).lineLimit(1)
                                Text(PhoneFilesModel.songCount(artist.songs.count)).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                            }
                            Spacer()
                        }.padding(.vertical, 6).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                }
            }
        }
    }

    private var openGroup: SongGroup? {
        guard tab == .music, let openGroupID else { return nil }
        let songs = items(.audio)
        return (PhoneFilesModel.albums(songs) + PhoneFilesModel.artists(songs)).first { $0.id == openGroupID }
    }

    /// A list row for songs, and for videos in list view; `number` shows the track number.
    private func songRow(_ item: PhoneMedia, _ number: Int?) -> some View {
        let order = selection.firstIndex(of: item.id).map { $0 + 1 }
        let onTv = item.id == onTvID
        let green = Color(red: 0.27, green: 0.83, blue: 0.51)
        return HStack(spacing: 12) {
            Button { open(item) } label: {
                HStack(spacing: 12) {
                    if selecting { SelectionMarker(order: order) }
                    if let number {
                        Text("\(number)").font(Theme.font(.subheadline)).foregroundColor(onTv ? green : Theme.onSurfaceVariant)
                            .frame(width: 22, alignment: .trailing)
                    } else {
                        PhoneMediaThumbnailView(item: item, url: library.localURL(item), size: CGSize(width: 160, height: 120))
                            .frame(width: item.kind == .video ? 80 : 44, height: item.kind == .video ? 45 : 44)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.title).font(Theme.font(.body)).foregroundColor(onTv ? green : Theme.onSurface).lineLimit(1)
                        let detail = onTv ? "Playing on \(PhoneMediaCasting.receiverName(vm))"
                            : [item.kind == .audio && number == nil ? item.artist : nil, item.duration.map(PhoneFilesModel.clock)]
                                .compactMap { $0 }.joined(separator: " · ")
                        if !detail.isEmpty { Text(detail).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1) }
                    }
                    Spacer(minLength: 0)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain)
            if !selecting {
                Menu { menu(item) } label: {
                    Image(systemName: "ellipsis").rotationEffect(.degrees(90)).foregroundColor(Theme.onSurfaceVariant).frame(width: 44, height: 44)
                }.accessibilityLabel("More for \(item.title)")
            }
        }
        .frame(minHeight: 60)
        .contextMenu { menu(item) }
    }

    @ViewBuilder private func menu(_ item: PhoneMedia) -> some View {
        if queueEnabled {
            Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") { send([item], .next) }
            Button("Add to queue", systemImage: "text.badge.plus") { send([item], .end) }
        }
        if toTV && item.kind != .video {
            Button("Show details", systemImage: "info.circle") { detail = item }
        }
        Button("Add to Collection", systemImage: "folder.badge.plus") { collectionItems = CollectionTarget(items: [item]) }
        if toTV && item.kind != .image {
            Button("Play on this phone", systemImage: "iphone") { detail = item }
        }
        Button("Select", systemImage: "checkmark.circle") { selection = PhoneFilesModel.toggle(selection, item.id) }
        if item.source == .imported {
            Button("Remove imported copy", systemImage: "trash", role: .destructive) { deleteItem = item }
        }
    }

    // MARK: Access and import (board 11)

    @ViewBuilder private var photosAccess: some View {
        switch library.authorization {
        case .notDetermined:
            accessCard(title: "Let PlayBridge see your \(tab == .videos ? "videos" : "photos")",
                       body: "Files stay on your iPhone. PlayBridge only plays them on the device you pick.",
                       button: "Allow access") { Task { await library.requestPhotos() } }
        case .denied, .restricted:
            accessCard(title: "Photos access is off",
                       body: "Allow PlayBridge in Settings to list your Photos library, or import files instead.",
                       button: "Open Settings") {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        case .limited:
            HStack(spacing: 10) {
                Text("Showing the photos you allowed").font(Theme.font(.footnote))
                Spacer()
                Button("Manage") { managePhotos() }.font(Theme.font(.subheadline).bold())
            }
            .padding(.leading, 14).padding(.trailing, 8).frame(minHeight: 52)
            .background(Theme.surfaceContainerHigh).cornerRadius(14)
        default:
            EmptyView()
        }
        if library.isScanning && items(tab.kind).isEmpty { ProgressView("Loading Photos…").font(Theme.font(.caption)) }
    }

    private var importMusicCard: some View {
        accessCard(title: "Add music from Files",
                   body: "Songs from the Music app can’t be played here. Audio files you import from Files, iCloud Drive or a USB drive can.",
                   button: "Import from Files") { showImporter = true }
    }

    private func accessCard(title: String, body: String, button: String, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(Theme.font(.headline))
            Text(body).font(Theme.font(.footnote)).foregroundColor(Theme.onSurfaceVariant)
            Button(action: action) {
                Text(button).font(Theme.font(.subheadline).bold()).foregroundColor(Theme.onPrimary)
                    .padding(.horizontal, 18).frame(minHeight: 44).background(Capsule().fill(Theme.primary))
            }.buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(16)
        .background(Theme.surfaceContainer).cornerRadius(20)
    }

    private func managePhotos() {
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
              var controller = scene.windows.first(where: \.isKeyWindow)?.rootViewController else { return }
        while let presented = controller.presentedViewController { controller = presented }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: controller)
    }

    // MARK: Selection (board 3)

    private var selectionBar: some View {
        let picked = selection.compactMap { library.item($0) }
        let total = picked.reduce(0) { $0 + max(0, $1.duration ?? 0) }
        return VStack(alignment: .leading, spacing: 10) {
            Text(total > 0 ? "Plays in the order you picked · \(PhoneFilesModel.clock(total)) in total" : "Plays in the order you picked")
                .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
            if queueEnabled {
                Button { playList(picked, shuffle: false); selection = [] } label: {
                    Label("Play \(picked.count) on \(PhoneMediaCasting.receiverName(vm))", systemImage: "play.fill")
                        .font(Theme.font(.body).bold()).foregroundColor(Theme.onPrimary).lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 50).background(Capsule().fill(Theme.primary))
                }.buttonStyle(.plain)
                HStack(spacing: 8) {
                    outlined("Add to queue") { send(picked, .end); selection = [] }
                    outlined("Collection") { collectionItems = CollectionTarget(items: picked); selection = [] }
                    outlined("Shuffle play") { playList(picked, shuffle: true); selection = [] }
                }
            } else {
                Button {
                    collectionItems = CollectionTarget(items: picked)
                    selection = []
                } label: {
                    Label("Add \(picked.count) to collection", systemImage: "folder.badge.plus")
                        .font(Theme.font(.body).bold()).foregroundColor(Theme.onPrimary)
                        .frame(maxWidth: .infinity, minHeight: 50).background(Capsule().fill(Theme.primary))
                }.buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 12)
        .background(Theme.surfaceContainer.ignoresSafeArea(edges: .bottom))
    }

    // MARK: Actions

    /// Videos open details; songs and photos play straight away on a TV, else open details.
    private func open(_ item: PhoneMedia) {
        if selecting { selection = PhoneFilesModel.toggle(selection, item.id); return }
        guard toTV, item.kind != .video else { detail = item; return }
        Task {
            if let failure = await PhoneMediaCasting.cast(item, library: library, vm: vm) {
                show(failure)
            } else {
                show("\(item.kind == .image ? "Showing" : "Playing") on \(PhoneMediaCasting.receiverName(vm))")
            }
        }
    }

    /// Videos from the same album (or all of them) for "Play videos in the album too".
    private var slideshowVideos: [PhoneMedia] {
        let videos = items(.video)
        if chipByTab[PhoneFilesTab.photos.rawValue] == Self.importedChip { return videos.filter { $0.source == .imported } }
        if let id = chipByTab[PhoneFilesTab.photos.rawValue], let album = library.albums.first(where: { $0.id == id }) {
            return videos.filter { $0.assetIdentifier.map(album.assetIDs.contains) ?? false }
        }
        return videos
    }

    private func outlined(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(Theme.font(.footnote).weight(.medium)).lineLimit(1).minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 44).overlay(Capsule().stroke(Theme.outlineVariant, lineWidth: 1))
                .contentShape(Capsule())
        }.buttonStyle(.plain)
    }

    private var queueNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "iphone").foregroundColor(.orange)
            Text("Keep PlayBridge running. If iOS closes it, the TV stops after the current file.")
                .font(Theme.font(.footnote))
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(12)
        .background(Color.orange.opacity(0.15)).cornerRadius(12)
    }

    private func playList(_ list: [PhoneMedia], shuffle: Bool) {
        send(shuffle ? list.shuffled() : list, .replace)
    }

    private func send(_ list: [PhoneMedia], _ mode: PhoneQueueCoordinator.Mode, displayDurationMs: Int? = nil) {
        guard !list.isEmpty else { return }
        Task {
            let outcome = await phoneQueue.send(list, mode: mode, displayDurationMs: displayDurationMs, library: library, vm: vm)
            let tv = PhoneMediaCasting.receiverName(vm)
            let count = list.count == 1 ? "1 item" : "\(list.count) items"
            switch outcome {
            case .playing: show(list.count == 1 ? "Playing on \(tv)" : "Playing \(count) on \(tv)")
            case .added: show(mode == .next ? "Plays next on \(tv)" : "Added \(count) to Up next")
            case .addedAtEnd: show("\(tv) can’t reorder its queue, so it was added at the end")
            case .failed(let message): show(message)
            }
        }
    }

    /// Builds the order from the options; `from` keeps the current order and restarts at that
    /// item instead (a new time per photo).
    private func startSlideshow(_ run: SlideshowRun, from: Int? = nil) {
        let ordered: [PhoneMedia]
        if let from, !run.ordered.isEmpty {
            ordered = Array(run.ordered[from...] + run.ordered[..<from])
        } else {
            ordered = PhoneFilesModel.slideshow(photos: run.photos, videos: run.videos, includeVideos: slideVideos,
                                                shuffle: slideShuffle, startIndex: 0, limit: .max)
        }
        var updated = run
        updated.ordered = ordered
        slideshow = updated
        send(ordered, .replace, displayDurationMs: slideSeconds * 1000)
    }

    private func show(_ message: String) {
        withAnimation { toast = message }
        Task {
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            withAnimation { if toast == message { toast = nil } }
        }
    }

    private func chipRow(_ options: [(String?, String)], selected: String?, includeAll: Bool = true,
                         onSelect: @escaping (String?) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if includeAll { pill("All", selected: selected == nil) { onSelect(nil) } }
                ForEach(options, id: \.0) { option in
                    pill(option.1, selected: selected == option.0) { onSelect(option.0) }
                }
            }
        }
    }

    private func pill(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(Theme.font(.footnote).weight(selected ? .bold : .regular)).lineLimit(1)
                .foregroundColor(selected ? Theme.onPrimary : Theme.onSurface)
                .padding(.horizontal, 14).frame(minHeight: 36)
                .background(Capsule().fill(selected ? Theme.primary : Color.clear))
                .overlay(Capsule().stroke(selected ? Color.clear : Theme.outlineVariant, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected, .isButton] : .isButton)
    }

    private func empty(_ text: String) -> some View {
        Text(text).font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
            .multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.vertical, 40)
    }
}

/// Items for the Add to Collection sheet.
struct CollectionTarget: Identifiable {
    let id = UUID()
    let items: [PhoneMedia]
}

/// Album or artist page (board 6).
private struct SongGroupPage<Row: View>: View {
    let group: SongGroup
    let onTvID: String?
    let tvName: String
    let selection: [String]
    let onPlay: (([PhoneMedia], Bool) -> Void)?
    let onBack: () -> Void
    let row: (PhoneMedia, Int?) -> Row
    @EnvironmentObject private var library: PhoneMediaLibrary

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Button(action: onBack) { Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).frame(width: 44, height: 44) }
                    .accessibilityLabel("Back to music")
                HStack(alignment: .bottom, spacing: 16) {
                    PhoneMediaThumbnailView(item: group.songs[0], url: library.localURL(group.songs[0]), size: CGSize(width: 320, height: 320))
                        .frame(width: 132, height: 132).clipShape(RoundedRectangle(cornerRadius: 16))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(group.title).font(Theme.font(.title3).bold()).lineLimit(2)
                        if let subtitle = group.subtitle { Text(subtitle).font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant) }
                        Text("\(PhoneFilesModel.songCount(group.songs.count)) · \(PhoneFilesModel.minutes(group.totalDuration))")
                            .font(Theme.font(.footnote)).foregroundColor(Theme.onSurfaceVariant)
                    }.padding(.bottom, 4)
                }
                if let onPlay {
                    PlayShuffleButtons(playTitle: "Play", shuffleTitle: "Shuffle",
                                       onPlay: { onPlay(group.songs, false) }, onShuffle: { onPlay(group.songs, true) })
                }
                LazyVStack(spacing: 0) {
                    ForEach(Array(group.songs.enumerated()), id: \.element.id) { index, song in
                        row(song, group.id.hasPrefix("album:") ? (song.trackNumber ?? index + 1) : index + 1)
                    }
                }
            }.padding(.horizontal, 16).padding(.bottom, 16)
        }
    }
}

/// Pick-order number when selected, an empty ring otherwise.
private struct SelectionMarker: View {
    let order: Int?
    var body: some View {
        if let order {
            Text("\(order)").font(Theme.font(.caption).bold()).foregroundColor(Theme.onPrimary)
                .frame(width: 26, height: 26).background(Circle().fill(Theme.primary))
        } else {
            Circle().stroke(.white, lineWidth: 2).background(Circle().fill(.black.opacity(0.3))).frame(width: 24, height: 24)
        }
    }
}

private struct OnTvBadge: View {
    var body: some View {
        Text("ON TV").font(Theme.font(.caption2).bold()).foregroundColor(Theme.onSecondaryContainer)
            .padding(.horizontal, 6).padding(.vertical, 2).background(Theme.secondaryContainer).cornerRadius(4)
    }
}

private struct PlayShuffleButtons: View {
    let playTitle: String
    let shuffleTitle: String
    let onPlay: () -> Void
    let onShuffle: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Button(action: onPlay) {
                Label(playTitle, systemImage: "play.fill").font(Theme.font(.subheadline).bold()).foregroundColor(Theme.onPrimary)
                    .frame(maxWidth: .infinity, minHeight: 44).background(Capsule().fill(Theme.primary))
            }.buttonStyle(.plain)
            Button(action: onShuffle) {
                Label(shuffleTitle, systemImage: "shuffle").font(Theme.font(.subheadline).weight(.medium))
                    .frame(maxWidth: .infinity, minHeight: 44).overlay(Capsule().stroke(Theme.outlineVariant, lineWidth: 1))
                    .contentShape(Capsule())
            }.buttonStyle(.plain)
        }
    }
}

/// A slideshow in progress: what it shows and how many items were sent.
struct SlideshowRun: Identifiable {
    let id = UUID()
    let title: String
    let photos: [PhoneMedia]
    let videos: [PhoneMedia]
    /// The order sent, so a new time per photo keeps it.
    var ordered: [PhoneMedia] = []
}

/// Slideshow controls (board 8). Changing an option resends the list.
private struct SlideshowSheet: View {
    let run: SlideshowRun
    @Binding var seconds: Int
    @Binding var shuffle: Bool
    @Binding var includeVideos: Bool
    /// Restarts the slideshow: at an item of the current order, or (nil) in a new order.
    let restart: (Int?) -> Void
    let onStop: () -> Void
    @EnvironmentObject private var vm: ConnectionViewModel
    @ObservedObject private var phoneQueue = PhoneQueueCoordinator.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Slideshow").font(Theme.font(.title3).bold())
                Text("\(run.title) on \(PhoneMediaCasting.receiverName(vm))").font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
            }
            if phoneQueue.active, phoneQueue.count > 0 {
                Text("\(phoneQueue.position + 1) of \(phoneQueue.count)").font(Theme.font(.headline)).frame(maxWidth: .infinity)
            }
            HStack(spacing: 20) {
                control("backward.fill", label: "Previous photo") { phoneQueue.step(-1) }
                control(vm.coordinator.playback?.state == "paused" ? "play.fill" : "pause.fill", label: "Pause slideshow", primary: true) {
                    vm.control(vm.coordinator.playback?.state == "paused" ? "play" : "pause")
                }
                control("forward.fill", label: "Next photo") { phoneQueue.step(1) }
            }.frame(maxWidth: .infinity)
            VStack(spacing: 0) {
                HStack {
                    Text("Each photo")
                    Spacer()
                    Picker("Time per photo", selection: Binding(get: { seconds }, set: { seconds = $0; restart(phoneQueue.active ? phoneQueue.position : 0) })) {
                        ForEach([3, 5, 10], id: \.self) { Text("\($0) s").tag($0) }
                    }.pickerStyle(.segmented).frame(width: 170)
                }.frame(minHeight: 52)
                Toggle("Shuffle", isOn: Binding(get: { shuffle }, set: { shuffle = $0; restart(nil) })).frame(minHeight: 52)
                if !run.videos.isEmpty {
                    Toggle("Play videos in the album too", isOn: Binding(get: { includeVideos }, set: { includeVideos = $0; restart(nil) }))
                        .frame(minHeight: 52)
                }
            }
            .font(Theme.font(.body)).padding(.horizontal, 14).background(Theme.surfaceContainerHigh).cornerRadius(16)
            Button(role: .destructive, action: onStop) {
                Text("Stop slideshow").font(Theme.font(.body).bold()).frame(maxWidth: .infinity, minHeight: 44)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .foregroundColor(Theme.onSurface)
        .background(Theme.surfaceContainer.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func control(_ systemImage: String, label: String, primary: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: primary ? 24 : 20))
                .foregroundColor(primary ? Theme.onPrimary : Theme.onSurface)
                .frame(width: primary ? 64 : 52, height: primary ? 64 : 52)
                .background(Circle().fill(primary ? Theme.primary : Theme.surfaceContainerHigh))
        }
        .buttonStyle(.plain).accessibilityLabel(label)
    }
}
