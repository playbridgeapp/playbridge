// SPDX-License-Identifier: MPL-2.0
import Foundation

/// WebKit supplies an immutable, already encoded Data snapshot on the main thread.
/// Envelope encoding, decoding and file I/O run exclusively on this serial queue.
final class BrowserTabStatePersistence: @unchecked Sendable {
    static let maximumBytes = 2 * 1024 * 1024
    private struct Snapshot: Codable { let url: String; let state: Data }
    private final class ReadResult: @unchecked Sendable { var state: Data? }
    private let queue = DispatchQueue(label: "com.playbridge.browser-tab-state", qos: .utility)
    private let directory: URL
    private let delay: TimeInterval
    private let write: (Data, URL) throws -> Void
    private var pending: [String: Snapshot] = [:]
    private var revision = UUID()
    private var work: DispatchWorkItem?

    init(directory: URL, delay: TimeInterval = 0.75,
         write: @escaping (Data, URL) throws -> Void = { try $0.write(to: $1, options: .atomic) }) {
        self.directory = directory
        self.delay = delay
        self.write = write
        queue.async { self.prepareDirectory() }
    }

    private func prepareDirectory() {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var url = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
    }

    func save(_ state: Data, url: String, id: String) {
        guard UUID(uuidString: id) != nil else { return }
        queue.async {
            // Drop a stale snapshot if its replacement cannot be persisted.
            guard state.count <= Self.maximumBytes else { self.delete(id); return }
            self.pending[id] = Snapshot(url: url, state: state)
            self.work?.cancel()
            let revision = UUID()
            self.revision = revision
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.revision == revision else { return }
                self.writePending()
            }
            self.work = work
            self.queue.asyncAfter(deadline: .now() + self.delay, execute: work)
        }
    }

    private func writePending() {
        prepareDirectory()
        for (id, snapshot) in pending {
            do {
                let encoder = PropertyListEncoder()
                encoder.outputFormat = .binary
                let data = try encoder.encode(snapshot)
                guard data.count <= Self.maximumBytes else { delete(id); continue }
                try write(data, directory.appendingPathComponent(id))
                pending.removeValue(forKey: id)
            } catch { /* Keep this snapshot for a lifecycle retry. No page data in logs. */ }
        }
        work = nil
    }

    /// Lazy selection waits only for this tab's bounded read, never encodes on the caller.
    func load(_ id: String, matching url: String) -> Data? {
        guard UUID(uuidString: id) != nil else { return nil }
        let finished = DispatchSemaphore(value: 0)
        let result = ReadResult()
        queue.async {
            defer { finished.signal() }
            let file = self.directory.appendingPathComponent(id)
            guard let size = try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                  size <= Self.maximumBytes, let data = try? Data(contentsOf: file),
                  let snapshot = try? PropertyListDecoder().decode(Snapshot.self, from: data),
                  snapshot.url == url, !snapshot.state.isEmpty else { return }
            result.state = snapshot.state
        }
        finished.wait()
        return result.state
    }

    func remove(_ id: String) {
        guard UUID(uuidString: id) != nil else { return }
        queue.async { self.delete(id) }
    }

    private func delete(_ id: String) {
        pending.removeValue(forKey: id)
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(id))
    }

    func prune(keeping ids: Set<String>) {
        queue.async {
            let files = (try? FileManager.default.contentsOfDirectory(at: self.directory,
                includingPropertiesForKeys: nil)) ?? []
            for file in files where !ids.contains(file.lastPathComponent) {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    func flush() {
        let finished = DispatchSemaphore(value: 0)
        queue.async {
            self.work?.cancel()
            self.revision = UUID()
            self.writePending()
            finished.signal()
        }
        finished.wait()
    }
}
