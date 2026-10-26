import SwiftUI
import UniformTypeIdentifiers

struct RemoteControlView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var atv: AtvRemoteCoordinator
    @EnvironmentObject private var browserStore: BrowserStore
    @EnvironmentObject private var nav: NavigationViewModel
    @ObservedObject private var phoneQueue = PhoneQueueCoordinator.shared
    @ObservedObject private var tints = QueueTintStore.shared
    /// A phone list colours the Remote from its artwork, like Up next.
    private var palette: RemotePalette { phoneQueue.tintedItem(on: vm) != nil ? RemotePalette(tints.tint) : .theme }
    @AppStorage(RemoteHapticPreferences.enabledKey) private var hapticsEnabled = true
    @AppStorage(RemoteHapticPreferences.levelKey) private var hapticsLevel: RemoteHapticLevel = .light
    @State private var padPreferences = RemotePadPreferences()
    @State private var showPairedTVs = false
    @State private var looping = false
    @State private var maximized = false
    @State private var presented: Presented?
    @State private var keyboardTicket: RemoteKeyboardTicket?
    @State private var agentName = ""
    @State private var agentValue = ""
    @State private var addingAgent = false
    @State private var importingScript = false
    @State private var scriptImportDestinationID: String?
    @State private var seekFeedback: RemoteSeekBar.Feedback?

    private var combined: CombinedRemotePolicy { vm.combinedRemote }
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
    private var inputLayout: RemoteInputLayout {
        RemoteInputLayout(policy: policy, context: context, image: isImage,
            external: vm.isExternalReceiver || vm.isAirPlay, externalProtocol: protocolID, fullControl: combined.fullControlReady)
    }
    private var padSurface: RemotePadSurface { inputLayout.surface(in: context, preferences: padPreferences) }
    private var canUseKeyboard: Bool { vm.remoteKeyboardTicket != nil }
    private var canEditKeyboard: Bool { keyboardTicket != nil && keyboardTicket == vm.remoteKeyboardTicket }
    private var supportsVolume: Bool {
        if combined.fullControlReady { return true }
        if let protocolID, ["google_cast", "dlna"].contains(protocolID), vm.googleCast.volume.supported == false { return false }
        return policy.isLegacy ? RemoteMode.supportsVolume(externalProtocol: protocolID) : policy.hasVolume("relative_step")
    }
    private func keyAllowed(_ key: String) -> Bool { combined.allowsKey(key) }
    private func settingAllowed(_ id: String, legacy: Bool = true) -> Bool { policy.isLegacy ? legacy : policy.settings.contains(id) }
    private func browserAllowed(_ action: String) -> Bool { policy.isLegacy || policy.allowsBrowser(action) }
    private var canToggle: Bool { policy.isLegacy || policy.canToggle }
    private var externalAbsoluteVolume: Bool {
        guard vm.isExternalReceiver, vm.isConnected, vm.googleCast.volume.supported != false else { return false }
        return protocolID == "google_cast" || protocolID == "dlna" && vm.googleCast.volume.supported == true
    }
    private var anyVolume: Bool { combined.fullControlReady || supportsVolume || externalAbsoluteVolume || !policy.isLegacy && (policy.hasVolume("absolute") || policy.hasVolume("mute_toggle")) }
    private var canAddSubtitle: Bool { policy.isLegacy || !policy.tracks.isDisjoint(with: ["subtitle_url_add", "subtitle_resource_add"]) }

    /// A phone list keeps Up next open between items, when the receiver briefly offers no queue.
    private var canShowQueue: Bool {
        policy.isLegacy || policy.state.connected && (phoneQueue.active || !policy.queue.isDisjoint(with: ["jump_index", "jump_item"]))
    }
    private var allowedPresentations: Set<String> {
        policy.allowedPresentations.union(canShowQueue ? ["queue"] : []).union(canUseKeyboard ? ["keyboard"] : [])
            .union(anyVolume ? ["volume"] : []).union(showPlayerMore ? ["settings"] : [])
            .union(canAdjustSubtitles ? ["subtitles"] : [])
    }
    private static let stopRed = Color(red: 229 / 255, green: 57 / 255, blue: 53 / 255)
    private var canStop: Bool { videoActive && (policy.isLegacy || policy.transport.contains("stop")) }
    private var canLoop: Bool { !vm.isExternalReceiver && !vm.isAirPlay && !isImage && settingAllowed("loop") }
    private var showPlayerMore: Bool { canLoop || vm.isAirPlay || policy.isLegacy || !policy.settings.isEmpty }
    private var canAdjustSubtitles: Bool { !vm.isExternalReceiver && !vm.isAirPlay && settingAllowed("subtitle_offset") }
    private var volumeTitle: String {
        if combined.fullControlReady { return combined.volumeLabel }
        if protocolID == "google_cast" { return "Volume" }
        if protocolID == "dlna" { return "Volume" }
        return policy.isLegacy ? "Volume" : policy.volumeLabel
    }
    private var volumePill: RemoteVolumePill {
        var percent: Int?, muted: Bool?
        if combined.fullControlReady {
            // Until the television reports, leave the step-only pill unknown. App volume
            // belongs to a different channel and must never stand in for whole-TV volume.
            if let tv = combined.fullControl.television, tv.volumeRevision > 0 {
                if let level = tv.volumeLevel, let maximum = tv.volumeMax, maximum > 0 {
                    percent = Int((Double(level) / Double(maximum) * 100).rounded())
                }
                muted = tv.volumeMuted
            }
        } else if externalAbsoluteVolume {
            percent = vm.googleCast.volume.level.map { Int(($0 * 100).rounded()) }
            muted = vm.googleCast.volume.muted
        } else if RemoteControlPresentation.volumeReportMatches(policy: policy, scope: vm.coordinator.receiverVolumeScope) {
            if policy.hasVolume("level_report") { percent = vm.coordinator.receiverVolumeLevel.map { Int(($0 * 100).rounded()) } }
            if policy.hasVolume("mute_report") { muted = vm.coordinator.receiverVolumeMuted }
        }
        return RemoteVolumePill(absolute: combined.absoluteVolume || externalAbsoluteVolume,
            wholeTV: combined.fullControlReady,
            appFallback: policy.volume?.scope == "system" && vm.coordinator.receiverVolumeScope == "app",
            percent: percent, muted: muted)
    }
    private var canMuteVolume: Bool { !vm.isExternalReceiver && !vm.isAirPlay && combined.mute }

    var body: some View {
        RemoteContentBounds { contextBody }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .disabled(!vm.isConnected && !vm.isAirPlay && !combined.fullControlReady)
        .overlay {
            if let seekFeedback {
                RemoteSeekBar.FeedbackHUD(feedback: seekFeedback)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
        }
        .sheet(item: $presented) { sheet in
            Group {
                switch sheet {
                case .settings: settings
                case .subtitles: subtitles
                case .volume: volumeSheet
                case .more: browserMore
                case .scripts: scripts
                case .agents: agents
                case .airPlayQueue: AirPlayQueueSheet(controller: vm.airPlay)
                case .queue:
                    if phoneListPlaying {
                        PhoneQueueSheet(looping: policy.isLegacy ? looping : vm.coordinator.receiverLoop ?? looping,
                                        canLoop: canLoop, onTogglePlay: { playPause(browser: false) }) { value in
                            looping = value; playerCommand(value ? "loop_on" : "loop_off")
                        }
                    } else {
                        queueSheet
                    }
                case .keyboard: keyboardSheet
                }
            }
            // Editors stay mounted with their drafts when authority disappears.
            .disabled(sheet != .keyboard && !allowedPresentations.contains(sheet.rawValue))
        }
        .fileImporter(isPresented: $importingScript, allowedContentTypes: [.javaScript, .plainText]) { result in
            guard case .success(let url) = result, scriptImportDestinationID == vm.destinationID else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty else { return }
            vm.installUserScript(name: url.deletingPathExtension().lastPathComponent, content: text)
            vm.queryUserScripts()
        }
        .onChange(of: allowedPresentations) { allowed in
            if let presented, !allowed.contains(presented.rawValue), !presented.preservesDraft {
                self.presented = nil
            }
            if !allowed.contains("scripts") { importingScript = false }
            if !allowed.contains("agents") { addingAgent = false }
        }
        .onAppear {
            RemoteHaptics.shared.prepare()
            openRequestedQueue()
        }
        .onChange(of: nav.openRemoteQueue) { _ in openRequestedQueue() }
        .onDisappear { vm.cancelNavigation() }
        .onChange(of: playback?.title) { _ in looping = false }
        .onChange(of: vm.destinationID) { _ in
            vm.cancelNavigation()
            presented = nil; keyboardTicket = nil
            padPreferences = RemotePadPreferences(); looping = false; maximized = false
            addingAgent = false; importingScript = false
        }
        .onChange(of: context) { _ in
            // A draft is retained, but never sent into a different context on a transition.
            if presented == .keyboard { presented = nil; keyboardTicket = nil }
        }
    }

    @ViewBuilder private var contextBody: some View {
        if combined.fullControlReady && !vm.isConnected {
            VStack(spacing: 12) {
                padArea
                playerBottomBar
            }
        } else { switch context {
        case "player":
            VStack(spacing: 12) {
                VStack(spacing: 8) {
                    nowPlaying(title: playback?.title, kind: vm.coordinator.mediaKind)
                    if !vm.isExternalReceiver && (!isImage || vm.isAirPlay) { tracks }
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(12)
                .background(palette.surfaceLow, in: RoundedRectangle(cornerRadius: 20))
                episodes
                if vm.isAirPlay && vm.airPlay.preparing { ProgressView("Preparing subtitles…") }
                padArea
                if (!isImage || duration > 0) && (policy.isLegacy || canToggle || canSeek || supportsVolume) {
                    seekBar(playing: playback?.state == nil || playback?.state == "playing" || playback?.state == "buffering", browser: false)
                }
                relativeSeekFallback
                playerBottomBar
                if vm.isAirPlay && vm.airPlay.routeAvailable { AirPlayVolumeControl().frame(height: 30).padding(.horizontal, 12) }
                if protocolID == "google_cast" {
                    Button("End receiver session") { playerCommand("end_receiver") }
                        .font(Theme.font(.caption)).foregroundStyle(Theme.danger)
                }
            }
        case "browser":
            VStack(spacing: 12) {
                if videoActive { nowPlaying(title: playback?.title, kind: "video") }
                padArea
                if videoActive && (policy.isLegacy || canToggle || canSeek || supportsVolume) {
                    seekBar(playing: playback?.state == "playing" || playback?.state == "buffering", browser: true)
                }
                if !policy.isLegacy && policy.transport.contains("seek_relative") && !canSeek {
                    HStack {
                        action("gobackward.10", "-10s") { vm.browserControl("seek_back") }
                        action("goforward.10", "+10s") { vm.browserControl("seek_forward") }
                    }
                }
                if isBrowser { browserRow }
                playerBottomBar
            }
        default:
            VStack(spacing: 12) {
                Spacer(minLength: 0)
                Image(systemName: "tv").font(Theme.font(size: 56)).foregroundStyle(Theme.onSurfaceVariant.opacity(0.35))
                Text(vm.isConnected ? "Nothing playing" : "Connect to a TV").font(Theme.font(.headline))
                Text("on \(vm.receiverName ?? "your TV")").font(Theme.font(.subheadline)).foregroundStyle(Theme.onSurfaceVariant)
                padArea
                playerBottomBar
                Spacer(minLength: 0)
            }
        } }
    }

    private func nowPlaying(title: String?, kind: String) -> some View {
        VStack(spacing: 0) {
            Text(title ?? "Playing on TV")
                .font(Theme.font(size: 16, weight: .semibold))
                .multilineTextAlignment(.center)
                .lineLimit(1)
                .fixedSize(horizontal: false, vertical: true)
            if let detail = RemoteControlPresentation.nowPlayingDetail(title: title, kind: kind, playlist: vm.coordinator.playlist) {
                Text(detail)
                    .font(Theme.font(size: 12))
                    .foregroundStyle(Theme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var tracks: some View {
        HStack(spacing: 8) {
            if !isImage && (policy.isLegacy || policy.tracks.contains("audio_select")) {
                trackMenu("Audio", icon: "waveform", tracks: vm.coordinator.audioTracks, command: "audio_track:")
            }
            if !isAudio && !isImage && (policy.isLegacy || policy.tracks.contains("subtitle_select") || canAddSubtitle || canAdjustSubtitles) {
                Button { presented = .subtitles } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "captions.bubble")
                        Text(vm.coordinator.subtitleTracks.first(where: { $0.selected && !["off", "none", "no", "-1"].contains($0.id.lowercased()) }).map { RemoteControlPresentation.trackLabel($0, in: vm.coordinator.subtitleTracks, subtitle: true) } ?? "Subtitles off").lineLimit(1)
                    }.font(Theme.font(size: 12)).padding(.horizontal, 10).frame(maxWidth: .infinity).frame(height: 32)
                        .background(palette.surfaceHigh, in: Capsule())
                }.buttonStyle(.plain)
            }
            if showPlayerMore {
                Button { presented = .settings } label: {
                    Image(systemName: "ellipsis").frame(width: 32, height: 32)
                        .background(palette.surfaceHigh, in: Capsule())
                }.accessibilityLabel("More player controls")
            }
        }
    }

    private func trackMenu(_ label: String, icon: String, tracks: [MediaTrack], command: String) -> some View {
        Menu {
            ForEach(tracks) { track in
                Button { playerCommand(command + track.id) } label: {
                    if track.selected { Label(RemoteControlPresentation.trackLabel(track, in: tracks), systemImage: "checkmark") } else { Text(RemoteControlPresentation.trackLabel(track, in: tracks)) }
                }
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                // Icon + chosen track, or just the category when none is chosen (matches Android).
                Text(tracks.first(where: \.selected).map { RemoteControlPresentation.trackLabel($0, in: tracks) } ?? label).lineLimit(1)
                Spacer(minLength: 0)
            }.font(Theme.font(size: 12)).padding(.horizontal, 10).frame(height: 32)
                .background(palette.surfaceHigh, in: Capsule())
        }.disabled(tracks.isEmpty)
    }

    @ViewBuilder private var episodes: some View {
        if phoneListPlaying {
            phoneListRow
        } else if let playlist = vm.coordinator.playlist, playlist.items.count > 1, canShowQueue {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Up Next").font(Theme.font(.subheadline).bold())
                    Spacer()
                    Button { presented = vm.isAirPlay ? .airPlayQueue : .queue } label: {
                        HStack(spacing: 4) {
                            Text("All (\(playlist.totalCount))")
                            Image(systemName: "chevron.right")
                        }.font(Theme.font(.caption))
                    }.accessibilityLabel("Show all \(playlist.totalCount) playlist items")
                }
                ScrollViewReader { reader in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 10) {
                            ForEach(playlist.items) { item in
                                Button { _ = selectQueueItem(item) } label: {
                                    queueCard(item, playlist: playlist)
                                }.buttonStyle(.plain).id(item.index)
                            }
                        }
                    }
                    // Cards are 70 points tall plus 10 points of padding on each edge.
                    // Keep the horizontal scroller from consuming the pad's vertical space.
                    .frame(height: 90)
                    .onAppear { reader.scrollTo(max(0, playlist.currentIndex - 1), anchor: .leading) }
                    .onChange(of: playlist.currentIndex) { index in withAnimation { reader.scrollTo(max(0, index - 1), anchor: .leading) } }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .background(palette.surfaceLow, in: RoundedRectangle(cornerRadius: 20))
        }
    }

    /// The inline Up next for a phone list: the whole list this iPhone holds, not the receiver's
    /// window, scrolled so the current item comes first.
    @ViewBuilder private var phoneListRow: some View {
        if phoneQueue.count > 1 {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("Up Next").font(Theme.font(.subheadline).bold())
                    Spacer()
                    Button { presented = .queue } label: {
                        HStack(spacing: 4) {
                            Text("All (\(phoneQueue.count))")
                            Image(systemName: "chevron.right")
                        }.font(Theme.font(.caption))
                    }.accessibilityLabel("Show all \(phoneQueue.count) items")
                }
                ScrollViewReader { reader in
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 10) {
                            ForEach(Array(phoneQueue.items.enumerated()), id: \.element.id) { index, item in
                                let current = index == phoneQueue.position
                                Button { if !current { phoneQueue.play(item.id) } } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        HStack {
                                            Text("\(index + 1)").font(Theme.font(.caption2).bold())
                                            Spacer()
                                            if current { Image(systemName: "play.circle.fill").foregroundStyle(palette.accent) }
                                        }
                                        Text(item.media.title).font(Theme.font(.caption)).lineLimit(2)
                                        Spacer(minLength: 0)
                                    }
                                    .frame(width: 136, height: 70, alignment: .leading).padding(10)
                                    .background(current ? palette.selected : palette.surface, in: RoundedRectangle(cornerRadius: 16))
                                }
                                .buttonStyle(.plain).id(index)
                            }
                        }
                    }
                    .frame(height: 90)
                    .onAppear { reader.scrollTo(phoneQueue.position, anchor: .leading) }
                    .onChange(of: phoneQueue.position) { index in withAnimation { reader.scrollTo(index, anchor: .leading) } }
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(12)
            .background(palette.surfaceLow, in: RoundedRectangle(cornerRadius: 20))
        }
    }

    private func selectQueueItem(_ item: PlaylistEpisode) -> Bool {
        // Re-read the current queue at the action boundary, not a captured card snapshot.
        RemoteControlPresentation.selectQueueItem(item, playlist: vm.coordinator.playlist) { vm.jump(to: $0) }
    }

    private func queueCard(_ item: PlaylistEpisode, playlist: PlaylistUiState) -> some View {
        let current = RemoteControlPresentation.isCurrent(item, playlist: playlist)
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(RemoteControlPresentation.episodeBadge(item)).font(Theme.font(.caption2).bold())
                Spacer()
                if current { Image(systemName: "play.circle.fill").foregroundStyle(palette.accent) }
            }
            Text(RemoteControlPresentation.episodeTitle(item)).font(Theme.font(.caption)).lineLimit(2)
            Spacer(minLength: 0)
            if let progress = RemoteControlPresentation.progress(item, playlist: playlist, playback: playback, live: isLive) {
                ProgressView(value: progress).tint(palette.accent)
                    .accessibilityLabel("Playback progress").accessibilityValue("\(Int(progress * 100)) percent")
            }
        }.frame(width: 136, height: 70, alignment: .leading).padding(10)
            .background(current ? palette.selected : palette.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    private var queueSheet: some View {
        NavigationStack {
            List {
                if let playlist = vm.coordinator.playlist {
                    ForEach(playlist.items) { item in
                        Button { if selectQueueItem(item) { presented = nil } } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(RemoteControlPresentation.episodeBadge(item)).font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
                                    Text(RemoteControlPresentation.episodeTitle(item))
                                }
                                Spacer()
                                if RemoteControlPresentation.isCurrent(item, playlist: playlist) { Image(systemName: "play.circle.fill") }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Playlist").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { presented = nil } } }
        }
    }

    @ViewBuilder private var padArea: some View {
        if padSurface == .none {
            Spacer(minLength: 0)
        } else {
            ZStack(alignment: .topLeading) {
                switch padSurface {
                case .buttons:
                    GeometryReader { geometry in
                        dpad.frame(width: 236, height: 236)
                            .scaleEffect(min(1, max(0.4, min(geometry.size.width, geometry.size.height) / 236)))
                            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                    }
                case .swipe:
                    RemoteNavigationPad(allowedKeys: Set(RemoteNavigationKey.names.filter(keyAllowed)),
                        begin: vm.beginNavigationGesture, end: vm.endNavigationGesture,
                        cancel: vm.cancelNavigation, send: { vm.routeRemoteKey($0, gesture: $1) }, fill: palette.surfaceLow)
                        .id(combined.fullControl.ticket?.connection ?? vm.ws.capabilitySessionID)
                case .pointer: touchpad
                case .none: EmptyView()
                }
                if padSurface == .swipe || padSurface == .buttons {
                    Button {
                        vm.cancelNavigation()
                        padPreferences.toggleButtons(in: context)
                        RemoteHaptics.shared.perform(.padStep, levelOverride: .light)
                    } label: {
                        Label(padSurface == .buttons ? "Touchpad" : "D-pad", systemImage: padSurface == .buttons ? "hand.draw" : "gamecontroller")
                            .font(Theme.font(.caption)).padding(10).frame(minWidth: 44, minHeight: 44)
                    }.buttonStyle(.plain)
                        .accessibilityLabel(padSurface == .buttons ? "Show navigation touchpad" : "Show D-pad")
                        .accessibilityValue(padSurface == .buttons ? "D-pad buttons" : "Swipe pad")
                }
            }
            .id(context)
            .overlay(alignment: .bottomTrailing) {
                if inputLayout.canSwitchPadPointer {
                    HStack(spacing: 4) {
                        padSurfaceButton("Pad", icon: "gamecontroller", pointer: false)
                        padSurfaceButton("Pointer", icon: "cursorarrow", pointer: true)
                    }.padding(4).background(palette.surfaceHigh, in: Capsule())
                        .padding(.trailing, 8)
                        .padding(.bottom, padSurface == .pointer && isImage && context == "player" ? 80 : 8)
                }
            }
            .frame(minHeight: padSurface == .pointer && isImage && inputLayout.canSwitchPadPointer ? 180 : 100, maxHeight: .infinity)
        }
    }

    private func padSurfaceButton(_ title: String, icon: String, pointer: Bool) -> some View {
        let selected = (padSurface == .pointer) == pointer
        return Button { padPreferences.setPointer(pointer, in: context) } label: {
            Label(title, systemImage: icon).font(Theme.font(.caption))
                .padding(.horizontal, 10).frame(minHeight: 44)
                .foregroundStyle(selected ? palette.onAccent : Theme.onSurfaceVariant)
                .background(selected ? palette.accent : .clear, in: Capsule())
        }.buttonStyle(.plain).accessibilityLabel(pointer ? "Show pointer controls" : "Show navigation pad")
            .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func seekBar(playing: Bool, browser: Bool) -> some View {
        RemoteSeekBar(
            positionMs: Double(playback?.positionMs ?? 0),
            durationMs: duration,
            isLive: browser ? false : isLive,
            isSeekable: vm.isConnected && (policy.isLegacy && browser ? duration > 0 : canSeek),
            isPlaying: playing,
            enableVolume: supportsVolume && !isImage,
            enablePlayPause: canToggle,
            volumeLabel: volumeTitle,
            onSeekTo: { playerCommand("seek_to:\($0)") },
            onVolumeUp: { vm.remote("volume_up") },
            onVolumeDown: { vm.remote("volume_down") },
            onPlayPause: { playPause(browser: browser) },
            feedback: $seekFeedback,
            isBuffering: playback?.state == "buffering",
            bufferedMs: browser ? nil : playback?.bufferedMs.map(Double.init),
            chapters: browser ? [] : timelineMarks?.chapters ?? [],
            segments: browser ? [] : timelineMarks?.segments ?? [],
            palette: palette
        )
    }

    /// A timeline names its item; never paint the previous item's chapters on the next one.
    private var timelineMarks: TvTimeline? {
        vm.coordinator.timeline?.marks(forQueueItem: vm.coordinator.playlist?.currentItemId)
    }

    @ViewBuilder private var relativeSeekFallback: some View {
        // The seek surface already handles horizontal relative scrubbing on seekable routes.
        if !canSeek && (policy.isLegacy ? (!isImage && !isLive && (vm.isExternalReceiver || vm.isAirPlay ? RemoteMode.supportsExternalSeek(externalProtocol: protocolID) : vm.coordinator.playerIsSeekable)) : policy.transport.contains("seek_relative")) {
            HStack {
                action("gobackward.10", "Back 10 seconds") { playerCommand("seek_back") }
                action("goforward.10", "Forward 10 seconds") { playerCommand("seek_forward") }
            }
        }
    }

    private var playerBottomBar: some View { navigationBar }

    private var navigationBar: some View {
        HStack(spacing: 0) {
            if (policy.isLegacy ? inputLayout.hasNavigation : keyAllowed("back")) { action("arrow.uturn.backward", "Back") { vm.remote("back") } }
            if (policy.isLegacy ? inputLayout.hasNavigation : keyAllowed("home")) { action("house", "Home") { vm.remote("home") } }
            keyboardButton
            if anyVolume {
                Button { presented = .volume } label: {
                    HStack(spacing: 5) {
                        Image(systemName: volumePill.icon)
                        if !volumePill.text.isEmpty { Text(volumePill.text).monospacedDigit() }
                    }.font(Theme.font(size: 14, weight: .semibold)).padding(.horizontal, 12).frame(minHeight: 44)
                        .background(palette.accent.opacity(0.14), in: Capsule())
                }.buttonStyle(.plain).foregroundStyle(palette.accent).frame(maxWidth: .infinity)
                    .accessibilityLabel(volumeTitle)
                    .accessibilityValue(volumePill.muted ? "Muted" : volumePill.percent.map { "\($0) of 100" } ?? "Level unavailable")
                    .accessibilityHint("Double-tap to adjust")
                    .accessibilityIdentifier("remote.volumePill")
            }
            if canStop { action("stop.fill", "Stop", tint: Self.stopRed) { playerCommand("stop") } }
        }.padding(.vertical, 4).background(palette.surface, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder private var keyboardButton: some View {
        if inputLayout.hasKeyboard {
            action("keyboard", "Keyboard") {
                guard canUseKeyboard else { return }
                vm.endPointerGesture()
                keyboardTicket = vm.remoteKeyboardTicket
                presented = .keyboard
            }
        }
    }

    private var volumeSheet: some View {
        let destination = vm.destinationID
        let session = vm.ws.capabilitySessionID
        let exact = volumePill.exact
        let wholeTV = combined.fullControlReady
        return RemoteVolumeSheet(title: volumeTitle, pill: volumePill, canStep: supportsVolume, canMute: canMuteVolume,
            onSet: { percent in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID, volumePill.exact else { return }
                if externalAbsoluteVolume { vm.setCastVolume(Double(percent) / 100) }
                else { vm.setReceiverVolume(Double(percent) / 100) }
            }, onStep: { vm.remote($0 ? "volume_up" : "volume_down") }, onMute: { vm.remote("mute") })
            .id("\(session)-\(exact)-\(wholeTV)-\(policy.volume?.scope ?? "legacy")")
    }

    private var browserRow: some View {
        HStack {
            if browserAllowed("forward") { action("arrow.forward", "Forward") { vm.browserControl("forward") } }
            if browserAllowed("refresh") { action("arrow.clockwise", "Refresh") { vm.browserControl("refresh") } }
            if browserAllowed("maximize_video") { action(maximized ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right", maximized ? "Restore" : "Fullscreen") {
                maximized.toggle()
                vm.browserControl(maximized ? "maximize_video" : "restore_video")
            } }
            if policy.isLegacy || !policy.browser.isDisjoint(with: ["cycle_source", "unmute", "manage_user_scripts", "manage_user_agent"]) { action("ellipsis", "More") { presented = .more } }
        }.padding(.vertical, 8).frame(minHeight: 64).background(palette.surface, in: RoundedRectangle(cornerRadius: 16))
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
            .background(palette.surfaceLow, in: RoundedRectangle(cornerRadius: 24))
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
        .onDisappear { vm.endPointerGesture() }
    }

    private var pointerHint: String {
        [("move", "1 finger: move"), ("scroll", "2 fingers: scroll"), ("zoom", "Pinch: zoom"),
         ("click", "Tap: click"), ("down", "Long-press+drag: drag"), ("rotate", "Twist: rotate"),
         ("reset", "Double-tap: reset")].filter { policy.pointer.contains($0.0) }.map { $0.1 }.joined(separator: "  ·  ")
    }

    private var dpad: some View {
        ZStack {
            Circle().fill(palette.surface).frame(width: 236, height: 236)
            Circle().stroke(Theme.outlineVariant, lineWidth: 1).frame(width: 236, height: 236)
            VStack(spacing: 4) {
                pad("chevron.up", "Up", "dpad_up")
                HStack(spacing: 4) {
                    pad("chevron.left", "Left", "dpad_left")
                    Button { vm.remote("dpad_center") } label: {
                        Text("OK").font(Theme.font(size: 16, weight: .bold))
                            .frame(width: 72, height: 72)
                            .foregroundStyle(palette.onAccent)
                            .background(palette.accent, in: Circle())
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

    @ViewBuilder private var keyboardSheet: some View {
        if let ticket = keyboardTicket {
            RemoteKeyboardSheet(ticket: ticket, valid: canEditKeyboard, canEnter: ticket.route == .atv || policy.allowsKey("key_enter")) { [weak vm] command in
                guard let vm else { throw AtvClientError.closed }
                try await vm.sendKeyboard(command, ticket: ticket)
            }
        }
    }

    private var settings: some View {
        NavigationStack {
            Form {
                let speed = settingAllowed("speed", legacy: vm.coordinator.speedAvailable)
                let quality = settingAllowed("quality", legacy: vm.coordinator.qualityAvailable) && vm.coordinator.videoTracks.filter({ $0.id != "auto" }).count > 1
                let scaling = settingAllowed("scaling", legacy: vm.coordinator.scalingAvailable)
                let boost = settingAllowed("audio_boost", legacy: vm.coordinator.audioBoostAvailable)
                let engine = settingAllowed("engine")
                // Same grouping as Android's Player settings: Playback, Picture, Audio, then app preferences.
                if speed || canLoop {
                    Section("Playback") {
                        if speed {
                            chipRow([0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2].map { ($0 == 1 ? "1x" : "\($0)x", String($0)) }, selected: speedKey(vm.coordinator.playerSpeed)) { value in
                                if let speed = Float(value) { playerCommand("speed:\(speed)") }
                            }
                        }
                        if canLoop {
                            Toggle("Loop", isOn: Binding(get: { policy.isLegacy ? looping : vm.coordinator.receiverLoop ?? looping }, set: { value in
                                looping = value; playerCommand(value ? "loop_on" : "loop_off")
                            }))
                        }
                    }
                }
                if quality || scaling {
                    Section("Picture") {
                        if quality {
                            chipRow(vm.coordinator.videoTracks.map { ($0.name, $0.id) }, selected: vm.coordinator.qualityMaxHeight == 0 ? "auto" : "max:\(vm.coordinator.qualityMaxHeight)") { id in
                                let value = id == "auto" ? "auto" : id.replacingOccurrences(of: "max:", with: "")
                                playerCommand("video_quality:\(value)")
                            }
                        }
                        if scaling {
                            chipRow([("Fit", "Fit"), ("Crop to fill", "Zoom"), ("Stretch", "Fill")], selected: vm.coordinator.playerScaling) { playerCommand("scaling:\($0)") }
                        }
                    }
                }
                if boost || engine {
                    Section("Audio") {
                        if boost {
                            Toggle("Audio boost", isOn: Binding(get: { vm.coordinator.audioBoost }, set: { _ in playerCommand("audio_boost") }))
                        }
                        if engine {
                            chipRow(policy.isLegacy ? [("ExoPlayer", "exo"), ("MPV", "mpv")] : policy.playerEngines.map { (ReceiverRemotePolicy.playerLabels[$0]!, $0) }, selected: vm.coordinator.playerEngine) { playerCommand("switch_player:\($0)") }
                        }
                    }
                }
                if vm.isAirPlay {
                    Section("AirPlay") {
                        Button("Next", systemImage: "forward.end.fill") { playerCommand("next") }.disabled(vm.airPlay.upcoming.isEmpty)
                        Button("Queue", systemImage: "list.bullet") { presented = .airPlayQueue }
                    }
                }
                Section("Remote feedback") {
                    Toggle("Haptic feedback", isOn: $hapticsEnabled)
                    Picker("Haptic level", selection: $hapticsLevel) {
                        ForEach(RemoteHapticLevel.allCases, id: \.self) { level in
                            Text(level.title).tag(level)
                        }
                    }.disabled(!hapticsEnabled)
                }
                // Paired TVs manages Android TV full-control pairings; irrelevant on a desktop or Apple TV.
                if !vm.isExternalReceiver && (policy.isLegacy || policy.hostOS == "android") {
                    Section("Receiver") {
                        Button("Paired TVs") { showPairedTVs = true }
                            .accessibilityLabel("Manage paired TVs")
                            .sheet(isPresented: $showPairedTVs) { AtvPairedTVsSheet(coordinator: atv) }
                    }
                }
            }
            .navigationTitle("Player settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { presented = nil } } }
        }
    }

    private var subtitles: some View {
        let detector = browserStore.activeTab?.detector
        let destination = vm.destinationID
        let session = vm.ws.capabilitySessionID
        return RemoteSubtitlesSheet(tracks: vm.coordinator.subtitleTracks,
            canSelect: policy.isLegacy || policy.tracks.contains("subtitle_select"),
            canAdjust: canAdjustSubtitles,
            canAdd: !vm.isExternalReceiver && !isAudio && !isImage && canAddSubtitle,
            offset: vm.coordinator.subtitleOffsetMs, airPlay: vm.isAirPlay, preparing: vm.isAirPlay && vm.airPlay.preparing,
            detector: detector, detected: detector?.videos.filter {
                $0.isSubtitle && (policy.isLegacy || policy.tracks.contains("subtitle_resource_add") || VideoDetector.subtitleHeaders(for: $0).isEmpty)
            } ?? [], destinationID: vm.destinationID,
            select: { id in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID else { return }
                playerCommand("sub_track:\(id)")
            }, adjust: { offset in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID else { return }
                playerCommand("sub_offset:\(offset)")
            },
            addDetected: { item in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID else { return false }
                return vm.addSubtitle(url: item.url, headers: VideoDetector.subtitleHeaders(for: item), label: item.displayTitle)
            },
            addLocal: { file in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID else { return false }
                guard let served = await vm.serveLocalSubtitle(file), destination == vm.destinationID,
                      session == vm.ws.capabilitySessionID else { return false }
                return vm.addSubtitle(url: served, label: file.lastPathComponent)
            }, addLink: { link in
                guard destination == vm.destinationID, session == vm.ws.capabilitySessionID else { return false }
                return vm.addSubtitle(url: link)
            }, done: { presented = nil })
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
                    guard policy.allowedPresentations.contains("agents") else { return }
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
            if selected { Image(systemName: "checkmark").foregroundStyle(palette.accent) }
        }
    }

    private func chipRow(_ options: [(String, String)], selected: String, onSelect: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(options, id: \.1) { option in
                    Button(option.0) { onSelect(option.1) }
                        .buttonStyle(.borderedProminent)
                        .tint(option.1 == selected ? Theme.primaryDim : palette.surfaceHigh)
                }
            }
        }
    }

    private func speedKey(_ value: Float) -> String {
        let speeds: [Float] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
        let nearest = speeds.min { abs($0 - value) < abs($1 - value) } ?? 1
        return String(nearest)
    }

    private func playPause(browser: Bool) {
        guard canToggle, vm.isConnected || vm.isAirPlay && vm.airPlay.routeAvailable else { return }
        let feedback = { RemoteHaptics.shared.perform(.playPause); return }
        if browser { vm.browserControl("toggle_play", onSent: feedback) }
        else { vm.control(policy.isLegacy ? (playback?.state == "playing" ? "pause" : "play") : policy.toggleCommand, onSent: feedback) }
    }

    private func action(_ icon: String, _ label: String, tint: Color = Theme.onSurface, perform: @escaping () -> Void) -> some View {
        Button(action: perform) {
            Image(systemName: icon).font(Theme.font(size: 20))
                .frame(maxWidth: .infinity).frame(minHeight: 48).contentShape(Rectangle()).foregroundStyle(tint)
        }.buttonStyle(.plain).accessibilityLabel(label)
    }

    /// The receiver is playing a phone list from this iPhone (its current item is one of the list's).
    private var phoneListPlaying: Bool {
        guard phoneQueue.active, let current = vm.coordinator.playlist?.currentItemId else { return false }
        return phoneQueue.items.contains { $0.id == current }
    }

    /// The mini player's Up next pill asked for Up next.
    private func openRequestedQueue() {
        guard nav.openRemoteQueue else { return }
        nav.openRemoteQueue = false
        if phoneListPlaying || canShowQueue { presented = .queue }
    }

    private func playerCommand(_ command: String) {
        guard vm.isConnected else { return }
        if isBrowser { vm.browserControl(command == "play" || command == "pause" ? "toggle_play" : command) }
        else { vm.control(command) }
    }

    private enum Presented: String, Identifiable {
        case settings, subtitles, volume, more, scripts, agents, airPlayQueue, queue, keyboard
        var id: String { rawValue }
        var preservesDraft: Bool { self == .subtitles || self == .agents || self == .keyboard }
    }
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
