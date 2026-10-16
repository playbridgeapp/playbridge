import SwiftUI

struct RemoteNavigationPad: View {
    let allowedKeys: Set<String>
    let send: (String) -> Void
    @State private var gesture = RemoteNavigationGesture()

    private func perform(_ key: String) {
        guard allowedKeys.contains(key) else { return }
        send(key)
    }

    var body: some View {
        RoundedRectangle(cornerRadius: 24)
            .fill(Theme.surfaceContainerLow)
            .overlay {
                VStack(spacing: 4) {
                    Image(systemName: "hand.draw").font(Theme.font(.title2))
                    Text("Swipe to move").font(Theme.font(.caption))
                    if allowedKeys.contains("dpad_center") { Text("Tap for OK").font(Theme.font(.caption2)) }
                }.foregroundStyle(Theme.onSurfaceVariant).allowsHitTesting(false)
            }
            .contentShape(RoundedRectangle(cornerRadius: 24))
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    if let key = gesture.update(x: value.translation.width, y: value.translation.height) { perform(key) }
                }
                .onEnded { value in
                    if let key = gesture.end(x: value.translation.width, y: value.translation.height) { perform(key) }
                })
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Navigation touchpad")
            .accessibilityHint("Swipe to move, tap for OK. Use actions for individual directions.")
            .accessibilityActions {
                if allowedKeys.contains("dpad_center") { Button("Select") { perform("dpad_center") } }
                if allowedKeys.contains("dpad_up") { Button("Up") { perform("dpad_up") } }
                if allowedKeys.contains("dpad_down") { Button("Down") { perform("dpad_down") } }
                if allowedKeys.contains("dpad_left") { Button("Left") { perform("dpad_left") } }
                if allowedKeys.contains("dpad_right") { Button("Right") { perform("dpad_right") } }
            }
    }
}
