import SwiftUI

struct MenuButton: View {
    let title: String
    let icon: String
    @Binding var currentScreen: AppScreen
    let screen: AppScreen
    @State private var isFocused = false

    var isSelected: Bool { currentScreen == screen }

    var body: some View {
        Button(action: { currentScreen = screen }) {
            HStack(spacing: 20) {
                Image(systemName: icon).font(.system(size: 28)).frame(width: 40)
                Text(title).font(
                    .system(size: 26, weight: isSelected || isFocused ? .bold : .medium))
                    // Bold + the selection dot can push two-word titles onto a second line.
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .layoutPriority(1)
                Spacer(minLength: 0)
                if isSelected { Circle().fill(Theme.accent).frame(width: 8, height: 8) }
            }
            .padding(.vertical, 20)
            .padding(.horizontal, 24)
            .foregroundColor(isFocused ? Theme.accent : (isSelected ? .white : Theme.secondaryText))
            .scaleEffect(isFocused ? 1.1 : 1.0)
            .animation(.interactiveSpring(), value: isFocused)
        }
        .buttonStyle(.plain)
        .appRemoteAction({ currentScreen = screen }, focusChanged: { isFocused = $0 })
    }
}
