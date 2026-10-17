import SwiftUI
import UIKit

/// A fresh editor per sheet/ticket: no draft is replayed after a route or session change.
struct RemoteKeyboardSheet: View {
    let ticket: RemoteKeyboardTicket
    let valid: Bool
    let canEnter: Bool
    @StateObject private var editor: RemoteKeyboardEditor
    @Environment(\.dismiss) private var dismiss
    @State private var command = FieldCommand()
    init(ticket: RemoteKeyboardTicket, valid: Bool, canEnter: Bool, execute: @escaping (RemoteKeyboardCommand) async throws -> Void) {
        self.ticket = ticket; self.valid = valid; self.canEnter = canEnter
        _editor = StateObject(wrappedValue: RemoteKeyboardEditor(route: ticket.route, execute: execute))
    }
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Tap a text box on the TV, then type here — it's sent as you type.")
                    .font(.subheadline).foregroundStyle(.secondary)
                RemoteCompositionField(text: editor.draft, enabled: valid && !editor.failed,
                    command: command, limit: ticket.route == .atv ? 64 : 65_536, onChange: { editor.update($0, marked: $1) }, onEnter: { if canEnter { editor.enter() } })
                    .frame(height: 52).privacySensitive().accessibilityLabel("Text to send to TV")
                if !valid { Text("Keyboard context changed. Close and reopen the keyboard.") }
                if editor.failed { Text("Could not send text. Close and reopen the keyboard; the last edit may have been partially sent.") }
                if editor.limitReached { Text(ticket.route == .atv ? "Use up to 64 characters at a time." : "Text is too long.") }
                if editor.busy { Text("Wait for typing to finish, then try again.") }
                HStack {
                    Button("Delete") { command = .init(action: .delete) }.accessibilityLabel("Delete previous character")
                    Button("Clear") { command = .init(action: .clear) }.accessibilityLabel("Clear typed text")
                    Spacer()
                    Button("Enter") { command = .init(action: .enter) }.accessibilityLabel("Send Enter to TV").disabled(!canEnter)
                }.buttonStyle(.bordered).disabled(!valid || editor.failed)
                Spacer()
            }.padding(16)
                .navigationTitle("Keyboard").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onChange(of: valid) { if !$0 { editor.close() } }
        .onDisappear { editor.close() }
    }
}

private struct FieldCommand: Equatable {
    enum Action { case none, delete, clear, enter }
    var id = UUID()
    var action: Action = .none
}

/// SwiftUI TextField doesn't expose marked text. UIKit owns composition and cursor/selection;
/// model updates are deferred until unmarking, including a Return or explicit Enter action.
private struct RemoteCompositionField: UIViewRepresentable {
    let text: String
    let enabled: Bool
    let command: FieldCommand
    let limit: Int
    let onChange: (String, Bool) -> Void
    let onEnter: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UITextField {
        let field = UITextField()
        field.placeholder = "Type to send to TV…"; field.borderStyle = .roundedRect
        field.autocorrectionType = .no; field.autocapitalizationType = .none; field.returnKeyType = .go
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.accessibilityLabel = "Text to send to TV"
        context.coordinator.lastCommand = command.id
        DispatchQueue.main.async { [weak field] in if field?.isEnabled == true { field?.becomeFirstResponder() } }
        return field
    }
    func updateUIView(_ field: UITextField, context: Context) {
        context.coordinator.parent = self
        field.isEnabled = enabled
        if !enabled { field.resignFirstResponder(); return }
        if field.markedTextRange == nil && field.text != text { field.text = text }
        guard context.coordinator.lastCommand != command.id else { return }
        context.coordinator.lastCommand = command.id
        context.coordinator.suppress = true
        switch command.action {
        case .none: break
        case .delete: field.deleteBackward()
        case .clear: field.unmarkText(); field.text = ""
        case .enter: field.unmarkText()
        }
        context.coordinator.suppress = false
        context.coordinator.changed(field)
        if command.action == .enter { onEnter() }
    }
    static func dismantleUIView(_ field: UITextField, coordinator: Coordinator) {
        field.delegate = nil; field.removeTarget(coordinator, action: nil, for: .allEvents)
        field.resignFirstResponder()
    }
    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: RemoteCompositionField
        var lastCommand: UUID?
        var suppress = false
        init(_ parent: RemoteCompositionField) { self.parent = parent }
        @objc func changed(_ field: UITextField) { guard !suppress else { return }; parent.onChange(field.text ?? "", field.markedTextRange != nil) }
        func textFieldDidChangeSelection(_ textField: UITextField) { changed(textField) }
        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            textField.unmarkText(); changed(textField); parent.onEnter(); return false
        }
        func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
            let before = textField.text ?? ""
            guard let range = Range(range, in: before) else { return false }
            let next = before.replacingCharacters(in: range, with: string)
            if next.unicodeScalars.count > parent.limit { parent.onChange(next, textField.markedTextRange != nil); return false }
            return true
        }
    }
}
