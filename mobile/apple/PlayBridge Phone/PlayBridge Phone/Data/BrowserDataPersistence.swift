// SPDX-License-Identifier: MPL-2.0
import Foundation

/// All encoding and atomic writes run on this queue. Callers pass value snapshots.
final class BrowserDataPersistence: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.playbridge.browser-data", qos: .utility)
    private let historyURL: URL
    private let bookmarksURL: URL
    private let delay: TimeInterval
    private let write: (Data, URL) throws -> Void
    private var pendingHistory: [HistoryEntry]?
    private var pendingBookmarks: [Bookmark]?
    private var historyRevision = UUID()
    private var historyWork: DispatchWorkItem?

    init(historyURL: URL, bookmarksURL: URL, delay: TimeInterval,
         write: @escaping (Data, URL) throws -> Void = { try $0.write(to: $1, options: .atomic) }) {
        self.historyURL = historyURL
        self.bookmarksURL = bookmarksURL
        self.delay = delay
        self.write = write
    }
    func saveHistory(_ snapshot: [HistoryEntry]) {
        queue.async {
            self.pendingHistory = snapshot
            self.historyWork?.cancel()
            let revision = UUID()
            self.historyRevision = revision
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.historyRevision == revision else { return }
                self.writeHistory()
            }
            self.historyWork = work
            self.queue.asyncAfter(deadline: .now() + self.delay, execute: work)
        }
    }
    func saveBookmarks(_ snapshot: [Bookmark]) {
        queue.async {
            self.pendingBookmarks = snapshot
            self.writeBookmarks()
        }
    }
    private func writeHistory() {
        guard let snapshot = pendingHistory else { return }
        do {
            try write(JSONEncoder().encode(snapshot), historyURL)
            pendingHistory = nil
        } catch { /* Keep the snapshot for a lifecycle flush/retry. */ }
        historyWork = nil
    }
    private func writeBookmarks() {
        guard let snapshot = pendingBookmarks else { return }
        do {
            try write(JSONEncoder().encode(snapshot), bookmarksURL)
            pendingBookmarks = nil
        } catch { /* Keep the snapshot for a lifecycle flush/retry. */ }
    }
    /// Lifecycle/deinit barrier: even here the work executes on the background
    /// queue, rather than DispatchQueue.sync executing I/O on the calling thread.
    func flush() {
        let finished = DispatchSemaphore(value: 0)
        queue.async {
            self.historyWork?.cancel()
            self.historyRevision = UUID()
            self.writeHistory()
            self.writeBookmarks()
            finished.signal()
        }
        finished.wait()
    }
}
