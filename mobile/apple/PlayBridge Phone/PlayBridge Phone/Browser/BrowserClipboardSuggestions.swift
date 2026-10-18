// SPDX-License-Identifier: MPL-2.0
import UIKit
import Combine

final class BrowserClipboardSuggestions: ObservableObject {
    enum Kind { case link, text }
    @Published private(set) var offered: Kind?
    private var offeredChangeCount: Int?
    private var policy = BrowserClipboardOfferPolicy()
    private let changeCount: () -> Int
    private let detect: () async throws -> Set<PartialKeyPath<UIPasteboard.DetectedValues>>
    private let read: () -> String?

    init(changeCount: @escaping () -> Int = { UIPasteboard.general.changeCount },
         detect: @escaping () async throws -> Set<PartialKeyPath<UIPasteboard.DetectedValues>> = {
             try await UIPasteboard.general.detectedPatterns(for: [\.probableWebURL, \.probableWebSearch])
         }, read: @escaping () -> String? = { UIPasteboard.general.string }) {
        self.changeCount = changeCount
        self.detect = detect
        self.read = read
    }

    @MainActor func refresh() async {
        let count = changeCount()
        offered = nil
        offeredChangeCount = nil
        guard policy.allows(count) else { return }
        guard let patterns = try? await detect(), !Task.isCancelled,
              changeCount() == count, policy.allows(count) else { return }
        if patterns.contains(\.probableWebURL) { offered = .link }
        else if patterns.contains(\.probableWebSearch) { offered = .text }
        if offered != nil { offeredChangeCount = count }
    }

    func dismiss() {
        if let count = offeredChangeCount { policy.dismiss(count) }
        offered = nil
        offeredChangeCount = nil
    }

    /// Contents handed over by the system Paste button, which needs no paste prompt.
    func target(pasted text: String?, search: (String) -> String) -> String? {
        let suggestion = BrowserClipboardClassifier.classify(text)
        dismiss()
        return suggestion?.target(search: search)
    }

    /// The only direct clipboard-content read. Called exclusively by the user's
    /// suggestion tap, never by focus, pattern detection, or rendering.
    func takeTarget(search: (String) -> String) -> String? {
        guard let count = offeredChangeCount, count == changeCount() else { dismiss(); return nil }
        let raw = read()
        guard count == changeCount() else { dismiss(); return nil }
        let suggestion = BrowserClipboardClassifier.classify(raw)
        dismiss()
        return suggestion?.target(search: search)
    }
}
