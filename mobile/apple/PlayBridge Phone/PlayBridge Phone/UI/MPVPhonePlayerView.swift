import SwiftUI
import Metal

/// Touch controls for the mpv-only phone player, operating through PlaybackSession.
struct MPVPhonePlayerView: View {
    @ObservedObject var session: PlaybackSession
    @StateObject private var controls: PhonePlayerControls
    let onDismiss: (() -> Void)?
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scrubbing = false
    @State private var scrubPosition = 0.0
    @State private var showSettings = false
    @State private var showQueue = false
    @State private var seekFeedback = 0
    @State private var feedbackTask: Task<Void, Never>?
    @State private var scrubPreview: PhonePlayerScrub.Preview?

    init(session: PlaybackSession, controls: PhonePlayerControls? = nil, onDismiss: (() -> Void)? = nil) {
        self.session = session
        self.onDismiss = onDismiss
        _controls = StateObject(wrappedValue: controls ?? PhonePlayerControls())
    }

    var body: some View {
        ZStack {
            if let engine = session.alternativeEngine as? MPVPhonePlayback {
                MPVPhoneSurface(engine: engine)
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
            if session.mpvState.buffering {
                ProgressView().tint(.white).allowsHitTesting(false)
            }
            if !session.websiteCaption.isEmpty {
                VStack {
                    Spacer()
                    Text(session.websiteCaption)
                        .font(.system(size: 20 * session.preferences.subtitleScale))
                        .multilineTextAlignment(.center)
                        .foregroundColor(session.preferences.subtitleColor == .yellow ? .yellow : .white)
                        .padding(8)
                        .background(session.preferences.subtitleBackground ? Color.black.opacity(0.75) : .clear)
                        .padding(.horizontal, 20)
                        .padding(.bottom, controls.visible && !controls.locked ? 124 : 24)
                }.allowsHitTesting(false)
            }
            if controls.visible && !controls.locked {
                VStack(spacing: 0) {
                    toolbar
                    Spacer(minLength: 0)
                    transport
                }.transition(.opacity)
            }
            if controls.locked && controls.unlockVisible {
                VStack {
                    HStack {
                        Spacer()
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
                        .padding(16).background(.black.opacity(0.7), in: Capsule())
                    if seekFeedback < 0 { Spacer() }
                }.padding(24).allowsHitTesting(false).accessibilityHidden(true)
            }
            if let scrubPreview {
                VStack(spacing: 4) {
                    Text(scrubPreview.offsetLabel).font(.system(size: 40, weight: .bold)).monospacedDigit()
                    Text(scrubPreview.timeLabel).font(.title3.weight(.semibold)).monospacedDigit()
                }
                .padding(.horizontal, 24).padding(.vertical, 16)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .foregroundStyle(.white).tint(.white)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: controls.visible)
        .onAppear { controls.setSuspended(false); updateControls() }
        .onChange(of: session.isPlaying) { _ in updateControls() }
        .onChange(of: session.mpvState.buffering) { _ in updateControls() }
        .onChange(of: session.websiteWaitingForNext) { _ in updateControls() }
        .onChange(of: session.websiteQueueChangingItem) { _ in updateControls() }
        .onChange(of: voiceOver) { _ in updateControls() }
        .onChange(of: scenePhase) { controls.setSuspended($0 != .active) }
        .onChange(of: session.failure) { if $0 != nil { controls.unlock() } }
        .onChange(of: showSettings) { _ in controls.setSheetPresented(showSettings || showQueue) }
        .onChange(of: showQueue) { _ in controls.setSheetPresented(showSettings || showQueue) }
        .onChange(of: session.alternativeEngine.map { ObjectIdentifier($0) }) { _ in
            scrubbing = false; controls.setScrubbing(false)
            feedbackTask?.cancel(); seekFeedback = 0; scrubPreview = nil
            controls.reveal()
        }
        .onDisappear { controls.stop(); feedbackTask?.cancel(); feedbackTask = nil }
        .sheet(isPresented: $showSettings, onDismiss: { controls.reveal() }) {
            PhonePlayerSettingsView(session: session)
        }
        .sheet(isPresented: $showQueue, onDismiss: { controls.reveal() }) { queue }
    }

    private var toolbar: some View {
        HStack(spacing: 4) {
            if let onDismiss {
                Button(action: onDismiss) { Image(systemName: "xmark").frame(width: 44, height: 44) }
                    .accessibilityLabel("Close player")
            }
            Spacer(minLength: 0)
            if !session.websiteQueueTitles.isEmpty {
                Button { showQueue = true } label: { Image(systemName: "list.bullet").frame(width: 44, height: 44) }
                    .accessibilityLabel("Queue")
            }
            Button {
                controls.reveal()
                session.updatePreferences { $0.sizing = $0.sizing == .fit ? .fill : .fit }
            } label: {
                Text(session.preferences.sizing == .fit ? "Fit" : "Fill").font(.callout.bold()).frame(width: 44, height: 44)
            }.accessibilityLabel("Video sizing: " + (session.preferences.sizing == .fit ? "Fit" : "Fill"))
                .accessibilityHint("Switch between fitting the picture and filling the screen")
                .accessibilityIdentifier("phone-player-sizing")
            Button { showSettings = true } label: {
                Text(String(format: "%g×", session.preferences.speed)).font(.callout.bold()).frame(minWidth: 44, minHeight: 44)
            }.accessibilityLabel("Playback speed").accessibilityValue(String(format: "%g times", session.preferences.speed))
            Button { controls.lock() } label: { Image(systemName: "lock.fill").frame(width: 44, height: 44) }
                .accessibilityLabel("Lock player controls").accessibilityIdentifier("phone-player-lock")
            Button { showSettings = true } label: { Image(systemName: "gearshape").frame(width: 44, height: 44) }
                .accessibilityLabel("Player settings").accessibilityIdentifier("phone-player-settings")
        }
        .padding(.horizontal, 8)
        .background(LinearGradient(colors: [.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom))
    }
    private var transport: some View {
        VStack(spacing: 12) {
            if session.websiteWaitingForNext { ProgressView("Loading next episode…").tint(.white) }
            if let error = session.websiteQueueError { Text(error).font(.footnote) }
            if let error = session.websiteSubtitleError { Text(error).font(.footnote) }
            HStack(spacing: 16) {
                if !session.websiteQueueTitles.isEmpty {
                    Button { controls.reveal(); session.onWebsiteJump?(session.websiteQueueIndex - 1) } label: {
                        Image(systemName: "backward.end.fill").frame(width: 44, height: 44)
                    }.accessibilityLabel("Previous episode").disabled(session.websiteQueueIndex == 0 || session.websiteQueueChangingItem)
                }
                Button { skip(-10, fromTransport: true) } label: { Image(systemName: "gobackward.10").frame(width: 44, height: 44) }
                    .accessibilityLabel("Back 10 seconds")
                Button { controls.reveal(); session.isPlaying ? session.pause() : session.play() } label: {
                    Image(systemName: session.isPlaying ? "pause.fill" : "play.fill").font(.title).frame(width: 44, height: 44)
                }.accessibilityLabel(session.isPlaying ? "Pause" : "Play").accessibilityIdentifier("phone-player-play")
                Button { skip(10, fromTransport: true) } label: { Image(systemName: "goforward.10").frame(width: 44, height: 44) }
                    .accessibilityLabel("Forward 10 seconds")
                if !session.websiteQueueTitles.isEmpty {
                    Button { controls.reveal(); session.onWebsiteJump?(session.websiteQueueIndex + 1) } label: {
                        Image(systemName: "forward.end.fill").frame(width: 44, height: 44)
                    }.accessibilityLabel("Next episode").disabled(session.websiteQueueIndex + 1 >= session.websiteQueueTitles.count || session.websiteQueueChangingItem)
                }
            }
            HStack {
                Text(time(scrubbing ? scrubPosition : session.positionSeconds)).monospacedDigit()
                Slider(value: Binding(get: {
                    min(max(0, scrubbing ? scrubPosition : session.positionSeconds), max(1, session.durationSeconds))
                }, set: { scrubPosition = $0 }), in: 0...max(1, session.durationSeconds), onEditingChanged: { editing in
                    if editing { scrubPosition = max(0, session.positionSeconds); scrubbing = true }
                    else { session.seek(to: scrubPosition); scrubbing = false }
                    controls.setScrubbing(editing); controls.reveal()
                })
                .disabled(session.durationSeconds <= 0)
                .accessibilityLabel("Playback position").accessibilityValue(time(session.positionSeconds))
                Text(time(session.durationSeconds)).monospacedDigit()
            }.font(.caption)
        }
        .padding(.horizontal, 20).padding(.vertical, 14)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.9)], startPoint: .top, endPoint: .bottom))
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
                    Text(session.websiteWaitingForNext ? "Loading next episode…" : "Streams resolves upcoming episodes as needed.")
                }
            }
            .navigationTitle("Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showQueue = false } } }
        }
    }
    private func updateControls() {
        controls.update(playing: session.isPlaying,
                        buffering: session.mpvState.buffering || session.websiteWaitingForNext || session.websiteQueueChangingItem,
                        voiceOver: voiceOver)
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
    private func time(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let value = Int(max(0, min(seconds, 360_000)))
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60) : String(format: "%d:%02d", value / 60, value % 60)
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
