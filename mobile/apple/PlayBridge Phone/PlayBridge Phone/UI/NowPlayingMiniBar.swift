import SwiftUI

/// Persistent destination/playback affordance for secondary screens. The host inserts it
/// into the safe area so scrolling content and controls are never covered by the bar.
struct NowPlayingMiniBar: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @Binding var showDestinationPicker: Bool

    private var playback: TvPlaybackStatus? { vm.coordinator.playback }
    private var playing: Bool {
        guard vm.isConnected, vm.coordinator.activeContext == "player" else { return false }
        return !["stopped", "idle", "error"].contains(playback?.state.lowercased() ?? "")
    }
    private var paused: Bool { playback?.state.lowercased() == "paused" }
    private var progress: CGFloat? {
        guard playing, let playback, playback.durationMs > 0 else { return nil }
        return CGFloat(min(1, max(0, Double(playback.positionMs) / Double(playback.durationMs))))
    }
    private var deviceName: String { vm.receiverName ?? "TV" }
    private var primaryText: String {
        if playing { return playback?.title ?? "Now playing" }
        return vm.isConnected ? deviceName : "This Device"
    }
    private var secondaryText: String {
        if playing { return "\(paused ? "Paused · " : "")on \(deviceName)" }
        if vm.isConnected {
            return vm.externalReceiver.map { "\($0.protocolName) · Ready to cast" } ?? "Ready to cast"
        }
        return "Tap to cast to a device"
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Button {
                    if playing {
                        vm.queryContext()
                        nav.navigate(to: .remote)
                    } else {
                        showDestinationPicker = true
                    }
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: playing ? (paused ? "pause.fill" : "waveform") : (vm.isConnected ? "tv" : "iphone"))
                            .font(.system(size: 18, weight: .semibold))
                            .frame(width: 36, height: 36)
                            .background(Theme.onSecondaryContainer.opacity(0.13), in: RoundedRectangle(cornerRadius: 11))
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(primaryText)
                                .font(Theme.font(size: 14, weight: .semibold))
                                .lineLimit(1)
                            Text(secondaryText)
                                .font(Theme.font(size: 11))
                                .opacity(0.78)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(playing ? "Now playing: \(primaryText). Open Remote" : "\(primaryText). Choose a device")

                if playing {
                    Button { showDestinationPicker = true } label: {
                        Image(systemName: "tv")
                            .font(.system(size: 17, weight: .semibold))
                            .frame(width: 42, height: 42)
                            .background(Theme.onSecondaryContainer.opacity(0.12), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Choose cast device")
                }
            }
            .foregroundColor(Theme.onSecondaryContainer)
            .padding(.leading, 10)
            .padding(.trailing, 6)
            .padding(.vertical, 7)

            if let progress {
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Theme.onSecondaryContainer.opacity(0.18)
                        Theme.onSecondaryContainer.frame(width: geometry.size.width * progress)
                    }
                }
                .frame(height: 3)
                .accessibilityHidden(true)
            }
        }
        .background(
            LinearGradient(colors: [Theme.secondaryContainer, Theme.surfaceContainerHigh],
                           startPoint: .leading, endPoint: .trailing),
            in: RoundedRectangle(cornerRadius: 18)
        )
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Theme.onSecondaryContainer.opacity(0.18)))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.surface.opacity(0.96))
        .onAppear { if vm.isConnected { vm.queryContext() } }
        .onChange(of: vm.state) { state in if state.isConnected { vm.queryContext() } }
    }
}
