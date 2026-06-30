import SwiftUI

struct IptvDetailScreen: View {
    let playlistId: UUID

    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var iptv: IptvStore

    @State private var search = ""
    @State private var isRefreshing = false
    @State private var toast: String?
    @State private var pendingAdd: CollectionDraft?

    private var playlist: IptvPlaylist? { iptv.playlist(playlistId) }

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.surface.ignoresSafeArea()

            if let playlist {
                VStack(spacing: 12) {
                    header(playlist)
                    searchField
                    channelList(playlist)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
            } else {
                Text("Playlist not found.").foregroundColor(Theme.onSurfaceVariant)
            }

            if let toast {
                Text(toast)
                    .font(.system(size: 13, weight: .medium)).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Theme.primaryDim))
                    .padding(.bottom, 24)
            }
        }
        .sheet(item: $pendingAdd) { draft in
            AddToCollectionSheet(draft: draft)
        }
    }

    private func header(_ pl: IptvPlaylist) -> some View {
        HStack(spacing: 12) {
            Button { nav.navigate(to: .iptv) } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(pl.name).font(.system(size: 20, weight: .bold)).foregroundColor(Theme.onSurface).lineLimit(1)
                Text("\(pl.channelCount) channels").font(.system(size: 12)).foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button { refresh() } label: {
                if isRefreshing { ProgressView() }
                else { Image(systemName: "arrow.clockwise").font(.system(size: 17, weight: .semibold)).foregroundColor(Theme.primary) }
            }
            .disabled(isRefreshing)
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundColor(Theme.onSurfaceVariant)
            TextField("Search channels", text: $search)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .foregroundColor(Theme.onSurface)
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainer))
    }

    private func channelList(_ pl: IptvPlaylist) -> some View {
        let groups = grouped(pl.channels)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 6, pinnedViews: [.sectionHeaders]) {
                ForEach(groups, id: \.0) { group in
                    Section {
                        ForEach(group.1) { ch in channelRow(ch) }
                    } header: {
                        Text(group.0)
                            .font(.system(size: 12, weight: .bold)).foregroundColor(Theme.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 6).padding(.horizontal, 4)
                            .background(Theme.surface.opacity(0.95))
                    }
                }
            }
            .padding(.bottom, 80)
        }
    }

    private func channelRow(_ ch: IptvChannel) -> some View {
        Button { cast(ch) } label: {
            HStack(spacing: 10) {
                Image(systemName: "play.tv")
                    .font(.system(size: 15)).foregroundColor(Theme.primary).frame(width: 24)
                Text(ch.name).font(.system(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                Spacer()
            }
            .padding(.vertical, 9).padding(.horizontal, 8)
            .background(RoundedRectangle(cornerRadius: 10).fill(Theme.surfaceContainer.opacity(0.5)))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { cast(ch) } label: { Label("Cast", systemImage: "play.tv.fill") }
            Button {
                pendingAdd = CollectionDraft(title: ch.name, url: ch.url, headers: ch.headers, logo: ch.logo, sourceTag: "iptv")
            } label: { Label("Add to Collection", systemImage: "plus.rectangle.on.folder") }
        }
    }

    // MARK: - Logic

    private func grouped(_ channels: [IptvChannel]) -> [(String, [IptvChannel])] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        let filtered = q.isEmpty ? channels : channels.filter { $0.name.lowercased().contains(q) }
        let dict = Dictionary(grouping: filtered) { $0.groupTitle?.isEmpty == false ? $0.groupTitle! : "Uncategorized" }
        return dict.sorted { $0.key.localizedCaseInsensitiveCompare($1.key) == .orderedAscending }
            .map { ($0.key, $0.value.sorted { $0.order < $1.order }) }
    }

    private func cast(_ ch: IptvChannel) {
        guard vm.isConnected else { showToast("Not connected — open Connection first"); return }
        vm.castMedia(url: ch.url, title: ch.name, headers: ch.headers)
        showToast("Casting \(ch.name)")
    }

    private func refresh() {
        isRefreshing = true
        Task {
            do { try await iptv.refresh(playlistId); showToast("Updated") }
            catch { showToast(error.localizedDescription) }
            isRefreshing = false
        }
    }

    private func showToast(_ text: String) {
        toast = text
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if toast == text { toast = nil }
        }
    }
}
