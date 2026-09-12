import SwiftUI
import UIKit

/// Separate one/two-finger recognizers prevent a scroll from becoming a mouse move.
struct RemoteTouchpad: UIViewRepresentable {
    var send: (String, Float, Float) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(send: send) }
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isMultipleTouchEnabled = true
        view.isAccessibilityElement = true
        view.accessibilityLabel = "TV touchpad"
        view.accessibilityHint = "Drag to move the pointer. Use two fingers to scroll. Double tap to click."
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.click))
        let move = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.move(_:)))
        move.maximumNumberOfTouches = 1
        let scroll = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.scroll(_:)))
        scroll.minimumNumberOfTouches = 2
        scroll.maximumNumberOfTouches = 2
        tap.require(toFail: move)
        tap.require(toFail: scroll)
        view.addGestureRecognizer(tap)
        view.addGestureRecognizer(move)
        view.addGestureRecognizer(scroll)
        view.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Click", target: context.coordinator, selector: #selector(Coordinator.accessibilityClick))]
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) { context.coordinator.send = send }

    final class Coordinator: NSObject {
        var send: (String, Float, Float) -> Void
        init(send: @escaping (String, Float, Float) -> Void) { self.send = send }
        @objc func click() { send("click", 0, 0) }
        @objc func accessibilityClick() -> Bool { click(); return true }
        @objc func move(_ gesture: UIPanGestureRecognizer) { pan(gesture, event: "move") }
        @objc func scroll(_ gesture: UIPanGestureRecognizer) { pan(gesture, event: "scroll") }
        private func pan(_ gesture: UIPanGestureRecognizer, event: String) {
            guard gesture.state == .began || gesture.state == .changed || gesture.state == .ended else { return }
            let delta = gesture.translation(in: gesture.view)
            gesture.setTranslation(.zero, in: gesture.view)
            if delta != .zero { send(event, Float(delta.x), Float(delta.y)) }
        }
    }
}
