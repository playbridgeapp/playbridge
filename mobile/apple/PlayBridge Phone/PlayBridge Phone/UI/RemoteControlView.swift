import SwiftUI
import UniformTypeIdentifiers

struct RemoteControlView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var browserStore: BrowserStore
    @State private var modes: [String: RemoteMode] = [:]
    @State private var looping = false
    @State private var volumeDraft = 0.5
    @State private var maximized = false
    @State private var presented: Presented?
    @State private var keyboardText = ""
    @State private var agentName = ""
    @State private var agentValue = ""
    @State private var addingAgent = false
    @State private var importingScript = false
    @State private var scriptImportDestinationID: String?
    @State private var seekFeedback: RemoteSeekBar.Feedback?
    @FocusState private var keyboardFocused: Bool

    private var policy: ReceiverRemotePolicy { vm.nativeRemotePolicy }
    private var context: String { !policy.isLegacy && vm.coordinator.activeContext == "browser_external" ? "browser" : vm.coordinator.activeContext }
    private var playback: TvPlaybackStatus? { vm.coordinator.playback }
    private var isBrowser: Bool { context == "browser" && (policy.isLegacy ? vm.supportsBrowser : true) }
    private var isImage: Bool { vm.coordinator.mediaKind == "image" }
    private var isAudio: Bool { vm.coordinator.mediaKind == "audio" }
    private var isLive: Bool { context == "player" && vm.coordinator.playerIsLive }
    private var duration: Double { max(0, Double(playback?.durationMs ?? 0)) }
    private var protocolID: String? { vm.isAirPlay ? "airplay" : vm.externalReceiver?.protocolID }
    private var videoActive: Bool {
        playback?.state == "playing" || playback?.state == "paused" || playback?.state == "buffering"
    }
    private var canSeek: Bool {
        if !policy.isLegacy { return policy.transport.contains("seek_absolute") }
        if vm.isAirPlay && !vm.airPlay.routeAvailable { return false }
        return RemoteMode.canSeek(context: context, externalProtocol: protocolID, duration: playback?.durationMs ?? 0,
                           isLive: isLive, isSeekable: vm.coordinator.playerIsSeekable, isImage: isImage)
    }
    private var availableModes: [RemoteMode] {
        if !policy.isLegacy {
            return [.context] + (policy.hasDpad ? [.dpad] : []) + (policy.hasPointer ? [.touchpad] : []) + (policy.text.contains("replace") ? [.keyboard] : [])
        }
        return RemoteMode.available(context: context, external: vm.isExternalReceiver || vm.isAirPlay, supportsRemote: RemoteMode.supportsRemote(externalProtocol: protocolID))
    }
    private var mode: RemoteMode {
        let selected = modes[context] ?? (context == "browser" ? .touchpad : .context)
        // Keep an open keyboard/draft stable when only authenticated metadata changes.
        // Execution rechecks current authority, including text removal.
        if selected == .keyboard && modes[context] != nil { return selected }
        return availableModes.contains(selected) ? selected : .context
    }
    private var supportsVolume: Bool { policy.isLegacy ? RemoteMode.supportsVolume(externalProtocol: protocolID) : policy.hasVolume("relative_step") }
    private func keyAllowed(_ key: String) -> Bool { policy.isLegacy || policy.keys.contains(key) }
    private func settingAllowed(_ id: String, legacy: Bool = true) -> Bool { policy.isLegacy ? legacy : policy.settings.contains(id) }
    private func browserAllowed(_ action: String) -> Bool { policy.isLegacy || policy.allowsBrowser(action) }
    private var canToggle: Bool { policy.isLegacy || policy.canToggle }
    private var anyVolume: Bool { supportsVolume || !policy.isLegacy && (policy.hasVolume("absolute") || policy.hasVolume("mute_toggle")) }
    private var canAddSubtitle: Bool { policy.isLegacy || !policy.tracks.isDisjoint(with: ["subtitle_url_add", "subtitle_resource_add"]) }

    var body: some View {
        VStack(spacing: 12) {
            modeSelector
            VStack(spacing: 12) {
                switch mode {
                case .context: contextBody
                case .dpad:
                    dpad.frame(maxWidth: .infinity, maxHeight: .infinity)
                    modeBottom
                case .touchpad:
                    touchpad.frame(maxWidth: .infinity, maxHeight: .infinity)
                    modeBottom
                case .keyboard:
                    keyboard.frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .disabled(!vm.isConnected && !vm.isAirPlay)
        .overlay {
            if let seekFeedback {
                RemoteSeekBar.FeedbackHUD(feedback: seekFeedback)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .sheet(item: $presented) { sheet in
            switch sheet {
            case .settings: settings
            case .subtitles: subtitles
            case .addSubtitle: addSubtitle
            case .more: browserMore
            case .scripts: scripts
            case .agents: agents
            case .airPlayQueue: AirPlayQueueSheet(controller: vm.airPlay)
            }
        }
        .fileImporter(isPresented: $importingScript, allowedContentTypes: [.javaScript, .plainText]) { result in
            guard case .success(let url) = result, scriptImportDestinationID == vm.destinationID else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty else { return }
            vm.installUserScript(name: url.deletingPathExtension().lastPathComponent, content: text)
            vm.queryUserScripts()
        }
        .onChange(of: playback?.title) { _ in looping = false }
        .onChange(of: vm.destinationID) { _ in modes = [:]; keyboardText = ""; looping = false; maximized = false; presented = nil; addingAgent = false; importingScript = false }
        .onChange(of: videoActive) { active in
            if isBrowser && active { modes["browser"] = .context }
        }
        .onAppear {
            if isBrowser && videoActive { modes["browser"] = .context }
        }
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
        switch context {
        case "player":
            VStack(spacing: 12) {
                VStack(spacing: 4) {
                    nowPlaying(title: playback?.title, kind: vm.coordinator.mediaKind)
                    if !vm.isExternalReceiver && !isImage { tracks }
                }
                episodes
                if vm.isAirPlay && vm.airPlay.preparing { ProgressView("Preparing subtitles…") }
                Spacer(minLength: 0)
                if (!isImage || duration > 0) && (policy.isLegacy || canToggle || canSeek || supportsVolume) {
                    seekBar(playing: playback?.state == nil || playback?.state == "playing" || playback?.state == "buffering", browser: false)
                }
                mediaControls
                if !policy.isLegacy && anyVolume { volumeRow }
                if vm.isAirPlay && vm.airPlay.routeAvailable { AirPlayVolumeControl().frame(height: 30).padding(.horizontal, 12) }
                if protocolID == "google_cast" {
                    Button("End receiver session") { playerCommand("end_receiver") }
                        .font(Theme.font(.caption)).foregroundStyle(Theme.danger)
                }
            }
        case "browser":
            VStack(spacing: 12) {
                if videoActive { nowPlaying(title: playback?.title, kind: "video") }
                Spacer(minLength: 0)
                if videoActive && (policy.isLegacy || canToggle || canSeek || supportsVolume) {
                    seekBar(playing: playback?.state == "playing" || playback?.state == "buffering", browser: true)
                } else if anyVolume {
                    volumeRow
                }
                if !policy.isLegacy && policy.transport.contains("seek_relative") && !canSeek {
                    HStack {
                        action("gobackward.10", "-10s") { vm.browserControl("seek_back") }
                        action("goforward.10", "+10s") { vm.browserControl("seek_forward") }
                    }
                }
                if isBrowser { browserRow }
            }
        default:
            VStack(spacing: 12) {
                Spacer(minLength: 0)
                Image(systemName: "tv").font(Theme.font(size: 56)).foregroundStyle(Theme.onSurfaceVariant.opacity(0.35))
                Text(vm.isConnected ? "Nothing playing" : "Connect to a TV").font(Theme.font(.headline))
                Text("on \(vm.receiverName ?? "your TV")").font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
                if availableModes.contains(.touchpad) || availableModes.contains(.dpad) {
                    Button { modes[context] = availableModes.contains(.touchpad) ? .touchpad : .dpad } label: {
                        Label("Show touchpad & controls", systemImage: "hand.draw")
                    }.buttonStyle(.borderedProminent).tint(Theme.primaryDim)
                }
                if !policy.isLegacy && anyVolume { volumeRow }
                Spacer(minLength: 0)
            }
        }
    }

    private func nowPlaying(title: String?, kind: String) -> some View {
        VStack(spacing: 0) {
            Text(title ?? "Playing on TV")
                .font(Theme.font(size: 16, weight: .semibold))
                .multilineTextAlignment(.center)
                .lineLimit(1)
                .fixedSize(horizontal: false, vertical: true)
            Text(kind == "audio" ? "Playing music" : kind == "image" ? "Viewing image" : "Playing video")
                .font(Theme.font(size: 12))
                .foregroundStyle(Theme.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var tracks: some View {
        HStack(spacing: 8) {
            if !isImage && (policy.isLegacy || policy.tracks.contains("audio_select")) {
                trackMenu("Audio", icon: "waveform", tracks: vm.coordinator.audioTracks, command: "audio_track:")
            }
            if !isAudio && !isImage && (policy.isLegacy || policy.tracks.contains("subtitle_select") || canAddSubtitle) {
                Button { presented = .subtitles } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "captions.bubble")
                        Text("Subs: \(vm.coordinator.subtitleTracks.first(where: \.selected)?.name ?? "Off")").lineLimit(1)
                    }.font(Theme.font(size: 12)).padding(.horizontal, 10).frame(maxWidth: .infinity).frame(height: 32)
                        .background(Theme.surfaceContainerHigh, in: Capsule())
                }.buttonStyle(.plain)
            }
            if !vm.isAirPlay && (policy.isLegacy || !policy.settings.isEmpty) {
                Button { presented = .settings } label: {
                    Image(systemName: "ellipsis").frame(width: 32, height: 32)
                        .background(Theme.surfaceContainerHigh, in: Capsule())
                }.accessibilityLabel("Player settings")
            }
        }
    }

    private func trackMenu(_ label: String, icon: String, tracks: [MediaTrack], command: String) -> some View {
        Menu {
            ForEach(tracks) { track in
                Button { playerCommand(command + track.id) } label: {
                    if track.selected { Label(track.name, systemImage: "checkmark") } else { Text(track.name) }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                Text("\(label): \(tracks.first(where: \.selected)?.name ?? "Default")").lineLimit(1)
                Spacer(minLength: 0)
                Image(systemName: "chevron.down").font(Theme.font(.caption2))
            }.font(Theme.font(size: 12)).padding(.horizontal, 10).frame(height: 32)
                .background(Theme.surfaceContainerHigh, in: Capsule())
        }.disabled(tracks.isEmpty)
    }

    @ViewBuilder private var episodes: some View {
        if let playlist = vm.coordinator.playlist, playlist.items.count > 1, policy.isLegacy || !policy.queue.isDisjoint(with: ["jump_index", "jump_item"]) {
            VStack(alignment: .leading, spacing: 8) {
                Text("PLAYLIST · \(playlist.currentIndex + 1)/\(playlist.totalCount)")
                    .font(Theme.font(.caption2).bold()).foregroundStyle(Theme.onSurfaceVariant)
                ScrollViewReader { reader in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 10) {
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

    private func seekBar(playing: Bool, browser: Bool) -> some View {
        RemoteSeekBar(
            positionMs: Double(playback?.positionMs ?? 0),
            durationMs: duration,
            isLive: browser ? false : isLive,
            isSeekable: policy.isLegacy && browser ? duration > 0 : canSeek,
            isPlaying: playing,
            enableVolume: supportsVolume && !isImage,
            enablePlayPause: canToggle,
            volumeLabel: policy.isLegacy ? "Volume" : policy.volumeLabel,
            onSeekTo: { playerCommand("seek_to:\($0)") },
            onVolumeUp: { vm.remote("volume_up") },
            onVolumeDown: { vm.remote("volume_down") },
            onPlayPause: { playPause(browser: browser) },
            feedback: $seekFeedback
        )
    }

    private var mediaControls: some View {
        HStack {
            if policy.isLegacy ? (!isImage && !isLive && (vm.isExternalReceiver ? RemoteMode.supportsExternalSeek(externalProtocol: protocolID) : vm.coordinator.playerIsSeekable)) : policy.transport.contains("seek_relative") {
                action("gobackward.10", "-10s") { playerCommand("seek_back") }
                action("goforward.10", "+10s") { playerCommand("seek_forward") }
            }
            if !vm.isExternalReceiver && !vm.isAirPlay && !isImage && settingAllowed("loop") {
                action("repeat", "Loop", tint: (policy.isLegacy ? looping : vm.coordinator.receiverLoop ?? looping) ? Theme.primary : Theme.onSurface) {
                    looping = !(policy.isLegacy ? looping : vm.coordinator.receiverLoop ?? looping)
                    playerCommand(looping ? "loop_on" : "loop_off")
                }
            }
            if vm.isAirPlay {
                action("forward.end.fill", "Next") { playerCommand("next") }.disabled(vm.airPlay.upcoming.isEmpty)
                action("list.bullet", "Queue") { presented = .airPlayQueue }
            }
            if policy.isLegacy || policy.transport.contains("stop") {
                action("stop.fill", "Stop", tint: Theme.danger) { playerCommand("stop") }
            }
        }.padding(.vertical, 8).frame(minHeight: 64).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder private var modeBottom: some View {
        if isBrowser {
            VStack(spacing: 8) {
                if anyVolume { volumeRow }
                if !policy.isLegacy && policy.transport.contains("seek_relative") && !canSeek {
                    HStack {
                        action("gobackward.10", "-10s") { vm.browserControl("seek_back") }
                        action("goforward.10", "+10s") { vm.browserControl("seek_forward") }
                    }
                }
                if isBrowser { browserRow }
            }
        } else if context == "player" {
            mediaControls
            if !policy.isLegacy {
                HStack {
                    if keyAllowed("back") { action("arrow.uturn.backward", "Back") { vm.remote("back") } }
                    if keyAllowed("home") { action("house", "Home") { vm.remote("home") } }
                }
                if anyVolume { volumeRow }
            }
        }
    }

    private var volumeRow: some View {
        VStack(spacing: 8) {
            if !policy.isLegacy { Text(policy.volumeLabel).font(Theme.font(.caption)) }
            HStack(spacing: 18) {
                if supportsVolume {
                    Button { vm.remote("volume_down") } label: { Image(systemName: "minus").frame(width: 44, height: 44) }
                        .accessibilityLabel(policy.isLegacy ? "Volume down" : policy.volumeLabel + " down")
                    Image(systemName: "speaker.wave.2").foregroundStyle(Theme.onSurfaceVariant)
                    Button { vm.remote("volume_up") } label: { Image(systemName: "plus").frame(width: 44, height: 44) }
                        .accessibilityLabel(policy.isLegacy ? "Volume up" : policy.volumeLabel + " up")
                }
                if !policy.isLegacy && policy.hasVolume("mute_toggle") {
                    Button { vm.remote("mute") } label: { Image(systemName: "speaker.slash").frame(width: 44, height: 44) }
                        .accessibilityLabel(policy.volumeLabel + " mute toggle")
                }
            }
            if !policy.isLegacy && policy.hasVolume("absolute") {
                Slider(value: $volumeDraft, in: 0...1) { editing in
                    if !editing { vm.setReceiverVolume(volumeDraft) }
                }.accessibilityLabel("Set " + policy.volumeLabel)
                Text("Set " + policy.volumeLabel).font(Theme.font(.caption2))
            }
            if !policy.isLegacy, let scope = vm.coordinator.receiverVolumeScope,
               scope == policy.volume?.scope || policy.volume?.scope == "system" && policy.volume?.fallbackScope == "app" && policy.hasVolume("relative_step") {
                if policy.hasVolume("level_report"), let level = vm.coordinator.receiverVolumeLevel {
                    Text("\(scope == "app" ? "App volume" : "TV volume"): \(Int((level * 100).rounded()))%")
                }
                if policy.hasVolume("mute_report"), let muted = vm.coordinator.receiverVolumeMuted {
                    Text((scope == "app" ? "App volume" : "TV volume") + (muted ? " muted" : " unmuted"))
                }
            }
        }.padding(.horizontal, 12).frame(maxWidth: .infinity).frame(minHeight: 48)
            .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }

    private var browserRow: some View {
        HStack {
            if keyAllowed("back") { action("arrow.uturn.backward", "Back") { vm.remote("back") } }
            if browserAllowed("forward") { action("arrow.forward", "Forward") { vm.browserControl("forward") } }
            if browserAllowed("refresh") { action("arrow.clockwise", "Refresh") { vm.browserControl("refresh") } }
            if browserAllowed("maximize_video") { action(maximized ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", maximized ? "Restore" : "Fullscreen") {
                maximized.toggle()
                vm.browserControl(maximized ? "maximize_video" : "restore_video")
            } }
            if keyAllowed("home") { action("house", "Home") { vm.remote("home") } }
            if policy.isLegacy || !policy.browser.isDisjoint(with: ["cycle_source", "unmute", "manage_user_scripts", "manage_user_agent"]) { action("ellipsis", "More") { presented = .more } }
        }.padding(.vertical, 8).frame(minHeight: 64).background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }

    private var touchpad: some View {
        ZStack(alignment: .bottom) {
            RemoteTouchpad(imageGestures: isImage && context == "player") { event, dx, dy in
                guard vm.isConnected else { return }
                vm.mouse(event: event, dx: dx, dy: dy)
            } onGestureEnd: {
                vm.endPointerGesture()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 24))
            VStack(spacing: 4) {
                Image(systemName: "hand.draw").font(Theme.font(.title2)).opacity(0.15)
                Text(policy.isLegacy ? (isImage && context == "player"
                     ? "1 finger: move  ·  Pinch: zoom  ·  Twist: rotate  ·  Double-tap: reset"
                     : "1 finger: move  ·  2 fingers: scroll  ·  Pinch: zoom  ·  Tap: click  ·  Long-press+drag: drag") : pointerHint)
                    .multilineTextAlignment(.center)
            }
            .font(Theme.font(.caption2)).foregroundStyle(Theme.onSurfaceVariant.opacity(0.28))
            .padding(22).allowsHitTesting(false).frame(maxHeight: .infinity, alignment: .center)
            if isImage && context == "player" && (policy.isLegacy || policy.pointer.contains("rotate")) {
                HStack(spacing: 16) {
                    Button { vm.mouse(event: "rotate", dx: -90, dy: 0) } label: {
                        Image(systemName: "rotate.left").frame(width: 44, height: 44)
                    }.accessibilityLabel("Rotate left 90 degrees")
                    Button { vm.mouse(event: "rotate", dx: 90, dy: 0) } label: {
                        Image(systemName: "rotate.right").frame(width: 44, height: 44)
                    }.accessibilityLabel("Rotate right 90 degrees")
                }.padding(.bottom, 18)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var pointerHint: String {
        [("move", "1 finger: move"), ("scroll", "2 fingers: scroll"), ("zoom", "Pinch: zoom"),
         ("click", "Tap: click"), ("down", "Long-press+drag: drag"), ("rotate", "Twist: rotate"),
         ("reset", "Double-tap: reset")].filter { policy.pointer.contains($0.0) }.map { $0.1 }.joined(separator: "  ·  ")
    }

    private var dpad: some View {
        ZStack {
            Circle().fill(Theme.surfaceContainer).frame(width: 236, height: 236)
            Circle().stroke(Theme.outlineVariant, lineWidth: 1).frame(width: 236, height: 236)
            VStack(spacing: 4) {
                pad("chevron.up", "Up", "dpad_up")
                HStack(spacing: 4) {
                    pad("chevron.left", "Left", "dpad_left")
                    Button { vm.remote("dpad_center") } label: {
                        Text("OK").font(Theme.font(size: 16, weight: .bold))
                            .frame(width: 72, height: 72)
                            .foregroundStyle(Theme.onPrimary)
                            .background(Theme.primary, in: Circle())
                    }.accessibilityLabel("Select").disabled(!keyAllowed("dpad_center"))
                        .opacity(keyAllowed("dpad_center") ? 1 : 0).accessibilityHidden(!keyAllowed("dpad_center"))
                    pad("chevron.right", "Right", "dpad_right")
                }
                pad("chevron.down", "Down", "dpad_down")
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func pad(_ icon: String, _ label: String, _ key: String) -> some View {
        Button { vm.remote(key) } label: {
            Image(systemName: icon).font(Theme.font(size: 22, weight: .semibold))
                .frame(width: 64, height: 52)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label).disabled(!keyAllowed(key))
        .opacity(keyAllowed(key) ? 1 : 0).accessibilityHidden(!keyAllowed(key))
    }

    private var keyboard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Tap a text box on the TV, then type here — it's sent as you type.")
                .font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
            TextField("Type to send to TV…", text: $keyboardText)
                .focused($keyboardFocused)
                .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.go)
                .padding(16).background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
                .onChange(of: keyboardText) { text in
                    guard vm.isConnected, mode == .keyboard else { return }
                    vm.remote("text:" + Data(text.utf8).base64EncodedString())
                }.onSubmit { vm.remote("key_enter") }
            Button { vm.remote("key_enter") } label: { Label("Enter", systemImage: "return") }
                .buttonStyle(.borderedProminent).tint(Theme.primaryDim).frame(maxWidth: .infinity)
                .disabled(!policy.isLegacy && !policy.allowsKey("key_enter"))
            Spacer()
        }.padding(16).background(Theme.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 20))
            .onAppear { keyboardFocused = true }
    }

    private var settings: some View {
        NavigationStack {
            Form {
                if settingAllowed("quality", legacy: vm.coordinator.qualityAvailable) && vm.coordinator.videoTracks.filter({ $0.id != "auto" }).count > 1 {
                    Section("Quality") {
                        chipRow(vm.coordinator.videoTracks.map { ($0.name, $0.id) }, selected: vm.coordinator.qualityMaxHeight == 0 ? "auto" : "max:\(vm.coordinator.qualityMaxHeight)") { id in
                            let value = id == "auto" ? "auto" : id.replacingOccurrences(of: "max:", with: "")
                            playerCommand("video_quality:\(value)")
                        }
                    }
                }
                if settingAllowed("speed", legacy: vm.coordinator.speedAvailable) {
                    Section("Speed") {
                        chipRow([0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map { ($0 == 1 ? "1x" : "\($0)x", String($0)) }, selected: speedKey(vm.coordinator.playerSpeed)) { value in
                            if let speed = Float(value) { playerCommand("speed:\(speed)") }
                        }
                    }
                }
                if settingAllowed("scaling", legacy: vm.coordinator.scalingAvailable) {
                    Section("Scaling") {
                        chipRow([("Fit", "Fit"), ("Crop to fill", "Zoom"), ("Stretch", "Fill")], selected: vm.coordinator.playerScaling) { playerCommand("scaling:\($0)") }
                    }
                }
                if settingAllowed("subtitle_offset") {
                    Section("Subtitle offset") {
                        HStack {
                            Button("−250ms") { playerCommand("sub_offset:-250") }
                            Spacer()
                            Text("\(vm.coordinator.subtitleOffsetMs) ms")
                            Spacer()
                            Button("+250ms") { playerCommand("sub_offset:250") }
                        }
                    }
                }
                if settingAllowed("audio_boost", legacy: vm.coordinator.audioBoostAvailable) {
                    Toggle("Audio boost", isOn: Binding(get: { vm.coordinator.audioBoost }, set: { _ in playerCommand("audio_boost") }))
                }
                if settingAllowed("engine") {
                    Section("Player engine") {
                        chipRow(policy.isLegacy ? [("ExoPlayer", "exo"), ("MPV", "mpv")] : policy.playerEngines.map { (ReceiverRemotePolicy.playerLabels[$0]!, $0) }, selected: vm.coordinator.playerEngine) { playerCommand("switch_player:\($0)") }
                    }
                }
            }
            .navigationTitle("Player settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { presented = nil } } }
        }
    }

    private var subtitles: some View {
        NavigationStack {
            List {
                if policy.isLegacy || policy.tracks.contains("subtitle_select") {
                    Button("Off") { playerCommand("sub_track:\(subtitleOffID)"); presented = nil }
                    ForEach(groupedSubtitles, id: \.key) { group in
                        Section(group.label) {
                            ForEach(group.tracks) { track in
                                Button { playerCommand("sub_track:\(track.id)"); presented = nil } label: {
                                    HStack {
                                        Text(track.name)
                                        Spacer()
                                        if track.selected { Image(systemName: "checkmark").foregroundStyle(Theme.primary) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Subtitles").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) {
                if vm.isAirPlay {
                    Text("Added SRT or WebVTT subtitles require a compatible HLS stream and Wi-Fi. Adding a track may briefly reload the video at its current position.")
                        .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                        .padding().frame(maxWidth: .infinity).background(Theme.surface)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !vm.isExternalReceiver && !isAudio && !isImage && canAddSubtitle {
                        Button { presented = .addSubtitle } label: { Label("Add", systemImage: "plus") }
                            .disabled(vm.isAirPlay && vm.airPlay.preparing)
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { presented = nil } }
            }
        }
    }

    private var addSubtitle: some View {
        let detector = browserStore.activeTab?.detector
        return SubtitleSourcePickerView(detector: detector,
            detected: detector?.videos.filter {
                $0.isSubtitle && (policy.isLegacy || policy.tracks.contains("subtitle_resource_add") || VideoDetector.subtitleHeaders(for: $0).isEmpty)
            } ?? [], title: "Add subtitle",
            onDetected: { item in
                vm.addSubtitle(url: item.url, headers: VideoDetector.subtitleHeaders(for: item), label: item.displayTitle)
            },
            onLocal: { file in
                let destination = vm.destinationID
                guard let served = await vm.serveLocalSubtitle(file), destination == vm.destinationID else { return false }
                return vm.addSubtitle(url: served, label: file.lastPathComponent)
            },
            onURL: { vm.addSubtitle(url: $0) })
    }

    private var browserMore: some View {
        NavigationStack {
            VStack(spacing: 18) {
                Text("More controls").font(Theme.font(.headline))
                HStack {
                    if policy.isLegacy { action("shield", "Ad Block") { vm.browserControl("toggle_ublock"); presented = nil } }
                    if browserAllowed("video_target_cycle") { action("square.stack.3d.up", "Source") { vm.browserControl("video_target_cycle"); presented = nil } }
                    if browserAllowed("video_unmute") { action("speaker.wave.2", "Unmute") { vm.browserControl("video_unmute"); presented = nil } }
                    if policy.isLegacy || policy.allowsManager("manage_user_scripts") { action("curlybraces", "Scripts") { vm.queryUserScripts(); presented = .scripts } }
                    if policy.isLegacy || policy.allowsManager("manage_user_agent") { action("globe", "User Agent") { vm.queryUserAgents(); presented = .agents } }
                }
                Spacer()
            }.padding(24)
        }.presentationDetents([.medium])
    }

    private var scripts: some View {
        NavigationStack {
            List {
                if vm.coordinator.installedUserScripts.isEmpty {
                    Text("None installed.").foregroundStyle(Theme.onSurfaceVariant)
                }
                ForEach(vm.coordinator.installedUserScripts, id: \.self) { name in
                    HStack {
                        Text(name)
                        Spacer()
                        Button { vm.installUserScript(name: name, content: ""); vm.queryUserScripts() } label: {
                            Image(systemName: "trash").foregroundStyle(Theme.danger)
                        }.accessibilityLabel("Remove \(name)")
                    }
                }
            }
            .navigationTitle("User scripts on TV").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { presented = nil } }
                ToolbarItem(placement: .confirmationAction) { Button("Install") { scriptImportDestinationID = vm.destinationID; importingScript = true } }
            }
        }
    }

    private var agents: some View {
        NavigationStack {
            List {
                Button { vm.setUserAgent(name: "", value: "", save: false) } label: {
                    agentRow("Default (Mobile)", selected: vm.coordinator.userAgentActive.isEmpty)
                }
                ForEach(RemoteUserAgents.presets, id: \.label) { preset in
                    Button { vm.setUserAgent(name: preset.label, value: preset.value, save: false) } label: {
                        agentRow(preset.label, selected: vm.coordinator.userAgentActive == preset.label)
                    }
                }
                if !vm.coordinator.savedUserAgents.isEmpty {
                    Section("Saved on TV") {
                        ForEach(vm.coordinator.savedUserAgents, id: \.name) { entry in
                            HStack {
                                Button { vm.setUserAgent(name: entry.name, value: entry.value, save: true) } label: {
                                    agentRow(entry.name, selected: vm.coordinator.userAgentActive == entry.name)
                                }
                                Button { vm.setUserAgent(name: entry.name, value: "", save: true) } label: {
                                    Image(systemName: "trash").foregroundStyle(Theme.danger)
                                }.accessibilityLabel("Remove \(entry.name)")
                            }
                        }
                    }
                }
            }
            .navigationTitle("User Agent (TV Browser)").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { presented = nil } }
                ToolbarItem(placement: .confirmationAction) { Button("Add") { addingAgent = true } }
            }
            .alert("Add custom user agent", isPresented: $addingAgent) {
                TextField("Name", text: $agentName)
                TextField("User agent string", text: $agentValue)
                Button("Save") {
                    let name = agentName.trimmingCharacters(in: .whitespacesAndNewlines)
                    let value = agentValue.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !name.isEmpty, !value.isEmpty else { return }
                    vm.setUserAgent(name: name, value: value, save: true)
                    agentName = ""
                    agentValue = ""
                }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    private func agentRow(_ label: String, selected: Bool) -> some View {
        HStack {
            Text(label).fontWeight(selected ? .semibold : .regular)
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(Theme.primary) }
        }
    }

    private func chipRow(_ options: [(String, String)], selected: String, onSelect: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(options, id: \.1) { option in
                    Button(option.0) { onSelect(option.1) }
                        .buttonStyle(.borderedProminent)
                        .tint(option.1 == selected ? Theme.primaryDim : Theme.surfaceContainerHigh)
                }
            }
        }
    }

    private var groupedSubtitles: [SubtitleGroup] {
        let tracks = vm.coordinator.subtitleTracks.filter { !["off", "none", "no", "-1"].contains($0.id.lowercased()) }
        let embedded = tracks.filter { !isExternalSubtitle($0) }
        let remote = tracks.filter { isExternalSubtitle($0) && !$0.name.contains("OpenSubtitles #") }
        let external = tracks.filter { isExternalSubtitle($0) && $0.name.contains("OpenSubtitles #") }
        return [
            SubtitleGroup(key: "embedded", label: vm.isAirPlay ? "Available tracks" : "Embedded", tracks: embedded),
            SubtitleGroup(key: "remote", label: "Phone Remote", tracks: remote),
            SubtitleGroup(key: "external", label: "External", tracks: external),
        ].filter { !$0.tracks.isEmpty }
    }

    private var subtitleOffID: String {
        vm.coordinator.subtitleTracks.first { ["off", "none", "no", "-1"].contains($0.id.lowercased()) }?.id ?? "none"
    }

    private func isExternalSubtitle(_ track: MediaTrack) -> Bool {
        track.type == "external_sub" || track.id.hasPrefix("external_") || track.id.contains("://")
    }

    private func speedKey(_ value: Float) -> String {
        let speeds: [Float] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
        let nearest = speeds.min { abs($0 - value) < abs($1 - value) } ?? 1
        return String(nearest)
    }

    private func playPause(browser: Bool) {
        if !policy.isLegacy {
            if browser { vm.browserControl("toggle_play") } else { vm.control(policy.toggleCommand) }
        } else if browser { vm.browserControl("toggle_play") }
        else { playerCommand(playback?.state == "playing" ? "pause" : "play") }
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
        if isBrowser { vm.browserControl(command == "play" || command == "pause" ? "toggle_play" : command) }
        else { vm.control(command) }
    }

    private enum Presented: String, Identifiable {
        case settings, subtitles, addSubtitle, more, scripts, agents, airPlayQueue
        var id: String { rawValue }
    }
}

private struct SubtitleGroup {
    let key: String
    let label: String
    let tracks: [MediaTrack]
}

enum RemoteUserAgents {
    static let presets: [(label: String, value: String)] = [
        ("Chrome — Android", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"),
        ("Chrome — Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
        ("Chrome — macOS", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"),
        ("Firefox — Android", "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"),
        ("Firefox — Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"),
        ("Safari — iPhone", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"),
        ("Safari — macOS", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"),
        ("Samsung Internet — Android", "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"),
    ]
}
