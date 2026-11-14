import Combine
import Foundation
import UIKit

/// Watch Together on Apple TV (`docs/design-watch-together.md`), matching Android's `TvTogether.kt`.
/// Party playback skips history, bookmarks, and the progress webhook, and its URLs,
/// headers, and invite links never reach logs.
@MainActor
final class TvTogether: ObservableObject {
    static let shared = TvTogether()
    static let defaultPairingURL = "https://pair.playbridge.app"
    private static let pairingURLKey = "watch_together_pairing_url"

    struct PartyState: Equatable {
        var relay: String
        var connected: Bool = false
        var members: [String: String] = [:]
        var memberOrder: [String] = []
        var hostName: String? = nil
        var title: String? = nil
        var waitingFor: [String] = []
        var notice: String? = nil

        func toPartyUi() -> TogetherPartyUi {
            let host = hostName.flatMap { $0.isEmpty ? nil : $0 }
            let status: TogetherPartyStatus
            if !connected {
                status = .connecting(relay: relay)
            } else if let title, !title.isEmpty {
                status = .nowPlaying(title: title)
            } else {
                status = .waitingForHost
            }
            let orderedKeys = memberOrder.filter { members[$0] != nil }
                + members.keys.filter { !memberOrder.contains($0) }.sorted()
            let memberUis = orderedKeys.compactMap { key -> PartyMemberUi? in
                guard let name = members[key] else { return nil }
                return PartyMemberUi(
                    id: key,
                    name: name,
                    isHost: host != nil && name == host,
                    isWaiting: waitingFor.contains(name)
                )
            }
            return TogetherPartyUi(
                partyName: host.map { "\($0)’s watch party" } ?? "Watch party",
                status: status,
                members: memberUis,
                waitingFor: waitingFor,
                notice: notice
            )
        }
    }

    enum State: Equatable {
        case idle
        case registering
        case code(code: String, qrSize: Int, qrModules: String, expiresAtMs: Int64)
        case invited(from: String, relay: String)
        case inParty(PartyState)
        case ended(message: String)

        var stepKey: String {
            switch self {
            case .idle: return "idle"
            case .registering: return "registering"
            case .code: return "code"
            case .invited: return "invited"
            case .inParty: return "inParty"
            case .ended: return "ended"
            }
        }
    }

    struct RoomJoinedInfo {
        let member: String
        let boxKey: String
        let signKey: String
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var pairingURL: String

    /// Called when the party loads a video. Wired by `ContentView` / `WebSocketServer`.
    var onPlayPartyItem: ((_ payload: Playbridge_PlayPayload, _ itemID: String) -> Bool)?

    private var client: TogetherClient?
    private var pendingPhoneJoin: ((RoomJoinedInfo?, String?) -> Void)?
    private var me: String?
    private var room: String?
    private var loadingItem: String?
    private var attachment = TogetherPlayerAttachment()
    private var playerItem: String? { attachment.item }
    private var partyCommandHandler: ((TogetherPlayerCommand) -> Void)?
    private var noticeClearTask: Task<Void, Never>?

    var isAvailable: Bool { RustTogetherBoundary.isCompiledWithCastCore }
    var isInParty: Bool {
        if case .inParty = state { return true }
        return false
    }
    var currentParty: PartyState? {
        if case .inParty(let party) = state { return party }
        return nil
    }
    var activeItemID: String? { loadingItem ?? attachment.item }

    func isPartyItem(_ itemID: String?) -> Bool {
        TogetherSessionMatcher.isPartyItem(itemID, loadingItem: loadingItem, playerItem: attachment.item)
    }

    private init() {
        let saved = UserDefaults.standard.string(forKey: Self.pairingURLKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        self.pairingURL = (saved?.isEmpty == false) ? saved! : Self.defaultPairingURL
    }

    func setPairingURL(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(trimmed, forKey: Self.pairingURLKey)
        pairingURL = trimmed.isEmpty ? Self.defaultPairingURL : trimmed
    }

    func startPairing() {
        let pairing = pairingURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !pairing.isEmpty else {
            state = .ended(message: "Set the pairing service address first.")
            return
        }
        guard let session = ensureClient() else { return }
        if isInParty { return }
        state = .registering
        if !session.pairRegister(pairing: pairing) {
            state = .ended(message: "Watch Together isn't available on this Apple TV.")
        }
    }

    func cancelPairing() {
        client?.pairCancel()
        if !isInParty {
            state = .idle
        }
    }

    func accept() {
        guard let session = client, case .invited(_, let relay) = state else { return }
        state = .inParty(PartyState(relay: relay))
        session.pairAccept(device: TogetherDevice(name: deviceName(), kind: .tv, screen: true))
    }

    func decline() {
        client?.pairCancel()
        state = .idle
    }

    func leave() {
        client?.leave()
        state = .idle
        loadingItem = nil
        attachment.reset()
        partyCommandHandler = nil
    }

    func joinFromPhone(
        invite: String,
        from: String,
        reply: @escaping (RoomJoinedInfo?, String?) -> Void
    ) {
        guard let session = ensureClient() else {
            reply(nil, "unavailable")
            return
        }
        pendingPhoneJoin?(nil, "unavailable")
        pendingPhoneJoin = reply
        client?.pairCancel()
        let hostName = from.trimmingCharacters(in: .whitespacesAndNewlines)
        state = .inParty(PartyState(relay: "", hostName: hostName.isEmpty ? nil : hostName))
        if !session.join(invite: invite, device: TogetherDevice(name: deviceName(), kind: .tv, screen: true)) {
            pendingPhoneJoin = nil
            state = .idle
            reply(nil, "unavailable")
        }
    }

    // MARK: - Player Bridge

    func attach(item: String, token: UUID, handler: @escaping (TogetherPlayerCommand) -> Void) {
        attachment.attach(item: item, token: token)
        partyCommandHandler = handler
    }

    func detach(token: UUID) {
        guard attachment.detach(token: token) else { return }
        partyCommandHandler = nil
    }

    func onOtherPlayback() {
        guard isInParty else { return }
        leave()
        state = .ended(message: "You left the watch party because something else started playing.")
    }

    func onLoaded(item: String, durationMs: Int64?) {
        guard item == loadingItem else { return }
        client?.loaded(item: item, durationMs: durationMs)
    }

    func onPlayerState(item: String, positionMs: Int64, playing: Bool, buffering: Bool) {
        guard item == attachment.item else { return }
        client?.snapshot(positionMs: max(0, positionMs), playing: playing, buffering: buffering)
    }

    @discardableResult
    func localPlay() -> Bool {
        playerItem != nil && client?.play() == true
    }

    @discardableResult
    func localPause() -> Bool {
        playerItem != nil && client?.pause() == true
    }

    @discardableResult
    func localSeek(positionMs: Int64) -> Bool {
        let pos = max(0, positionMs)
        return playerItem != nil && client?.seek(positionMs: pos) == true
    }

    // MARK: - Client & Events

    private func ensureClient() -> TogetherClient? {
        if let client { return client }
        do {
            let created = try TogetherClient { [weak self] event in
                Task { @MainActor [weak self] in
                    self?.onEvent(event)
                }
            }
            self.client = created
            return created
        } catch {
            state = .ended(message: "Watch Together isn't available on this Apple TV.")
            return nil
        }
    }

    private func onEvent(_ event: TogetherEvent) {
        switch event {
        case .pairingCode(let code, _, let qrSize, let qrModules, let expiresAtMs):
            state = .code(code: code, qrSize: qrSize, qrModules: qrModules, expiresAtMs: expiresAtMs)

        case .pairingInvite(let from, let relay):
            state = .invited(from: from, relay: relay)

        case .room(_, _, let relay, let member, let boxKey, let signKey, _):
            // Note: `roomId` is stored to scope `.stopped` events.
            if case .room(let roomId, _, _, _, _, _, _) = event {
                room = roomId
            }
            me = member
            pendingPhoneJoin?(RoomJoinedInfo(member: member, boxKey: boxKey, signKey: signKey), nil)
            pendingPhoneJoin = nil
            state = .inParty(PartyState(relay: relay))

        case .connected:
            noticeClearTask?.cancel()
            noticeClearTask = nil
            updateParty { party in
                party.connected = true
                party.notice = nil
            }

        case .disconnected:
            noticeClearTask?.cancel()
            noticeClearTask = nil
            updateParty { party in
                party.connected = false
                party.notice = "Reconnecting…"
            }

        case .member(let member, let name, _, let host):
            let wasConnected = currentParty?.connected == true
            let wasMember = currentParty?.members[member] != nil
            updateParty { party in
                if party.members[member] == nil {
                    party.memberOrder.append(member)
                }
                party.members[member] = name
                if host && (party.hostName == nil || party.hostName?.isEmpty == true) {
                    party.hostName = name
                }
            }
            if wasConnected && !wasMember && member != me && !name.isEmpty {
                showTransientNotice("\(name) joined", durationSeconds: 3)
            }

        case .memberLeft(let member):
            let departedName = currentParty?.members[member]
            updateParty { party in
                party.members.removeValue(forKey: member)
                party.memberOrder.removeAll { $0 == member }
            }
            if let departedName, !departedName.isEmpty {
                showTransientNotice("\(departedName) left", durationSeconds: 3)
            }

        case .video(let item):
            updateParty { party in
                party.title = item.title
            }

        case .waiting(let members):
            updateParty { party in
                party.waitingFor = members
                    .filter { $0 != me }
                    .map { party.members[$0] ?? "a device" }
            }

        case .warning(let message):
            showTransientNotice(message, durationSeconds: 5)

        case .player(let command):
            onPlayerCommand(command)

        case .error(let context, let message, _):
            if context == "join" {
                pendingPhoneJoin?(nil, "invalid_invite")
                pendingPhoneJoin = nil
                state = .ended(message: "The invite from your phone isn't valid.")
            } else if context == "pair" || !isInParty {
                state = .ended(message: message.isEmpty ? "Something went wrong." : message)
            }

        case .stopped(let roomId, let reason):
            if !roomId.isEmpty, roomId != room { return }
            noticeClearTask?.cancel()
            noticeClearTask = nil
            room = nil
            loadingItem = nil
            attachment.reset()
            partyCommandHandler = nil
            if isInParty {
                switch reason {
                case .left:
                    state = .idle
                case .roomFull:
                    state = .ended(message: "This watch party is full.")
                case .expired:
                    state = .ended(message: "This watch party has ended.")
                case .unauthorized:
                    state = .ended(message: "This invite isn't valid any more.")
                case .replaced:
                    state = .ended(message: "This TV joined the party somewhere else.")
                case .error:
                    state = .ended(message: "The watch party connection failed.")
                }
            }

        default:
            break
        }
    }

    private func updateParty(_ change: (inout PartyState) -> Void) {
        guard case .inParty(var party) = state else { return }
        change(&party)
        state = .inParty(party)
    }

    private func onPlayerCommand(_ command: TogetherPlayerCommand) {
        if case .load(let item, let headers, let startMs) = command {
            load(item: item, headers: headers, startMs: startMs)
            return
        }
        partyCommandHandler?(command)
    }

    private func load(item: TogetherItem, headers: [String: String], startMs: Int64) {
        let url: String
        switch item.source {
        case .url(let u):
            url = u
        case .page, .app:
            updateParty { party in
                party.notice = "This TV can't play \"\(item.title)\" yet."
            }
            return
        }

        loadingItem = item.id
        var payload = Playbridge_PlayPayload()
        payload.url = url
        payload.title = item.title
        payload.headers = headers
        payload.subtitles = item.subtitles
        payload.startPositionMs = max(0, startMs)
        payload.skipHistory = true
        payload.itemID = item.id

        if onPlayPartyItem?(payload, item.id) != true {
            updateParty { party in
                party.notice = "Couldn't open the player."
            }
        }
    }

    private func showTransientNotice(_ message: String, durationSeconds: UInt64) {
        noticeClearTask?.cancel()
        updateParty { party in
            party.notice = message
        }
        noticeClearTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: durationSeconds * 1_000_000_000)
            guard !Task.isCancelled else { return }
            self?.updateParty { party in
                if party.notice == message {
                    party.notice = nil
                }
            }
        }
    }

    private func deviceName() -> String {
        UIDevice.current.name.isEmpty ? "Apple TV" : UIDevice.current.name
    }
}
