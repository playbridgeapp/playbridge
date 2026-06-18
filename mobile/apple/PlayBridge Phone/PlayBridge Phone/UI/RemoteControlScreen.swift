import SwiftUI

/// Main screen once connected: now-playing sync, cast-a-URL, the TV playlist, and the remote.
struct RemoteControlScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var castURL: String = ""

    private var serverName: String {
        if case .connected(let name, _) = vm.state { return name }
        return vm.pairedDevice?.name ?? "TV"
    }

    private var isSecure: Bool {
        if case .connected(_, let secure) = vm.state { return secure }
        return false
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                topBar
                nowPlayingCard
                castCard
                playlistCard
                RemoteControlView()
            }
            .padding(20)
        }
        .background(Theme.surface.ignoresSafeArea())
    }

    // MARK: - Top bar

    private var topBar: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(serverName).font(.title3.bold()).foregroundColor(Theme.onSurface)
                    if isSecure { Image(systemName: "lock.fill").font(.caption).foregroundColor(Theme.primary) }
                }
                Text(statusLine).font(.caption).foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button {
                nav.navigate(to: nav.remoteOrigin ?? nav.lastMainScreen)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.title2).foregroundColor(Theme.onSurfaceVariant)
            }
        }
        .padding(.top, 8)
    }

    private var statusLine: String {
        switch vm.coordinator.activeContext {
        case "player": return "Now playing"
        case "browser": return "Browsing"
        default: return "Connected · idle"
        }
    }

    // MARK: - Now playing

    @ViewBuilder private var nowPlayingCard: some View {
        if let p = vm.coordinator.playback {
            VStack(alignment: .leading, spacing: 12) {
                Text(p.title ?? "Untitled")
                    .font(.headline).foregroundColor(Theme.onSurface)
                    .lineLimit(2)
                ProgressView(value: progress(p))
                    .tint(Theme.primary)
                HStack {
                    Text(format(ms: p.positionMs)).foregroundColor(Theme.onSurfaceVariant)
                    Spacer()
                    Text(p.state.capitalized).foregroundColor(Theme.primary)
                    Spacer()
                    Text(format(ms: p.durationMs)).foregroundColor(Theme.onSurfaceVariant)
                }
                .font(.caption)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surfaceContainer)
            .cornerRadius(16)
        }
    }

    private func progress(_ p: TvPlaybackStatus) -> Double {
        guard p.durationMs > 0 else { return 0 }
        return min(1, max(0, Double(p.positionMs) / Double(p.durationMs)))
    }

    // MARK: - Cast

    private var castCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("Cast a link")
            HStack {
                TextField("Paste a video URL (mp4, m3u8, …)", text: $castURL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .foregroundColor(Theme.onSurface)
                    .padding(12)
                    .background(Theme.surfaceContainerLow)
                    .cornerRadius(12)
                Button {
                    vm.cast(urlString: castURL)
                    castURL = ""
                } label: {
                    Image(systemName: "play.tv.fill").padding(.horizontal, 4)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.primaryDim)
                .disabled(castURL.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    // MARK: - Playlist

    @ViewBuilder private var playlistCard: some View {
        if let pl = vm.coordinator.playlist, pl.items.count > 1 {
            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("Playlist (\(pl.currentIndex + 1)/\(pl.totalCount))")
                ForEach(pl.items) { item in
                    Button { vm.jump(toIndex: item.index) } label: {
                        HStack {
                            Image(systemName: item.index == pl.currentIndex ? "play.circle.fill" : "circle")
                                .foregroundColor(item.index == pl.currentIndex ? Theme.primary : Theme.onSurfaceVariant)
                            Text(item.title).foregroundColor(Theme.onSurface).lineLimit(1)
                            Spacer()
                        }
                        .padding(10)
                        .background(item.index == pl.currentIndex ? Theme.surfaceContainerHigh : Theme.surfaceContainerLow)
                        .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Helpers

    private func sectionTitle(_ text: String) -> some View {
        Text(text.uppercased()).font(.caption.bold()).foregroundColor(Theme.onSurfaceVariant)
    }

    private func format(ms: Int64) -> String {
        let totalSeconds = Int(ms / 1000)
        let h = totalSeconds / 3600
        let m = (totalSeconds % 3600) / 60
        let s = totalSeconds % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
