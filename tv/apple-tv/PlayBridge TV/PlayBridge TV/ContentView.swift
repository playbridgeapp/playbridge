//
//  ContentView.swift
//  PlayBridge TV
//

import PlayBridgeProtocol
import SwiftUI

// MARK: - Root Router

struct ContentView: View {
    @EnvironmentObject var server: WebSocketServer
    @State private var coordinator: ServerCoordinator?

    var body: some View {
        Group {
            if let coordinator = coordinator {
                switch coordinator.route {
                case .home:
                    HomeView(server: server)
                case .prePlay(let payload):
                    PrePlayScreen(payload: payload) { stream in
                        coordinator.selectStream(stream, from: payload)
                    }
                case .player:
                    PlayerScreen(viewModel: server.playerViewModel)
                        .onExitCommand { coordinator.exitPlayer() }
                }
            } else {
                ProgressView("Initializing…")
            }
        }
        .onAppear {
            if coordinator == nil {
                coordinator = ServerCoordinator(server: server)
            }
        }
    }
}

// MARK: - Home Screen

struct HomeView: View {
    @ObservedObject var server: WebSocketServer

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                HStack(spacing: 0) {

                    // ── Left panel: status + settings ────────────────────────
                    VStack(alignment: .leading, spacing: 0) {
                        Spacer()

                        // App icon + name
                        VStack(alignment: .leading, spacing: 16) {
                            Image(
                                systemName: server.isRunning
                                    ? "antenna.radiowaves.left.and.right"
                                    : "antenna.radiowaves.left.and.right.slash"
                            )
                            .font(.system(size: 72))
                            .foregroundStyle(server.isRunning ? .green : .red)

                            Text("PlayBridge TV")
                                .font(.system(size: 52, weight: .bold))
                                .foregroundColor(.white)
                        }

                        Spacer().frame(height: 40)

                        // Status rows
                        VStack(alignment: .leading, spacing: 14) {
                            HomeStatusRow(
                                icon: "circle.fill",
                                text: server.connectionState.description,
                                tint: server.isRunning ? .green : .red
                            )
                            HomeStatusRow(
                                icon: "network",
                                text: "\(server.localIP):8765",
                                tint: .secondary
                            )
                            HomeStatusRow(
                                icon: "iphone",
                                text: server.connectionCount == 0
                                    ? "No phone connected"
                                    : "\(server.connectionCount) phone connected",
                                tint: .secondary
                            )
                        }

                        Spacer()

                        // Settings navigation link
                        NavigationLink(destination: SettingsView()) {
                            Label("Settings", systemImage: "gearshape.fill")
                                .font(.title3.weight(.medium))
                                .padding(.horizontal, 28)
                                .padding(.vertical, 18)
                                .background(Color.white.opacity(0.1))
                                .cornerRadius(14)
                        }

                        Spacer().frame(height: 80)
                    }
                    .padding(.leading, 100)
                    .frame(width: geo.size.width * 0.5)

                    // ── Divider ───────────────────────────────────────────────
                    Rectangle()
                        .fill(Color.white.opacity(0.12))
                        .frame(width: 1)
                        .padding(.vertical, 80)

                    // ── Right panel: PIN or connected badge ───────────────────
                    VStack {
                        Spacer()
                        if server.connectionCount == 0 {
                            PairingPinView(pin: server.pairingPin)
                        } else {
                            ConnectedBadge()
                        }
                        Spacer()
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.trailing, 100)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .background(Color.black.ignoresSafeArea())
        }
    }
}

// MARK: - Home sub-views

private struct HomeStatusRow: View {
    let icon: String
    let text: String
    let tint: Color

    var body: some View {
        Label(text, systemImage: icon)
            .font(.title3)
            .foregroundColor(tint == .secondary ? .secondary : tint)
    }
}

private struct PairingPinView: View {
    let pin: String

    var body: some View {
        VStack(spacing: 20) {
            Text("Pairing PIN")
                .font(.title2)
                .foregroundColor(.secondary)

            Text(pin)
                .font(.system(size: 100, weight: .bold, design: .monospaced))
                .foregroundColor(.white)
                .padding(.horizontal, 48)
                .padding(.vertical, 36)
                .background(
                    RoundedRectangle(cornerRadius: 24)
                        .fill(Color.white.opacity(0.06))
                        .overlay(
                            RoundedRectangle(cornerRadius: 24)
                                .strokeBorder(Color.white.opacity(0.15), lineWidth: 1)
                        )
                )

            Text("Enter this PIN in the PlayBridge phone app")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }
}

private struct ConnectedBadge: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 80))
                .foregroundColor(.green)
            Text("Phone Connected")
                .font(.title2)
                .foregroundColor(.secondary)
        }
    }
}

// MARK: - Settings Screen

struct SettingsView: View {
    @State private var playerModeOverride = AppSettings.shared.playerModeOverride

    private struct EngineOption {
        let label: String
        let detail: String
        let value: String
    }

    private let engineOptions: [EngineOption] = [
        .init(
            label: "Auto",
            detail: "Respect the engine requested by the phone app",
            value: "auto"),
        .init(
            label: "AVPlayer",
            detail: "Apple's native player — best for HLS, DASH, and standard MP4",
            value: "internal"),
        .init(
            label: "VLC",
            detail: "TVVLCKit — wider codec support, useful for MKV, RTSP, and niche formats",
            value: "internal_vlc"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 60) {

                // ── Player Engine ─────────────────────────────────────────────
                SettingsSection(
                    title: "Player Engine",
                    subtitle: "Controls which engine plays incoming streams."
                ) {
                    ForEach(engineOptions, id: \.value) { option in
                        SettingsPickerRow(
                            label: option.label,
                            detail: option.detail,
                            isSelected: playerModeOverride == option.value
                        ) {
                            playerModeOverride = option.value
                            AppSettings.shared.playerModeOverride = option.value
                        }
                    }
                }

                // ── Device Info ───────────────────────────────────────────────
                SettingsSection(title: "Device Info", subtitle: nil) {
                    SettingsInfoRow(label: "Server Port", value: "8765")
                    SettingsInfoRow(
                        label: "Device UUID",
                        value: PairingStore.shared.deviceUUID)
                }
            }
            .padding(.horizontal, 100)
            .padding(.vertical, 60)
        }
        .navigationTitle("Settings")
    }
}

// MARK: - Settings reusable components

private struct SettingsSection<Content: View>: View {
    let title: String
    let subtitle: String?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.title2.bold())
                    .foregroundColor(.white)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
            content()
        }
    }
}

private struct SettingsPickerRow: View {
    let label: String
    let detail: String
    let isSelected: Bool
    let action: () -> Void

    @FocusState private var isFocused: Bool

    var body: some View {
        Button(action: action) {
            HStack(spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(label)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundColor(.gray)
                }
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(.blue)
                        .font(.title2)
                }
            }
            .padding(24)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(
                        isSelected
                            ? Color.blue.opacity(isFocused ? 0.4 : 0.2)
                            : Color.white.opacity(isFocused ? 0.15 : 0.07)
                    )
            )
        }
        .buttonStyle(.plain)
        .focused($isFocused)
    }
}

private struct SettingsInfoRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
                .font(.headline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.system(.body, design: .monospaced))
                .foregroundColor(.white)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .padding(24)
        .background(Color.white.opacity(0.07))
        .cornerRadius(14)
    }
}
