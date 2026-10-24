import Foundation
import Combine

/// Phone-hosted browser receiver. The Rust host stays in the sender-services
/// process: a TV page that reconnects with the same receiver id is approved
/// again without a new code, until this process stops the host.
/// The connection model is not main-actor isolated, so construction and
/// teardown stay nonisolated. UI and playback updates run on the main actor.
@MainActor
final class BrowserReceiverController: ObservableObject {
    @Published private(set) var state = BrowserHostSnapshot()
    var onActivate: (@MainActor (BrowserReadySession) -> Void)?
    var onPlayback: (@MainActor (String, BrowserPlaybackSnapshot) -> Void)?
    var onReadyChanged: (@MainActor () -> Void)?

    private var wantsRunning = false
    private var pendingActivation: String?
    private var hostGeneration: UUID?
    private nonisolated(unsafe) var handlerID: UUID?
    private nonisolated(unsafe) let services: PhoneSenderServices

    nonisolated init(services: PhoneSenderServices = .shared) {
        self.services = services
        handlerID = services.addServicesEventHandler { [weak self] event in
            Task { @MainActor in self?.apply(event) }
        }
    }

    nonisolated deinit {
        if let handlerID { services.removeServicesEventHandler(handlerID) }
    }

    func openSetup() {
        guard !state.running, !state.busy else { return }
        Task { await startHost() }
    }

    /// The TV page reconnects itself while this process is still hosting.
    func resumeIfNeeded() {
        guard wantsRunning else { return }
        Task { await startHost(quiet: true) }
    }

    func startHost(quiet: Bool = false) async {
        if !(quiet && state.running) {
            state.busy = true
            state.lastError = nil
        }
        do {
            let data = try await services.submitCommand([
                "command": "browser_start",
                "preferred_port": BrowserReceiverState.preferredPort,
            ])
            let urls = BrowserReceiverState.stringList(data["urls"])
            let port = BrowserReceiverState.int(data["port"]) ?? 0
            let generation = data["hostGeneration"] as? UUID
            if let previous = hostGeneration, previous != generation {
                pendingActivation = nil
                state.pending = []
                state.ready = []
            }
            hostGeneration = generation
            state.running = true
            state.urls = urls
            state.port = port
            state.busy = false
            state.lastError = nil
            wantsRunning = true
            onReadyChanged?()
        } catch {
            state.busy = false
            if !quiet || !state.running {
                state.running = false
                state.lastError = BrowserReceiverState.humanizeStartError(error.localizedDescription)
            }
        }
    }

    func stopHost() async {
        wantsRunning = false
        state.busy = true
        state.lastError = nil
        do {
            _ = try await services.submitCommand(["command": "browser_stop"])
            pendingActivation = nil
            hostGeneration = nil
            state = BrowserHostSnapshot()
            onReadyChanged?()
        } catch {
            state.busy = false
            state.lastError = BrowserReceiverState.publicError(error.localizedDescription)
        }
    }

    func approve(sessionId: String, code: String) async {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard state.running, !trimmed.isEmpty else { return }
        pendingActivation = sessionId
        state.lastError = nil
        do {
            _ = try await services.submitCommand([
                "command": "browser_approve",
                "session_id": sessionId,
                "code": trimmed,
            ])
        } catch {
            if pendingActivation == sessionId { pendingActivation = nil }
            state.lastError = BrowserReceiverState.publicError(error.localizedDescription)
        }
    }

    func forget(receiverId: String) async {
        guard !receiverId.isEmpty else { return }
        do {
            _ = try await services.submitCommand([
                "command": "browser_forget",
                "receiver_id": receiverId,
            ])
            state.pending.removeAll { $0.receiverId == receiverId }
            state.ready.removeAll { $0.receiverId == receiverId }
            onReadyChanged?()
        } catch {
            state.lastError = BrowserReceiverState.publicError(error.localizedDescription)
        }
    }

    func disconnect(sessionId: String) async {
        do {
            _ = try await services.submitCommand([
                "command": "browser_disconnect",
                "session_id": sessionId,
            ])
            state.pending.removeAll { $0.sessionId == sessionId }
            state.ready.removeAll { $0.sessionId == sessionId }
            onReadyChanged?()
        } catch {
            state.lastError = BrowserReceiverState.publicError(error.localizedDescription)
        }
    }

    func load(sessionId: String, url: URL, title: String?, contentType: String?, startPositionMs: Int?) async throws {
        if url.user != nil || url.password != nil || url.host == "127.0.0.1" || url.host == "localhost" {
            throw StreamRoutingError.message("Connect to Wi-Fi so the TV browser can reach this stream.")
        }
        var media: [String: Any] = ["url": url.absoluteString]
        if let title, !title.isEmpty { media["title"] = title }
        if let contentType, !contentType.isEmpty { media["contentType"] = contentType }
        if let startPositionMs, startPositionMs > 0 { media["startPositionMs"] = startPositionMs }
        _ = try await services.submitCommand([
            "command": "browser_load",
            "session_id": sessionId,
            "media": media,
        ])
    }

    func control(sessionId: String, action: String, value: Double?) async throws {
        var fields: [String: Any] = [
            "command": "browser_control",
            "session_id": sessionId,
            "action": action,
        ]
        if let value { fields["value"] = value }
        _ = try await services.submitCommand(fields)
    }

    func receiver(for session: BrowserReadySession, lanHost: String?) -> ExternalReceiverDevice {
        let host = BrowserReceiverState.pageHost(urls: state.urls, port: state.port, lanHost: lanHost) ?? ""
        let port = (1...65535).contains(state.port) ? UInt16(state.port) : 0
        return ExternalReceiverDevice(
            id: session.sessionId,
            name: session.name,
            addresses: host.isEmpty ? [] : [host],
            port: port,
            model: "Browser",
            receiverProtocol: "browser")
    }

    var pageAddress: String? {
        BrowserReceiverState.pageURL(urls: state.urls, port: state.port, lanHost: LocalFileServer.lanIPAddress())
    }

    private func apply(_ event: [String: Any]) {
        let readyBefore = state.ready.map(\.sessionId)
        var activation = pendingActivation
        let result = BrowserReceiverState.reduce(&state, event: event, now: Date(), pendingActivation: &activation)
        pendingActivation = activation
        if state.ready.map(\.sessionId) != readyBefore {
            onReadyChanged?()
        }
        if let session = result.activate { onActivate?(session) }
        if let sessionId = result.playbackSessionId, let playback = result.playback {
            onPlayback?(sessionId, playback)
        }
    }
}
