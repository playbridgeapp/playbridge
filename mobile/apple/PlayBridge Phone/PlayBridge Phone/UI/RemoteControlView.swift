import SwiftUI

/// Context-first layout matching the Android remote, with receiver-aware controls.
struct RemoteControlView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @State private var modes: [String: RemoteMode] = [:]
    @State private var castVolume = 0.5
    @State private var looping = false
    @State private var maximized = false
    @State private var showSettings = false
    @State private var keyboardText = ""
    @State private var seekPosition = 0.0
    @State private var scrubbing = false
    @State private var pendingSeek: Double?
    @State private var seekReset: Task<Void, Never>?

    private var context: String { vm.coordinator.activeContext }
    private var playback: TvPlaybackStatus? { vm.coordinator.playback }
    private var isBrowser: Bool { context == "browser" && vm.supportsBrowser }
    private var isImage: Bool { vm.coordinator.mediaKind == "image" }
    private var isLive: Bool { context == "player" && vm.coordinator.playerIsLive }
    private var duration: Double { max(0, Double(playback?.durationMs ?? 0)) }
    private var canSeek: Bool {
        RemoteMode.canSeek(context: context, externalProtocol: vm.externalReceiver?.protocolID,
                           duration: playback?.durationMs ?? 0, isLive: isLive,
                           isSeekable: vm.coordinator.playerIsSeekable) && !isImage
    }
    private var availableModes: [RemoteMode] {
        RemoteMode.available(context: context, external: vm.isExternalReceiver, browser: vm.supportsBrowser)
    }
    private var mode: RemoteMode {
        let selected = modes[context] ?? (isBrowser ? .touchpad : .context)
        return availableModes.contains(selected) ? selected : .context
    }
    private var position: Double {
        min(max(0, scrubbing ? seekPosition : pendingSeek ?? Double(playback?.positionMs ?? 0)), max(1, duration))
    }

    var body: some View {
        VStack(spacing: 16) {
            if availableModes.count > 1 { modeSelector }
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 16) {
                        switch mode {
                        case .context: contextBody
                        case .dpad:
                            Spacer(minLength: 12)
                            dpad
                            Spacer(minLength: 12)
                            navigationRow
                            if isBrowser { browserPlaybackControls } else { transport }
                        case .touchpad:
                            RemoteTouchpad { event, dx, dy in
                                guard vm.isConnected else { return }
                                vm.mouse(event: event, dx: dx, dy: dy)
                            }
                            .frame(height: max(240, geometry.size.height - 170))
                            .background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 24))
                            .overlay(alignment: .top) {
                                VStack(spacing: 4) {
                                    Image(systemName: "hand.draw").font(Theme.font(.title2))
                                    Text("Drag to move · tap to click")
                                    Text("Two fingers to scroll").font(Theme.font(.caption2))
                                }.font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                                    .padding(22).allowsHitTesting(false)
                            }
                            navigationRow
                            if isBrowser { browserPlaybackControls } else { transport }
                        case .keyboard: keyboard
                        }
                    }.frame(maxWidth: .infinity).frame(minHeight: geometry.size.height)
                }
            }
        }
        .disabled(!vm.isConnected)
        .sheet(isPresented: $showSettings) { settings }
        .onChange(of: playback?.title) { _ in resetSeek(); looping = false }
        .onChange(of: context) { _ in resetSeek() }
        .onChange(of: vm.destinationID) { _ in modes = [:]; keyboardText = ""; resetSeek(); looping = false }
        .onChange(of: vm.isConnected) { connected in if !connected { resetSeek() } }
        .onDisappear { seekReset?.cancel() }
    }

    private var modeSelector: some View {
        HStack(spacing: 4) {
            ForEach(availableModes) { candidate in
                Button { modes[context] = candidate } label: {
                    HStack(spacing: 6) {
                        Image(systemName: candidate.icon).font(Theme.font(size: 15))
                        Text(candidate.rawValue).font(Theme.font(size: 11, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 10)
                    .foregroundStyle(mode == candidate ? Theme.onPrimary : Theme.onSurfaceVariant)
                    .background(mode == candidate ? Theme.primary : .clear, in: Capsule())
                }.buttonStyle(.plain).accessibilityAddTraits(mode == candidate ? .isSelected : [])
            }
        }.padding(4).background(Theme.surfaceContainer, in: Capsule())
    }

    @ViewBuilder private var contextBody: some View {
        if context == "player" || isBrowser {
            VStack(spacing: 6) {
                Text(isBrowser ? "TV BROWSER" : "NOW PLAYING")
                    .font(Theme.font(.caption2).bold()).tracking(2).foregroundStyle(Theme.primary)
                Text(playback?.title ?? (isBrowser ? "Browsing on your TV" : "Untitled"))
                    .font(Theme.font(.title3).bold()).multilineTextAlignment(.center).lineLimit(3)
            }.padding(.top, 8)
            if !vm.isExternalReceiver && !isBrowser && !isImage { tracks }
            episodes
            Spacer(minLength: 16)
            if !isImage && (!isBrowser || playback != nil) { timeline }
            if isBrowser { navigationRow }
            if isBrowser { browserPlaybackControls } else { transport }
        } else {
            Spacer(minLength: 24)
            Image(systemName: "tv").font(Theme.font(size: 56)).foregroundStyle(Theme.primary.opacity(0.65))
            Text(vm.isConnected ? "Nothing playing" : "Connect to a TV").font(Theme.font(.headline))
            Text("on \(vm.receiverName ?? "your TV")").font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
            if availableModes.contains(.dpad) {
                Button("Show remote controls") { modes[context] = vm.supportsBrowser ? .touchpad : .dpad }
                    .buttonStyle(.borderedProminent).tint(Theme.primaryDim)
            }
            Spacer(minLength: 24)
            if vm.isExternalReceiver { transport }
        }
        if vm.externalReceiver?.protocolID == "google_cast" {
            HStack {
                Image(systemName: "speaker.wave.1")
                Slider(value: $castVolume, in: 0...1, onEditingChanged: { editing in
                    if !editing { vm.setCastVolume(castVolume) }
                }).accessibilityLabel("Set receiver volume")
                Image(systemName: "speaker.wave.3")
            }.foregroundStyle(Theme.onSurfaceVariant).tint(Theme.primary)
            Button("End receiver session") { playerCommand("end_receiver") }
                .font(Theme.font(.caption)).foregroundStyle(Theme.danger)
        }
    }

    private var tracks: some View {
        HStack(spacing: 8) {
            trackMenu("Audio", icon: "waveform", tracks: vm.coordinator.audioTracks, command: "audio_track:")
            if vm.coordinator.mediaKind != "audio" {
                trackMenu("Subs", icon: "captions.bubble", tracks: vm.coordinator.subtitleTracks, command: "sub_track:", off: true)
            }
            if vm.coordinator.speedAvailable || vm.coordinator.scalingAvailable {
                Button { showSettings = true } label: {
                    Image(systemName: "ellipsis").frame(width: 44, height: 44)
                        .background(Theme.surfaceContainerHigh, in: Capsule())
                }.accessibilityLabel("Player settings")
            }
        }
    }

    private func trackMenu(_ label: String, icon: String, tracks: [MediaTrack], command: String, off: Bool = false) -> some View {
        Menu {
            if off { Button("Off") { playerCommand(command + "none") } }
            ForEach(tracks) { track in
                Button { playerCommand(command + track.id) } label: {
                    if track.selected { Label(track.name, systemImage: "checkmark") } else { Text(track.name) }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                Text("\(label): \(tracks.first(where: \.selected)?.name ?? (off ? "Off" : "Default"))")
                    .lineLimit(1)
                Spacer(minLength: 0)
                Image(systemName: "chevron.down").font(Theme.font(.caption2))
            }.font(Theme.font(.caption)).padding(12)
                .background(Theme.surfaceContainerHigh, in: Capsule())
        }.disabled(tracks.isEmpty)
    }

    @ViewBuilder private var episodes: some View {
        if let playlist = vm.coordinator.playlist, playlist.items.count > 1 {
            VStack(alignment: .leading, spacing: 8) {
                Text("PLAYLIST · \(playlist.currentIndex + 1)/\(playlist.totalCount)")
                    .font(Theme.font(.caption2).bold()).foregroundStyle(Theme.onSurfaceVariant)
                ScrollViewReader { reader in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(playlist.items) { item in
                                Button { vm.jump(to: item) } label: {
                                    VStack(alignment: .leading, spacing: 10) {
                                        Image(systemName: item.index == playlist.currentIndex ? "play.circle.fill" : "play.rectangle")
                                            .font(Theme.font(.title2)).foregroundStyle(Theme.primary)
                                        Text(item.title).font(Theme.font(.caption)).lineLimit(2)
                                    }.frame(width: 136, height: 90, alignment: .leading).padding(12)
                                        .background(item.index == playlist.currentIndex ? Theme.secondaryContainer : Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
                                }.buttonStyle(.plain).id(item.index)
                            }
                        }
                    }
                    .onAppear { reader.scrollTo(playlist.currentIndex, anchor: .center) }
                    .onChange(of: playlist.currentIndex) { index in withAnimation { reader.scrollTo(index, anchor: .center) } }
                }
            }
        }
    }

    private var timeline: some View {
        VStack(spacing: 12) {
            HStack(spacing: 14) {
                Button { playerCommand(playback?.state == "playing" ? "pause" : "play") } label: {
                    Image(systemName: playback?.state == "playing" ? "pause.fill" : "play.fill")
                        .font(Theme.font(.title2)).frame(width: 54, height: 54)
                        .foregroundStyle(Theme.onPrimary).background(Theme.primary, in: Circle())
                }.accessibilityLabel(playback?.state == "playing" ? "Pause" : "Play")
                VStack(spacing: 4) {
                    Slider(value: Binding(get: { position }, set: { seekPosition = $0 }), in: 0...max(1, duration), onEditingChanged: { editing in
                        if editing { seekPosition = position }
                        scrubbing = editing
                        if !editing, canSeek {
                            let target = min(max(0, seekPosition), duration)
                            guard let ms = Int64(exactly: target.rounded(.towardZero)) else { return }
                            playerCommand("seek_to:\(ms)")
                            pendingSeek = target
                            seekReset?.cancel()
                            seekReset = Task { @MainActor in
                                try? await Task.sleep(for: .seconds(2))
                                if !Task.isCancelled { pendingSeek = nil }
                            }
                        }
                    }).tint(Theme.primary).disabled(!canSeek).accessibilityLabel("Playback position")
                    HStack {
                        Text(RemoteMode.time(Int64(min(position, Double(Int64.max / 2)))))
                        Spacer()
                        Text(isLive ? "● LIVE" : duration > 0 ? RemoteMode.time(playback?.durationMs ?? 0) : "--:--")
                    }.font(Theme.font(.caption2)).monospacedDigit().foregroundStyle(Theme.onSurfaceVariant)
                }
            }
        }.padding(14).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 20))
    }

    private var transport: some View {
        HStack {
            if mode != .context || context == "idle" {
                action(playback?.state == "playing" ? "pause.fill" : "play.fill", playback?.state == "playing" ? "Pause" : "Play") {
                    playerCommand(playback?.state == "playing" ? "pause" : "play")
                }
            }
            if !isImage && !isLive && (context != "player" || vm.coordinator.playerIsSeekable) {
                action("backward.fill", "Rewind") { playerCommand("seek_back") }
                action("forward.fill", "Forward") { playerCommand("seek_forward") }
            }
            if !vm.isExternalReceiver && !isImage && !isBrowser {
                action("repeat", "Loop", tint: looping ? Theme.primary : Theme.onSurface) {
                    looping.toggle(); playerCommand(looping ? "loop_on" : "loop_off")
                }
            }
            if !vm.isExternalReceiver && vm.supportsBrowser && !isImage {
                action("speaker.minus", "Vol −") { vm.remote("volume_down") }
                action("speaker.plus", "Vol +") { vm.remote("volume_up") }
            }
            action("stop.fill", "Stop", tint: Theme.danger) { playerCommand("stop") }
        }.padding(8).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 18))
    }

    private var dpad: some View {
        VStack(spacing: 0) {
            pad("chevron.up", "Up", "dpad_up")
            HStack(spacing: 0) {
                pad("chevron.left", "Left", "dpad_left")
                Button { vm.remote("dpad_center") } label: {
                    Text("OK").font(Theme.font(.title3).bold()).frame(width: 88, height: 88)
                        .foregroundStyle(Theme.onPrimary).background(Theme.primary, in: Circle())
                }.accessibilityLabel("Select")
                pad("chevron.right", "Right", "dpad_right")
            }
            pad("chevron.down", "Down", "dpad_down")
        }.padding(12).background(Theme.surfaceContainerLow, in: Circle())
    }

    private func pad(_ icon: String, _ label: String, _ key: String) -> some View {
        Button { vm.remote(key) } label: {
            Image(systemName: icon).font(Theme.font(.title2)).frame(width: 80, height: 72)
        }.accessibilityLabel(label)
    }

    private var browserPlaybackControls: some View {
        HStack {
            action("speaker.minus", "Volume −") { vm.remote("volume_down") }
            action("playpause.fill", "Play / pause") { vm.browserControl("toggle_play") }
            action("speaker.plus", "Volume +") { vm.remote("volume_up") }
        }.padding(8).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 18))
    }

    private var navigationRow: some View {
        HStack {
            action("arrow.uturn.backward", "Back") { vm.remote("back") }
            if isBrowser {
                action("arrow.forward", "Forward") { vm.browserControl("forward") }
                action("arrow.clockwise", "Refresh") { vm.browserControl("refresh") }
                action(maximized ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", maximized ? "Restore" : "Fullscreen") {
                    maximized.toggle(); vm.browserControl(maximized ? "maximize_video" : "restore_video")
                }
                action("house", "Home") { vm.remote("home") }
            }
        }.padding(8).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 18))
    }

    private var keyboard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Select a text field on the TV, then type here.").font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
            TextField("Type to send to TV…", text: $keyboardText)
                .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.go)
                .padding(16).background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
                .onChange(of: keyboardText) { text in
                    guard vm.isConnected, mode == .keyboard else { return }
                    vm.remote("text:" + Data(text.utf8).base64EncodedString())
                }.onSubmit { vm.remote("key_enter") }
            HStack {
                Button("Clear") { keyboardText = "" }.buttonStyle(.bordered)
                Spacer()
                Button("Enter") { vm.remote("key_enter") }.buttonStyle(.borderedProminent).tint(Theme.primaryDim)
            }
            Spacer()
        }.padding(16).background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 20))
    }

    private var settings: some View {
        NavigationStack {
            Form {
                if vm.coordinator.speedAvailable {
                    Section("Speed") {
                        Picker("Speed", selection: Binding(get: { vm.coordinator.playerSpeed }, set: { playerCommand("speed:\($0)") })) {
                            ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 1.75, 2], id: \.self) { Text("\($0.formatted())×").tag($0) }
                        }
                    }
                }
                if vm.coordinator.scalingAvailable {
                    Picker("Picture size", selection: Binding(get: { vm.coordinator.playerScaling }, set: { playerCommand("scaling:\($0)") })) {
                        Text("Fit").tag("Fit"); Text("Crop to fill").tag("Zoom"); Text("Stretch").tag("Fill")
                    }
                }
                if !vm.coordinator.speedAvailable && !vm.coordinator.scalingAvailable {
                    Text("This receiver has not reported additional playback settings.").foregroundStyle(.secondary)
                }
            }.navigationTitle("Player settings").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showSettings = false } } }
        }.presentationDetents([.medium, .large])
    }

    private func action(_ icon: String, _ label: String, tint: Color = Theme.onSurface, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(Theme.font(size: 20))
                Text(label).font(Theme.font(.caption2)).lineLimit(1).minimumScaleFactor(0.7)
            }.frame(maxWidth: .infinity).frame(minHeight: 48).foregroundStyle(tint)
        }.buttonStyle(.plain)
    }

    private func playerCommand(_ command: String) {
        guard vm.isConnected else { return }
        if isBrowser {
            vm.browserControl(command == "play" || command == "pause" ? "toggle_play" : command)
        } else { vm.control(command) }
    }

    private func resetSeek() {
        seekReset?.cancel(); pendingSeek = nil; scrubbing = false; seekPosition = 0
    }
}
