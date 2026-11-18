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

    init(session: PlaybackSession, controls: PhonePlayerControls? = nil, onDismiss: (() -> Void)? = nil) {
        self.session = session
        self.onDismiss = onDismiss
        _controls = StateObject(wrappedValue: controls ?? PhonePlayerControls())
    }

    private var buffering: Bool { session.mpvState.buffering || session.websiteWaitingForNext || session.websiteQueueChangingItem }
    private var hasQueue: Bool { !session.websiteQueueTitles.isEmpty }
    private var landscape: Bool { verticalSizeClass == .compact }
    private var chromeShown: Bool { controls.visible && !controls.locked }
    private var sheetShown: Bool { showMore || showTracks || showQueue }

    var body: some View {
        ZStack {
            if let engine = session.alternativeEngine as? MPVPhonePlayback {
                MPVPhoneSurface(engine: engine)
                    .id(ObjectIdentifier(engine))
                    .ignoresSafeArea()
            } else if let engine = session.alternativeEngine as? AVPlayerPhonePlayback {
                AVPlayerPhoneSurface(engine: engine)
                    .id(ObjectIdentifier(engine))
                    .ignoresSafeArea()
            }
            PhonePlayerSurfaceGestures(
                duration: session.durationSeconds,
                position: session.positionSeconds,
                locked: controls.locked,
                voiceOver: voiceOver,
                onTap: { controls.tap() },
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
            .ignoresSafeArea()
            .accessibilityHidden(true)
            if buffering && !chromeShown {
                ProgressView().tint(.white).allowsHitTesting(false)
            }
            if !overlayCaption.isEmpty {
                VStack {
                    Spacer()
                    SubtitleCaptionText(text: overlayCaption, style: subtitleStyle.style)
                        .padding(.horizontal, 20)
                        .padding(.bottom, chromeShown ? 128 : 24)
                }.allowsHitTesting(false)
            }
            if chromeShown {
                ZStack {
                    VStack(spacing: 0) {
                        topBar
                        Spacer(minLength: 0)
                        bottomBar
                    }
                    centre
                }.transition(.opacity)
            }
            if controls.locked && controls.unlockVisible {
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
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .foregroundStyle(.white).tint(Theme.primary)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: controls.visible)
        .onAppear { controls.setSuspended(false); updateControls() }
        .onChange(of: session.isPlaying) { _ in updateControls() }
        .onChange(of: session.mpvState.buffering) { _ in updateControls() }
        .onChange(of: session.websiteWaitingForNext) { _ in updateControls() }
        .onChange(of: session.websiteQueueChangingItem) { _ in updateControls() }
        .onChange(of: voiceOver) { _ in updateControls() }
        .onChange(of: scenePhase) { controls.setSuspended($0 != .active) }
        .onChange(of: session.failure) { if $0 != nil { controls.unlock() } }
        .onChange(of: sheetShown) { controls.setSheetPresented($0) }
        .onChange(of: session.alternativeEngine.map { ObjectIdentifier($0) }) { _ in
            timelineTarget = nil; controls.setScrubbing(false)
            feedbackTask?.cancel(); seekFeedback = 0; scrubPreview = nil
            controls.reveal()
        }
        .onDisappear {
            controls.stop(); feedbackTask?.cancel(); feedbackTask = nil
            PhonePlayerOrientationLock.release()
        }
        .sheet(isPresented: $showMore, onDismiss: { controls.reveal() }) { PhonePlayerMoreSheet(session: session) }
        .sheet(isPresented: $showTracks, onDismiss: { controls.reveal() }) { PhonePlayerTracksSheet(session: session) }
        .sheet(isPresented: $showQueue, onDismiss: { controls.reveal() }) { queue }
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
                if let onDismiss {
                    Button(action: onDismiss) { Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44) }
                        .accessibilityLabel("Close player")
                        .accessibilityIdentifier("phone-player-close")
                }
                if landscape { titleBlock.padding(.leading, onDismiss == nil ? 8 : 0) }
                Spacer(minLength: 8)
                topActions
            }
            if !landscape { titleBlock.padding(.horizontal, 12) }
        }
        .padding(.horizontal, 8).padding(.bottom, 28)
        .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .top))
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
                    Circle().fill(.black.opacity(0.45))
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
            if session.websiteWaitingForNext {
                Text("Getting the next episode from the website…").font(Theme.font(size: 13))
            }
            if let error = session.websiteQueueError { Text(error).font(Theme.font(size: 13)) }
            if let error = session.websiteSubtitleError { Text(error).font(Theme.font(size: 13)) }
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
            HStack(spacing: 4) {
                Button { controls.lock() } label: { Image(systemName: "lock").frame(width: 44, height: 44) }
                    .accessibilityLabel("Lock player controls").accessibilityIdentifier("phone-player-lock")
                Button {
                    controls.reveal()
                    PhonePlayerOrientationLock.toggle()
                } label: { Image(systemName: "rotate.right").frame(width: 44, height: 44) }
                    .accessibilityLabel(PhonePlayerOrientationLock.isLandscape ? "Rotate to portrait" : "Rotate to landscape")
                    .accessibilityIdentifier("phone-player-rotate")
                Spacer(minLength: 0)
                chip(PhonePlayerChrome.speedLabel(session.preferences.speed), active: session.preferences.speed != 1) { showMore = true }
                    .accessibilityLabel("Playback speed")
                    .accessibilityValue(String(format: "%g times", session.preferences.speed))
                    .accessibilityHint("Opens More")
                    .accessibilityIdentifier("phone-player-speed")
            }
            .font(.system(size: 18, weight: .medium))
        }
        .padding(.horizontal, landscape ? 28 : 16).padding(.top, 32).padding(.bottom, 6)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.88)], startPoint: .top, endPoint: .bottom).ignoresSafeArea(edges: .bottom))
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
