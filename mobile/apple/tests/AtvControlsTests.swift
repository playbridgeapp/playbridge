import Foundation

@MainActor private func check(_ value: @autoclosure () -> Bool, _ label: String, line: UInt = #line) {
    if !value() { fatalError("ATV controls: \(label), line \(line)") }
}
@MainActor private func pump() async { for _ in 0..<80 { await Task.yield() } }
private let endpoint = "playbridge:living-room"
private let controlTicket = AtvControlTicket(owner: UUID(), connection: UUID(), endpoint: endpoint)
private func base(_ connected: Bool = true, context: String = "player") -> ReceiverRemotePolicy {
    var result = ReceiverRemotePolicy(isLegacy: false, state: ReceiverRemoteState(connected: connected, context: context))
    result.keys = ["dpad_up", "back", "key_enter"]
    result.transport = ["toggle"]
    result.volume = .init(scope: "app", operations: ["relative_step", "absolute", "mute_toggle"], step: 0.05, fallbackScope: nil)
    if context == "browser" { result.text = ["replace", "delete", "enter"]; result.pointer = ["move", "click"] }
    return result
}
private func projection(_ receiver: ReceiverRemotePolicy = base(), connected: Bool = true) -> CombinedRemotePolicy {
    .init(receiver: receiver, endpoint: endpoint,
          fullControl: .init(endpoint: endpoint, ticket: connected ? controlTicket : nil, paired: true, available: true), native: true)
}
@MainActor private func projectionChecks() {
    for connected in [true,false] {
        for context in ["idle","player","browser"] {
            let combined = projection(base(connected, context: context))
            check(combined.fullControlReady && combined.route("dpad_up") == .atv && combined.route("home") == .atv, "ATV overlays keys")
            check(combined.route("volume_up") == .atv && combined.route("mute") == .atv && combined.route("menu") == .atv, "TV volume and menu route")
            check(combined.keyboardRoute == (connected && context == "browser" ? .receiver : .atv), "Android browser text exception")
            let layout = RemoteInputLayout(policy: combined.receiver, context: context, image: false, external: false, externalProtocol: nil, fullControl: combined.fullControlReady)
            check(layout.hasKeyboard && layout.hasNavigation, "pad/keyboard in every ATV context")
            if !connected {
                check(!layout.hasPointer && layout.surface(in: context, preferences: RemotePadPreferences()) == .swipe, "receiver down keeps pad and removes pointer")
                check(!combined.receiver.allowsControl("toggle"), "ATV does not authorize receiver playback")
            }
        }
    }
    var result = projection(connected: false)
    check(result.route("dpad_up") == .receiver && result.route("home") == .unavailable && result.route("menu") == .unavailable, "receiver fallback follows exact policy")
    check(result.keyboardRoute == .unavailable && result.absoluteVolume, "no player keyboard without ATV; app volume unaffected")
    result = .init(receiver: base(false), endpoint: endpoint, fullControl: .init(endpoint: "playbridge:other", ticket: controlTicket, paired: true), native: true)
    check(!result.fullControlReady && result.route("home") == .unavailable && result.badge == nil, "foreign endpoint never grants control")
    result = .init(receiver: base(), endpoint: endpoint, fullControl: projection().fullControl, native: false)
    check(!result.fullControlReady, "external routes cannot acquire ATV")
    check(projection().badge == "Full control" && !projection().absoluteVolume, "full control badge and relative volume only")
    let unpaired = AtvFullControlSnapshot(endpoint: endpoint, available: true)
    result = .init(receiver: base(), endpoint: endpoint, fullControl: unpaired, native: true)
    check(result.badge == "Get full control", "unpaired entry")
    let paired = AtvFullControlSnapshot(endpoint: endpoint, paired: true, available: true)
    result = .init(receiver: base(), endpoint: endpoint, fullControl: paired, native: true)
    check(result.badge == nil, "temporary disconnect does not claim full control or invite pairing")
    var repair = paired; repair.needsRepair = true
    result = .init(receiver: base(), endpoint: endpoint, fullControl: repair, native: true)
    check(result.badge == "Pair again", "trust repair entry")
    var tv = AtvTelevisionState(isOn: false, volumeLevel: 32, volumeMax: 100, volumeMuted: false, volumeRevision: 0, currentApp: nil, model: nil, vendor: nil)
    func volume(_ value: AtvTelevisionState) -> CombinedRemotePolicy {
        var snapshot = projection().fullControl; snapshot.television = value
        return .init(receiver: base(), endpoint: endpoint, fullControl: snapshot, native: true)
    }
    check(volume(tv).atvVolumeStatus == nil && volume(tv).televisionOff, "no volume before first report; truthful Off")
    tv = .init(isOn: true, volumeLevel: 32, volumeMax: 100, volumeMuted: false, volumeRevision: 1, currentApp: nil, model: nil, vendor: nil)
    check(volume(tv).atvVolumeStatus == "TV volume 32/100 · Unmuted", "actual report label")
    tv = .init(isOn: true, volumeLevel: 3, volumeMax: 12, volumeMuted: true, volumeRevision: 2, currentApp: nil, model: nil, vendor: nil)
    check(volume(tv).atvVolumeStatus == "TV volume 3/12 · Muted", "actual range never fabricated")
    tv = .init(isOn: true, volumeLevel: nil, volumeMax: nil, volumeMuted: true, volumeRevision: 3, currentApp: nil, model: nil, vendor: nil)
    check(volume(tv).atvVolumeStatus == "TV volume · Muted", "mute-only report")
}
@MainActor private func routeChecks() async {
    var policy = projection(), session = UUID(), generation = UUID(), revision: UInt64 = 1
    var receiverFrames: [String] = [], atvFrames: [AtvControlAction] = []
    var fail = false
    let router = RemoteInputRouter(latest: { policy }, keyboardTicket: { policy.keyboardTicket(session: session, revision: revision, contextGeneration: generation) },
        sendReceiver: { receiverFrames.append($0); return true }, sendAtv: { action, _ in atvFrames.append(action); if fail { throw AtvClientError.native(.protocolError) } })
    do {
        try await router.key("home")
        check(atvFrames == [.key("home")] && receiverFrames.isEmpty, "ATV chosen once")
        fail = true
        do { try await router.key("back"); check(false, "failure expected") } catch {}
        check(receiverFrames.isEmpty, "failed ATV command never retries receiver")
        fail = false
        let old = policy.fullControl.ticket
        policy = projection(connected: false)
        do { try await router.key("dpad_up", route: .atv, ticket: old); check(false, "stale key must fail") } catch {}
        check(receiverFrames.isEmpty, "retired route does not fall back")
        try await router.key("dpad_up")
        check(receiverFrames == ["dpad_up"], "receiver fallback before send")
        policy = projection(base(context: "browser"))
        let browser = router.keyboardTicket()!
        try await router.keyboard(.replace("Browser text"), ticket: browser)
        check(receiverFrames.last?.hasPrefix("text:") == true && atvFrames.count == 2, "browser whole draft stays receiver")
        do { try await router.keyboard(.edit(deletes: 1, suffix: "", enter: false), ticket: browser); check(false, "wrong edit shape") } catch {}
        for change in 0..<4 {
            let ticket = router.keyboardTicket()!, count = receiverFrames.count + atvFrames.count
            switch change {
            case 0: session = UUID()
            case 1: generation = UUID() // A -> B -> A context still retires old draft.
            case 2: revision += 1
            default: policy = projection(base(false, context: "browser"))
            }
            do { try await router.keyboard(.replace("Retired"), ticket: ticket); check(false, "retired keyboard ticket") } catch {}
            check(receiverFrames.count + atvFrames.count == count, "stale keyboard frame dropped")
        }
        let atv = router.keyboardTicket()!
        try await router.keyboard(.edit(deletes: 1, suffix: "😀", enter: true), ticket: atv)
        check(atvFrames.last == .edit(deletes: 1, suffix: "😀", enter: true), "receiver-down keyboard ATV edit")
        do { try await router.keyboard(.replace("Whole draft"), ticket: atv); check(false, "whole draft forbidden on ATV") } catch {}
    } catch { check(false, "unexpected route failure") }
}
@MainActor private func keyboardChecks() async {
    check(AtvTextEdit.between("😀", "😃") == .init(deletes: 1, suffix: "😃"), "surrogate pair boundary")
    check(AtvTextEdit.between("e\u{301}x", "e\u{301}y") == .init(deletes: 1, suffix: "y"), "combining prefix scalar diff")
    check(AtvTextEdit.between("abc", "axc") == .init(deletes: 2, suffix: "xc"), "middle edit replaces suffix")
    check(AtvTextEdit.between(String(repeating: "😀", count: 65), "") == nil, "delete cap")
    var edits: [RemoteKeyboardCommand] = []
    let editor = RemoteKeyboardEditor(route: .atv) { edits.append($0) }
    editor.update("に", marked: true); editor.enter(); await pump()
    check(edits.isEmpty, "marked text stays local")
    editor.update("日本", marked: false); await pump()
    check(edits == [.edit(deletes: 0, suffix: "日本", enter: false)], "IME commit inserts once")
    editor.update("日本", marked: false); await pump()
    check(edits.count == 1, "selection callbacks cannot enqueue duplicate text")
    editor.update("日", marked: false); await pump()
    editor.update("日😀", marked: false); await pump(); editor.enter(); await pump()
    check(edits.suffix(3) == [.edit(deletes: 1, suffix: "", enter: false), .edit(deletes: 0, suffix: "😀", enter: false), .edit(deletes: 0, suffix: "", enter: true)], "delete, suffix, Enter ordered")
    editor.update(String(repeating: "x", count: 65), marked: false)
    check(editor.limitReached && editor.draft == "日😀", "bounded draft")
    editor.update("", marked: false); await pump()
    check(edits.last == .edit(deletes: 2, suffix: "", enter: false), "clear deletes scalars, never inserts empty replacement")
    editor.close()

    var gate: CheckedContinuation<Void, Error>?, attempts = 0
    let partial = RemoteKeyboardEditor(route: .atv) { _ in attempts += 1; try await withCheckedThrowingContinuation { gate = $0 } }
    partial.update("abc", marked: false); await pump()
    partial.update("a", marked: false); partial.update("ab", marked: false)
    gate?.resume(throwing: AtvClientError.native(.protocolError)); gate = nil; await pump()
    check(partial.failed && attempts == 1, "partial/ambiguous failure stops queued edits")
    partial.update("retry", marked: false); partial.enter(); await pump()
    check(attempts == 1, "failed draft never replays")
    partial.close()

    let busy = RemoteKeyboardEditor(route: .atv) { _ in attempts += 1; try await withCheckedThrowingContinuation { gate = $0 } }
    busy.update("a", marked: false); await pump()
    for i in 1...70 { busy.update(String(i), marked: false) }
    check(busy.busy, "bounded edit queue")
    let count = attempts; busy.close(); gate?.resume(); gate = nil; await pump()
    check(attempts == count, "close drops queued edits")

    var browser: [RemoteKeyboardCommand] = []
    let receiver = RemoteKeyboardEditor(route: .receiver) { browser.append($0) }
    let large = String(repeating: "a", count: 80)
    receiver.update(large, marked: false); await pump(); receiver.enter(); await pump()
    check(browser == [.replace(large), .enter], "receiver replacement unchanged and not ATV draft capped")
    receiver.close()
}
@main struct AtvControlsTests {
    @MainActor static func main() async {
        projectionChecks(); await routeChecks(); await keyboardChecks(); await RemoteNavigationDispatchTests.run()
        print("PASS: Android-equivalent combined routing, receiver-down controls, stale tickets, no retries, scalar/IME/ordered keyboard and truthful badges/volume")
    }
}
