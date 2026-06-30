import SwiftUI

struct DashboardScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showExitConfirm = false
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

    var body: some View {
        ZStack {
            // ── Animated Ambient Mesh Background ─────────────────────────────────
            MeshBackground()

            // ── Main Content ─────────────────────────────────────────────────────
            ScrollView {
                VStack(alignment: .center, spacing: 0) {
                    Spacer().frame(height: 60)

                    Text("PlayBridge")
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundColor(Theme.onSurface)

                    Spacer().frame(height: 4)

                    Text("CONSOLE HUB")
                        .font(.system(size: 10, weight: .bold))
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
                    cardsGrid

                    Spacer().frame(height: 32)

                    // ── Exit Button ───────────────────────────────────────────────────
                    exitButton

                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 24)
            }

            // ── Top Left Close Button ─────────────────────────────────────────
            closeButton
        }
        .alert("Exit PlayBridge?", isPresented: $showExitConfirm) {
            Button("Exit", role: .destructive) { exit(0) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This fully quits the app. Any cast that relies on PlayBridge — phone files, DLNA, or queued playback — will stop or error out.")
        }
        .alert("\(comingSoonFeatureName) Coming Soon", isPresented: $showComingSoonAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This feature is not yet ported from Android to iOS. The core bridge and web browser are fully functional.")
        }
    }

    // MARK: - Components

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
                    isActive: nav.lastMainScreen == .browser,
                    action: { nav.navigate(to: .browser) }
                )

                cardView(
                    title: "Connection",
                    subtitle: isConnected ? "Connected" : "Not connected",
                    systemImage: "tv",
                    gradient: isConnected ? [Color(hex: 0x2E7D32), Color(hex: 0x43A047)] : [Color(hex: 0x424242), Color(hex: 0x616161)],
                    tall: true,
                    isActive: nav.lastMainScreen == .connection,
                    action: { nav.navigate(to: .connection) }
                )
            }

            // Row 2: Phone Files (live), IPTV
            HStack(spacing: 12) {
                cardView(
                    title: "Phone Files",
                    subtitle: "Cast videos & audio",
                    systemImage: "folder",
                    gradient: [Color(hex: 0x4527A0), Color(hex: 0x5E35B1)],
                    tall: false,
                    isActive: nav.currentScreen == .phoneFiles,
                    action: { nav.navigate(to: .phoneFiles) }
                )

                cardView(
                    title: "IPTV",
                    subtitle: "Live channels",
                    systemImage: "tv.fill",
                    gradient: [Color(hex: 0x00695C), Color(hex: 0x00897B)],
                    tall: false,
                    isActive: nav.currentScreen == .iptv,
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
                    isActive: nav.currentScreen == .collections,
                    action: { nav.navigate(to: .collections) }
                )

                cardView(
                    title: "Cast History",
                    subtitle: "Recent casts",
                    systemImage: "clock.arrow.circlepath",
                    gradient: [Color(hex: 0xE65100), Color(hex: 0xFB8C00)],
                    tall: false,
                    isActive: false,
                    comingSoon: true
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
                        Image(systemName: systemImage)
                            .font(.system(size: 20))
                            .foregroundColor(.white)
                    }

                    Spacer()

                    // Text Details
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.system(size: tall ? 16 : 13, weight: .semibold))
                            .foregroundColor(.white)
                            .lineLimit(1)

                        Text(subtitle)
                            .font(.system(size: tall ? 12 : 10))
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

    private var exitButton: some View {
        Button {
            showExitConfirm = true
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "power")
                    .font(.system(size: 16, weight: .medium))
                Text("Exit PlayBridge")
                    .font(.system(size: 15, weight: .medium))
            }
            .foregroundColor(Theme.danger)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(Theme.danger.opacity(0.5), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var closeButton: some View {
        VStack {
            HStack {
                Button {
                    nav.navigate(to: nav.dashboardOrigin ?? .browser)
                } label: {
                    ZStack {
                        Circle()
                            .fill(Theme.surfaceContainer.opacity(0.5))
                            .frame(width: 40, height: 40)
                        Image(systemName: "xmark")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(Theme.onSurface.opacity(0.8))
                    }
                }
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
                    .font(.system(size: 12, weight: .medium))
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
                .font(.system(size: 8, weight: .bold))
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
