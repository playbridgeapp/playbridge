import Foundation

/// The connected TV's subtitle style and custom fonts, edited from this phone.
///
/// The TV keeps one style (`subtitle_style_v1`) and up to five fonts (`subtitle_font_v1`),
/// applies changes at once and reports its style to every connected phone. Font bytes are
/// never logged.
@MainActor
final class TvSubtitleStyleController: ObservableObject {
    struct Progress: Equatable {
        let sent: Int
        let total: Int
        var percent: Int { total > 0 ? sent * 100 / total : 0 }
    }

    enum UploadResult: Equatable {
        case installed(String)
        case failed(String)
    }

    /// The style the TV last reported; nil until it answers.
    @Published private(set) var style: SubtitleStylePreferences?
    /// The TV's custom fonts, selected or not.
    @Published private(set) var installedFonts: [String] = []
    /// Set while a font is being sent.
    @Published private(set) var uploadProgress: Progress?
    /// The connection the reported style came on. A new connection starts empty.
    private(set) var session: UUID?

    // Set once by the nonisolated init and only called on the main actor.
    nonisolated(unsafe) private let send: (String) -> Bool
    nonisolated private let replyTimeoutNanoseconds: UInt64
    private var pendingReply: (token: UUID, transferId: String, continuation: CheckedContinuation<[String: Any]?, Never>)?

    nonisolated init(replyTimeoutNanoseconds: UInt64 = 10_000_000_000, send: @escaping (String) -> Bool) {
        self.replyTimeoutNanoseconds = replyTimeoutNanoseconds
        self.send = send
    }

    /// The style for `session`, or nil when it was reported on another connection.
    func style(for session: UUID?) -> SubtitleStylePreferences? {
        self.session == session ? style : nil
    }

    func fonts(for session: UUID?) -> [String] {
        self.session == session ? installedFonts : []
    }

    /// A `subtitle_style`, `subtitle_font_ack` or `subtitle_font_result` from the TV.
    func handle(_ json: [String: Any], session: UUID?) {
        if session != self.session {
            // Only a change from one connection to another drops what the old one reported.
            if self.session != nil { reset() }
            self.session = session
        }
        switch json["type"] as? String {
        case "subtitle_style":
            let fonts = (json["installedFonts"] as? [Any] ?? [])
                .compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .map { String($0.prefix(128)) }
            installedFonts = Array(fonts.prefix(SubtitleFontLimits.maxFonts))
            style = SubtitleStylePreferences.fromWire(
                size: json["size"] as? String,
                background: json["background"] as? String,
                edge: json["edge"] as? String,
                font: json["font"] as? String,
                overrideStyled: json["overrideStyled"] as? Bool,
                customFont: json["customFont"] as? String,
                installedFonts: installedFonts
            )
        case "subtitle_font_ack", "subtitle_font_result":
            guard let pending = pendingReply, pending.transferId == json["transferId"] as? String else { return }
            pendingReply = nil
            pending.continuation.resume(returning: json)
        default:
            break
        }
    }

    /// Forgets the reported style and ends a font transfer that is waiting for a reply.
    func reset() {
        style = nil
        installedFonts = []
        session = nil
        if let pending = pendingReply {
            pendingReply = nil
            pending.continuation.resume(returning: nil)
        }
    }

    /// Asks the TV for its style; it answers with `subtitle_style`.
    @discardableResult
    func query() -> Bool { send(WireProtocol.subtitleStyleQuery()) }

    /// Replaces the TV's style. The TV echoes what it applied to every connected phone.
    func set(_ next: SubtitleStylePreferences) {
        let normalized = next.normalized(installedFonts: installedFonts)
        if send(WireProtocol.subtitleStyle(normalized.wireDictionary(installedFonts: nil))) {
            style = normalized
        }
    }

    /// Uninstalls `family`; the TV reports its fonts again in `subtitle_style`.
    func removeFont(_ family: String) {
        _ = send(WireProtocol.subtitleFontRemove(family: family))
    }

    /// Sends a checked font in ordered pieces, each after the TV acknowledges the one before.
    /// On success the TV selects the font and reports its new style.
    func upload(_ data: Data) async -> UploadResult {
        guard uploadProgress == nil else { return .failed("busy") }
        guard !data.isEmpty, data.count <= SubtitleFontLimits.maxFontBytes else { return .failed("too_large") }
        let id = UUID().uuidString
        uploadProgress = Progress(sent: 0, total: data.count)
        defer { uploadProgress = nil }

        var reply = await exchange(id, WireProtocol.subtitleFontBegin(
            transferId: id, size: data.count, sha256: SubtitleFontLibrary.sha256Hex(data)))
        var offset = 0
        while offset < data.count {
            guard Self.isAck(reply, received: offset) else { return Self.failure(reply) }
            let end = min(offset + SubtitleFontLimits.chunkBytes, data.count)
            reply = await exchange(id, WireProtocol.subtitleFontChunk(
                transferId: id, offset: offset, data: data.subdata(in: offset..<end)))
            offset = end
            uploadProgress = Progress(sent: offset, total: data.count)
        }
        guard Self.isAck(reply, received: data.count) else { return Self.failure(reply) }
        reply = await exchange(id, WireProtocol.subtitleFontEnd(transferId: id))
        if reply?["ok"] as? Bool == true, let family = reply?["family"] as? String, !family.isEmpty {
            return .installed(family)
        }
        return Self.failure(reply)
    }

    /// Sends `frame` and waits for the TV's reply to transfer `id`; nil when it can't be sent,
    /// no reply comes in time or the connection changes.
    private func exchange(_ id: String, _ frame: String) async -> [String: Any]? {
        let token = UUID()
        return await withCheckedContinuation { continuation in
            pendingReply = (token, id, continuation)
            guard send(frame) else {
                resolve(token)
                return
            }
            let timeout = replyTimeoutNanoseconds
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: timeout)
                self?.resolve(token)
            }
        }
    }

    private func resolve(_ token: UUID) {
        guard let pending = pendingReply, pending.token == token else { return }
        pendingReply = nil
        pending.continuation.resume(returning: nil)
    }

    private static func isAck(_ reply: [String: Any]?, received: Int) -> Bool {
        reply?["type"] as? String == "subtitle_font_ack" && (reply?["received"] as? Int) == received
    }

    private static func failure(_ reply: [String: Any]?) -> UploadResult {
        let code = (reply?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "no_reply"
        return .failed(code)
    }
}
