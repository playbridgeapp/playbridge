import SwiftUI
import UIKit

struct RemoteNavigationPad: View {
    let allowedKeys: Set<String>
    let begin: () -> UUID
    let end: (UUID) -> Void
    let cancel: () -> Void
    let send: (String, UUID?) -> Void
    /// The pad's fill: the theme's, or a phone list's artwork tone.
    var fill: Color = Theme.surfaceContainerLow
    @Environment(\.isEnabled) private var enabled

    private func accessibleKey(_ key: String) {
        guard enabled, allowedKeys.contains(key) else { return }
        cancel()
        send(key, nil)
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 24)
            .fill(fill)
            .overlay {
                VStack(spacing: 4) {
                    Image(systemName: "hand.draw").font(Theme.font(.title2))
                    Text("Swipe to move").font(Theme.font(.caption))
                    if allowedKeys.contains("dpad_center") { Text("Tap for OK").font(Theme.font(.caption2)) }
                }.foregroundStyle(Theme.onSurfaceVariant).allowsHitTesting(false)
            }
            .overlay {
                NavigationTouchSurface(allowedKeys: allowedKeys, enabled: enabled,
                    begin: begin, end: end, send: send)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .accessibilityHidden(true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Navigation touchpad")
            .accessibilityHint("Swipe to move, tap for OK. Use actions for individual directions.")
            .accessibilityActions {
                if allowedKeys.contains("dpad_center") { Button("Select") { accessibleKey("dpad_center") } }
                if allowedKeys.contains("dpad_up") { Button("Up") { accessibleKey("dpad_up") } }
                if allowedKeys.contains("dpad_down") { Button("Down") { accessibleKey("dpad_down") } }
                if allowedKeys.contains("dpad_left") { Button("Left") { accessibleKey("dpad_left") } }
                if allowedKeys.contains("dpad_right") { Button("Right") { accessibleKey("dpad_right") } }
            }
    }
}

/// UIKit gives explicit touch-up/cancel hooks; a SwiftUI drag's onEnded is not
/// called when a gesture is interrupted. Coordinates are points, independent of screen scale.
private struct NavigationTouchSurface: UIViewRepresentable {
    let allowedKeys: Set<String>
    let enabled: Bool
    let begin: () -> UUID
    let end: (UUID) -> Void
    let send: (String, UUID?) -> Void

    func makeUIView(context: Context) -> TouchView { TouchView() }
    func updateUIView(_ view: TouchView, context: Context) {
        view.begin = begin; view.end = end; view.send = send
        if view.allowedKeys != allowedKeys || view.isUserInteractionEnabled != enabled { view.cancelGesture() }
        view.allowedKeys = allowedKeys
        view.isUserInteractionEnabled = enabled
    }
    static func dismantleUIView(_ view: TouchView, coordinator: ()) { view.cancelGesture() }

    final class TouchView: UIView {
        var allowedKeys: Set<String> = []
        var begin: () -> UUID = { UUID() }
        var end: (UUID) -> Void = { _ in }
        var send: (String, UUID?) -> Void = { _, _ in }
        private let pan = UIPanGestureRecognizer()
        private var gesture = RemoteNavigationGesture()
        private var gestureID: UUID?
        private var touch: UITouch?
        private var holdTask: Task<Void, Never>?
        private var now: Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1_000) }

        override init(frame: CGRect) {
            super.init(frame: frame)
            isAccessibilityElement = false
            pan.cancelsTouchesInView = false
            pan.addTarget(self, action: #selector(panned(_:)))
            addGestureRecognizer(pan)
            NotificationCenter.default.addObserver(self, selector: #selector(interrupted),
                name: UIApplication.willResignActiveNotification, object: nil)
        }
        required init?(coder: NSCoder) { nil }
        deinit { holdTask?.cancel(); NotificationCenter.default.removeObserver(self) }
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window == nil { cancelGesture() } else { claimScrollGestures() }
        }
        @objc private func interrupted() { cancelGesture() }
        @objc private func panned(_ sender: UIPanGestureRecognizer) {
            if sender.state == .cancelled { cancelGesture() }
        }
        private func claimScrollGestures() {
            var view = superview
            while let current = view {
                if let scroll = current as? UIScrollView { scroll.panGestureRecognizer.require(toFail: pan) }
                view = current.superview
            }
        }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            cancelGesture()
            guard isUserInteractionEnabled, let touch = touches.first, !allowedKeys.isEmpty else { return }
            claimScrollGestures()
            self.touch = touch
            gestureID = begin()
            let p = touch.location(in: self)
            gesture.down(x: p.x, y: p.y, at: Int64(touch.timestamp * 1_000))
            RemoteHaptics.shared.prepare()
        }
        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let touch, touches.contains(touch), gestureID != nil else { return }
            let p = touch.location(in: self)
            emit(gesture.move(x: p.x, y: p.y, at: Int64(touch.timestamp * 1_000)))
            scheduleHold()
        }
        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            guard let touch, touches.contains(touch) else { return }
            let p = touch.location(in: self)
            let taps = gesture.up(x: p.x, y: p.y, at: Int64(touch.timestamp * 1_000))
            cancelGesture() // Retire pending arrows before dispatching a tap's discrete OK.
            for step in taps where allowedKeys.contains(step.key) { send(step.key, nil) }
        }
        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelGesture() }

        func cancelGesture() {
            holdTask?.cancel(); holdTask = nil
            gesture.cancel()
            if let gestureID { end(gestureID) }
            gestureID = nil; touch = nil
        }
        private func emit(_ steps: [RemoteNavigationGesture.Step]) {
            guard let gestureID, isUserInteractionEnabled else { return }
            for step in steps where allowedKeys.contains(step.key) { send(step.key, gestureID) }
        }
        private func scheduleHold() {
            holdTask?.cancel(); holdTask = nil
            guard let deadline = gesture.holdDeadline, let id = gestureID else { return }
            let delay = UInt64(max(0, deadline - now)) * 1_000_000
            holdTask = Task { [weak self] in
                do { try await Task.sleep(nanoseconds: delay) } catch { return }
                guard let self, !Task.isCancelled, self.gestureID == id else { return }
                self.emit(self.gesture.tick(at: self.now))
                self.scheduleHold()
            }
        }
    }
}
