import SwiftUI

struct DashboardScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @State private var tilePage = 0
    @State private var appToRemove: BridgedApp?
    @State private var showComingSoonAlert = false
    @State private var comingSoonFeatureName = ""

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
            MeshBackground()

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

            // ── Top Left Close Button ─────────────────────────────────────────
            closeButton
        }
        .alert("\(comingSoonFeatureName) Coming Soon", isPresented: $showComingSoonAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This feature is not yet ported from Android to iOS. The core bridge and web browser are fully functional.")
        }
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

    private var tilePages: some View {
        VStack(spacing: 16) {
            TabView(selection: $tilePage) {
                cardsGrid.tag(0)
                appsGrid.tag(1)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: max(414, CGFloat((store.bridgedApps.apps.count + 2) / 2) * 132 - 12))
            HStack(spacing: 12) {
                ForEach(0..<2) { page in
                    Button { withAnimation { tilePage = page } } label: {
                        Circle().fill(tilePage == page ? Theme.primary : Theme.onSurfaceVariant.opacity(0.4))
                            .frame(width: 8, height: 8).padding(8)
                    }
                    .accessibilityLabel(page == 0 ? "Dashboard tiles" : "Apps and history tiles")
                    .accessibilityAddTraits(tilePage == page ? .isSelected : [])
                }
            }
        }
    }

    private var appsGrid: some View {
        VStack(spacing: 12) {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                cardView(title: "Cast History", subtitle: "Recent casts", systemImage: "clock.arrow.circlepath",
                         gradient: [Color(hex: 0xE65100), Color(hex: 0xFB8C00)], tall: false,
                         isActive: isSource(.castHistory), action: { nav.navigate(to: .castHistory) })
                ForEach(store.bridgedApps.apps) { app in
                    cardView(title: app.name, subtitle: "Bridged App", systemImage: "app.connected.to.app.below.fill",
                             gradient: [Color(hex: 0x1565C0), Color(hex: 0x5E35B1)], tall: false,
                             isActive: store.activeBridgedApp?.origin == app.origin, iconURL: app.iconURL,
                             action: {
                                 if store.openBridgedApp(app) != nil { nav.navigate(to: .browser) }
                             })
                    .contextMenu {
                        Button("Remove Bridged App", role: .destructive) { appToRemove = app }
                    }
                    .accessibilityLabel("\(app.name), Bridged App")
                    .accessibilityIdentifier("bridged-app-\(app.origin.absoluteString)")
                }
            }
            if store.bridgedApps.apps.isEmpty {
                Text("Open a supported website in Browser, then choose Add Bridged App from its menu.")
                    .font(Theme.font(.footnote)).foregroundStyle(Theme.onSurfaceVariant)
                    .multilineTextAlignment(.center).padding(.top, 16)
            }
            Spacer(minLength: 0)
        }
    }

    private var cardsGrid: some View {
        VStack(spacing: 12) {
            // Row 1: Primary features (Browser, Connection)
            HStack(spacing: 12) {
                cardView(
                    title: "Browser",
                    subtitle: "Browse the web",
                    systemImage: "globe",
                    gradient: [Color(hex: 0x1565C0), Color(hex: 0x1E88E5)],
                    tall: true,
                    isActive: isSource(.browser),
                    action: { nav.openBrowser() }
                )

                cardView(
                    title: "Connection",
                    subtitle: isConnected ? "Connected" : "Not connected",
                    systemImage: "tv",
                    gradient: isConnected ? [Color(hex: 0x2E7D32), Color(hex: 0x43A047)] : [Color(hex: 0x424242), Color(hex: 0x616161)],
                    tall: true,
                    isActive: isSource(.connection),
                    action: { nav.navigate(to: .connection) }
                )
            }

            // Row 2: Phone Files (live), IPTV
            HStack(spacing: 12) {
                cardView(
                    title: "Media Library",
                    subtitle: "Videos, images & audio",
                    systemImage: "folder",
                    gradient: [Color(hex: 0x4527A0), Color(hex: 0x5E35B1)],
                    tall: false,
                    isActive: isSource(.phoneFiles),
                    action: { nav.navigate(to: .phoneFiles) }
                )

                cardView(
                    title: "IPTV",
                    subtitle: "Live channels",
                    systemImage: "tv.fill",
                    gradient: [Color(hex: 0x00695C), Color(hex: 0x00897B)],
                    tall: false,
                    isActive: isSource(.iptv),
                    action: { nav.navigate(to: .iptv) }
                )
            }

            // Row 3: Collections, Cast History
            HStack(spacing: 12) {
                cardView(
                    title: "Collections",
                    subtitle: "Your playlists",
                    systemImage: "play.rectangle.fill",
                    gradient: [Color(hex: 0xAD1457), Color(hex: 0xD81B60)],
                    tall: false,
                    isActive: isSource(.collections),
                    action: { nav.navigate(to: .collections) }
                )

                cardView(
                    title: "Remote",
                    subtitle: "Control your TV",
                    systemImage: "av.remote",
                    gradient: [Color(hex: 0xE65100), Color(hex: 0xFB8C00)],
                    tall: false,
                    isActive: isSource(.remote),
                    action: { nav.navigate(to: .remote) }
                )
            }
        }
    }

    @ViewBuilder
    private func cardView(
        title: String,
        subtitle: String,
        systemImage: String,
        gradient: [Color],
        tall: Bool,
        isActive: Bool,
        comingSoon: Bool = false,
        iconURL: URL? = nil,
        action: (() -> Void)? = nil
    ) -> some View {
        Button {
            if comingSoon {
                comingSoonFeatureName = title
                showComingSoonAlert = true
            } else {
                action?()
            }
        } label: {
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
                    .stroke(
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
                    .clipped()

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
                            .lineLimit(1)

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
            .frame(height: tall ? 150 : 120)
            .opacity(comingSoon ? 0.4 : 1.0)
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
