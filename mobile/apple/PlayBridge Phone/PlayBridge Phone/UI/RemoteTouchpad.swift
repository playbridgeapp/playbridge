import SwiftUI
import UIKit

struct RemoteTouchpad: UIViewRepresentable {
    var imageGestures: Bool
    var send: (String, Float, Float) -> Void
    var onGestureEnd: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(send: send, onGestureEnd: onGestureEnd) }
    func makeUIView(context: Context) -> TouchpadView {
        let view = TouchpadView()
        view.coordinator = context.coordinator
        view.isMultipleTouchEnabled = true
        view.isAccessibilityElement = true
        view.accessibilityLabel = "TV touchpad"
        return view
    }
    func updateUIView(_ uiView: TouchpadView, context: Context) {
        context.coordinator.send = send
        context.coordinator.onGestureEnd = onGestureEnd
        uiView.imageGestures = imageGestures
        uiView.accessibilityHint = imageGestures
            ? "Drag to move. Pinch to zoom. Twist to rotate. Double tap to reset."
            : "Drag to move. Use two fingers to scroll. Pinch to zoom. Double tap to click. Long-press and drag to drag."
        uiView.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Click", target: context.coordinator, selector: #selector(Coordinator.accessibilityClick))]
    }

    final class Coordinator: NSObject {
        var send: (String, Float, Float) -> Void
        var onGestureEnd: () -> Void
        init(send: @escaping (String, Float, Float) -> Void, onGestureEnd: @escaping () -> Void) {
            self.send = send
            self.onGestureEnd = onGestureEnd
        }
        @objc func accessibilityClick() -> Bool { send("click", 0, 0); return true }
    }

    final class TouchpadView: UIView {
        weak var coordinator: Coordinator?
        var imageGestures = false
        private var twoFingerMode = TwoFingerMode.undecided
        private var initialPinchDistance: CGFloat?
        private var twoFingerStart = Date()
        private var accumPan: CGFloat = 0
        private var rotationReference: CGFloat?
        private var downTime = Date.distantPast
        private var downPos = CGPoint.zero
        private var maxMove: CGFloat = 0
        private var lastTap = Date.distantPast
        private var lastTapPos = CGPoint.zero
        private var scrolling = false
        private var dragging = false
        private var longPress: Timer?

        private let clickSlop: CGFloat = 15
        private let clickTimeout: TimeInterval = 0.3
        private let doubleTapTimeout: TimeInterval = 0.35
        private let doubleTapSlop: CGFloat = 40
        private let gestureSlop: CGFloat = 24
        private let zoomSlopLogRatio: CGFloat = 0.08
        private let imageTransformSlop: CGFloat = 16
        private let imageZoomDelay: TimeInterval = 0.12
        private let modeDominance: CGFloat = 1.2

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            let pressed = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? []
            if pressed.count == 1, let touch = pressed.first {
                downTime = Date()
                downPos = touch.location(in: self)
                maxMove = 0
                longPress?.invalidate()
                let timer = Timer(timeInterval: 0.5, repeats: false) { [weak self] _ in
                    guard let self, !self.scrolling, !self.dragging else { return }
                    self.dragging = true
                    self.coordinator?.send("down", 0, 0)
                }
                longPress = timer
                RunLoop.main.add(timer, forMode: .common)
            } else if pressed.count >= 2 {
                longPress?.invalidate()
                if dragging {
                    dragging = false
                    coordinator?.send("up", 0, 0)
                }
                scrolling = true
            }
        }

        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            let pressed = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? []
            let scale = window?.screen.scale ?? 2
            if pressed.count >= 2 {
                scrolling = true
                guard let a = pressed.first, let b = pressed.dropFirst().first else { return }
                let currentA = a.location(in: self)
                let currentB = b.location(in: self)
                let previousA = a.previousLocation(in: self)
                let previousB = b.previousLocation(in: self)
                let midpoint = CGPoint(x: (currentA.x + currentB.x) / 2, y: (currentA.y + currentB.y) / 2)
                coordinator?.send("transform_anchor", Float(midpoint.x / max(bounds.width, 1)), Float(midpoint.y / max(bounds.height, 1)))
                let curDist = hypot(currentA.x - currentB.x, currentA.y - currentB.y)
                let prevDist = hypot(previousA.x - previousB.x, previousA.y - previousB.y)
                let panX = ((currentA.x + currentB.x) - (previousA.x + previousB.x)) / 2
                let panY = ((currentA.y + currentB.y) - (previousA.y + previousB.y)) / 2
                let currentAngle = atan2(currentA.y - currentB.y, currentA.x - currentB.x)
                let previousAngle = atan2(previousA.y - previousB.y, previousA.x - previousB.x)
                let angleDelta = wrapped(currentAngle - previousAngle)
                let reference = rotationReference ?? currentAngle
                if rotationReference == nil { rotationReference = currentAngle }
                let netRotation = wrapped(currentAngle - reference)
                if twoFingerMode == .undecided {
                    let start = initialPinchDistance ?? curDist
                    if initialPinchDistance == nil {
                        initialPinchDistance = curDist
                        twoFingerStart = Date()
                    }
                    accumPan += abs(panX) + abs(panY)
                    if imageGestures {
                        let radial = abs(curDist - start)
                        let tangential = abs(netRotation) * ((curDist + start) / 4) * 2
                        let elapsed = Date().timeIntervalSince(twoFingerStart)
                        if tangential > imageTransformSlop && tangential >= radial * modeDominance {
                            twoFingerMode = .rotate
                        } else if elapsed >= imageZoomDelay && radial > imageTransformSlop && radial >= tangential * 1.5 {
                            twoFingerMode = .zoom
                        }
                    } else {
                        let zoomProgress = start > 0 && curDist > 0 ? abs(log(curDist / start)) / zoomSlopLogRatio : 0
                        let panProgress = accumPan / gestureSlop
                        if zoomProgress > 1 && zoomProgress >= panProgress * modeDominance {
                            twoFingerMode = .zoom
                        } else if panProgress > 1 {
                            twoFingerMode = .scroll
                        } else if zoomProgress > 1.75 {
                            twoFingerMode = .zoom
                        }
                    }
                }
                switch twoFingerMode {
                case .zoom where prevDist > 0:
                    coordinator?.send("zoom", Float(curDist / prevDist), 0)
                case .rotate:
                    coordinator?.send("rotate", Float(angleDelta * 180 / .pi), 0)
                case .scroll:
                    coordinator?.send("scroll", Float(panX * scale), Float(panY * scale * 2))
                default:
                    break
                }
                return
            }
            guard pressed.count == 1, !scrolling, let touch = pressed.first else { return }
            let pos = touch.location(in: self)
            let previous = touch.previousLocation(in: self)
            maxMove = max(maxMove, hypot(pos.x - downPos.x, pos.y - downPos.y))
            if maxMove > clickSlop { longPress?.invalidate() }
            let dx = (pos.x - previous.x) * scale * 1.5
            let dy = (pos.y - previous.y) * scale * 1.5
            if dx != 0 || dy != 0 { coordinator?.send("move", Float(dx), Float(dy)) }
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) { finish(event) }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { finish(event) }

        private func finish(_ event: UIEvent?) {
            let pressed = event?.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled } ?? []
            guard pressed.isEmpty else { return }
            longPress?.invalidate()
            let duration = Date().timeIntervalSince(downTime)
            if dragging { coordinator?.send("up", 0, 0) }
            else if !scrolling && duration < clickTimeout && maxMove < clickSlop && downTime != .distantPast {
                let now = Date()
                let doubleTap = lastTap != .distantPast && now.timeIntervalSince(lastTap) <= doubleTapTimeout && hypot(downPos.x - lastTapPos.x, downPos.y - lastTapPos.y) <= doubleTapSlop
                if doubleTap {
                    coordinator?.send(imageGestures ? "reset" : "click", 0, 0)
                    lastTap = .distantPast
                } else {
                    coordinator?.send("click", 0, 0)
                    lastTap = now
                    lastTapPos = downPos
                }
            }
            scrolling = false
            dragging = false
            twoFingerMode = .undecided
            initialPinchDistance = nil
            accumPan = 0
            rotationReference = nil
            downTime = .distantPast
            coordinator?.onGestureEnd()
        }

        private func wrapped(_ angle: CGFloat) -> CGFloat {
            var value = angle
            while value > .pi { value -= 2 * .pi }
            while value < -.pi { value += 2 * .pi }
            return value
        }
    }

    private enum TwoFingerMode { case undecided, scroll, zoom, rotate }
}
