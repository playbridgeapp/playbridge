import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

struct AtvPairingSheet: View {
    @ObservedObject var coordinator: AtvRemoteCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let name = coordinator.receiverName { Text(verbatim: name).font(.headline).accessibilityLabel("Selected TV: \(name)") }
                    Text(AtvPairingPresentation.explanation)
                }
                Section {
                    Text(AtvPairingPresentation.message(coordinator.pairing))
                        .accessibilityIdentifier("atv-pairing-status")
                        .accessibilityAddTraits(.updatesFrequently)
                    switch coordinator.pairing.phase {
                    case .recoveryConfirmation:
                        Text(AtvPairingPresentation.recoveryExplanation)
                        Button("Pair again and recover") { coordinator.beginPairing(confirmRecovery: true) }
                            .accessibilityLabel("Pair again and recover TV identity")
                    case .starting, .verifying:
                        ProgressView().accessibilityLabel(coordinator.pairing.phase == .starting ? "Starting pairing" : "Checking pairing code")
                    case .waitingForCode:
                        TextField("Pairing code", text: $code)
                            .textInputAutocapitalization(.characters).autocorrectionDisabled()
                            .privacySensitive()
                            .accessibilityLabel("Pairing code")
                            .accessibilityHint("6 hexadecimal characters shown on the selected TV")
                            .accessibilityIdentifier("atv-pairing-code")
                            .onChange(of: code) { code = AtvPairingPresentation.normalizedInput($0) }
                            .onSubmit { coordinator.submitCode(code) }
                        Button("Pair") { coordinator.submitCode(code) }
                            .disabled(AtvPairingPresentation.validCode(code) == nil)
                            .accessibilityLabel("Pair TV").accessibilityIdentifier("atv-pair")
                    case .failed:
                        if coordinator.pairing.storageFailure, let error = coordinator.storageError {
                            Text(error.errorDescription ?? "TV pairing storage unavailable")
                        }
                        Button("Retry") { code = ""; coordinator.beginPairing() }
                            .accessibilityLabel("Retry pairing").accessibilityIdentifier("atv-pairing-retry")
                    case .paired:
                        if coordinator.identityRecovered { Text(AtvPairingPresentation.recoveredExplanation) }
                        Button("Done") { dismiss() }.accessibilityLabel("Finish TV pairing")
                    case .idle:
                        Button("Start pairing") { coordinator.beginPairing() }.accessibilityLabel("Start TV pairing")
                    }
                }
                Section("Local Network") {
                    Text(AtvPairingPresentation.networkHelp)
                    AtvSettingsRecoveryButton()
                }
            }
            .navigationTitle("Full control").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }.accessibilityLabel("Cancel pairing")
            } }
        }
        .task { if coordinator.pairing.phase == .idle { coordinator.beginPairing() } }
        .onChange(of: coordinator.pairing.phase) { _ in code = "" }
        .onDisappear { code = ""; coordinator.cancelPairing() }
    }
}

struct AtvPairedTVsSheet: View {
    @ObservedObject var coordinator: AtvRemoteCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var pendingForget: AtvTvRecord?
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Whole-TV pairings are separate from saved PlayBridge receivers. Forgetting a TV keeps this install's identity.")
                    Text(AtvPairingPresentation.recoveryExplanation)
                    if coordinator.identityRecovered { Text(AtvPairingPresentation.recoveredExplanation) }
                }
                Section("Paired TVs") {
                    if coordinator.records.isEmpty { Text("No paired TVs") }
                    ForEach(coordinator.records, id: \.fingerprint) { record in
                        HStack {
                            Text(verbatim: record.serverName ?? "TV").lineLimit(2)
                            Spacer()
                            Button("Forget", role: .destructive) { pendingForget = record }
                                .accessibilityLabel("Forget \(record.serverName ?? "TV")")
                                .disabled(coordinator.managementBusy)
                        }
                    }
                }
                if let error = coordinator.storageError {
                    Section {
                        Text(error.errorDescription ?? "TV pairing storage unavailable")
                        Button("Retry storage") { coordinator.reloadManagement() }
                            .accessibilityLabel("Retry TV pairing storage")
                    }
                }
            }
            .navigationTitle("Paired TVs").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { coordinator.reloadManagement() }
            .confirmationDialog("Forget TV pairing?", isPresented: Binding(get: { pendingForget != nil }, set: { if !$0 { pendingForget = nil } }), titleVisibility: .visible) {
                Button("Forget", role: .destructive) {
                    if let record = pendingForget { coordinator.forget(record.fingerprint) }
                    pendingForget = nil
                }
                Button("Cancel", role: .cancel) { pendingForget = nil }
            } message: { Text("Live TV control disconnects. Pair again from the Remote to restore it.") }
        }
    }
}

private struct AtvSettingsRecoveryButton: View {
    var body: some View {
        Button("Open Settings") {
#if canImport(UIKit)
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
#endif
        }.accessibilityLabel("Open PlayBridge settings for Local Network access")
    }
}
