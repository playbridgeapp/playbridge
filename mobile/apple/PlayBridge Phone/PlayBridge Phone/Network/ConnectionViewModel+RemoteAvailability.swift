import Foundation

extension ConnectionViewModel {
    @MainActor var remoteTVRows: [RemoteTVRow] {
        var online = Set(savedDevices.filter { onlineStatus[deviceKey($0)] == true }.map { AtvReceiverTarget(playBridge: $0).endpoint })
        if isConnected, let endpoint = remoteEndpoint { online.insert(endpoint) }
        return RemoteTVCatalog.rows(saved: savedDevices, selected: nativeReceiverSelected ? pairedDevice : nil,
            records: remoteTVRecords, discovered: browser.devices, tvDevices: remoteTVDiscoveries,
            receiverOnline: online, connectedTV: combinedRemote.fullControlReady ? remoteEndpoint : nil,
            receiverOffline: Set(savedDevices.filter { onlineStatus[deviceKey($0)] == false }.map { AtvReceiverTarget(playBridge: $0).endpoint }).subtracting(online))
    }
    @MainActor var selectedRemoteTV: RemoteTVRow? { remoteTVRows.first { $0.id == remoteEndpoint } }
    @MainActor var remoteAvailability: RemoteAvailability {
        .resolve(receiver: isConnected || isAirPlay, tvRemote: combinedRemote.fullControlReady,
                 paired: selectedRemoteTV?.paired == true, ambiguous: selectedRemoteTV?.ambiguous == true)
    }
    @MainActor var remoteSubtitle: String {
        guard let row = selectedRemoteTV else { return "Control your TV" }
        return row.device.name + " · " + row.status
    }
    @MainActor func prepareIndependentRemote() -> Bool {
        if isAirPlay || isExternalReceiver { return true }
        switch RemoteTVSelection.resolve(selected: remoteEndpoint, choices: remoteTVRows.map(\.choice)) {
        case .selected(let endpoint):
            if remoteEndpoint == nil, let row = remoteTVRows.first(where: { $0.id == endpoint }) { connectSaved(row.device) }
            reconnectIndependentReceiver()
            return true
        case .picker: return false
        }
    }
    @MainActor func retryIndependentRemote() {
        atvRemote?.refresh()
        reconnectIndependentReceiver()
    }
    @MainActor private func reconnectIndependentReceiver() {
        guard nativeReceiverSelected, !isConnected, let row = selectedRemoteTV else { return }
        switch state {
        case .disconnected, .error:
            reconnectSaved(toHost: row.device.ip)
        case .retrying where row.device.ip != pairedDevice?.ip:
            reconnectSaved(toHost: row.device.ip)
        default: break // Pairing/trust failures and active attempts retain their existing policy.
        }
    }
    @MainActor func requestOpenPlayBridge() {
        guard let row = selectedRemoteTV, row.paired, !row.ambiguous else { return }
        pendingPlayBridgeLaunch = (row.id, Date().addingTimeInterval(30))
        completePlayBridgeLaunchIfReady()
    }
    @MainActor func completePlayBridgeLaunchIfReady() {
        guard let pending = pendingPlayBridgeLaunch else { return }
        guard remoteEndpoint == pending.endpoint, pending.expires > Date() else { pendingPlayBridgeLaunch = nil; return }
        if isConnected { pendingPlayBridgeLaunch = nil; return }
        guard combinedRemote.fullControlReady, let atv = atvRemote, let ticket = combinedRemote.fullControl.ticket else { return }
        pendingPlayBridgeLaunch = nil
        Task { [weak self] in
            do {
                try await atv.execute(.launch(RemoteAvailability.playBridgePackage), ticket: ticket)
                guard let self, self.remoteEndpoint == ticket.endpoint else { return }
                self.reconnectIndependentReceiver()
            } catch {
                if self?.remoteEndpoint == ticket.endpoint { self?.operationError = "Couldn't open PlayBridge on this TV." }
            }
        }
    }
}
