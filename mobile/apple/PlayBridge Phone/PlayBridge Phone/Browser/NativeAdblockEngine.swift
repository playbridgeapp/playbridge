// SPDX-License-Identifier: MPL-2.0
import Foundation
import CryptoKit
#if canImport(PlayBridgeCastCore)
import PlayBridgeCastCore
#endif

enum AdblockFailure: String, Error {
    case unavailable, invalidResponse, engineFailure, conversionFailure
}

/// Immutable ownership of a native handle; Rust serializes access and never logs inputs.
final class NativeAdblockEngine: @unchecked Sendable {
    private let handle: UInt64
    private init(handle: UInt64) { self.handle = handle }
    deinit {
        #if canImport(PlayBridgeCastCore)
        pb_adblock_destroy(handle)
        #endif
    }
    private static func object(_ pointer: UnsafeMutablePointer<CChar>?) throws -> [String: Any] {
        #if canImport(PlayBridgeCastCore)
        guard let pointer else { throw AdblockFailure.invalidResponse }
        defer { pb_adblock_string_free(pointer) }
        guard let object = try JSONSerialization.jsonObject(with: Data(String(cString: pointer).utf8)) as? [String: Any],
              object["error"] == nil else { throw AdblockFailure.engineFailure }
        return object
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    static func load(lists: [String], cached: Data?) throws -> NativeAdblockEngine {
        #if canImport(PlayBridgeCastCore)
        guard pb_adblock_abi_version() == 1 else { throw AdblockFailure.unavailable }
        let config = try JSONSerialization.data(withJSONObject: ["lists": lists])
        let created: [String: Any] = try config.withUnsafeBytes { configBytes in
            let p = configBytes.bindMemory(to: UInt8.self).baseAddress
            if let cached, let restored = try? cached.withUnsafeBytes({ bytes in
                try object(pb_adblock_deserialize(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count, p, config.count))
            }) { return restored }
            return try object(pb_adblock_create_json(p, config.count))
        }
        guard let handle = created["handle"] as? UInt64, handle != 0 else { throw AdblockFailure.invalidResponse }
        return NativeAdblockEngine(handle: handle)
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    func serialize() throws -> Data {
        #if canImport(PlayBridgeCastCore)
        var count = 0
        guard let bytes = pb_adblock_serialize(handle, &count) else { throw AdblockFailure.engineFailure }
        defer { pb_adblock_bytes_free(bytes, count) }
        return Data(bytes: bytes, count: count)
        #else
        throw AdblockFailure.unavailable
        #endif
    }
    func matches(url: String, source: String, type: String, method: String = "GET") -> Bool {
        #if canImport(PlayBridgeCastCore)
        guard let input = try? JSONSerialization.data(withJSONObject: ["op": "check", "url": url,
            "source_url": source, "request_type": type, "method": method]),
              let result = try? input.withUnsafeBytes({ bytes in
                  try Self.object(pb_adblock_call_json(handle, bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
              }) else { return false }
        return result["matched"] as? Bool == true
        #else
        return false
        #endif
    }
    static func chunks(lists: [String]) throws -> [String] {
        #if canImport(PlayBridgeCastCore)
        let input = try JSONSerialization.data(withJSONObject: ["lists": lists, "chunk_limit": 140000, "json_strings": true])
        let result = try input.withUnsafeBytes { bytes in
            try object(pb_adblock_content_blocking_json(bytes.bindMemory(to: UInt8.self).baseAddress, bytes.count))
        }
        guard let chunks = result["chunks"] as? [String] else { throw AdblockFailure.conversionFailure }
        return chunks
        #else
        throw AdblockFailure.unavailable
        #endif
    }
}

/// Readers retain a snapshot before releasing the lock; replacement cannot free an in-flight handle.
final class AdblockSnapshot: @unchecked Sendable {
    static let shared = AdblockSnapshot()
    private let lock = NSLock()
    private var engine: NativeAdblockEngine?
    func publish(_ next: NativeAdblockEngine) { lock.lock(); engine = next; lock.unlock() }
    func matches(url: String, source: String, type: String, method: String = "GET") -> Bool {
        lock.lock(); let current = engine; lock.unlock()
        return current?.matches(url: url, source: source, type: type, method: method) ?? false
    }
}

/// Parsing, hashing, serialization and conversion never run on the main actor.
actor AdblockEngineWorker {
    static let shared = AdblockEngineWorker()
    private let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("PlayBridgeAdblock-v1", isDirectory: true)
    }
    func load(files: [URL]) throws -> (NativeAdblockEngine, [String]) {
        guard files.count <= 128 else { throw AdblockFailure.engineFailure }
        var lists: [String] = []
        var bytes = 0
        for file in files {
            guard FileManager.default.fileExists(atPath: file.path) else { continue }
            let size = (try file.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? 0
            bytes += size
            guard size <= 16 * 1024 * 1024, bytes <= 32 * 1024 * 1024 else { throw AdblockFailure.engineFailure }
            let text = try String(contentsOf: file, encoding: .utf8)
            let head = text.prefix(4096).lowercased()
            guard !head.contains("<html"), !head.contains("<!doctype html") else { continue }
            // Legacy downloads have not expanded includes yet; apply the profile
            // immediately, then ensureListsDownloaded refreshes them transactionally.
            lists.append(AdblockListPreparser.activeLines(text).joined(separator: "\n"))
        }
        let cache = directory.appendingPathComponent("engine.bin")
        let cacheSize = (try? cache.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        let cached = cacheSize > 0 && cacheSize <= 64 * 1024 * 1024 ? try? Data(contentsOf: cache) : nil
        let engine = try NativeAdblockEngine.load(lists: lists, cached: cached)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let serialized = try? engine.serialize() { try? serialized.write(to: cache, options: .atomic) }
        return (engine, lists)
    }
    struct Chunk: Sendable { let identifier: String; let json: String }
    static func cacheIdentifier(for text: String) -> String {
        SHA256.hash(data: Data(("engine-v1\n" + text).utf8)).map { String(format: "%02x", $0) }.joined()
    }
    func convert(lists: [String]) throws -> [Chunk] {
        try NativeAdblockEngine.chunks(lists: lists).map {
            Chunk(identifier: "playbridge-adblock-engine-" + Self.cacheIdentifier(for: $0), json: $0)
        }
    }
}
