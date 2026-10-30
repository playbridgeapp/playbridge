import Photos
import SwiftUI

struct DashboardScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var iptv: IptvStore
    @EnvironmentObject private var collections: CollectionsStore
    @EnvironmentObject private var library: PhoneMediaLibrary
    @State private var appToInspect: BridgedApp?
    @State private var showCustomize = false
    @AppStorage("dashboard_tile_order") private var savedOrder = "[]"
    @AppStorage("dashboard_hidden_tiles") private var savedHidden = "[]"
    @ScaledMetric(relativeTo: .body) private var largeTileHeight = 140.0
    @ScaledMetric(relativeTo: .body) private var smallTileHeight = 112.0

    /// Socket security only counts while playback is aimed at the PlayBridge receiver.
    private var playOnDashboard: PlayOnDashboardStatus {
        let route: PlayOnRoute
        if vm.isExternalReceiver {
            route = .external
        } else if vm.isAirPlay {
            route = .airPlay
        } else if vm.playBridgeOutputSelected {
            route = .playBridge
        } else {
            route = .thisPhone
        }
        let socketSecure: Bool
        if vm.playBridgeOutputSelected, case .connected(_, let secure) = vm.state {
            socketSecure = secure
        } else {
            socketSecure = false
        }
        let connecting: Bool
        let reconnecting: Bool
        switch vm.state {
        case .connecting, .waitingForApproval, .waitingForCodeInput, .verifyingCode:
            connecting = true; reconnecting = false
        case .retrying:
            connecting = false; reconnecting = true
        default:
            connecting = false; reconnecting = false
        }
        return PlayOnCatalog.dashboardStatus(
            route: route,
            socketConnected: vm.playBridgeLinkConnected,
            nativeName: vm.pairedDevice?.name,
            externalName: vm.isAirPlay ? vm.airPlay.routeName : vm.externalReceiver?.name,
            externalReady: vm.isExternalReceiver && vm.isConnected,
            airPlayReady: vm.isAirPlay && vm.airPlay.routeAvailable,
            socketSecure: socketSecure,
            externalProtocol: vm.externalReceiver?.protocolName,
            connecting: connecting,
            reconnecting: reconnecting
        )
    }

    private func isSource(_ destination: AppScreen) -> Bool {
        switch (nav.dashboardSource, destination) {
        case (.iptvDetail(_), .iptv), (.collectionDetail(_), .collections): return true
        case (let source?, _): return source == destination
        case (nil, _): return false
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                topBar
                DashboardDeviceChip(status: playOnDashboard) { nav.openPlayOn() }
                // A dropped TV stays chosen; switching to this phone is the user's call.
                if playOnDashboard.state == .reconnecting || playOnDashboard.state == .offline {
                    LostTVNotice(tvName: playOnDashboard.deviceName) { vm.selectThisPhone() }
                }
                HStack {
                    Text("WATCH")
                        .font(Theme.font(size: 12, weight: .bold))
                        .tracking(0.8)
                        .foregroundColor(Theme.onSurfaceVariant)
                    Spacer()
                    Button("Customize") { showCustomize = true }
                        .font(Theme.font(.subheadline))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("dashboard-reorder")
                }
                tileGrid(visibleTiles)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .background(Theme.surface.ignoresSafeArea())
        .onAppear { normalizeOrder() }
        .onChange(of: availableTiles.map(\.id)) { _ in normalizeOrder() }
        .sheet(item: $appToInspect) { app in
            BridgedAppInfoView(app: app)
        }
        .fullScreenCover(isPresented: $showCustomize) {
            DashboardCustomizeView(
                tiles: orderedTiles,
                hidden: hidden,
                onMove: { saveOrder($0) },
                onToggle: setHidden,
                onReset: {
                    saveOrder(availableTiles.map(\.id))
                    savedHidden = "[]"
                }
            )
        }
    }

    // MARK: - Components

    private var topBar: some View {
        HStack(spacing: 4) {
            Image("Logo")
                .resizable().scaledToFit()
                .frame(width: 30, height: 30)
                .clipShape(Circle())
                .accessibilityHidden(true)
            Text("PlayBridge")
                .font(Theme.font(size: 22, weight: .bold, design: .rounded))
                .foregroundColor(Theme.onSurface)
                .padding(.leading, 6)
            Spacer()
            // Always shown: the Remote picks its own TV, even when videos play on this phone.
            topBarButton("av.remote", label: "Open remote") { nav.navigate(to: .remote) }
                .accessibilityIdentifier("dashboard-remote")
            topBarButton("gearshape", label: "Settings") { nav.navigate(to: .browserSettings) }
        }
        .frame(height: 52)
    }

    private func topBarButton(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 19, weight: .semibold))
                .foregroundColor(Theme.onSurface)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private var hidden: Set<String> { Set(DashboardTileOrder.decode(savedHidden)) }

    private func setHidden(_ id: String, _ hide: Bool) {
        guard !DashboardTileOrder.alwaysShown.contains(id) else { return }
        var ids = hidden
        if hide { ids.insert(id) } else { ids.remove(id) }
        savedHidden = DashboardTileOrder.encode(ids.sorted())
    }

    private var mediaSubtitle: String {
        switch library.authorization {
        case .authorized, .limited: return "Videos & photos"
        default: return "Allow access"
        }
    }

    private var lastCastSubtitle: String {
        let entries = vm.castHistory.entries
        guard let newest = entries.max(by: { $0.date < $1.date }) else { return "Nothing yet" }
        let title = newest.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? "Recent casts" : "Last: \(title)"
    }

    private var availableTiles: [DashboardTile] {
        var tiles = [
            DashboardTile(id: DashboardTileOrder.browserID, title: "Browser", subtitle: "Find videos on the web", systemImage: "globe",
                          gradient: [Color(hex: 0x0D47A1), Color(hex: 0x1565C0)], isOpen: isSource(.browser),
                          action: { nav.openBrowser() }),
            DashboardTile(id: "phone-files", title: "Media Library", subtitle: mediaSubtitle, systemImage: "folder",
                          gradient: [Color(hex: 0x311B92), Color(hex: 0x4527A0)], isOpen: isSource(.phoneFiles),
                          action: { nav.navigate(to: .phoneFiles) }),
            DashboardTile(id: "iptv", title: "Playlists", subtitle: "Play your own M3U playlists",
                          systemImage: "tv.fill",
                          gradient: [Color(hex: 0xA3300B), Color(hex: 0xBF360C)], isOpen: isSource(.iptv),
                          action: { nav.navigate(to: .iptv) }),
            DashboardTile(id: "collections", title: "Collections",
                          subtitle: Self.count(collections.collections.count, "collection", empty: "Create your first"),
                          systemImage: "play.rectangle.fill",
                          gradient: [Color(hex: 0x7B0D45), Color(hex: 0x9C1155)], isOpen: isSource(.collections),
                          action: { nav.navigate(to: .collections) }),
            DashboardTile(id: "cast-history", title: "Cast History", subtitle: lastCastSubtitle, systemImage: "clock.arrow.circlepath",
                          gradient: [Color(hex: 0x4E342E), Color(hex: 0x6D4C41)], isOpen: isSource(.castHistory),
                          action: { nav.navigate(to: .castHistory) }),
        ]
        if store.bridgedApps.apps.isEmpty {
            tiles.append(DashboardTile(
                id: DashboardTileOrder.addWebAppID, title: "Add web app", subtitle: "From the Browser", systemImage: "plus",
                gradient: [], isOpen: false,
                action: { nav.openBrowser() }
            ))
        } else {
            tiles += store.bridgedApps.apps.map { app in
                DashboardTile(
                    id: "app:\(app.origin.absoluteString)", title: app.name, subtitle: "Web app",
                    systemImage: "app.connected.to.app.below.fill",
                    gradient: [Color(hex: 0x263238), Color(hex: 0x37474F)],
                    isOpen: store.activeBridgedApp?.origin == app.origin,
                    app: app,
                    action: { if store.openBridgedApp(app) != nil { nav.navigate(to: .browser) } }
                )
            }
        }
        return tiles
    }

    static func count(_ value: Int, _ noun: String, empty: String) -> String {
        switch value {
        case 0: return empty
        case 1: return "1 \(noun)"
        default: return "\(value) \(noun)s"
        }
    }

    private var orderedTiles: [DashboardTile] {
        let available = availableTiles
        let byID = Dictionary(uniqueKeysWithValues: available.map { ($0.id, $0) })
        return DashboardTileOrder.reconcile(saved: DashboardTileOrder.decode(savedOrder), available: available.map(\.id))
            .compactMap { byID[$0] }
    }

    private var visibleTiles: [DashboardTile] {
        let ordered = orderedTiles
        let ids = Set(DashboardTileOrder.visible(ordered.map(\.id), hidden: hidden))
        return ordered.filter { ids.contains($0.id) }
    }

    private func saveOrder(_ ids: [String]) {
        savedOrder = DashboardTileOrder.encode(ids)
    }

    private func normalizeOrder() {
        saveOrder(orderedTiles.map(\.id))
    }

    /// Two large tiles, then rows of three; the whole Dashboard scrolls instead of paging.
    private func tileGrid(_ tiles: [DashboardTile]) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                ForEach(Array(tiles.prefix(2))) { tile in tileView(tile, tall: true) }
                if tiles.count == 1 { Color.clear.frame(maxWidth: .infinity) }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
                ForEach(Array(tiles.dropFirst(2))) { tile in tileView(tile, tall: false) }
            }
        }
    }

    private func tileView(_ tile: DashboardTile, tall: Bool) -> some View {
        let card = DashboardTileCard(tile: tile, tall: tall, height: tall ? largeTileHeight : smallTileHeight)
            .frame(maxWidth: .infinity)
        return Group {
            if let app = tile.app {
                card.highPriorityGesture(LongPressGesture(minimumDuration: 0.6).onEnded { _ in
                    appToInspect = app
                })
                .accessibilityAction(named: "App Info") { appToInspect = app }
            } else {
                card.contextMenu {
                    Button { showCustomize = true } label: {
                        Label("Customize home", systemImage: "slider.horizontal.3")
                    }
                }
            }
        }
        .accessibilityLabel((tile.app == nil ? "\(tile.title), \(tile.subtitle)" : "\(tile.title), Web app")
                            + (tile.isOpen ? ", open" : ""))
        .accessibilityIdentifier(tile.app.map { "bridged-app-\($0.origin.absoluteString)" } ?? "dashboard-\(tile.id)")
    }
}

struct DashboardTile: Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let systemImage: String
    /// Empty for the dashed "Add web app" placeholder.
    let gradient: [Color]
    /// The screen the Dashboard was opened from; tapping returns there.
    let isOpen: Bool
    var app: BridgedApp? = nil
    let action: () -> Void

    var isAddPlaceholder: Bool { id == DashboardTileOrder.addWebAppID }
}

/// The original gradient tile: sheen, decorative circles and a frosted icon box.
private struct DashboardTileCard: View {
    let tile: DashboardTile
    let tall: Bool
    let height: Double

    var body: some View {
        Button(action: tile.action) {
            ZStack(alignment: .topTrailing) {
                if tile.isAddPlaceholder {
                    RoundedRectangle(cornerRadius: 20)
                        .strokeBorder(Theme.outlineVariant, style: StrokeStyle(lineWidth: 1, dash: [6, 5]))
                } else {
                    RoundedRectangle(cornerRadius: 20)
                        .fill(LinearGradient(colors: tile.gradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                    RoundedRectangle(cornerRadius: 20)
                        .fill(RadialGradient(colors: [Color.white.opacity(0.16), Color.clear],
                                             center: .topLeading, startRadius: 0, endRadius: 150))
                    RoundedRectangle(cornerRadius: 20)
                        .strokeBorder(Color.white.opacity(0.12), lineWidth: 1)
                    Circle()
                        .fill(Color.white.opacity(0.08))
                        .frame(width: 80, height: 80)
                        .offset(x: 15, y: -15)
                }

                VStack(alignment: .leading, spacing: 0) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(tile.isAddPlaceholder ? Theme.surfaceContainerHigh : Color.white.opacity(0.2))
                            .frame(width: 40, height: 40)
                        if let iconURL = tile.app?.iconURL {
                            AsyncImage(url: iconURL) { image in
                                image.resizable().scaledToFit().frame(width: 32, height: 32)
                            } placeholder: {
                                Image(systemName: tile.systemImage).foregroundColor(.white)
                            }
                        } else {
                            Image(systemName: tile.systemImage)
                                .font(Theme.font(size: 20))
                                .foregroundColor(tile.isAddPlaceholder ? Theme.onSurfaceVariant : .white)
                        }
                    }
                    Spacer()
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tile.title)
                            .font(Theme.font(size: tall ? 17 : 14, weight: .semibold))
                            .foregroundColor(tile.isAddPlaceholder ? Theme.onSurface : .white)
                            .lineLimit(1)
                        Text(tile.subtitle)
                            .font(Theme.font(size: tall ? 13 : 12))
                            .foregroundColor(tile.isAddPlaceholder ? Theme.onSurfaceVariant : Color.white.opacity(0.86))
                            .lineLimit(1)
                    }
                }
                .padding(tall ? 16 : 12)
                .frame(maxWidth: .infinity, alignment: .leading)

                if tile.isOpen {
                    // Small tiles keep just the dot so the badge doesn't crowd the icon.
                    HStack(spacing: 5) {
                        Circle().fill(Color.white).frame(width: 6, height: 6)
                        if tall {
                            Text("OPEN")
                                .font(Theme.font(size: 11, weight: .bold))
                                .foregroundColor(.white)
                        }
                    }
                    .padding(.horizontal, tall ? 8 : 6)
                    .padding(.vertical, tall ? 3 : 6)
                    .background(Color.black.opacity(0.28))
                    .clipShape(Capsule())
                    .padding(tall ? 12 : 10)
                    .accessibilityHidden(true)
                }
            }
            .frame(height: height)
            .clipShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
    }
}

/// Shown while the chosen TV is unreachable. The route never changes by itself.
private struct LostTVNotice: View {
    let tvName: String
    let useThisPhone: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(Theme.surface)
                .accessibilityHidden(true)
            Text("Lost \(tvName)")
                .font(Theme.font(size: 14))
                .foregroundColor(Theme.surface)
                .lineLimit(1)
            Spacer(minLength: 0)
            Button("Use this phone", action: useThisPhone)
                .font(Theme.font(size: 14, weight: .bold))
                .foregroundColor(Theme.primaryDim)
                .frame(minHeight: 44)
                .accessibilityIdentifier("dashboard-use-this-phone")
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .background(RoundedRectangle(cornerRadius: 12).fill(Theme.onSurface))
        .accessibilityElement(children: .combine)
    }
}

/// Where videos play: a chosen TV or this phone. Opens the Play on sheet.
struct DashboardDeviceChip: View {
    let status: PlayOnDashboardStatus
    let action: () -> Void

    private var accent: Color? {
        switch status.state {
        case .connected: return status.unencrypted ? Color(hex: 0xFFB74D) : Color(hex: 0x46D483)
        case .connecting, .reconnecting: return Color(hex: 0xFFB74D)
        case .offline: return Theme.danger
        case .thisPhone: return nil
        }
    }

    private var systemImage: String {
        if !status.tvChosen { return "iphone" }
        switch status.receiver {
        case "AirPlay": return "airplayvideo"
        case nil, "PlayBridge": return "tv"
        default: return "dot.radiowaves.left.and.right"
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    Circle()
                        .fill(accent?.opacity(0.15) ?? Theme.surfaceContainerHighest)
                        .frame(width: 40, height: 40)
                    Image(systemName: systemImage)
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(accent ?? Theme.onSurfaceVariant)
                        .frame(width: 40, height: 40)
                    if let accent {
                        Circle().fill(accent).frame(width: 10, height: 10).offset(x: -3, y: -3)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(status.deviceName)
                        .font(Theme.font(size: 16, weight: .medium))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(1)
                    Text(status.statusLine)
                        .font(Theme.font(size: 13))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.down")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(.leading, 10)
            .padding(.trailing, 16)
            .frame(height: 60)
            .background(Capsule().fill(Theme.surfaceContainer))
            .overlay(Capsule().stroke(accent?.opacity(0.35) ?? Theme.outlineVariant, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(status.deviceName), \(status.statusLine)")
        .accessibilityHint("Change where to play")
        .accessibilityIdentifier("dashboard-device-chip")
    }
}

/// Customize home: reorder tiles and show or hide each one. Browser is always shown.
private struct DashboardCustomizeView: View {
    let tiles: [DashboardTile]
    let hidden: Set<String>
    let onMove: ([String]) -> Void
    let onToggle: (String, Bool) -> Void
    let onReset: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var tileToMove: DashboardTile?

    var body: some View {
        NavigationStack {
            Group {
                if let tile = tileToMove { positionList(for: tile) } else { tileList }
            }
            .navigationTitle(tileToMove.map { "Move \($0.title)" } ?? "Customize home")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if tileToMove != nil {
                        Button("Back") { tileToMove = nil }
                    } else {
                        Button { dismiss() } label: { Image(systemName: "chevron.left") }
                            .accessibilityLabel("Done")
                    }
                }
                if tileToMove == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Reset", action: onReset)
                    }
                }
            }
        }
        .accessibilityIdentifier("dashboard-reorder-popup")
    }

    private var tileList: some View {
        List {
            Section {
                ForEach(Array(tiles.enumerated()), id: \.element.id) { index, tile in
                    HStack(spacing: 12) {
                        Button { tileToMove = tile } label: {
                            HStack(spacing: 12) {
                                tileIcon(tile)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(tile.title).foregroundStyle(.primary)
                                    if tile.app != nil {
                                        Text("Web app").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Position \(index + 1), \(tile.title)")
                        .accessibilityHint("Choose a new position")
                        Spacer()
                        if DashboardTileOrder.alwaysShown.contains(tile.id) {
                            Label("Always shown", systemImage: "lock.fill")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        } else {
                            Toggle("Show \(tile.title)", isOn: Binding(
                                get: { !hidden.contains(tile.id) },
                                set: { onToggle(tile.id, !$0) }
                            ))
                            .labelsHidden()
                        }
                    }
                    .padding(.vertical, 4)
                }
                .onMove { source, destination in
                    var ids = tiles.map(\.id)
                    ids.move(fromOffsets: source, toOffset: destination)
                    onMove(ids)
                }
            } header: {
                Text("Tiles")
            } footer: {
                Text("Drag the handles to reorder, or tap a tile to choose its exact position. Switch a tile off to hide it. Changes are saved automatically.")
            }
        }
        .environment(\.editMode, .constant(.active))
    }

    private func tileIcon(_ tile: DashboardTile) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10)
                .fill(tile.gradient.count >= 2
                      ? AnyShapeStyle(LinearGradient(colors: tile.gradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                      : AnyShapeStyle(Theme.surfaceContainerHighest))
                .frame(width: 32, height: 32)
            Image(systemName: tile.systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(tile.gradient.count >= 2 ? .white : Theme.onSurfaceVariant)
        }
        .accessibilityHidden(true)
    }

    private func positionList(for tile: DashboardTile) -> some View {
        List(Array(tiles.enumerated()), id: \.element.id) { index, target in
            Button("\(index + 1) · \(target.title)") {
                onMove(DashboardTileOrder.move(tiles.map(\.id), id: tile.id, to: index))
                tileToMove = nil
            }
        }
    }
}
