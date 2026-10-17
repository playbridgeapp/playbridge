import Foundation

extension ConnectionViewModel {
    @MainActor var remoteEndpoint: String? {
        if nativeReceiverSelected, !isExternalReceiver, !isAirPlay {
            return pairedDevice.map { AtvReceiverTarget(playBridge: $0).endpoint }
        }
        return isExternalReceiver || isAirPlay ? destinationID : nil
    }
    @MainActor var combinedRemote: CombinedRemotePolicy {
        .init(receiver: nativeRemotePolicy, endpoint: remoteEndpoint,
              fullControl: atvRemote?.fullControl ?? AtvFullControlSnapshot(),
              native: nativeReceiverSelected && !isExternalReceiver && !isAirPlay)
    }
    @MainActor var remoteKeyboardTicket: RemoteKeyboardTicket? {
        combinedRemote.keyboardTicket(session: ws.capabilitySessionID,
            revision: ws.receiverCapabilitySession.snapshot.revision, contextGeneration: coordinator.remoteContextGeneration)
    }
    @MainActor private func inputRouter(session: UUID, endpoint: String?, keyboard: RemoteKeyboardTicket? = nil,
                                        contextGeneration: UUID? = nil,
                                        authority: @escaping @MainActor () -> Bool = { true }) -> RemoteInputRouter {
        let context = contextGeneration ?? coordinator.remoteContextGeneration
        return RemoteInputRouter(latest: { self.combinedRemote }, keyboardTicket: { [weak self] in self?.remoteKeyboardTicket },
            sendReceiver: { [weak self] key in
                guard let self, authority(), self.remoteEndpoint == endpoint, self.ws.capabilitySessionID == session, self.coordinator.remoteContextGeneration == context,
                      self.isConnected, self.nativeRemotePolicy.allowsKey(key) else { return false }
                if self.isAirPlay || self.isExternalReceiver {
                    self.receiverRemoteKey(key, onSent: { RemoteHaptics.shared.perform(RemoteFeedback.key(key)) })
                    return true
                }
                return self.ws.send(WireProtocol.remoteCommand(key: key))
            }, sendReceiverNavigation: { [weak self] key in
                guard let self, authority(), self.remoteEndpoint == endpoint, self.ws.capabilitySessionID == session,
                      self.coordinator.remoteContextGeneration == context, self.isConnected,
                      self.nativeRemotePolicy.allowsKey(key) else { throw AtvClientError.superseded }
                if self.isAirPlay || self.isExternalReceiver {
                    try await self.sendExternalNavigation(key, authority: authority)
                } else {
                    try await self.ws.sendNavigation(WireProtocol.remoteCommand(key: key), authority: authority)
                }
            }, sendAtv: { [weak self] action, ticket in
                guard let self, self.remoteEndpoint == ticket.endpoint, let atv = self.atvRemote else { throw AtvClientError.superseded }
                try await atv.execute(action, ticket: ticket, authority: { [weak self] in
                    authority() && (keyboard == nil || self?.remoteKeyboardTicket == keyboard)
                })
            })
    }
    @MainActor func beginNavigationGesture() -> UUID { navigationDispatcher.beginGesture() }
    @MainActor func endNavigationGesture(_ id: UUID) { navigationDispatcher.endGesture(id) }
    @MainActor func cancelNavigation() { navigationDispatcher.cancel() }
    @MainActor func routeRemoteKey(_ key: String, gesture: UUID? = nil) {
        let policy = combinedRemote, endpoint = remoteEndpoint, session = ws.capabilitySessionID
        let route = policy.route(key), ticket = policy.fullControl.ticket
        let external = isAirPlay || isExternalReceiver
        guard route != .unavailable else { return }
        let failure: (Error) -> Void = { [weak self] error in
            if error is CancellationError || (error as? AtvClientError) == .superseded { return }
            guard let self, self.remoteEndpoint == endpoint,
                  route != .atv || self.atvRemote?.fullControl.ticket == ticket else { return }
            self.operationError = (error as? AtvClientError)?.errorDescription ?? "Could not send TV control"
        }
        if RemoteNavigationKey.names.contains(key) {
            let context = coordinator.remoteContextGeneration
            navigationDispatcher.submit(gesture: gesture, send: { [weak self] authority in
                guard let self, authority() else { throw AtvClientError.superseded }
                try await self.inputRouter(session: session, endpoint: endpoint,
                    contextGeneration: context, authority: authority).key(key, route: route, ticket: ticket)
            }, onSent: {
                RemoteHaptics.shared.perform(RemoteFeedback.key(key))
            }, onError: failure)
        } else {
            let router = inputRouter(session: session, endpoint: endpoint)
            Task {
                do {
                    try await RemoteHaptics.shared.afterSending(external ? nil : RemoteFeedback.key(key)) {
                        try await router.key(key, route: route, ticket: ticket)
                    }
                } catch { failure(error) }
            }
        }
    }
    @MainActor func sendKeyboard(_ command: RemoteKeyboardCommand, ticket: RemoteKeyboardTicket) async throws {
        try await inputRouter(session: ticket.receiverSession, endpoint: ticket.endpoint, keyboard: ticket).keyboard(command, ticket: ticket)
    }
    @MainActor func sendTVControl(_ action: AtvControlAction, ticket expected: AtvControlTicket?) {
        guard combinedRemote.fullControlReady, let atv = atvRemote, let ticket = expected, atv.fullControl.ticket == ticket else { return }
        Task { [weak self] in
            do { try await atv.execute(action, ticket: ticket) }
            catch {
                guard self?.atvRemote?.fullControl.ticket == ticket else { return }
                self?.operationError = (error as? AtvClientError)?.errorDescription ?? "Could not send TV control"
            }
        }
    }
}
