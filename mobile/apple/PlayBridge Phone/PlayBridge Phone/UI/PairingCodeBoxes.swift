import SwiftUI

/// Six-digit boxes used by PlayBridge pairing and Browser on a TV.
struct PairingCodeBoxes: View {
    @Binding var code: String
    /// A rejected attempt. Boxes use the error colour only while `code` is empty.
    var rejected: Bool
    var focus: FocusState<Bool>.Binding
    var accessibilityLabel: String
    var onFilled: (String) -> Void

    var body: some View {
        let showRejected = rejected && code.isEmpty
        return HStack(spacing: 8) {
            ForEach(0..<6, id: \.self) { index in
                let active = !showRejected && index == code.count
                Text(digit(index))
                    .font(Theme.font(size: 28, weight: .bold, design: .monospaced))
                    .foregroundColor(Theme.onSurface)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(showRejected ? Theme.danger : (active ? Theme.primary : Theme.outlineVariant),
                                    lineWidth: (showRejected || active) ? 2 : 1)
                    )
            }
        }
        .accessibilityHidden(true)
        .background {
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused(focus)
                .opacity(0.02)
                .accessibilityLabel(accessibilityLabel)
                .onChange(of: code) { newValue in
                    let filtered = String(newValue.filter(\.isNumber).prefix(6))
                    if filtered != newValue { code = filtered }
                    if filtered.count == 6 { onFilled(filtered) }
                }
        }
        .onTapGesture { focus.wrappedValue = true }
    }

    private func digit(_ index: Int) -> String {
        guard index < code.count else { return "" }
        let character = code[code.index(code.startIndex, offsetBy: index)]
        return String(character)
    }
}
