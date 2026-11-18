import SwiftUI
import Metal
import AVFoundation

/// Touch controls for the phone player (mpv or AVPlayer), operating through PlaybackSession.
/// Layout follows Android's redesign: Back, the title and Queue / Audio & subtitles / More on
/// top; Previous / Play / Next in the centre; the remote-style seek bar with Lock, Rotate and
/// a speed chip at the bottom. Double-tap skips, so there are no ±10 s buttons.
struct MPVPhonePlayerView: View {
    @ObservedObject var session: PlaybackSession
    @ObservedObject private var subtitleStyle = SubtitleStyleStore.shared
    @StateObject private var controls: PhonePlayerControls
    let onDismiss: (() -> Void)?
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var timelineTarget: Double?
    @State private var showMore = false
    @State private var showTracks = false
    @State private var showQueue = false
    @State private var seekFeedback = 0
    @State private var feedbackTask: Task<Void, Never>?
    @State private var scrubPreview: PhonePlayerScrub.Preview?
    @State private var handBackWhenTurned = false
    @AppStorage(PhoneAmbientMode.key) private var ambientEnabled = true
    /// The glow's colours, stepped toward each new frame.
    @State private var ambientImage: CGImage?
    /// The same, blurred, as drawn.
    @State private var ambientDisplay: CGImage?
    /// The full-screen player shows a landscape video on an upright phone in the portrait layout;
    /// inline previews never do.
    let allowsPortraitLayout: Bool
    /// The cast sheet's thumbnail, shown blurred until the first frame.
    let poster: UIImage?

    init(session: PlaybackSession, controls: PhonePlayerControls? = nil, allowsPortraitLayout: Bool = false,
         poster: UIImage? = nil, onDismiss: (() -> Void)? = nil) {
        self.session = session
        self.onDismiss = onDismiss
        self.allowsPortraitLayout = allowsPortraitLayout
        self.poster = poster
        _controls = StateObject(wrappedValue: controls ?? PhonePlayerControls())
    }

    private var buffering: Bool { session.mpvState.buffering || session.websiteWaitingForNext || session.websiteQueueChangingItem }
    private var hasQueue: Bool { !session.websiteQueueTitles.isEmpty }
    private var landscape: Bool { verticalSizeClass == .compact }
    private var chromeShown: Bool { controls.visible && !controls.locked }
    private var sheetShown: Bool { showMore || showTracks || showQueue }

    var body: some View {
        GeometryReader { geometry in
            let portrait = portraitLayout
            let videoHeight = portrait
                ? PhonePlayerOpening.portraitVideoHeight(width: session.mpvState.videoWidth, height: session.mpvState.videoHeight,
                                                         screenWidth: geometry.size.width, screenHeight: geometry.size.height)
                : geometry.size.height
            // The video keeps its place in the hierarchy in both layouts, so its surface survives rotation.
            ZStack(alignment: .top) {
                if portrait && ambientEnabled, let ambientDisplay {
                    ambientGlow(ambientDisplay, width: geometry.size.width, videoHeight: videoHeight)
                }
                videoArea(portrait: portrait)
                    .frame(width: geometry.size.width, height: videoHeight)
                if portrait {
                    portraitPanel
                        .frame(width: geometry.size.width, height: max(0, geometry.size.height - videoHeight))
                        .offset(y: videoHeight)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: .top)
        }
        .background((portraitLayout ? Theme.surface : Color.black).ignoresSafeArea())
        .foregroundStyle(.white).tint(Theme.primary)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: controls.visible)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.35), value: showsPoster)
        .onAppear {
            controls.setSuspended(false); updateControls()
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        }
        .onChange(of: session.isPlaying) { _ in updateControls() }
        .onChange(of: session.mpvState.buffering) { _ in updateControls() }
        .onChange(of: session.websiteWaitingForNext) { _ in updateControls() }
        .onChange(of: session.websiteQueueChangingItem) { _ in updateControls() }
        .onChange(of: voiceOver) { _ in updateControls() }
        .onChange(of: scenePhase) { controls.setSuspended($0 != .active) }
        .onChange(of: session.failure) { if $0 != nil { controls.unlock() } }
        .onChange(of: sheetShown) { controls.setSheetPresented($0) }
        .onChange(of: portraitLayout) { if $0 { controls.unlock() }; controls.reveal() }
        .onChange(of: session.alternativeEngine.map { ObjectIdentifier($0) }) { _ in
            timelineTarget = nil; controls.setScrubbing(false)
            feedbackTask?.cancel(); seekFeedback = 0; scrubPreview = nil
            controls.reveal()
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            // Portrait first: after Fullscreen, turning the phone to landscape hands rotation back to it.
            guard handBackWhenTurned, UIDevice.current.orientation.isLandscape else { return }
            handBackWhenTurned = false
            PhonePlayerOrientationLock.handBack()
        }
        .task(id: ambientActive) {
            guard ambientActive else { return }
            // A tiny frame every second while playing; paused keeps the last glow.
            var previousWasDark = false
            while !Task.isCancelled {
                if session.isPlaying || ambientImage == nil, let engine = session.alternativeEngine {
                    let frame = await withCheckedContinuation { (continuation: CheckedContinuation<CGImage?, Never>) in
                        engine.ambientFrame { continuation.resume(returning: $0) }
                    }
                    let frameLuma = frame.map(PhoneAmbientFrame.meanLuma)
                    let glowLuma = ambientImage.map(PhoneAmbientFrame.meanLuma)
                    let accepted = frameLuma.map { PhoneAmbientFrame.accepts(frameLuma: $0, glowLuma: glowLuma, previousWasDark: previousWasDark) } ?? false
                    if let frameLuma { previousWasDark = frameLuma < PhoneAmbientFrame.darkLuma }
                    if accepted, let frame, !Task.isCancelled, let glow = PhoneAmbientFrame.blend(ambientImage, toward: frame) {
                        // The glow steps a third of the way toward the frame; the layer crossfades.
                        ambientImage = glow
                        ambientDisplay = PhoneAmbientFrame.soften(glow) ?? glow
                    }
                }
                try? await Task.sleep(nanoseconds: PhoneAmbientMode.interval)
            }
        }
        .onChange(of: session.alternativeEngine.map { ObjectIdentifier($0) }) { _ in ambientImage = nil; ambientDisplay = nil }
        .onDisappear {
            controls.stop(); feedbackTask?.cancel(); feedbackTask = nil
            UIDevice.current.endGeneratingDeviceOrientationNotifications()
            PhonePlayerOrientationLock.release()
        }
        .sheet(isPresented: $showMore, onDismiss: { controls.reveal() }) { PhonePlayerMoreSheet(session: session) }
        .sheet(isPresented: $showTracks, onDismiss: { controls.reveal() }) { PhonePlayerTracksSheet(session: session) }
        .sheet(isPresented: $showQueue, onDismiss: { controls.reveal() }) { queue }
    }

    private var ambientActive: Bool { (portraitLayout || barsAmbient) && ambientEnabled && scenePhase == .active }
    /// Full screen with Fit: the surface takes the picture's size and the glow fills the bars.
    private var barsAmbient: Bool {
        allowsPortraitLayout && ambientEnabled && !portraitLayout && session.preferences.sizing == .fit
            && session.mpvState.videoWidth > 0 && session.mpvState.videoHeight > 0
    }

    /// The current frame blown up and blurred behind the video box, fading into the panel below.
    private func ambientGlow(_ image: CGImage, width: Double, videoHeight: Double) -> some View {
        let height = max(1, videoHeight) * 2.2
        return AmbientGlowLayer(image: image, crossfade: reduceMotion ? 0 : 0.9)
            .frame(width: width * 1.3, height: height)
            .opacity(0.65)
            .frame(width: width, height: height)
            .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.45),
                                         .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
            .offset(y: -videoHeight * 0.15)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    private var portraitLayout: Bool {
        allowsPortraitLayout && PhonePlayerOpening.usesPortraitLayout(
            width: session.mpvState.videoWidth, height: session.mpvState.videoHeight, screenIsLandscape: landscape)
    }
    /// The blurred poster covers the black screen until the first frame (and stays for audio).
    private var showsPoster: Bool {
        session.mpvState.videoWidth == 0 && session.failure == nil && (poster != nil || session.posterURL != nil)
    }

    /// The picture with everything drawn over it. In the full-screen layout it fills the screen
    /// under the safe areas; in the portrait layout it is the box at the top.
    private func videoArea(portrait: Bool) -> some View {
        let edges: Edge.Set = portrait ? [] : .all
        return ZStack {
            Color.black.ignoresSafeArea(edges: edges)
            if !portrait && barsAmbient, let ambientDisplay {
                // The whole screen glows; the fitted video covers its middle, leaving the bars lit.
                AmbientGlowLayer(image: ambientDisplay, crossfade: reduceMotion ? 0 : 0.9)
                    .opacity(0.55)
                    .ignoresSafeArea(edges: edges)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            // Always inside the same GeometryReader, so the surface keeps its identity when it
            // switches between filling the screen and fitting the picture.
            GeometryReader { full in
                let fitted = barsAmbient && !portrait
                    ? PhoneAmbientMode.fittedSize(width: session.mpvState.videoWidth, height: session.mpvState.videoHeight, in: full.size)
                    : nil
                Group {
                    if let engine = session.alternativeEngine as? MPVPhonePlayback {
                        MPVPhoneSurface(engine: engine)
                            .id(ObjectIdentifier(engine))
                    } else if let engine = session.alternativeEngine as? AVPlayerPhonePlayback {
                        AVPlayerPhoneSurface(engine: engine)
                            .id(ObjectIdentifier(engine))
                    }
                }
                .frame(width: fitted?.width ?? full.size.width, height: fitted?.height ?? full.size.height)
                .position(x: full.size.width / 2, y: full.size.height / 2)
            }
            .ignoresSafeArea(edges: edges)
            if showsPoster {
                posterLayer
                    .ignoresSafeArea(edges: edges)
                    .transition(.opacity)
            }
            PhonePlayerSurfaceGestures(
                duration: session.durationSeconds,
                position: session.positionSeconds,
                locked: controls.locked,
                voiceOver: voiceOver,
                onTap: { if !portrait { controls.tap() } },
                onDoubleTap: { skip($0, fromTransport: false) },
                onScrub: { preview in
                    scrubPreview = preview
                    controls.setScrubbing(preview != nil, source: .surfaceGesture)
                },
                onCommit: { preview in
                    scrubPreview = nil
                    controls.setScrubbing(false, source: .surfaceGesture)
                    guard !controls.locked, !voiceOver, session.durationSeconds > 0 else { return }
                    session.seek(to: preview.target)
                }
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .ignoresSafeArea(edges: edges)
            .accessibilityHidden(true)
            if buffering && (portrait || !chromeShown) {
                ProgressView().tint(.white).allowsHitTesting(false)
            }
            if !overlayCaption.isEmpty {
                VStack {
                    Spacer()
                    SubtitleCaptionText(text: overlayCaption, style: subtitleStyle.style)
                        .padding(.horizontal, 20)
                        .padding(.bottom, chromeShown && !portrait ? 128 : portrait ? 12 : 24)
                }.allowsHitTesting(false)
            }
            if portrait {
                VStack(spacing: 0) {
                    HStack(spacing: 2) {
                        closeButton
                        Spacer(minLength: 8)
                        topActions
                    }
                    .padding(.horizontal, 8).padding(.bottom, 20)
                    .background(LinearGradient(colors: [.black.opacity(showsPoster ? 0 : 0.7), .clear], startPoint: .top, endPoint: .bottom))
                    Spacer(minLength: 0)
                }
            } else if chromeShown {
                ZStack {
                    VStack(spacing: 0) {
                        topBar
                        Spacer(minLength: 0)
                        bottomBar
                    }
                    centre
                }.transition(.opacity)
            }
            if !portrait && controls.locked && controls.unlockVisible {
                VStack {
                    HStack(spacing: 10) {
                        Spacer()
                        Text("Controls locked")
                            .font(Theme.font(size: 13, weight: .medium))
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(.black.opacity(0.7), in: Capsule())
                            .accessibilityHidden(true)
                        Button { controls.unlock() } label: {
                            Image(systemName: "lock.open.fill").frame(width: 48, height: 48)
                                .background(.black.opacity(0.7), in: Circle())
                        }.accessibilityLabel("Unlock player").accessibilityIdentifier("phone-player-unlock")
                    }
                    Spacer()
                }.padding(12)
            }
            if seekFeedback != 0 {
                HStack {
                    if seekFeedback > 0 { Spacer() }
                    Text("\(seekFeedback > 0 ? "+" : "")\(seekFeedback) seconds")
                        .font(Theme.font(size: 15, weight: .semibold))
                        .padding(16).background(.black.opacity(0.7), in: Capsule())
                    if seekFeedback < 0 { Spacer() }
                }.padding(24).allowsHitTesting(false).accessibilityHidden(true)
            }
            if let scrubPreview {
                scrubHUD(big: scrubPreview.offsetLabel, small: scrubPreview.timeLabel)
            } else if let timelineTarget {
                scrubHUD(big: PhonePlayerChrome.clock(timelineTarget),
                         small: PhonePlayerChrome.signedOffset(target: timelineTarget, from: session.positionSeconds))
            }
        }
    }

    private var posterLayer: some View {
        GeometryReader { geometry in
            Group {
                if let poster {
                    Image(uiImage: poster).resizable().scaledToFill()
                } else if let url = session.posterURL {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image { image.resizable().scaledToFill() } else { Color.clear }
                    }
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .blur(radius: 18)
            .overlay(Color.black.opacity(0.35))
            .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    // MARK: Portrait layout

    /// A landscape video on an upright phone: title, seek bar and transport under the video,
    /// with Fullscreen and Up next.
    private var portraitPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                titleBlock
                statusLines
                timeline
                HStack { Spacer(); centre; Spacer() }
                HStack(spacing: 8) {
                    Button { goFullscreen() } label: {
                        Label("Fullscreen", systemImage: "arrow.up.left.and.arrow.down.right")
                            .font(Theme.font(size: 13, weight: .semibold))
                            .padding(.horizontal, 14).frame(minHeight: 32)
                            .background(Color.white.opacity(0.12), in: Capsule())
                            .frame(minHeight: 44).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("phone-player-fullscreen")
                    Spacer(minLength: 0)
                    speedChip
                }
                Text("Turn your phone for fullscreen")
                    .font(Theme.font(size: 12)).foregroundStyle(Theme.onSurfaceVariant)
                if !upNext.isEmpty {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Up next").font(Theme.font(size: 15, weight: .semibold))
                        ForEach(upNext, id: \.self) { index in
                            Button {
                                controls.reveal(); session.onWebsiteJump?(index)
                            } label: {
                                HStack(spacing: 10) {
                                    Text("\(index + 1)").font(Theme.font(size: 12)).monospacedDigit()
                                        .foregroundStyle(Theme.onSurfaceVariant).frame(minWidth: 22, alignment: .leading)
                                    Text(session.websiteQueueTitles[index]).font(Theme.font(size: 14)).lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                    Spacer(minLength: 0)
                                }
                                .frame(minHeight: 44).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(session.websiteQueueChangingItem)
                        }
                    }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 16)
        }
    }

    /// The next few queue items after the current one.
    private var upNext: [Int] {
        let start = session.websiteQueueIndex + 1
        guard start < session.websiteQueueTitles.count else { return [] }
        return Array(start..<min(session.websiteQueueTitles.count, start + 5))
    }

    private func goFullscreen() {
        controls.reveal()
        // Portrait first hands rotation back to the phone once it is turned to match.
        handBackWhenTurned = PhoneLandscapeOpening.load() == .portraitFirst
        PhonePlayerOrientationLock.hold(.landscape)
    }

    /// Website subtitles, or ticked subtitle files the engine doesn't render itself (AVPlayer).
    private var overlayCaption: String {
        session.websiteCaption.isEmpty ? session.mpvState.overlayCaption : session.websiteCaption
    }

    private var meta: String {
        PhonePlayerChrome.meta(queueIndex: session.websiteQueueIndex, queueCount: session.websiteQueueTitles.count,
                               engine: session.engineKind.label)
    }

    // MARK: Top bar

    /// In landscape the title sits between Back and the actions; in portrait it has its own row.
    private var topBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center, spacing: 2) {
                closeButton
                if landscape { titleBlock.padding(.leading, onDismiss == nil ? 8 : 0) }
                Spacer(minLength: 8)
                topActions
            }
            if !landscape { titleBlock.padding(.horizontal, 12) }
        }
        .padding(.horizontal, 8).padding(.bottom, 28)
        .background(scrim(top: true).ignoresSafeArea(edges: [.top, .horizontal]))
    }

    @ViewBuilder private var closeButton: some View {
        if let onDismiss {
            Button(action: onDismiss) { Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44) }
                .accessibilityLabel("Close player")
                .accessibilityIdentifier("phone-player-close")
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !session.title.isEmpty {
                Text(session.title)
                    .font(Theme.font(size: 16, weight: .semibold))
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            Text(meta)
                .font(Theme.font(size: 12))
                .foregroundStyle(Theme.onSurfaceVariant)
                .lineLimit(1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("phone-player-title")
    }

    private var topActions: some View {
        HStack(spacing: 2) {
            if hasQueue {
                Button { showQueue = true } label: { Image(systemName: "list.bullet").frame(width: 44, height: 44) }
                    .accessibilityLabel("Queue")
                    .accessibilityIdentifier("phone-player-queue")
            }
            Button { showTracks = true } label: { Image(systemName: "captions.bubble").frame(width: 44, height: 44) }
                .accessibilityLabel("Audio and subtitles")
                .accessibilityIdentifier("phone-player-tracks")
            Button { showMore = true } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                .accessibilityLabel("More")
                .accessibilityIdentifier("phone-player-more")
        }
        .font(.system(size: 18, weight: .medium))
    }

    // MARK: Centre

    private var centre: some View {
        HStack(spacing: landscape ? 56 : 40) {
            if hasQueue {
                Button { controls.reveal(); session.onWebsiteJump?(session.websiteQueueIndex - 1) } label: {
                    Image(systemName: "backward.end.fill").font(.system(size: 26)).frame(width: 56, height: 56)
                }
                .accessibilityLabel("Previous episode")
                .disabled(session.websiteQueueIndex == 0 || session.websiteQueueChangingItem)
            }
            Button { controls.reveal(); session.isPlaying ? session.pause() : session.play() } label: {
                ZStack {
                    // Over the blurred poster the scrims are off, so the button needs no dark disc either.
                    Circle().fill(.black.opacity(showsPoster ? 0.15 : 0.45))
                    if buffering {
                        ProgressView().tint(.white).scaleEffect(1.3)
                    } else {
                        Image(systemName: session.isPlaying ? "pause.fill" : "play.fill").font(.system(size: 34))
                    }
                }
                .frame(width: 76, height: 76)
            }
            .accessibilityLabel(session.isPlaying ? "Pause" : "Play")
            .accessibilityIdentifier("phone-player-play")
            if hasQueue {
                Button { controls.reveal(); session.onWebsiteJump?(session.websiteQueueIndex + 1) } label: {
                    Image(systemName: "forward.end.fill").font(.system(size: 26)).frame(width: 56, height: 56)
                }
                .accessibilityLabel("Next episode")
                .disabled(session.websiteQueueIndex + 1 >= session.websiteQueueTitles.count || session.websiteQueueChangingItem)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: Bottom

    private var bottomBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            statusLines
            timeline
            HStack(spacing: 4) {
                Button { controls.lock() } label: { Image(systemName: "lock").frame(width: 44, height: 44) }
                    .accessibilityLabel("Lock player controls").accessibilityIdentifier("phone-player-lock")
                Button {
                    controls.reveal()
                    handBackWhenTurned = false
                    PhonePlayerOrientationLock.toggle()
                } label: { Image(systemName: "rotate.right").frame(width: 44, height: 44) }
                    .accessibilityLabel(PhonePlayerOrientationLock.isLandscape ? "Rotate to portrait" : "Rotate to landscape")
                    .accessibilityIdentifier("phone-player-rotate")
                Spacer(minLength: 0)
                speedChip
            }
            .font(.system(size: 18, weight: .medium))
        }
        .padding(.horizontal, landscape ? 28 : 16).padding(.top, 32).padding(.bottom, 6)
        .background(scrim(top: false).ignoresSafeArea(edges: [.bottom, .horizontal]))
    }

    @ViewBuilder private var statusLines: some View {
        if session.websiteWaitingForNext {
            Text("Getting the next episode from the website…").font(Theme.font(size: 13))
        }
        if let error = session.websiteQueueError { Text(error).font(Theme.font(size: 13)) }
        if let error = session.websiteSubtitleError { Text(error).font(Theme.font(size: 13)) }
    }

    private var timeline: some View {
        PhonePlayerTimeline(
            position: session.positionSeconds, duration: session.durationSeconds,
            playing: session.isPlaying, buffering: buffering,
            onScrub: { target in
                timelineTarget = target
                controls.setScrubbing(target != nil)
            },
            onSeek: { session.seek(to: $0); controls.reveal() },
            onSkip: { skip($0, fromTransport: true) }
        )
    }

    private var speedChip: some View {
        chip(PhonePlayerChrome.speedLabel(session.preferences.speed), active: session.preferences.speed != 1) { showMore = true }
            .accessibilityLabel("Playback speed")
            .accessibilityValue(String(format: "%g times", session.preferences.speed))
            .accessibilityHint("Opens More")
            .accessibilityIdentifier("phone-player-speed")
    }

    /// The fade behind the top or bottom controls. It runs edge to edge (past the safe area,
    /// so Fill shows no hard sides) and eases out instead of ending in a line; it is off over
    /// the blurred poster.
    private func scrim(top: Bool) -> some View {
        let strength = showsPoster ? 0 : (top ? 0.75 : 0.85)
        let stops: [Gradient.Stop] = [
            .init(color: .black.opacity(strength), location: 0),
            .init(color: .black.opacity(strength * 0.6), location: 0.4),
            .init(color: .black.opacity(strength * 0.2), location: 0.75),
            .init(color: .clear, location: 1),
        ]
        return LinearGradient(stops: stops, startPoint: top ? .top : .bottom, endPoint: top ? .bottom : .top)
    }

    private func chip(_ title: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(Theme.font(size: 13, weight: .semibold)).monospacedDigit()
                .foregroundStyle(active ? Theme.onPrimary : .white)
                .padding(.horizontal, 14).frame(minHeight: 32)
                .background(active ? Theme.primary : Color.white.opacity(0.16), in: Capsule())
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func scrubHUD(big: String, small: String) -> some View {
        VStack(spacing: 4) {
            Text(big).font(.system(size: 40, weight: .bold)).monospacedDigit()
            Text(small).font(.title3.weight(.semibold)).monospacedDigit()
        }
        .padding(.horizontal, 24).padding(.vertical, 16)
        .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var queue: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(session.websiteQueueTitles.indices, id: \.self) { index in
                        Button {
                            session.onWebsiteJump?(index); showQueue = false
                        } label: {
                            HStack {
                                Text(session.websiteQueueTitles[index])
                                Spacer()
                                if index == session.websiteQueueIndex { Image(systemName: "play.fill").accessibilityLabel("Current episode") }
                            }
                        }.disabled(session.websiteQueueChangingItem)
                    }
                } footer: {
                    Text(session.websiteWaitingForNext ? "Getting the next episode from the website…" : "Streams resolves upcoming episodes as needed.")
                }
            }
            .navigationTitle("Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showQueue = false } } }
        }
        .presentationDetents([.medium, .large])
    }
    private func updateControls() {
        controls.update(playing: session.isPlaying, buffering: buffering, voiceOver: voiceOver)
    }
    private func skip(_ offset: Double, fromTransport: Bool) {
        guard !controls.locked else { return }
        controls.touchChrome(from: fromTransport ? .transport : .surfaceGesture)
        session.skip(by: offset)
        let step = Int(offset)
        seekFeedback = (seekFeedback.signum() == step.signum() ? seekFeedback : 0) + step
        feedbackTask?.cancel()
        feedbackTask = Task {
            do { try await Task.sleep(nanoseconds: 800_000_000) } catch { return }
            guard !Task.isCancelled else { return }; seekFeedback = 0
        }
    }
}

private struct PhonePlayerSurfaceGestures: UIViewRepresentable {
    var duration: Double
    var position: Double
    var locked: Bool
    var voiceOver: Bool
    var onTap: () -> Void
    var onDoubleTap: (Double) -> Void
    var onScrub: (PhonePlayerScrub.Preview?) -> Void
    var onCommit: (PhonePlayerScrub.Preview) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        let single = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.singleTap))
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTap(_:)))
        double.numberOfTapsRequired = 2
        single.require(toFail: double)
        let pan = PhonePlayerScrubPan(target: context.coordinator, action: #selector(Coordinator.pan(_:)))
        pan.maximumNumberOfTouches = 1
        pan.cancelsTouchesInView = true
        let coordinator = context.coordinator
        pan.gate = { [weak coordinator] in
            guard let parent = coordinator?.parent else { return PhonePlayerScrub.Gate(duration: 0, locked: true, voiceOver: true) }
            return PhonePlayerScrub.Gate(duration: parent.duration, locked: parent.locked, voiceOver: parent.voiceOver)
        }
        view.addGestureRecognizer(double)
        view.addGestureRecognizer(single)
        view.addGestureRecognizer(pan)
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.parent = self }
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject {
        var parent: PhonePlayerSurfaceGestures
        private var origin = 0.0
        private var preview: PhonePlayerScrub.Preview?
        private var tickBucket = 0
        private var heldClamp: PhonePlayerScrub.ClampEdge?
        private let startHaptic = UIImpactFeedbackGenerator(style: .light)
        private let edgeHaptic = UIImpactFeedbackGenerator(style: .medium)
        private let tickHaptic = UISelectionFeedbackGenerator()
        init(_ parent: PhonePlayerSurfaceGestures) { self.parent = parent }

        @objc func singleTap() { parent.onTap() }
        @objc func doubleTap(_ recognizer: UITapGestureRecognizer) {
            guard !parent.locked else { parent.onTap(); return }
            guard let view = recognizer.view else { return }
            parent.onDoubleTap(recognizer.location(in: view).x < view.bounds.midX ? -10 : 10)
        }
        @objc func pan(_ recognizer: UIPanGestureRecognizer) {
            guard let recognizer = recognizer as? PhonePlayerScrubPan else { return }
            switch recognizer.state {
            case .began:
                origin = parent.position
                tickBucket = 0
                heldClamp = nil
                startHaptic.prepare(); edgeHaptic.prepare(); tickHaptic.prepare()
                fallthrough
            case .changed:
                guard let translation = recognizer.dragTranslation() else { return }
                if preview == nil {
                    switch PhonePlayerScrub.decide(start: recognizer.dragStart ?? .zero, translation: translation, viewSize: recognizer.view?.bounds.size ?? .zero, position: origin, gate: recognizer.gate()) {
                    case .ignore: recognizer.state = .cancelled; return
                    case .pending: return
                    case .scrub(let next):
                        startHaptic.impactOccurred(); startHaptic.prepare()
                        noteTicks(next)
                        preview = next
                        parent.onScrub(next)
                    }
                } else if let next = recognizer.livePreview(origin: origin, duration: parent.duration) {
                    noteTicks(next)
                    preview = next
                    parent.onScrub(next)
                }
            case .ended:
                let translation = recognizer.dragTranslation() ?? .zero
                let size = recognizer.view?.bounds.size ?? .zero
                let qualifiedNow: PhonePlayerScrub.Preview? = {
                    guard case .scrub(let next) = PhonePlayerScrub.decide(start: recognizer.dragStart ?? .zero, translation: translation, viewSize: size, position: origin, gate: recognizer.gate()) else { return nil }
                    return next
                }()
                if let commit = PhonePlayerScrub.commit(recognizer.livePreview(origin: origin, duration: parent.duration) ?? qualifiedNow ?? preview, ended: preview != nil || qualifiedNow != nil) {
                    parent.onCommit(commit)
                }
                preview = nil
                parent.onScrub(nil)
            case .cancelled, .failed:
                _ = PhonePlayerScrub.commit(preview, ended: false)
                preview = nil
                tickBucket = 0
                heldClamp = nil
                parent.onScrub(nil)
            default: break
            }
        }

        private func noteTicks(_ next: PhonePlayerScrub.Preview) {
            let ticks = PhonePlayerScrub.hapticTicks(previousBucket: tickBucket, offset: next.offset, target: next.target, duration: parent.duration, heldClamp: heldClamp)
            tickBucket = PhonePlayerScrub.tickBucket(offset: next.offset)
            if ticks > 0 {
                for _ in 0..<ticks { tickHaptic.selectionChanged(); tickHaptic.prepare() }
            }
            if let edge = PhonePlayerScrub.clampEdgeArrival(target: next.target, duration: parent.duration, held: heldClamp) {
                edgeHaptic.impactOccurred(); edgeHaptic.prepare()
                heldClamp = edge
            } else if PhonePlayerScrub.clampEdge(target: next.target, duration: parent.duration) == nil {
                heldClamp = nil
            }
        }
    }
}

/// Fails immediately for edge, home-indicator, locked, VoiceOver, and unknown-duration drags.
private final class PhonePlayerScrubPan: UIPanGestureRecognizer {
    var gate: () -> PhonePlayerScrub.Gate = { PhonePlayerScrub.Gate(duration: 0, locked: true, voiceOver: true) }
    private(set) var dragStart: CGPoint?

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        dragStart = touches.first?.location(in: view)
        super.touchesBegan(touches, with: event)
        if let dragStart, let view, PhonePlayerScrub.ignoredStart(dragStart, in: view.bounds.size) || !PhonePlayerScrub.isEnabled(gate()) {
            state = .failed
        }
    }
    override func reset() { super.reset(); dragStart = nil }

    func dragTranslation() -> CGSize? {
        guard let dragStart, let view else { return nil }
        let now = location(in: view)
        return CGSize(width: now.x - dragStart.x, height: now.y - dragStart.y)
    }
    func livePreview(origin: Double, duration: Double) -> PhonePlayerScrub.Preview? {
        guard let dragStart, let view, !PhonePlayerScrub.ignoredStart(dragStart, in: view.bounds.size), PhonePlayerScrub.isEnabled(gate()) else { return nil }
        return PhonePlayerScrub.preview(translationX: location(in: view).x - dragStart.x, viewWidth: view.bounds.width, position: origin, duration: duration)
    }
}

private struct AVPlayerPhoneSurface: UIViewRepresentable {
    let engine: AVPlayerPhonePlayback
    func makeUIView(context: Context) -> AVPlayerPhoneSurfaceView {
        let view = AVPlayerPhoneSurfaceView()
        engine.attach(view.playerLayer)
        return view
    }
    func updateUIView(_ view: AVPlayerPhoneSurfaceView, context: Context) {
        if view.playerLayer.player !== engine.player { engine.attach(view.playerLayer) }
    }
}

private final class AVPlayerPhoneSurfaceView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
}

private struct MPVPhoneSurface: UIViewRepresentable {
    let engine: MPVPhonePlayback
    func makeUIView(context: Context) -> MPVPhoneSurfaceView { MPVPhoneSurfaceView(engine: engine) }
    func updateUIView(_ view: MPVPhoneSurfaceView, context: Context) { view.connect(engine) }
}

private final class MPVPhoneMetalLayer: CAMetalLayer {
    // MoltenVK can request a 1x1 drawable during presentation; do not let that
    // overwrite the actual phone viewport and leave playback visibly flickering.
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if newValue.width > 1 && newValue.height > 1 { super.drawableSize = newValue } }
    }
}

private final class MPVPhoneSurfaceView: UIView {
    override class var layerClass: AnyClass { MPVPhoneMetalLayer.self }
    private var engine: MPVPhonePlayback
    private var attached = false
    private var lastDrawableSize = CGSize.zero

    init(engine: MPVPhonePlayback) {
        self.engine = engine
        super.init(frame: .zero)
        backgroundColor = .black
        let metal = layer as! CAMetalLayer
        metal.device = MTLCreateSystemDefaultDevice()
        metal.framebufferOnly = true
        metal.colorspace = CGColorSpace(name: CGColorSpace.sRGB)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    func connect(_ engine: MPVPhonePlayback) {
        guard self.engine !== engine else { return }
        self.engine = engine; attached = false; lastDrawableSize = .zero
        setNeedsLayout()
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            setNeedsLayout()
        }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let metal = layer as! CAMetalLayer
        let scale = window?.screen.scale ?? contentScaleFactor
        metal.contentsScale = scale
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        metal.drawableSize = size
        if !attached, window != nil, bounds.width > 1, bounds.height > 1 {
            attached = true; engine.attach(metal)
        } else if attached, size != lastDrawableSize, size.width > 1, size.height > 1 {
            // MoltenVK sees the drawable resize, but mpv's embedded VO retains
            // its old viewport unless its output is reconfigured as well.
            engine.resize()
        }
        lastDrawableSize = size
    }
}
