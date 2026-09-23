import SwiftUI
import UIKit

struct RemoteSeekBar: View {
    var positionMs: Double
    var durationMs: Double
    var isLive: Bool
    var isSeekable: Bool
    var isPlaying: Bool
    var enableVolume: Bool
    var onSeekTo: (Int64) -> Void
    var onVolumeUp: () -> Void
    var onVolumeDown: () -> Void
    var onPlayPause: () -> Void
    @Binding var feedback: Feedback?

    struct Feedback {
        enum Kind { case seek, volume }
        let kind: Kind
        let mode: RemoteSeekBehavior.Mode
        let targetMs: Double
        let positionMs: Double
        let durationMs: Double
        let volumeDirection: String?
    }

    @State private var dragging = false
    @State private var dragMs = 0.0
    @State private var axis: Axis?
    @State private var scrubMode: RemoteSeekBehavior.Mode = .relative
    @State private var volumeDirection: String?

    private var hasDuration: Bool { durationMs > 0 && !isLive && isSeekable }
    private var displayMs: Double { dragging && hasDuration ? dragMs : positionMs }
    private var fraction: Double { hasDuration ? min(1, max(0, displayMs / durationMs)) : 0 }
    private var activeColor: Color {
        scrubMode == .absolute && axis == .vertical
            ? Color(red: 1, green: 0.32, blue: 0.32) : Theme.primary
    }

    var body: some View {
        ZStack {
            VStack(spacing: 8) {
                HStack(spacing: 6) {
                    Text(axis == .horizontal ? (scrubMode == .absolute ? "SCRUB ◄►" : "SEEK ◄►") : (isPlaying ? "SEEK · TAP PAUSE" : "SEEK · TAP PLAY"))
                        .lineLimit(1).minimumScaleFactor(0.75)
                    if enableVolume {
                        Text("·").foregroundStyle(Theme.onSurfaceVariant.opacity(0.4))
                        Text(axis == .vertical && scrubMode == .absolute ? "FAST VOL ▲▼" : "VOL ▲▼")
                            .lineLimit(1).minimumScaleFactor(0.75)
                    }
                }
                .font(Theme.font(size: 10, weight: .bold)).tracking(1).foregroundStyle(axis == nil ? Theme.onSurfaceVariant : activeColor)
                GeometryReader { geometry in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.16))
                        Capsule().fill(Theme.primary).frame(width: hasDuration ? geometry.size.width * fraction : 0)
                    }
                }.frame(height: 4)
                HStack {
                    Text(RemoteMode.time(Int64(min(displayMs, Double(Int64.max / 2)))))
                    Spacer()
                    Text(isLive ? "● LIVE" : durationMs > 0 ? RemoteMode.time(Int64(durationMs)) : "--:--")
                        .foregroundStyle(isLive ? Theme.primary : Theme.onSurfaceVariant)
                }.font(Theme.font(size: 11)).monospacedDigit().foregroundStyle(Theme.onSurfaceVariant)
            }.padding(.horizontal, 52).padding(.vertical, 14)
            HStack {
                Image(systemName: "chevron.left")
                Spacer()
                Image(systemName: "chevron.right")
            }.font(Theme.font(size: 16, weight: .semibold)).foregroundStyle(Theme.onSurfaceVariant.opacity(0.45)).padding(.horizontal, 12)
            if enableVolume {
                VStack {
                    Image(systemName: "chevron.up")
                    Spacer()
                    Image(systemName: "chevron.down")
                }.font(Theme.font(size: 12, weight: .semibold)).foregroundStyle(Theme.onSurfaceVariant.opacity(0.45)).padding(.vertical, 4)
            }
            SeekGesture(hasDuration: hasDuration, durationMs: durationMs, positionMs: positionMs, enableVolume: enableVolume) { event in
                switch event {
                case .playPause: onPlayPause()
                case .volumeUp:
                    volumeDirection = "up"
                    onVolumeUp()
                case .volumeDown:
                    volumeDirection = "down"
                    onVolumeDown()
                case .drag(let ms, let nextAxis, let mode):
                    dragging = nextAxis == .horizontal && hasDuration
                    dragMs = ms
                    axis = nextAxis
                    scrubMode = mode
                    feedback = Feedback(
                        kind: nextAxis == .horizontal ? .seek : .volume,
                        mode: mode,
                        targetMs: ms,
                        positionMs: positionMs,
                        durationMs: durationMs,
                        volumeDirection: volumeDirection
                    )
                case .end(let ms):
                    if let ms, hasDuration { onSeekTo(ms) }
                    dragging = false
                    axis = nil
                    scrubMode = .relative
                    volumeDirection = nil
                    feedback = nil
                }
            }
        }
        .frame(height: 86)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 28))
        .onDisappear { feedback = nil }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(isPlaying ? "Pause" : "Play")
        .accessibilityValue(isLive ? "Live" : "\(RemoteMode.time(Int64(displayMs))) of \(durationMs > 0 ? RemoteMode.time(Int64(durationMs)) : "unknown")")
        .accessibilityAdjustableAction { direction in
            guard hasDuration else { return }
            let delta: Double = direction == .increment ? 10_000 : -10_000
            onSeekTo(Int64(min(durationMs, max(0, positionMs + delta))))
        }
    }

    struct FeedbackHUD: View {
        let feedback: Feedback

        private var activeColor: Color {
            feedback.mode == .absolute && feedback.kind == .volume
                ? Color(red: 1, green: 0.32, blue: 0.32) : Theme.primary
        }

        var body: some View {
            Group {
                if feedback.kind == .seek {
                    VStack(spacing: 6) {
                        Text(feedback.mode == .absolute ? "Scrub" : "Seek")
                            .font(Theme.font(size: 12, weight: .bold)).tracking(1).foregroundStyle(activeColor)
                        Text("\(RemoteMode.time(Int64(feedback.targetMs))) / \(RemoteMode.time(Int64(feedback.durationMs)))")
                            .font(Theme.font(size: 17, weight: .semibold)).monospacedDigit()
                            .lineLimit(1).minimumScaleFactor(0.75)
                        Text(RemoteSeekBehavior.signedOffset(targetMs: feedback.targetMs, currentMs: feedback.positionMs))
                            .font(Theme.font(size: 13)).monospacedDigit().foregroundStyle(Color.white.opacity(0.8))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 28).padding(.vertical, 18)
                    .background(Color.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(activeColor.opacity(0.5)))
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: feedback.volumeDirection == "down" ? "speaker.wave.1.fill" : "speaker.wave.3.fill")
                            .font(Theme.font(size: 30)).foregroundStyle(activeColor)
                        Text(feedback.volumeDirection == nil ? (feedback.mode == .absolute ? "Volume (Fast)" : "Volume") :
                                "Volume \(feedback.volumeDirection == "up" ? "Up" : "Down")\(feedback.mode == .absolute ? " (Fast)" : "")")
                            .font(Theme.font(size: 13, weight: .bold)).tracking(1)
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 28).padding(.vertical, 22)
                    .background(Color.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(activeColor.opacity(0.5)))
                }
            }
        }
    }

    fileprivate enum Axis { case horizontal, vertical }
    fileprivate enum GestureEvent {
        case playPause, volumeUp, volumeDown
        case drag(Double, Axis, RemoteSeekBehavior.Mode)
        case end(Int64?)
    }

    private struct SeekGesture: UIViewRepresentable {
        var hasDuration: Bool
        var durationMs: Double
        var positionMs: Double
        var enableVolume: Bool
        var onEvent: (GestureEvent) -> Void

        func makeCoordinator() -> Coordinator { Coordinator(onEvent: onEvent) }
        func makeUIView(context: Context) -> GestureView {
            let view = GestureView()
            view.coordinator = context.coordinator
            view.isAccessibilityElement = false
            return view
        }
        func updateUIView(_ uiView: GestureView, context: Context) {
            context.coordinator.onEvent = onEvent
            uiView.hasDuration = hasDuration
            uiView.durationMs = durationMs
            uiView.positionMs = positionMs
            uiView.enableVolume = enableVolume
        }

        final class Coordinator {
            var onEvent: (GestureEvent) -> Void
            init(onEvent: @escaping (GestureEvent) -> Void) { self.onEvent = onEvent }
        }

        final class GestureView: UIView, UIGestureRecognizerDelegate {
            weak var coordinator: Coordinator?
            var hasDuration = false
            var durationMs = 0.0
            var positionMs = 0.0
            var enableVolume = false
            private let pan = UIPanGestureRecognizer()
            private var down = Date.distantPast
            private var axis: Axis?
            private var dragMs = 0.0
            private var volAccum: CGFloat = 0
            private var seekAccum: CGFloat = 0
            private var mode: RemoteSeekBehavior.Mode = .relative
            private var lastTranslation = CGPoint.zero
            private let tickFeedback = UISelectionFeedbackGenerator()
            private let holdFeedback = UIImpactFeedbackGenerator(style: .medium)

            override init(frame: CGRect) {
                super.init(frame: frame)
                pan.addTarget(self, action: #selector(panned(_:)))
                pan.delegate = self
                pan.cancelsTouchesInView = true
                addGestureRecognizer(pan)
            }
            required init?(coder: NSCoder) { nil }

            override func didMoveToWindow() {
                super.didMoveToWindow()
                claimScrollGestures()
            }

            func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
                false
            }

            @objc private func panned(_ gesture: UIPanGestureRecognizer) {
                switch gesture.state {
                case .began:
                    claimScrollGestures()
                    setEnclosingScrollEnabled(false)
                    tickFeedback.prepare()
                    if down == .distantPast { down = Date() }
                    mode = RemoteSeekBehavior.mode(heldFor: Date().timeIntervalSince(down))
                    if mode == .absolute { holdFeedback.impactOccurred() }
                    axis = nil
                    volAccum = 0
                    seekAccum = 0
                    dragMs = positionMs
                    lastTranslation = .zero
                case .changed:
                    let translation = gesture.translation(in: self)
                    let drag = CGPoint(x: translation.x - lastTranslation.x, y: translation.y - lastTranslation.y)
                    lastTranslation = translation
                    if axis == nil {
                        axis = abs(translation.x) >= abs(translation.y) ? .horizontal : .vertical
                        tick()
                    }
                    let location = gesture.location(in: self)
                    switch axis {
                    case .horizontal where hasDuration && bounds.width > 0:
                        dragMs = RemoteSeekBehavior.target(
                            currentMs: dragMs, durationMs: durationMs, width: Double(bounds.width),
                            fingerX: Double(location.x), dragX: Double(drag.x), mode: mode
                        )
                        seekAccum += abs(drag.x)
                        while seekAccum >= 16 {
                            tick()
                            seekAccum -= 16
                        }
                        coordinator?.onEvent(.drag(dragMs, .horizontal, mode))
                    case .vertical where enableVolume:
                        volAccum -= drag.y
                        let step: CGFloat = mode == .absolute ? 28 / 2.5 : 28
                        while volAccum >= step {
                            coordinator?.onEvent(.volumeUp)
                            tick()
                            volAccum -= step
                        }
                        while volAccum <= -step {
                            coordinator?.onEvent(.volumeDown)
                            tick()
                            volAccum += step
                        }
                        coordinator?.onEvent(.drag(positionMs, .vertical, mode))
                    default:
                        break
                    }
                case .ended:
                    finish(cancelled: false)
                case .cancelled, .failed:
                    finish(cancelled: true)
                default:
                    break
                }
            }

            override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
                down = Date()
                holdFeedback.prepare()
                claimScrollGestures()
                setEnclosingScrollEnabled(false)
            }

            override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
                setEnclosingScrollEnabled(true)
                if pan.state != .changed, pan.state != .ended, axis == nil, Date().timeIntervalSince(down) < 0.3 {
                    coordinator?.onEvent(.playPause)
                }
            }

            override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
                if pan.state == .possible || pan.state == .failed { setEnclosingScrollEnabled(true) }
            }

            private func finish(cancelled: Bool) {
                setEnclosingScrollEnabled(true)
                if !cancelled, axis == .horizontal, hasDuration, let ms = Int64(exactly: dragMs.rounded(.towardZero)) {
                    coordinator?.onEvent(.end(ms))
                } else {
                    coordinator?.onEvent(.end(nil))
                }
                axis = nil
                down = .distantPast
                mode = .relative
            }

            private func claimScrollGestures() {
                var view = superview
                while let current = view {
                    if let scroll = current as? UIScrollView {
                        scroll.panGestureRecognizer.require(toFail: pan)
                    }
                    view = current.superview
                }
            }

            private func setEnclosingScrollEnabled(_ enabled: Bool) {
                var view = superview
                while let current = view {
                    if let scroll = current as? UIScrollView { scroll.isScrollEnabled = enabled }
                    view = current.superview
                }
            }

            private func tick() {
                tickFeedback.selectionChanged()
                tickFeedback.prepare()
            }
        }
    }
}
