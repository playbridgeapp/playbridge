import SwiftUI

struct DashboardScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @State private var tilePage = 0
    @State private var appToRemove: BridgedApp?
    @State private var showReorder = false
    @State private var tileToMove: DashboardTile?
    @AppStorage("dashboard_tile_order") private var savedOrder = "[]"
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var largeTileHeight = 150.0
    @ScaledMetric(relativeTo: .body) private var smallTileHeight = 120.0

    private var isConnected: Bool { vm.isConnected }
    private var isSecure: Bool {
        if case .connected(_, let secure) = vm.state { return secure }
        return false
    }
    private var connectedDeviceName: String? {
        if case .connected(let name, _) = vm.state { return name }
        return vm.pairedDevice?.name
    }

    private func isSource(_ destination: AppScreen) -> Bool {
        switch (nav.dashboardSource, destination) {
        case (.iptvDetail(_), .iptv), (.collectionDetail(_), .collections): return true
        case (let source?, _): return source == destination
        case (nil, _): return false
        }
    }

    var body: some View {
        ZStack {
            // ── Animated Ambient Mesh Background ─────────────────────────────────
            MeshBackground().accessibilityHidden(true)

            // ── Main Content ─────────────────────────────────────────────────────
            ScrollView {
                VStack(alignment: .center, spacing: 0) {
                    Spacer().frame(height: 60)

                    Text("PlayBridge")
                        .font(Theme.font(size: 28, weight: .bold, design: .rounded))
                        .foregroundColor(Theme.onSurface)

                    Spacer().frame(height: 4)

                    Text("CONSOLE HUB")
                        .font(Theme.font(size: 10, weight: .bold))
                        .foregroundColor(Theme.primary.opacity(0.7))
                        .tracking(3)

                    Spacer().frame(height: 16)

                    // ── Interactive Connection Status Pill ────────────────────────────
                    StatusPill(
                        isConnected: isConnected,
                        isSecure: isSecure,
                        name: connectedDeviceName,
                        action: { nav.navigate(to: .connection) }
                    )

                    Spacer().frame(height: 36)

                    // ── Grid Cards ────────────────────────────────────────────────────
                    tilePages

                    Spacer().frame(height: 32)

                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 24)
            }
            .allowsHitTesting(!showReorder)
            .accessibilityHidden(showReorder)

            // ── Top Left Close Button ─────────────────────────────────────────
            closeButton
                .allowsHitTesting(!showReorder)
                .accessibilityHidden(showReorder)
        }
        .overlay {
            if showReorder { reorderPopup.transition(.opacity) }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.15), value: showReorder)
        .onAppear { normalizeOrder() }
        .onChange(of: availableTiles.map(\.id)) { _ in normalizeOrder() }
        .alert("Remove Bridged App?", isPresented: Binding(
            get: { appToRemove != nil }, set: { if !$0 { appToRemove = nil } }
        )) {
            Button("Remove", role: .destructive) {
                if let app = appToRemove { store.removeBridgedApp(app) }
                appToRemove = nil
            }
            Button("Cancel", role: .cancel) { appToRemove = nil }
        } message: {
            Text("Its dashboard tile and app session will be removed. Website data and casting permissions are managed separately.")
        }
    }

    // MARK: - Components

    private var availableTiles: [DashboardTile] {
        var tiles = [
            DashboardTile(id: "browser", title: "Browser", subtitle: "Browse the web", systemImage: "globe",
                          gradient: [Color(hex: 0x1565C0), Color(hex: 0x1E88E5)], isActive: isSource(.browser),
                          action: { nav.openBrowser() }),
            DashboardTile(id: "connection", title: "Connection", subtitle: isConnected ? "Connected" : "Not connected",
                          systemImage: "tv",
                          gradient: isConnected ? [Color(hex: 0x2E7D32), Color(hex: 0x43A047)] : [Color(hex: 0x424242), Color(hex: 0x616161)],
                          isActive: isSource(.connection), action: { nav.navigate(to: .connection) }),
            DashboardTile(id: "phone-files", title: "Media Library", subtitle: "Videos, images & audio", systemImage: "folder",
                          gradient: [Color(hex: 0x4527A0), Color(hex: 0x5E35B1)], isActive: isSource(.phoneFiles),
                          action: { nav.navigate(to: .phoneFiles) }),
            DashboardTile(id: "iptv", title: "IPTV", subtitle: "Live channels", systemImage: "tv.fill",
                          gradient: [Color(hex: 0x00695C), Color(hex: 0x00897B)], isActive: isSource(.iptv),
                          action: { nav.navigate(to: .iptv) }),
            DashboardTile(id: "collections", title: "Collections", subtitle: "Your playlists", systemImage: "play.rectangle.fill",
                          gradient: [Color(hex: 0xAD1457), Color(hex: 0xD81B60)], isActive: isSource(.collections),
                          action: { nav.navigate(to: .collections) }),
            DashboardTile(id: "remote", title: "Remote", subtitle: "Control your TV", systemImage: "av.remote",
                          gradient: [Color(hex: 0xE65100), Color(hex: 0xFB8C00)], isActive: isSource(.remote),
                          action: { nav.navigate(to: .remote) }),
            DashboardTile(id: "cast-history", title: "Cast History", subtitle: "Recent casts", systemImage: "clock.arrow.circlepath",
                          gradient: [Color(hex: 0xE65100), Color(hex: 0xFB8C00)], isActive: isSource(.castHistory),
                          action: { nav.navigate(to: .castHistory) }),
        ]
        if store.bridgedApps.apps.isEmpty {
            tiles.append(DashboardTile(
                id: "bridged-apps", title: "Bridged Apps", subtitle: "Open Browser to add", systemImage: "app.connected.to.app.below.fill",
                gradient: [Color(hex: 0x00695C), Color(hex: 0x00897B)], isActive: false,
                action: { nav.openBrowser() }
            ))
        } else {
            tiles += store.bridgedApps.apps.map { app in
                DashboardTile(
                    id: "app:\(app.origin.absoluteString)", title: app.name, subtitle: "Bridged App",
                    systemImage: "app.connected.to.app.below.fill",
                    gradient: [Color(hex: 0x00695C), Color(hex: 0x00897B)],
                    isActive: store.activeBridgedApp?.origin == app.origin,
                    app: app,
                    action: { if store.openBridgedApp(app) != nil { nav.navigate(to: .browser) } }
                )
            }
        }
        return tiles
    }

    private var orderedTiles: [DashboardTile] {
        let available = availableTiles
        let byID = Dictionary(uniqueKeysWithValues: available.map { ($0.id, $0) })
        return DashboardTileOrder.reconcile(saved: DashboardTileOrder.decode(savedOrder), available: available.map(\.id))
            .compactMap { byID[$0] }
    }

    private var pages: [[DashboardTile]] {
        let tiles = orderedTiles
        return stride(from: 0, to: tiles.count, by: DashboardTileOrder.tilesPerPage).map {
            Array(tiles[$0..<min($0 + DashboardTileOrder.tilesPerPage, tiles.count)])
        }
    }

    private func saveOrder(_ ids: [String]) {
        savedOrder = DashboardTileOrder.encode(ids)
    }

    private func normalizeOrder() {
        saveOrder(orderedTiles.map(\.id))
        tilePage = min(tilePage, max(0, pages.count - 1))
    }

    private var tileGridHeight: Double { largeTileHeight + 2 * smallTileHeight + 24 }

    private var tilePages: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                Button { showReorder = true } label: {
                    Label("Reorder", systemImage: "arrow.up.arrow.down")
                        .font(Theme.font(.subheadline))
                        .padding(.vertical, 12)
                }
                .accessibilityIdentifier("dashboard-reorder")
            }
            TabView(selection: $tilePage) {
                ForEach(pages.indices, id: \.self) { page in
                    tileGrid(pages[page]).tag(page)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            // Match the two row gaps plus breathing room inside the pager's clip.
            .frame(height: tileGridHeight + 16)
            if pages.count > 1 {
                HStack(spacing: 0) {
                    ForEach(pages.indices, id: \.self) { page in
                        Button {
                            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { tilePage = page }
                        } label: {
                            Circle().fill(tilePage == page ? Theme.primary : Theme.onSurfaceVariant.opacity(0.4))
                                .frame(width: tilePage == page ? 10 : 7, height: tilePage == page ? 10 : 7)
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("Dashboard page \(page + 1)")
                        .accessibilityAddTraits(tilePage == page ? .isSelected : [])
                    }
                }
                .padding(.top, 6)
            }
        }
    }

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
        // A Spacer in a spaced VStack adds another gap even when its height is zero.
        // Top-align sparse pages without making a full page taller than its viewport.
        .frame(height: tileGridHeight, alignment: .top)
        .padding(.vertical, 8)
    }

    private func tileView(_ tile: DashboardTile, tall: Bool) -> some View {
        cardView(
            title: tile.title, subtitle: tile.subtitle, systemImage: tile.systemImage,
            gradient: tile.gradient, tall: tall, isActive: tile.isActive,
            iconURL: tile.app?.iconURL, action: tile.action
        )
        .frame(maxWidth: .infinity)
        .contextMenu {
            Button { showReorder = true } label: {
                Label("Reorder Tiles", systemImage: "arrow.up.arrow.down")
            }
            if let app = tile.app {
                Button("Remove Bridged App", role: .destructive) { appToRemove = app }
            }
        }
        .accessibilityLabel(tile.app == nil ? "\(tile.title), \(tile.subtitle)" : "\(tile.title), Bridged App")
        .accessibilityIdentifier(tile.app.map { "bridged-app-\($0.origin.absoluteString)" } ?? "dashboard-\(tile.id)")
    }

    private var reorderPopup: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture { dismissReorder() }
                    .accessibilityHidden(true)

                reorderEditor
                    .frame(width: min(geometry.size.width - 32, 420),
                           height: min(geometry.size.height * 0.8, 640))
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .shadow(color: .black.opacity(0.25), radius: 24, y: 8)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("dashboard-reorder-popup")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityAction(.escape) { dismissReorder() }
    }

    private var reorderEditor: some View {
        NavigationStack {
            Group {
                if let tile = tileToMove {
                    positionList(for: tile)
                } else {
                    reorderList
                }
            }
            .navigationTitle(tileToMove.map { "Move \($0.title)" } ?? "Reorder Tiles")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismissReorder() }
                }
                if tileToMove != nil {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Back") { tileToMove = nil }
                    }
                }
            }
        }
    }

    private var reorderList: some View {
        List {
            Section {
                ForEach(Array(orderedTiles.enumerated()), id: \.element.id) { index, tile in
                    Button { tileToMove = tile } label: {
                        HStack(spacing: 12) {
                            Text("\(index + 1)")
                                .monospacedDigit().foregroundStyle(Theme.primary)
                                .frame(minWidth: 32, alignment: .trailing)
                                .fixedSize(horizontal: true, vertical: false)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(tile.title).foregroundStyle(.primary)
                                Text("\(index % DashboardTileOrder.tilesPerPage < 2 ? "Large" : "Compact") · Page \(index / DashboardTileOrder.tilesPerPage + 1)")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                    .accessibilityLabel("Position \(index + 1), \(tile.title)")
                    .accessibilityHint("Choose a new position")
                }
                .onMove { source, destination in
                    var ids = orderedTiles.map(\.id)
                    ids.move(fromOffsets: source, toOffset: destination)
                    saveOrder(ids)
                }
            } header: {
                Text("The first two tiles on each page are larger.")
            } footer: {
                Text("Drag the handles to reorder, or tap a tile to choose its exact position. Changes are saved automatically.")
            }
        }
        .environment(\.editMode, .constant(.active))
    }

    private func positionList(for tile: DashboardTile) -> some View {
        List(Array(orderedTiles.enumerated()), id: \.element.id) { index, target in
            Button("\(index + 1) · \(target.title)") {
                saveOrder(DashboardTileOrder.move(orderedTiles.map(\.id), id: tile.id, to: index))
                tileToMove = nil
            }
        }
    }

    private func dismissReorder() {
        tileToMove = nil
        showReorder = false
    }

    @ViewBuilder
    private func cardView(
        title: String,
        subtitle: String,
        systemImage: String,
        gradient: [Color],
        tall: Bool,
        isActive: Bool,
        iconURL: URL? = nil,
        action: (() -> Void)? = nil
    ) -> some View {
        Button { action?() } label: {
            ZStack(alignment: .topTrailing) {
                // Background Gradient
                RoundedRectangle(cornerRadius: 20)
                    .fill(LinearGradient(colors: gradient, startPoint: .topLeading, endPoint: .bottomTrailing))

                // Inner glow / glass sheen
                RoundedRectangle(cornerRadius: 20)
                    .fill(
                        RadialGradient(
                            colors: [Color.white.opacity(0.15), Color.clear],
                            center: .topLeading,
                            startRadius: 0,
                            endRadius: 150
                        )
                    )

                // Translucent borders
                RoundedRectangle(cornerRadius: 20)
                    .strokeBorder(
                        LinearGradient(
                            colors: [Color.white.opacity(isActive ? 0.35 : 0.15), Color.white.opacity(0.03)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ),
                        lineWidth: 1
                    )

                // Subtle Decorative Circles in Background
                Circle()
                    .fill(Color.white.opacity(0.08))
                    .frame(width: 80, height: 80)
                    .offset(x: 15, y: -15)

                // Content Column
                VStack(alignment: .leading, spacing: 0) {
                    // Icon Container
                    ZStack {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(Color.white.opacity(0.2))
                            .frame(width: 40, height: 40)
                        if let iconURL {
                            AsyncImage(url: iconURL) { image in
                                image.resizable().scaledToFit().frame(width: 32, height: 32)
                            } placeholder: {
                                Image(systemName: systemImage).foregroundColor(.white)
                            }
                        } else {
                            Image(systemName: systemImage)
                                .font(Theme.font(size: 20)).foregroundColor(.white)
                        }
                    }

                    Spacer()

                    // Text Details
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(Theme.font(size: tall ? 16 : 13, weight: .semibold))
                            .foregroundColor(.white)
                            .lineLimit(tall ? 1 : 2)

                        Text(subtitle)
                            .font(Theme.font(size: tall ? 12 : 10))
                            .foregroundColor(Color.white.opacity(0.7))
                            .lineLimit(1)
                    }
                }
                .padding(tall ? 16 : 12)
                .frame(maxWidth: .infinity, alignment: .leading)

                // ACTIVE indicator
                if isActive {
                    ActiveBadge()
                        .padding(.top, 12)
                        .padding(.trailing, 12)
                }
            }
            .frame(height: tall ? largeTileHeight : smallTileHeight)
            .clipShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
    }

    private var closeButton: some View {
        VStack {
            HStack {
                Button {
                    nav.returnFromDashboard()
                } label: {
                    ZStack {
                        Circle()
                            .fill(Theme.surfaceContainer.opacity(0.5))
                            .frame(width: 40, height: 40)
                        Image(systemName: "xmark")
                            .font(Theme.font(size: 16, weight: .bold))
                            .foregroundColor(Theme.onSurface.opacity(0.8))
                    }
                }
                .accessibilityLabel("Close dashboard")
                .padding(.leading, 16)
                .padding(.top, 8)
                Spacer()
            }
            Spacer()
        }
    }
}

private struct DashboardTile: Identifiable {
    let id: String
    let title: String
    let subtitle: String
    let systemImage: String
    let gradient: [Color]
    let isActive: Bool
    var app: BridgedApp? = nil
    let action: () -> Void
}

// MARK: - Helper Views

struct MeshBackground: View {
    @State private var animateBlob1 = false
    @State private var animateBlob2 = false

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()

            // Drift Blob 1 (Top-Right-ish)
            Circle()
                .fill(Theme.primary.opacity(0.12))
                .frame(width: 400, height: 400)
                .blur(radius: 80)
                .offset(x: animateBlob1 ? 100 : -50, y: animateBlob1 ? -100 : 50)
                .animation(.linear(duration: 28).repeatForever(autoreverses: true), value: animateBlob1)

            // Drift Blob 2 (Bottom-Left-ish)
            Circle()
                .fill(Color(hex: 0x8E24AA).opacity(0.10))
                .frame(width: 350, height: 350)
                .blur(radius: 70)
                .offset(x: animateBlob2 ? -100 : 50, y: animateBlob2 ? 100 : -50)
                .animation(.linear(duration: 42).repeatForever(autoreverses: true), value: animateBlob2)
        }
        .onAppear {
            animateBlob1 = true
            animateBlob2 = true
        }
    }
}



struct StatusPill: View {
    let isConnected: Bool
    let isSecure: Bool
    let name: String?
    let action: () -> Void

    @State private var pulse = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Circle()
                    .fill(isConnected ? (isSecure ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000)) : Theme.onSurfaceVariant.opacity(0.4))
                    .frame(width: 8, height: 8)
                    .opacity(isConnected ? (pulse ? 0.4 : 1.0) : 1.0)
                    .animation(isConnected ? .easeInOut(duration: 1.0).repeatForever(autoreverses: true) : .default, value: pulse)

                Text(text)
                    .font(Theme.font(size: 12, weight: .medium))
                    .foregroundColor(isConnected ? (isSecure ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000)) : Theme.onSurfaceVariant)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(isConnected ? (isSecure ? Color(hex: 0x4CAF50).opacity(0.15) : Color(hex: 0xFFA000).opacity(0.15)) : Theme.surfaceContainer.opacity(0.6))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(isConnected ? (isSecure ? Color(hex: 0x4CAF50).opacity(0.3) : Color(hex: 0xFFA000).opacity(0.3)) : Theme.outlineVariant.opacity(0.2), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .onAppear {
            pulse = true
        }
    }

    private var text: String {
        if isConnected {
            let displayName = name ?? "TV"
            let truncatedName = displayName.count > 18 ? String(displayName.prefix(15)) + "..." : displayName
            return isSecure ? "Connected to \(truncatedName) securely" : "Connected to \(displayName)"
        } else {
            return "No device connected"
        }
    }
}

struct ActiveBadge: View {
    @State private var pulse = false

    var body: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(Color.white)
                .frame(width: 4, height: 4)
                .opacity(pulse ? 0.4 : 1.0)
                .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: pulse)

            Text("ACTIVE")
                .font(Theme.font(size: 8, weight: .bold))
                .foregroundColor(.white)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 3)
        .background(Color.white.opacity(0.22))
        .cornerRadius(8)
        .onAppear {
            pulse = true
        }
    }
}
