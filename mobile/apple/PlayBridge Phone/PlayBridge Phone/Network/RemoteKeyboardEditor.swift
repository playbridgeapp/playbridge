import Foundation
import Combine

struct AtvTextEdit: Equatable {
    let deletes: Int
    let suffix: String
    static func between(_ previous: String, _ next: String) -> Self? {
        let before = Array(previous.unicodeScalars), after = Array(next.unicodeScalars)
        guard before.count <= 64, after.count <= 64 else { return nil }
        var common = 0
        while common < min(before.count, after.count), before[common] == after[common] { common += 1 }
        return .init(deletes: before.count - common, suffix: String(String.UnicodeScalarView(after.dropFirst(common))))
    }
}

/// Drafts never initialize from TV IME state. Only acknowledged writes advance the baseline.
/// Composition stays local; one failed/ambiguous edit permanently stops this sheet's editor.
@MainActor final class RemoteKeyboardEditor: ObservableObject {
    @Published private(set) var draft = ""
    @Published private(set) var failed = false
    @Published private(set) var limitReached = false
    @Published private(set) var busy = false
    private(set) var marked = false
    private let route: RemoteCommandRoute
    private let execute: (RemoteKeyboardCommand) async throws -> Void
    private var committed = ""
    private var edits: [(String, Bool)] = []
    private var worker: Task<Void, Never>?
    private var closed = false
    init(route: RemoteCommandRoute, execute: @escaping (RemoteKeyboardCommand) async throws -> Void) {
        self.route = route; self.execute = execute
    }
    func update(_ text: String, marked: Bool) {
        guard !closed && !failed else { return }
        guard route == .atv ? text.unicodeScalars.count <= 64 : text.utf8.count <= 65_536 else { limitReached = true; return }
        let changed = text != draft || self.marked && !marked
        draft = text; self.marked = marked; limitReached = false
        if !marked && changed { enqueue(text, enter: false) }
    }
    func enter() {
        guard !marked && !closed && !failed else { return }
        enqueue(draft, enter: true)
    }
    private func enqueue(_ text: String, enter: Bool) {
        guard edits.count < 64 else { busy = true; return }
        edits.append((text, enter)); busy = false
        guard worker == nil else { return }
        worker = Task { [weak self] in await self?.drain() }
    }
    private func drain() async {
        defer { worker = nil }
        while !closed && !edits.isEmpty {
            let (text, enter) = edits.removeFirst()
            if text == committed && !enter { continue }
            do {
                if route == .atv {
                    guard let edit = AtvTextEdit.between(committed, text) else { limitReached = true; continue }
                    try await execute(.edit(deletes: edit.deletes, suffix: edit.suffix, enter: enter))
                }
                else {
                    if text != committed { try await execute(.replace(text)) }
                    if enter { try await execute(.enter) }
                }
                guard !closed, !Task.isCancelled else { return }
                committed = text
            } catch {
                if !closed { failed = true }
                edits = []; return
            }
        }
    }
    func close() { closed = true; edits = []; worker?.cancel(); worker = nil }
    deinit { worker?.cancel() }
}
