import SwiftUI

/// Persistent destination/playback affordance for secondary screens. The host inserts it
/// into the safe area so scrolling content and controls are never covered by the bar.
struct NowPlayingMiniBar: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var pageCasting: PageCastCoordinator

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
        if let selected = vm.selectedRemoteTV { return selected.device.name }
        return vm.isConnected ? deviceName : "This phone"
    }
    private var secondaryText: String {
        if let site = pageCasting.controllerName { return "Controlled by \(site) · on \(deviceName)" }
        if playing { return "\(paused ? "Paused · " : "")on \(deviceName)" }
        if vm.isConnected {
            return vm.externalReceiver.map { "\($0.protocolName) · Ready to cast" } ?? "Ready to cast"
        }
        if let selected = vm.selectedRemoteTV { return selected.status }
        return "Tap to cast to a device"
    }

    @EnvironmentObject private var library: PhoneMediaLibrary
    @ObservedObject private var phoneQueue = PhoneQueueCoordinator.shared
    @ObservedObject private var tints = QueueTintStore.shared
    /// A phone list's bar is coloured like Up next's current card: the artwork's light tint fills as it plays.
    private var tint: QueueTint? { playing && phoneQueue.tintedItem(on: vm) != nil ? tints.tint : nil }
    private var textColor: Color { tint?.onCard ?? Theme.onSurface }
    private var subtleColor: Color { tint.map { $0.onCard.opacity(0.72) } ?? Theme.onSurfaceVariant }

    /// The phone list's current item, when the receiver is playing one of this iPhone's lists.
    private var phoneItem: PhoneQueueCoordinator.Item? {
        guard playing, phoneQueue.active, let current = vm.coordinator.playlist?.currentItemId,
              phoneQueue.current?.id == current else { return nil }
        return phoneQueue.current
    }
    private var upNextCount: Int {
        guard playing, !vm.isExternalReceiver, !vm.isAirPlay else { return 0 }
        if phoneItem != nil { return phoneQueue.remaining }
        guard let playlist = vm.coordinator.playlist else { return 0 }
        let at = playlist.items.firstIndex { item in
            playlist.currentItemId.map { item.itemId == $0 } ?? (item.index == playlist.currentIndex)
        }
        return max(0, at.map { playlist.items.count - $0 - 1 } ?? (playlist.totalCount - playlist.currentIndex - 1))
    }
    private var ready: Bool { !playing && vm.isConnected }

    var body: some View {
        HStack(spacing: 8) {
            Button {
                if playing || vm.selectedRemoteTV?.paired == true {
                    vm.queryContext()
                    nav.navigate(to: .remote)
                } else {
                    nav.openPlayOn()
                }
            } label: {
                HStack(spacing: 10) {
                    if playing {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8).fill(Theme.onSurface.opacity(0.12))
                            if let phoneItem {
                                PhoneMediaThumbnailView(item: phoneItem.media, url: library.localURL(phoneItem.media),
                                                        size: CGSize(width: 256, height: 144))
                            } else {
                                Image(systemName: "tv").font(.system(size: 16, weight: .semibold))
                            }
                        }
                        .frame(width: 64, height: 36).clipShape(RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)
                    } else {
                        Image(systemName: vm.isConnected ? "tv" : "iphone")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundColor(ready ? .green : Theme.onSurface)
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(ready ? Color.green.opacity(0.18) : Theme.onSurface.opacity(0.10)))
                            .accessibilityHidden(true)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(primaryText)
                            .font(Theme.font(size: playing ? 14 : 15, weight: .medium))
                            .lineLimit(1)
                        Text(secondaryText)
                            .font(Theme.font(size: 12))
                            .foregroundColor(subtleColor)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if !playing {
                        Image(systemName: "chevron.down").font(.system(size: 14, weight: .semibold))
                            .foregroundColor(Theme.onSurfaceVariant).accessibilityHidden(true)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(playing ? "Now playing: \(primaryText). Open Remote" : "\(primaryText). Choose a device")

            if upNextCount > 0 {
                Button {
                    nav.openRemoteQueue = true
                    vm.queryContext()
                    nav.navigate(to: .remote)
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "text.line.first.and.arrowtriangle.forward").font(.system(size: 14))
                        Text("\(upNextCount)").font(Theme.font(size: 13, weight: .medium))
                    }
                    .padding(.horizontal, 12).frame(height: 40)
                    .background(Capsule().fill(textColor.opacity(tint == nil ? 0.08 : 0.12)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Up next, \(upNextCount) items")
            }
            if playing && pageCasting.isLinked {
                Button { pageCasting.unlink() } label: {
                    Text("Unlink").font(Theme.font(size: 12, weight: .semibold)).frame(minWidth: 48, minHeight: 44)
                }
                .buttonStyle(.plain)
                .foregroundColor(Theme.onSurfaceVariant)
                .accessibilityLabel("Unlink website")
                .accessibilityHint("Stops website control while keeping the current media playing")
            }
            if playing {
                Button { vm.control(paused ? "play" : "pause") } label: {
                    Image(systemName: paused ? "play.fill" : "pause.fill")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundColor(tint?.cardFill ?? Theme.onPrimary)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(tint?.onCard ?? Theme.primary))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(paused ? "Play" : "Pause")
            }
        }
        .foregroundColor(textColor)
        .padding(.leading, 8)
        .padding(.trailing, 6)
        .frame(height: 64)
        .background {
            if let tint {
                ZStack(alignment: .leading) {
                    tint.cardBase
                    GeometryReader { geometry in tint.cardFill.frame(width: geometry.size.width * (progress ?? 0)) }
                }
            }
        }
        .overlay(alignment: .bottomLeading) {
            if let progress, tint == nil {
                GeometryReader { geometry in
                    Theme.primary.frame(width: geometry.size.width * progress, height: 2)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                }
                .accessibilityHidden(true)
            }
        }
        .background(Theme.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: 18))
        .task(id: phoneQueue.tintedItem(on: vm)?.id) {
            if let item = phoneQueue.tintedItem(on: vm) { await tints.load(item.media, url: library.localURL(item.media)) }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .shadow(color: .black.opacity(0.25), radius: 8, y: 4)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Theme.surface.opacity(0.96))
        .onAppear { if vm.isConnected { vm.queryContext() } }
        .onChange(of: vm.state) { state in if state.isConnected { vm.queryContext() } }
    }
}
