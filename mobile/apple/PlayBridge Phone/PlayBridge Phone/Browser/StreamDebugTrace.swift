import Foundation

/// Per-enrichment diagnostics kept in memory in debug builds; never written to logs.
final class StreamDebugTrace: @unchecked Sendable {
    @TaskLocal static var current: StreamDebugTrace?
#if DEBUG
    private let lock = NSLock()
    private var entries: [String] = []
#endif

    static func record(_ message: String) {
#if DEBUG
        guard let trace = current else { return }
        trace.lock.lock()
        defer { trace.lock.unlock() }
        if trace.entries.count < 40 { trace.entries.append(message) }
#endif
    }

    var text: String {
#if DEBUG
        lock.lock(); defer { lock.unlock() }
        return entries.joined(separator: "\n")
#else
        return ""
#endif
    }

    static func safeURL(_ value: String) -> String {
        guard var components = URLComponents(string: value) else { return "[invalid URL]" }
        components.user = nil
        components.password = nil
        components.fragment = nil
        // Some CDNs put an entire signed query in a path component.
        components.path = components.path.split(separator: "/", omittingEmptySubsequences: false)
            .map { $0.contains("=") ? "[redacted]" : String($0) }.joined(separator: "/")
        // Rust stateful proxy paths contain bearer session IDs.
        var path = components.path.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        if path.count > 2, path[1] == "s" { path[2] = "[redacted]" }
        components.path = path.joined(separator: "/")
        components.queryItems = components.queryItems?.map { URLQueryItem(name: $0.name, value: "[redacted]") }
        return components.string ?? "[invalid URL]"
    }
}
