import SwiftUI

struct DangerButton: View {
    let title: String
    let icon: String
    let action: () -> Void
    @State var isFocused = false
    
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                Text(title)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
            }
            .font(.headline)
            .foregroundColor(isFocused ? .white : .red.opacity(0.6))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(isFocused ? Color.red : Color.red.opacity(0.1))
            .cornerRadius(10)
        }
        .buttonStyle(.plain)
        .appRemoteAction(action, focusChanged: { isFocused = $0 })
    }
}
