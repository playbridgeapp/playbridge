import Foundation

enum RemoteCommandRoute: Equatable { case receiver, atv, unavailable }
struct RemoteKeyboardTicket: Equatable {
    let route: RemoteCommandRoute
    let endpoint: String
    let context: String
    let contextGeneration: UUID
    let receiverSession: UUID
    let receiverRevision: UInt64
    let atv: AtvControlTicket?
}

/// Pure Android FullControlProjection / RemoteInputRouter policy. Receiver permissions remain
/// unchanged. Both the view and action boundary use this same resolved projection.
struct CombinedRemotePolicy {
    let receiver: ReceiverRemotePolicy
    let endpoint: String?
    let fullControl: AtvFullControlSnapshot
    let native: Bool
    var fullControlReady: Bool {
        native && endpoint != nil && fullControl.endpoint == endpoint && fullControl.paired &&
            fullControl.ticket?.endpoint == endpoint
    }
    var receiverReady: Bool { receiver.state.connected }
    var keyboardRoute: RemoteCommandRoute {
        let browserText = native && receiverReady && receiver.state.context == "browser" &&
            (receiver.isLegacy || receiver.text.contains("replace"))
        if fullControlReady && !browserText { return .atv }
        return browserText || receiverReady && receiver.text.contains("replace") ? .receiver : .unavailable
    }
    func route(_ key: String) -> RemoteCommandRoute {
        if AtvControlAction.remoteKey(key) != nil && fullControlReady { return .atv }
        if key == "menu" { return .unavailable }
        return receiverReady && receiver.allowsKey(key) ? .receiver : .unavailable
    }
    func allowsKey(_ key: String) -> Bool { route(key) != .unavailable }
    var relativeVolume: Bool { allowsKey("volume_up") }
    var mute: Bool { allowsKey("mute") }
    var absoluteVolume: Bool { !fullControlReady && receiverReady && receiver.hasVolume("absolute") }
    var volumeLabel: String { fullControlReady ? "TV volume" : receiver.volumeLabel }
    var badge: String? {
        guard native, fullControl.endpoint == endpoint, endpoint != nil else { return nil }
        if fullControlReady { return "Full control" }
        if fullControl.needsRepair { return "Pair again" }
        return fullControl.available && !fullControl.paired ? "Get full control" : nil
    }
    var televisionOff: Bool { fullControlReady && fullControl.television?.isOn == false }
    var atvVolumeStatus: String? {
        guard fullControlReady, let tv = fullControl.television, tv.volumeRevision > 0 else { return nil }
        var label = "TV volume"
        if let level = tv.volumeLevel, let maximum = tv.volumeMax, maximum > 0 {
            // Report the actual range. Do not invent /100 for TVs whose maximum differs.
            label += " \(level)/\(maximum)"
        }
        return label + (tv.volumeMuted ? " · Muted" : " · Unmuted")
    }
    func keyboardTicket(session: UUID, revision: UInt64, contextGeneration: UUID) -> RemoteKeyboardTicket? {
        guard let endpoint, keyboardRoute != .unavailable else { return nil }
        return .init(route: keyboardRoute, endpoint: endpoint, context: receiver.state.context,
            contextGeneration: contextGeneration, receiverSession: session, receiverRevision: revision,
            atv: keyboardRoute == .atv ? fullControl.ticket : nil)
    }
}

enum RemoteKeyboardCommand: Equatable { case replace(String), edit(deletes: Int, suffix: String, enter: Bool), enter }

/// A route is captured before dispatch. Errors never cause a resend on a different channel.
@MainActor final class RemoteInputRouter {
    let latest: () -> CombinedRemotePolicy
    let keyboardTicket: () -> RemoteKeyboardTicket?
    let sendReceiver: (String) -> Bool
    let sendReceiverNavigation: ((String) async throws -> Void)?
    let sendAtv: (AtvControlAction, AtvControlTicket) async throws -> Void
    init(latest: @escaping () -> CombinedRemotePolicy, keyboardTicket: @escaping () -> RemoteKeyboardTicket?,
         sendReceiver: @escaping (String) -> Bool,
         sendReceiverNavigation: ((String) async throws -> Void)? = nil,
         sendAtv: @escaping (AtvControlAction, AtvControlTicket) async throws -> Void) {
        self.latest = latest; self.keyboardTicket = keyboardTicket
        self.sendReceiver = sendReceiver; self.sendAtv = sendAtv
        self.sendReceiverNavigation = sendReceiverNavigation
    }
    func key(_ key: String, route expected: RemoteCommandRoute? = nil, ticket expectedTicket: AtvControlTicket? = nil) async throws {
        let policy = latest()
        let route = expected ?? policy.route(key)
        guard policy.route(key) == route else { throw AtvClientError.superseded }
        if expected != nil && route == .atv && expectedTicket != policy.fullControl.ticket { throw AtvClientError.superseded }
        switch route {
        case .atv:
            guard let action = AtvControlAction.remoteKey(key), let ticket = policy.fullControl.ticket else { throw AtvClientError.notConnected }
            try await sendAtv(action, ticket)
        case .receiver:
            if RemoteNavigationKey.names.contains(key), let sendReceiverNavigation {
                try await sendReceiverNavigation(key)
            } else if !sendReceiver(key) { throw AtvClientError.rejected }
        case .unavailable: throw AtvClientError.notConnected
        }
    }
    func keyboard(_ command: RemoteKeyboardCommand, ticket: RemoteKeyboardTicket) async throws {
        guard keyboardTicket() == ticket else { throw AtvClientError.superseded }
        switch (ticket.route, command) {
        case (.atv, .edit(let deletes, let suffix, let enter)):
            guard let atv = ticket.atv else { throw AtvClientError.notConnected }
            try await sendAtv(.edit(deletes: deletes, suffix: suffix, enter: enter), atv)
        case (.receiver, .replace(let value)):
            guard sendReceiver("text:" + Data(value.utf8).base64EncodedString()) else { throw AtvClientError.rejected }
        case (.receiver, .enter):
            guard latest().receiver.allowsKey("key_enter"), sendReceiver("key_enter") else { throw AtvClientError.rejected }
        default: throw AtvClientError.invalidInput
        }
    }
}
