import CryptoKit
import Foundation
#if canImport(CoreText) && canImport(CoreGraphics)
import CoreGraphics
import CoreText
#endif
#if canImport(UIKit)
import UIKit
#endif

nonisolated enum SubtitleFontLimits {
    static let maxFontBytes = 10 * 1024 * 1024
    static let maxFonts = 5
    static let maxLibraryBytes = 30 * 1024 * 1024
    static let chunkBytes = 192 * 1024
}

nonisolated enum SubtitleFontParser {
    /// Extracts the family name (TrueType/OpenType/TTC `name` table) or nil if `data` is not a valid sfnt font.
    static func readFontFamily(_ data: Data) -> String? {
        guard data.count >= 12 else { return nil }
        let bytes = [UInt8](data)
        var face = 0
        if tag(bytes, at: 0) == "ttcf" {
            guard bytes.count >= 16, let offset = u32(bytes, at: 12) else { return nil }
            face = Int(offset)
        }
        guard face >= 0, face + 12 <= bytes.count else { return nil }
        let versionU32 = u32(bytes, at: face)
        let versionTag = tag(bytes, at: face)
        guard versionU32 == 0x00010000 || versionTag == "OTTO" || versionTag == "true" else {
            return nil
        }
        guard let tableCount = u16(bytes, at: face + 4) else { return nil }
        let dirStart = face + 12
        let dirEnd = dirStart + Int(tableCount) * 16
        guard dirEnd <= bytes.count else { return nil }

        var nameTableOffset: Int?
        var nameTableLength: Int?
        for idx in 0..<Int(tableCount) {
            let record = dirStart + idx * 16
            guard let tOffset = u32(bytes, at: record + 8),
                  let tLength = u32(bytes, at: record + 12) else {
                return nil
            }
            let end = Int(tOffset) + Int(tLength)
            guard Int(tOffset) >= 0, end <= bytes.count else { return nil }
            if tag(bytes, at: record) == "name" {
                nameTableOffset = Int(tOffset)
                nameTableLength = Int(tLength)
            }
        }
        guard let nameTable = nameTableOffset,
              let nameLen = nameTableLength,
              nameLen >= 6,
              nameTable + 6 <= bytes.count,
              let count = u16(bytes, at: nameTable + 2),
              let stringOffset = u16(bytes, at: nameTable + 4) else {
            return nil
        }
        let stringsStart = nameTable + Int(stringOffset)
        let recordsEnd = nameTable + 6 + Int(count) * 12
        guard recordsEnd <= nameTable + nameLen, stringsStart <= bytes.count else { return nil }

        struct Candidate {
            let platform: Int
            let language: Int
            let id: Int
            let value: String
        }
        var candidates: [Candidate] = []
        for idx in 0..<Int(count) {
            let record = nameTable + 6 + idx * 12
            guard let platform = u16(bytes, at: record),
                  let language = u16(bytes, at: record + 4),
                  let nameID = u16(bytes, at: record + 6),
                  let length = u16(bytes, at: record + 8),
                  let offset = u16(bytes, at: record + 10) else {
                continue
            }
            guard nameID == 1 || nameID == 16 else { continue }
            let start = stringsStart + Int(offset)
            let end = start + Int(length)
            guard start >= nameTable, end <= nameTable + nameLen, end <= bytes.count else { continue }
            let slice = Data(bytes[start..<end])
            let decoded: String?
            switch platform {
            case 0, 3:
                decoded = String(data: slice, encoding: .utf16BigEndian)
            case 1:
                decoded = String(data: slice, encoding: .isoLatin1)
            default:
                decoded = nil
            }
            guard let trimmed = decoded?
                .replacingOccurrences(of: "\0", with: "")
                .trimmingCharacters(in: .whitespacesAndNewlines),
                  !trimmed.isEmpty else {
                continue
            }
            candidates.append(Candidate(platform: Int(platform), language: Int(language), id: Int(nameID), value: trimmed))
        }

        let sorted = candidates.sorted { a, b in
            let aKey = (a.id != 1 ? 1 : 0, a.platform != 3 ? 1 : 0, a.language != 0x0409 ? 1 : 0)
            let bKey = (b.id != 1 ? 1 : 0, b.platform != 3 ? 1 : 0, b.language != 0x0409 ? 1 : 0)
            return aKey < bKey
        }
        guard let first = sorted.first else { return nil }
        return String(first.value.prefix(128))
    }

    static func fileExtension(for data: Data) -> String {
        guard data.count >= 4 else { return "ttf" }
        let prefix = String(data: data.prefix(4), encoding: .isoLatin1)
        switch prefix {
        case "OTTO": return "otf"
        case "ttcf": return "ttc"
        default: return "ttf"
        }
    }

    private static func u16(_ bytes: [UInt8], at offset: Int) -> UInt16? {
        guard offset >= 0, offset + 2 <= bytes.count else { return nil }
        return (UInt16(bytes[offset]) << 8) | UInt16(bytes[offset + 1])
    }

    private static func u32(_ bytes: [UInt8], at offset: Int) -> UInt32? {
        guard offset >= 0, offset + 4 <= bytes.count else { return nil }
        return (UInt32(bytes[offset]) << 24)
            | (UInt32(bytes[offset + 1]) << 16)
            | (UInt32(bytes[offset + 2]) << 8)
            | UInt32(bytes[offset + 3])
    }

    private static func tag(_ bytes: [UInt8], at offset: Int) -> String? {
        guard offset >= 0, offset + 4 <= bytes.count else { return nil }
        return String(bytes: bytes[offset..<(offset + 4)], encoding: .isoLatin1)
    }
}

/// Container-backed store of up to 5 phone-uploaded subtitle fonts (30 MiB total).
/// Only the currently selected font is linked/copied into `fontsDirectory` for libmpv.
final class SubtitleFontLibrary: @unchecked Sendable {
    enum AddResult: Equatable {
        case added(String)
        case failed(String)
    }

    static let shared: SubtitleFontLibrary = {
        let base = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("PlayBridge", isDirectory: true)
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("PlayBridge", isDirectory: true)
        return SubtitleFontLibrary(baseDirectory: base, registerWithCoreText: true)
    }()

    let fontsDirectory: URL
    private let libraryDirectory: URL
    private let indexURL: URL
    private let selectedMarkerURL: URL
    private let registerWithCoreText: Bool
    private let lock = NSLock()
    private var registeredPostScriptNames: [String: String] = [:]

    private static let activePrefix = "custom-subtitle."

    init(baseDirectory: URL, registerWithCoreText: Bool = false) {
        self.libraryDirectory = baseDirectory.appendingPathComponent("subtitle-fonts", isDirectory: true)
        self.fontsDirectory = baseDirectory.appendingPathComponent("fonts", isDirectory: true)
        self.indexURL = libraryDirectory.appendingPathComponent("index.json")
        self.selectedMarkerURL = libraryDirectory.appendingPathComponent(".selected")
        self.registerWithCoreText = registerWithCoreText
        if registerWithCoreText {
            registerAllExistingFonts()
        }
    }

    func families() -> [String] {
        lock.withLock {
            loadIndex().keys.sorted()
        }
    }

    func file(family: String) -> URL? {
        lock.withLock {
            guard let name = loadIndex()[family] else { return nil }
            let url = libraryDirectory.appendingPathComponent(name)
            return FileManager.default.fileExists(atPath: url.path) ? url : nil
        }
    }

    func add(_ data: Data) -> AddResult {
        if data.count > SubtitleFontLimits.maxFontBytes {
            return .failed("too_large")
        }
        guard let family = SubtitleFontParser.readFontFamily(data),
              Self.validateCoreTextFontData(data) else {
            return .failed("invalid_font")
        }
        return lock.withLock {
            var index = loadIndex()
            let others = index.keys.filter { $0 != family }
            let othersBytes: Int64 = others.reduce(0) { total, key in
                guard let fileName = index[key] else { return total }
                let fileURL = libraryDirectory.appendingPathComponent(fileName)
                let size = (try? FileManager.default.attributesOfItem(atPath: fileURL.path)[.size] as? NSNumber)?.int64Value ?? 0
                return total + size
            }
            if others.count >= SubtitleFontLimits.maxFonts
                || othersBytes + Int64(data.count) > Int64(SubtitleFontLimits.maxLibraryBytes) {
                return .failed("font_limit")
            }

            do {
                try FileManager.default.createDirectory(at: libraryDirectory, withIntermediateDirectories: true)
            } catch {
                return .failed("invalid_font")
            }

            let hashed = Self.sha256Hex(Data(family.utf8)).prefix(16)
            let ext = SubtitleFontParser.fileExtension(for: data)
            let fileName = "\(hashed).\(ext)"
            let stagedURL = libraryDirectory.appendingPathComponent(".incoming-\(UUID().uuidString)")
            let destinationURL = libraryDirectory.appendingPathComponent(fileName)

            do {
                try data.write(to: stagedURL, options: .atomic)
                if let oldName = index[family] {
                    let oldURL = libraryDirectory.appendingPathComponent(oldName)
                    if registerWithCoreText {
                        Self.unregisterCoreTextFont(at: oldURL)
                    }
                    if oldName != fileName {
                        try? FileManager.default.removeItem(at: oldURL)
                    }
                }
                if FileManager.default.fileExists(atPath: destinationURL.path) {
                    if registerWithCoreText {
                        Self.unregisterCoreTextFont(at: destinationURL)
                    }
                    try FileManager.default.removeItem(at: destinationURL)
                }
                try FileManager.default.moveItem(at: stagedURL, to: destinationURL)
            } catch {
                try? FileManager.default.removeItem(at: stagedURL)
                return .failed("invalid_font")
            }

            if registerWithCoreText {
                if let psName = Self.registerCoreTextFont(at: destinationURL) {
                    registeredPostScriptNames[family] = psName
                }
            }

            index[family] = fileName
            saveIndex(index)
            return .added(family)
        }
    }

    @discardableResult
    func remove(family: String) -> Bool {
        lock.withLock {
            var index = loadIndex()
            guard let fileName = index.removeValue(forKey: family) else { return false }
            let target = libraryDirectory.appendingPathComponent(fileName)
            if currentSelectedName() == fileName {
                clearActiveFontLocked()
            }
            if registerWithCoreText {
                Self.unregisterCoreTextFont(at: target)
            }
            registeredPostScriptNames.removeValue(forKey: family)
            try? FileManager.default.removeItem(at: target)
            saveIndex(index)
            return true
        }
    }

    @discardableResult
    func select(family: String?) -> Bool {
        lock.withLock {
            if let family {
                guard let name = loadIndex()[family] else { return false }
                let source = libraryDirectory.appendingPathComponent(name)
                guard FileManager.default.fileExists(atPath: source.path) else { return false }
                let current = existingActiveFontURL()
                if current != nil && currentSelectedName() == name {
                    return true
                }
                clearActiveFontLocked()
                do {
                    try FileManager.default.createDirectory(at: fontsDirectory, withIntermediateDirectories: true)
                    let target = fontsDirectory.appendingPathComponent(Self.activePrefix + source.pathExtension)
                    do {
                        try FileManager.default.linkItem(at: source, to: target)
                    } catch {
                        try FileManager.default.copyItem(at: source, to: target)
                    }
                    try name.write(to: selectedMarkerURL, atomically: true, encoding: .utf8)
                    return true
                } catch {
                    return false
                }
            } else {
                clearActiveFontLocked()
                return true
            }
        }
    }

    func activeFontURL() -> URL? {
        lock.withLock {
            existingActiveFontURL()
        }
    }

    #if canImport(UIKit)
    func uiFont(family: String, size: CGFloat) -> UIFont? {
        let postScript: String? = lock.withLock {
            registeredPostScriptNames[family]
        }
        if let postScript, let font = UIFont(name: postScript, size: size) {
            return font
        }
        return UIFont(name: family, size: size)
    }
    #endif

    static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private func registerAllExistingFonts() {
        lock.withLock {
            let index = loadIndex()
            for (family, fileName) in index {
                let url = libraryDirectory.appendingPathComponent(fileName)
                if FileManager.default.fileExists(atPath: url.path),
                   let psName = Self.registerCoreTextFont(at: url) {
                    registeredPostScriptNames[family] = psName
                }
            }
        }
    }

    private static func validateCoreTextFontData(_ data: Data) -> Bool {
        #if canImport(CoreText)
        guard let descriptors = CTFontManagerCreateFontDescriptorsFromData(data as CFData) as? [CTFontDescriptor] else {
            return false
        }
        return !descriptors.isEmpty
        #else
        return true
        #endif
    }

    private static func registerCoreTextFont(at url: URL) -> String? {
        #if canImport(CoreText) && canImport(CoreGraphics)
        var cfError: Unmanaged<CFError>?
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, &cfError)
        if let descriptors = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor],
           let first = descriptors.first {
            if let ps = CTFontDescriptorCopyAttribute(first, kCTFontNameAttribute) as? String, !ps.isEmpty {
                return ps
            }
            if let fam = CTFontDescriptorCopyAttribute(first, kCTFontFamilyNameAttribute) as? String, !fam.isEmpty {
                return fam
            }
        }
        if let provider = CGDataProvider(url: url as CFURL),
           let cgFont = CGFont(provider),
           let ps = cgFont.postScriptName as String? {
            return ps
        }
        #endif
        return nil
    }

    private static func unregisterCoreTextFont(at url: URL) {
        #if canImport(CoreText) && canImport(CoreGraphics)
        var cfError: Unmanaged<CFError>?
        CTFontManagerUnregisterFontsForURL(url as CFURL, .process, &cfError)
        #endif
    }

    private func existingActiveFontURL() -> URL? {
        guard let items = try? FileManager.default.contentsOfDirectory(at: fontsDirectory, includingPropertiesForKeys: nil) else {
            return nil
        }
        return items.first { $0.lastPathComponent.hasPrefix(Self.activePrefix) }
    }

    private func clearActiveFontLocked() {
        if let items = try? FileManager.default.contentsOfDirectory(at: fontsDirectory, includingPropertiesForKeys: nil) {
            for item in items where item.lastPathComponent.hasPrefix(Self.activePrefix) {
                try? FileManager.default.removeItem(at: item)
            }
        }
        try? FileManager.default.removeItem(at: selectedMarkerURL)
    }

    private func currentSelectedName() -> String? {
        try? String(contentsOf: selectedMarkerURL, encoding: .utf8)
    }

    private func loadIndex() -> [String: String] {
        guard let data = try? Data(contentsOf: indexURL),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: String] else {
            return [:]
        }
        return dict
    }

    private func saveIndex(_ index: [String: String]) {
        try? FileManager.default.createDirectory(at: libraryDirectory, withIntermediateDirectories: true)
        if let data = try? JSONSerialization.data(withJSONObject: index, options: [.sortedKeys]) {
            try? data.write(to: indexURL, options: .atomic)
        }
    }
}

/// Reassembles one chunked font transfer from a phone, enforcing limits before buffering.
final class SubtitleFontReceiver: @unchecked Sendable {
    enum Result: Equatable {
        case ack(Int)
        case installed(String)
        case failed(String)
    }

    private final class Transfer {
        let id: String
        let expectedSize: Int
        let expectedSha256: String
        var buffer: Data

        init(id: String, expectedSize: Int, expectedSha256: String) {
            self.id = id
            self.expectedSize = expectedSize
            self.expectedSha256 = expectedSha256
            self.buffer = Data()
        }
    }

    private let install: (Data) -> SubtitleFontLibrary.AddResult
    private let lock = NSLock()
    private var transfer: Transfer?

    init(install: @escaping (Data) -> SubtitleFontLibrary.AddResult) {
        self.install = install
    }

    func begin(transferId: String, size: Int64, sha256: String) -> Result {
        lock.withLock {
            transfer = nil
            guard !transferId.isEmpty, transferId.count <= 128 else {
                return .failed("invalid_chunk")
            }
            guard size > 0, size <= Int64(SubtitleFontLimits.maxFontBytes) else {
                return .failed("too_large")
            }
            transfer = Transfer(
                id: transferId,
                expectedSize: Int(size),
                expectedSha256: sha256.lowercased()
            )
            return .ack(0)
        }
    }

    func chunk(transferId: String, offset: Int, base64: String) -> Result {
        lock.withLock {
            guard let current = transfer, current.id == transferId else {
                return .failed("unknown_transfer")
            }
            guard let decoded = Data(base64Encoded: base64),
                  offset == current.buffer.count,
                  decoded.count <= SubtitleFontLimits.chunkBytes,
                  current.buffer.count + decoded.count <= current.expectedSize else {
                transfer = nil
                return .failed("invalid_chunk")
            }
            current.buffer.append(decoded)
            return .ack(current.buffer.count)
        }
    }

    func end(transferId: String) -> Result {
        let finished: Transfer? = lock.withLock {
            guard let current = transfer, current.id == transferId else {
                return nil
            }
            transfer = nil
            return current
        }
        guard let current = finished else {
            return .failed("unknown_transfer")
        }
        guard current.buffer.count == current.expectedSize,
              SubtitleFontLibrary.sha256Hex(current.buffer) == current.expectedSha256 else {
            return .failed("checksum_mismatch")
        }
        switch install(current.buffer) {
        case .added(let family):
            return .installed(family)
        case .failed(let code):
            return .failed(code)
        }
    }

    func abandon() {
        lock.withLock {
            transfer = nil
        }
    }
}
