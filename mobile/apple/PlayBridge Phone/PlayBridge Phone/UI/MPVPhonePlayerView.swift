import SwiftUI
import Metal

/// Touch controls for the standard MPVKit Metal renderer. AVPlayer retains its
/// own native controls; both views operate through PlaybackSession.
struct MPVPhonePlayerView: View {
    @ObservedObject var session: PlaybackSession
    @State private var scrubbing = false
    @State private var scrubPosition = 0.0

    var body: some View {
        ZStack(alignment: .bottom) {
            if let engine = session.alternativeEngine as? MPVPhonePlayback {
                MPVPhoneSurface(engine: engine)
                    .id(ObjectIdentifier(engine))
            }
            if session.mpvState.buffering { ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity) }
            VStack(spacing: 12) {
                HStack(spacing: 24) {
                    Button { session.seek(to: session.positionSeconds - 10) } label: {
                        Image(systemName: "gobackward.10").frame(width: 44, height: 44)
                    }.accessibilityLabel("Back 10 seconds")
                    Button { session.isPlaying ? session.pause() : session.play() } label: {
                        Image(systemName: session.isPlaying ? "pause.fill" : "play.fill").font(.title)
                            .frame(width: 44, height: 44)
                    }.accessibilityLabel(session.isPlaying ? "Pause" : "Play")
                    Button { session.seek(to: session.positionSeconds + 10) } label: {
                        Image(systemName: "goforward.10").frame(width: 44, height: 44)
                    }.accessibilityLabel("Forward 10 seconds")
                }
                HStack {
                    Text(time(scrubbing ? scrubPosition : session.positionSeconds)).monospacedDigit()
                    Slider(value: Binding(get: {
                        min(max(0, scrubbing ? scrubPosition : session.positionSeconds), max(1, session.durationSeconds))
                    }, set: { scrubPosition = $0 }), in: 0...max(1, session.durationSeconds), onEditingChanged: { editing in
                        if editing { scrubPosition = max(0, session.positionSeconds); scrubbing = true }
                        else { session.seek(to: scrubPosition); scrubbing = false }
                    })
                    .disabled(session.durationSeconds <= 0)
                    .accessibilityLabel("Playback position")
                    Text(time(session.durationSeconds)).monospacedDigit()
                }.font(.caption)
            }
            .foregroundStyle(.white).tint(.white)
            .padding(.horizontal, 20).padding(.vertical, 14)
            .background(LinearGradient(colors: [.clear, .black.opacity(0.9)], startPoint: .top, endPoint: .bottom))
        }
        .onChange(of: session.engineKind) { _ in scrubbing = false }
    }

    private func time(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let value = Int(max(0, min(seconds, 360_000)))
        return value >= 3600 ? String(format: "%d:%02d:%02d", value / 3600, value / 60 % 60, value % 60) : String(format: "%d:%02d", value / 60, value % 60)
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
        self.engine = engine; attached = false
        setNeedsLayout()
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let metal = layer as! CAMetalLayer
        let scale = window?.screen.scale ?? contentScaleFactor
        metal.contentsScale = scale
        metal.drawableSize = CGSize(width: bounds.width * scale, height: bounds.height * scale)
        if !attached, window != nil, bounds.width > 1, bounds.height > 1 {
            attached = true; engine.attach(metal)
        }
    }
}
