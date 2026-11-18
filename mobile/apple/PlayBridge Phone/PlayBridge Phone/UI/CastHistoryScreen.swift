import SwiftUI

struct CastHistoryScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel

    var body: some View {
        CastHistoryList(store: vm.castHistory)
            .environmentObject(vm)
            .environmentObject(nav)
    }
}

/// Cast history, newest first under Today, Yesterday and dates. Port of Android's redesign (#545):
/// a preview picture, the site and device, when it was cast, and whether its link has expired.
private struct CastHistoryList: View {
    @ObservedObject var store: CastHistoryStore
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var browser: BrowserStore
    @AppStorage(CastHistorySettings.enabledKey) private var enabled = true
    @State private var filter = CastHistoryFilter.all
    @State private var search = ""
    @State private var selected: CastHistoryStore.Entry?
    @State private var replaying = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            TimelineView(.periodic(from: .now, by: 60)) { context in
                list(now: context.date)
            }
            .font(Theme.font(.body))
            .searchable(text: $search, prompt: "Search casts")
            .navigationTitle("Cast history")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { DashboardNavigationButton() }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Cast history settings")
                }
            }
            .sheet(isPresented: $showSettings) {
                NavigationStack {
                    CastHistorySettingsForm(store: store)
                        .navigationTitle("Cast history").navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } } }
                }
            }
        }
    }

    private func visible(now: Date) -> [CastHistoryStore.Entry] {
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return store.entries.filter { entry in
            filter.matches(entry, now: now) && (query.isEmpty ||
                [entry.title, entry.host, entry.receiver ?? ""].contains { $0.localizedCaseInsensitiveContains(query) })
        }
    }

    @ViewBuilder
    private func list(now: Date) -> some View {
        let rows = visible(now: now)
        List {
            if !enabled {
                Section {
                    Label("Cast history is off. New casts aren't remembered.", systemImage: "pause.circle")
                        .font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
                }
            }
            if let error = store.persistenceError { Text(error).foregroundStyle(Theme.danger) }
            if !store.entries.isEmpty {
                Picker("Show", selection: $filter) {
                    ForEach(CastHistoryFilter.allCases) { option in
                        Text("\(option.rawValue) \(store.entries.filter { option.matches($0, now: now) }.count)").tag(option)
                    }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
            }
            if store.entries.isEmpty {
                Text("Videos you cast appear here, with where they played, so you can cast them again.")
                    .foregroundStyle(Theme.onSurfaceVariant)
            } else if rows.isEmpty {
                Text(search.isEmpty ? "Nothing here." : "No casts match.").foregroundStyle(Theme.onSurfaceVariant)
            }
            ForEach(CastHistoryPresentation.groupByDay(rows, now: now), id: \.label) { group in
                Section(group.label) {
                    ForEach(group.entries) { entry in
                        Button { open(entry) } label: {
                            CastHistoryRow(entry: entry, preview: store.previewURL(entry), now: now,
                                           playing: vm.castNowPlaying?.id == entry.id ? vm.castNowPlaying : nil,
                                           checking: store.checking.contains(entry.id))
                        }
                        .buttonStyle(.plain)
                        .disabled(replaying)
                        .swipeActions {
                            Button("Remove", role: .destructive) { store.delete(entry.id) }
                        }
                    }
                }
            }
        }
        .confirmationDialog(dialogTitle(now: now), isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }),
                            titleVisibility: .visible, presenting: selected) { entry in
            actions(entry, now: now)
        } message: { entry in
            Text(dialogMessage(entry, now: now))
        }
    }

    private func dialogTitle(now: Date) -> String {
        guard let selected else { return "" }
        if case .expired(let at) = selected.linkStatus(now: now) { return CastHistoryPresentation.expiredNotice(at, now: now) }
        return CastHistoryPresentation.title(selected)
    }

    private func dialogMessage(_ entry: CastHistoryStore.Entry, now: Date) -> String {
        if entry.linkStatus(now: now).isExpired {
            return entry.pageURL == nil ? "The page it came from isn't known, so only the old link can be tried."
                : "Open the page to find the video again with a fresh link."
        }
        var lines = [CastHistoryPresentation.subtitle(entry), CastHistoryPresentation.stoppedDetail(entry)]
        if entry.canResume && canCast && !vm.resumeStartsPartWay {
            lines.append("\(targetName.prefix(1).uppercased() + targetName.dropFirst()) can't start part-way, so Resume plays from the start.")
        }
        return lines.joined(separator: "\n")
    }

    private var canCast: Bool { vm.isConnected || vm.partyTargetActive }

    private var targetName: String {
        vm.partyTargetActive ? "the watch party" : (vm.receiverName ?? "the connected device")
    }

    @ViewBuilder
    private func actions(_ entry: CastHistoryStore.Entry, now: Date) -> some View {
        let expired = entry.linkStatus(now: now).isExpired
        if expired, let page = entry.pageURL {
            Button("Open the page to get a fresh link") { open(page) }
        }
        if vm.castNowPlaying?.id == entry.id {
            Button("Open the remote") { nav.navigate(to: .remote) }
        }
        if canCast {
            if !expired && entry.canResume {
                Button(CastHistoryPresentation.resumeLabel(entry)) { replay(entry, resume: true) }
            }
            let again = entry.status == .failed ? "Try again" : entry.canResume ? "Play from the start" : "Cast again"
            Button(expired ? "Try the old link" : "\(again) on \(targetName)") { replay(entry) }
        } else {
            Button("Choose a device to cast to") { nav.openPlayOn() }
        }
        if !expired, let page = entry.pageURL {
            Button("Open the page") { open(page) }
        }
        if !store.checking.contains(entry.id) {
            Button(entry.linkCheck == nil ? "Check the link" : "Check the link again") { Task { await store.checkLink(entry.id) } }
        }
        Button("Remove", role: .destructive) { store.delete(entry.id) }
    }

    /// With link checks on, a cast's link is checked when it's opened, at most every 10 minutes.
    private func open(_ entry: CastHistoryStore.Entry) {
        selected = entry
        let stale = entry.linkCheckedAt.map { Date().timeIntervalSince($0) > 600 } ?? true
        if store.settings.checkLinks, stale { Task { await store.checkLink(entry.id) } }
    }

    private func replay(_ entry: CastHistoryStore.Entry, resume: Bool = false) {
        replaying = true
        Task {
            await vm.replayCast(entry, resume: resume)
            replaying = false
            if vm.isConnected && !vm.partyTargetActive && vm.operationError == nil { nav.navigate(to: .remote) }
        }
    }

    private func open(_ page: String) {
        _ = browser.newTab(loading: page)
        nav.openBrowser()
    }
}

/// One cast: its picture, title, site and device, when it was cast and what its link says.
private struct CastHistoryRow: View {
    let entry: CastHistoryStore.Entry
    let preview: URL?
    let now: Date
    /// Set when this cast is playing on a receiver now.
    let playing: NowPlaying?
    let checking: Bool

    private var link: LinkStatus { entry.linkStatus(now: now, checking: checking) }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            tile
            VStack(alignment: .leading, spacing: 3) {
                Text(CastHistoryPresentation.title(entry))
                    .font(Theme.font(.subheadline).weight(.semibold))
                    .foregroundColor(Theme.onSurface)
                    .lineLimit(2)
                if let playing {
                    Label(CastHistoryPresentation.playingLine(entry) + (playing.live ? "" : " · \(CastHistoryPresentation.clock(playing.positionMs))"),
                          systemImage: playing.paused ? "pause.fill" : "play.fill")
                        .font(Theme.font(.caption).weight(.semibold)).foregroundColor(Theme.primary).lineLimit(1)
                } else {
                    Text(CastHistoryPresentation.subtitle(entry))
                        .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                    Text(CastHistoryPresentation.status(entry, now: now))
                        .font(Theme.font(.caption))
                        .foregroundColor(entry.status == .failed ? Theme.danger : Theme.onSurfaceVariant)
                }
                if playing == nil, let label = link.label(now: now) {
                    Label(label, systemImage: link.isExpired ? "link.badge.plus" : link == .checking ? "arrow.triangle.2.circlepath" : "clock")
                        .font(Theme.font(.caption))
                        .foregroundColor(link.isExpired ? Theme.danger : link == .checking ? Theme.onSurfaceVariant : .orange)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(playing.map { _ in "\(CastHistoryPresentation.title(entry)). \(CastHistoryPresentation.playingLine(entry))" }
                            ?? CastHistoryPresentation.description(entry, now: now))
        .accessibilityHint("Shows what you can do with this cast")
    }

    private var tile: some View {
        ZStack(alignment: .bottomTrailing) {
            if let preview, let image = UIImage(contentsOfFile: preview.path) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                LinearGradient(colors: [Theme.surfaceContainerHigh, Theme.surfaceContainerHighest],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                    .overlay(Image(systemName: symbol).font(.system(size: 20)).foregroundColor(Theme.onSurfaceVariant))
            }
            if let badge = CastHistoryPresentation.badge(entry) {
                Text(badge)
                    .font(Theme.font(size: 10, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 4).padding(.vertical, 1)
                    .background(Color.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 3))
                    .padding(4)
            }
        }
        .frame(width: 96, height: 54)
        .overlay(alignment: .bottom) {
            if let fraction = playingFraction ?? entry.progressFraction, fraction > 0 {
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(Color.black.opacity(0.5))
                        Rectangle().fill(Theme.primary).frame(width: proxy.size.width * fraction)
                    }
                }
                .frame(height: 3)
                .accessibilityHidden(true)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private var playingFraction: Double? {
        guard let playing, !playing.live, playing.durationMs > 0 else { return nil }
        return min(1, Double(playing.positionMs) / Double(playing.durationMs))
    }

    private var symbol: String {
        switch entry.mediaKind {
        case "audio": return "music.note"
        case "image": return "photo"
        default: return "play.rectangle"
        }
    }
}
