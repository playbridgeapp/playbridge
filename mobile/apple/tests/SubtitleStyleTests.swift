import Foundation

private func frame(_ text: String) -> [String: Any] {
    (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] ?? [:]
}

/// A TV that answers font transfers the way the receivers do.
@MainActor
private final class FakeTv {
    var frames: [[String: Any]] = []
    var session = UUID()
    var endError: String?
    var silent = false
    weak var controller: TvSubtitleStyleController?
    private var received = 0

    func send(_ text: String) -> Bool {
        let json = frame(text)
        frames.append(json)
        guard !silent, let id = json["transferId"] as? String else { return true }
        let reply: [String: Any]
        switch json["type"] as? String {
        case "subtitle_font_begin":
            received = 0
            reply = ["type": "subtitle_font_ack", "transferId": id, "received": 0]
        case "subtitle_font_chunk":
            received += Data(base64Encoded: json["data"] as? String ?? "")?.count ?? 0
            reply = ["type": "subtitle_font_ack", "transferId": id, "received": received]
        case "subtitle_font_end":
            reply = endError.map { ["type": "subtitle_font_result", "transferId": id, "ok": false, "error": $0] }
                ?? ["type": "subtitle_font_result", "transferId": id, "ok": true, "family": "Fixture Sans"]
        default:
            return true
        }
        // Replies arrive later, on the main queue, like the coordinator delivers them.
        let session = self.session
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.controller?.handle(reply, session: session) }
        }
        return true
    }
}

@main
enum SubtitleStyleTests {
    @MainActor
    static func main() async {
        wire()
        reportedStyle()
        await uploads()
        pickedFonts()
        migration()
        print("PASS: subtitle style checks")
    }

    static func wire() {
        var style = SubtitleStylePreferences()
        style.size = .large; style.background = .dim; style.edge = .dropShadow; style.overrideStyled = true
        let sent = frame(WireProtocol.subtitleStyle(style.wireDictionary(installedFonts: ["Other"])))
        precondition(sent["type"] as? String == "subtitle_style")
        precondition(sent["size"] as? String == "large" && sent["background"] as? String == "translucent")
        precondition(sent["edge"] as? String == "shadow" && sent["font"] as? String == "default")
        precondition(sent["overrideStyled"] as? Bool == true)
        precondition(sent["installedFonts"] == nil, "A sender never reports fonts")
        precondition(sent["customFont"] == nil)
        precondition(frame(WireProtocol.subtitleStyleQuery())["type"] as? String == "subtitle_style_query")
        let chunk = frame(WireProtocol.subtitleFontChunk(transferId: "t", offset: 3, data: Data([1, 2, 3])))
        precondition(chunk["offset"] as? Int == 3 && chunk["data"] as? String == Data([1, 2, 3]).base64EncodedString())
        let begin = frame(WireProtocol.subtitleFontBegin(transferId: "t", size: 9, sha256: "ab"))
        precondition(begin["type"] as? String == "subtitle_font_begin" && begin["size"] as? Int == 9)
        precondition(frame(WireProtocol.subtitleFontRemove(family: "F"))["family"] as? String == "F")
        precondition(frame(WireProtocol.subtitleFontEnd(transferId: "t"))["type"] as? String == "subtitle_font_end")
    }

    @MainActor
    static func reportedStyle() {
        var sent: [[String: Any]] = []
        let controller = TvSubtitleStyleController { sent.append(frame($0)); return true }
        let one = UUID(), two = UUID()
        precondition(controller.style == nil)
        controller.handle(["type": "subtitle_style", "size": "extra_large", "background": "solid", "edge": "none",
                           "font": "custom", "customFont": "Fixture Sans", "overrideStyled": true,
                           "installedFonts": ["Fixture Sans", "", "Other"]], session: one)
        let style = controller.style(for: one)!
        precondition(style.size == .extraLarge && style.background == .box && style.edge == .none)
        precondition(style.font == .custom && style.customFont == "Fixture Sans" && style.overrideStyled)
        precondition(controller.fonts(for: one) == ["Fixture Sans", "Other"])
        precondition(controller.style(for: two) == nil && controller.fonts(for: two).isEmpty,
                     "A style from another connection isn't shown")

        // Unknown values and a font that isn't installed read as the defaults.
        controller.handle(["type": "subtitle_style", "size": "huge", "font": "custom", "customFont": "Missing"], session: one)
        precondition(controller.style(for: one) == SubtitleStylePreferences())

        controller.query()
        precondition(sent.last?["type"] as? String == "subtitle_style_query")
        var next = SubtitleStylePreferences(); next.font = .custom; next.customFont = "Missing"; next.size = .small
        controller.set(next)
        precondition(sent.last?["font"] as? String == "default", "A font the TV doesn't have isn't sent")
        precondition(controller.style?.size == .small && controller.style?.font == .default)
        controller.removeFont("Other")
        precondition(sent.last?["type"] as? String == "subtitle_font_remove")

        // A new connection starts empty.
        controller.handle(["type": "subtitle_font_ack", "transferId": "x", "received": 0], session: two)
        precondition(controller.style == nil && controller.installedFonts.isEmpty && controller.session == two)

        let offline = TvSubtitleStyleController { _ in false }
        offline.set(next)
        precondition(offline.style == nil, "An unsent change isn't shown as applied")
    }

    @MainActor
    static func uploads() async {
        let tv = FakeTv()
        let controller = TvSubtitleStyleController(replyTimeoutNanoseconds: 200_000_000) { tv.send($0) }
        tv.controller = controller
        let font = Data((0..<(SubtitleFontLimits.chunkBytes * 2 + 10)).map { UInt8($0 % 251) })
        var result = await controller.upload(font)
        precondition(result == .installed("Fixture Sans"), "\(result)")
        let types = tv.frames.map { $0["type"] as? String ?? "" }
        precondition(types == ["subtitle_font_begin", "subtitle_font_chunk", "subtitle_font_chunk", "subtitle_font_chunk",
                               "subtitle_font_end"], "\(types)")
        precondition(tv.frames[0]["sha256"] as? String == SubtitleFontLibrary.sha256Hex(font))
        precondition(tv.frames[0]["size"] as? Int == font.count)
        precondition(tv.frames.dropFirst().dropLast().map { $0["offset"] as? Int ?? -1 }
                     == [0, SubtitleFontLimits.chunkBytes, SubtitleFontLimits.chunkBytes * 2])
        let rebuilt = tv.frames.dropFirst().dropLast().reduce(into: Data()) {
            $0.append(Data(base64Encoded: $1["data"] as? String ?? "")!)
        }
        precondition(rebuilt == font)
        precondition(controller.uploadProgress == nil)

        tv.frames = []; tv.endError = "font_limit"
        result = await controller.upload(Data([1, 2, 3]))
        precondition(result == .failed("font_limit"))
        precondition(PickedSubtitleFont.message(for: "font_limit").contains("Remove one first"))

        tv.frames = []; tv.silent = true
        result = await controller.upload(Data([1, 2, 3]))
        precondition(result == .failed("no_reply") && tv.frames.count == 1, "No reply stops the transfer")

        // A new connection ends a transfer that is waiting.
        let waiting = Task { @MainActor in await controller.upload(Data([4, 5, 6])) }
        try? await Task.sleep(nanoseconds: 20_000_000)
        precondition(controller.uploadProgress?.total == 3)
        controller.handle(["type": "subtitle_style"], session: UUID())
        result = await waiting.value
        precondition(result == .failed("no_reply") && controller.uploadProgress == nil)

        result = await controller.upload(Data(count: SubtitleFontLimits.maxFontBytes + 1))
        precondition(result == .failed("too_large"))
        let offline = TvSubtitleStyleController { _ in false }
        result = await offline.upload(Data([1]))
        precondition(result == .failed("no_reply"))
    }

    static func pickedFonts() {
        let fontURL = URL(fileURLWithPath: CommandLine.arguments[1])
        guard case .font(let data, let family) = PickedSubtitleFont.read(fontURL) else { preconditionFailure("Bundled font rejected") }
        precondition(family == "Poppins" && !data.isEmpty, family)
        precondition(PickedSubtitleFont.check(Data("not a font".utf8)) == .rejected(PickedSubtitleFont.message(for: "invalid_font")))
        precondition(PickedSubtitleFont.check(Data(count: SubtitleFontLimits.maxFontBytes + 1))
                     == .rejected(PickedSubtitleFont.message(for: "too_large")))
        precondition(PickedSubtitleFont.read(URL(fileURLWithPath: "/nonexistent/font.ttf")) == .rejected("Couldn't open that file."))
        precondition(PickedSubtitleFont.message(for: nil, toTV: false) == "Couldn't add that font. Try again.")
    }

    static func migration() {
        let name = "subtitle-style-tests-\(UUID().uuidString)"
        let store = UserDefaults(suiteName: name)!
        defer { store.removePersistentDomain(forName: name) }
        store.set(try! JSONSerialization.data(withJSONObject: ["speed": 1.0, "subtitleScale": 1.3, "subtitleBackground": false]),
                  forKey: PhonePlayerPreferences.key)
        PhoneSubtitleStyleMigration.migrateIfNeeded(store)
        var style = SubtitleStylePreferences.current(defaults: store)
        precondition(style.size == .large && style.background == .none, "\(style)")
        precondition(PhonePlayerPreferences.load(from: store).speed == 1, "Old fields still decode")

        // Runs once, and a chosen style wins.
        store.set("small", forKey: "subtitle_size")
        PhoneSubtitleStyleMigration.migrateIfNeeded(store)
        precondition(SubtitleStylePreferences.current(defaults: store).size == .small)

        let fresh = UserDefaults(suiteName: name + "-fresh")!
        defer { fresh.removePersistentDomain(forName: name + "-fresh") }
        fresh.set(try! JSONSerialization.data(withJSONObject: ["subtitleScale": 0.75]), forKey: PhonePlayerPreferences.key)
        PhoneSubtitleStyleMigration.migrateIfNeeded(fresh)
        style = SubtitleStylePreferences.current(defaults: fresh)
        precondition(style.size == .small && style.background == .dim, "The old dark box was on by default")

        let untouched = UserDefaults(suiteName: name + "-none")!
        defer { untouched.removePersistentDomain(forName: name + "-none") }
        PhoneSubtitleStyleMigration.migrateIfNeeded(untouched)
        precondition(untouched.object(forKey: "subtitle_size") == nil && untouched.bool(forKey: PhoneSubtitleStyleMigration.doneKey))
    }
}
