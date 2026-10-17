import Foundation

enum RemoteNavigationKey {
    static let names: Set<String> = ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center"]
}

/// One in flight + one latest pending step, shared by pad, D-pad and VoiceOver.
/// Release retires unsent gesture steps. Never cancel a native write already in flight:
/// that would tear down the ATV connection, and cannot retract a written key anyway.
@MainActor final class RemoteNavigationDispatcher {
    typealias Authority = @MainActor () -> Bool
    private struct Request {
        let gesture: UUID?
        let epoch: UUID
        let send: (@escaping Authority) async throws -> Void
        let onSent: () -> Void
        let onError: (Error) -> Void
    }
    private var gesture: UUID?
    private var epoch = UUID()
    private var pending: Request?
    private(set) var inFlight = false
    var pendingCount: Int { pending == nil ? 0 : 1 }

    func beginGesture() -> UUID {
        let id = UUID()
        pending = nil
        epoch = UUID()
        gesture = id
        return id
    }
    func endGesture(_ id: UUID) {
        guard gesture == id else { return }
        gesture = nil
        if pending?.gesture == id { pending = nil }
    }
    func cancel() { epoch = UUID(); gesture = nil; pending = nil }

    func submit(gesture id: UUID? = nil, send: @escaping (@escaping Authority) async throws -> Void,
                onSent: @escaping () -> Void, onError: @escaping (Error) -> Void) {
        guard id == nil || gesture == id else { return }
        let request = Request(gesture: id, epoch: epoch, send: send, onSent: onSent, onError: onError)
        if inFlight { pending = request } else { start(request) }
    }
    private func start(_ request: Request) {
        inFlight = true
        let valid: Authority = { [weak self] in
            guard let self else { return false }
            return self.epoch == request.epoch && (request.gesture == nil || self.gesture == request.gesture)
        }
        Task {
            do {
                guard valid() else { throw CancellationError() }
                try await request.send(valid)
                request.onSent()
            } catch {
                request.onError(error)
            }
            inFlight = false
            if let next = pending {
                pending = nil
                start(next)
            }
        }
    }
}
