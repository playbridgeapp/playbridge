import SwiftUI

struct PairingView: View {
    @EnvironmentObject var server: WebSocketServer

    var body: some View {
        ZStack {
            if let request = server.pendingPairingRequest {
                PairingApprovalCard(request: request)
            } else {
                PairingIdleView()
            }
        }
    }
}

struct PairingApprovalCard: View {
    let request: PairingRequest
    @EnvironmentObject var server: WebSocketServer
    @FocusState private var allowFocused: Bool

    var body: some View {
        VStack(spacing: 40) {
            Text("Allow device to connect?")
                .font(.system(size: 32, weight: .semibold))
                .foregroundColor(.white.opacity(0.7))

            Text(request.deviceName)
                .font(.system(size: 72, weight: .bold))
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            HStack(spacing: 40) {
                Button(action: { server.approvePairing() }) {
                    Text("Allow")
                        .font(.system(size: 28, weight: .bold))
                        .padding(.horizontal, 40)
                        .padding(.vertical, 16)
                }
                .buttonStyle(.card)
                .focused($allowFocused)

                Button(action: { server.denyPairing() }) {
                    Text("Deny")
                        .font(.system(size: 28, weight: .bold))
                        .padding(.horizontal, 40)
                        .padding(.vertical, 16)
                }
                .buttonStyle(.card)
            }
            .onAppear { allowFocused = true }

            Text("Request expires in 30 seconds")
                .font(.system(size: 18))
                .foregroundColor(.white.opacity(0.4))
        }
        .padding(60)
        .background(
            RoundedRectangle(cornerRadius: 32)
                .fill(.ultraThinMaterial)
        )
    }
}

struct PairingIdleView: View {
    @EnvironmentObject var server: WebSocketServer
    @State private var showDevices = false
    @FocusState private var manageFocused: Bool

    var body: some View {
        VStack(spacing: 60) {
            VStack(spacing: 10) {
                Text(server.serverState.uppercased())
                    .font(.system(size: 20, weight: .black))
                    .foregroundColor(Theme.accent)
                Text(server.deviceName)
                    .font(.system(size: 70, weight: .black))
                    .foregroundColor(.white)
            }

            Text("\(server.localIP):8765")
                .font(.system(size: 32, design: .monospaced))
                .foregroundColor(.white.opacity(0.6))

            Text(
                server.isAuthenticated
                    ? "Ready to receive videos!\nUse your phone to send content."
                    : "Open PlayBridge on your phone and connect to this device."
            )
            .font(.system(size: 24))
            .foregroundColor(.secondary)
            .multilineTextAlignment(.center)

            if !server.pairedDevicesList.isEmpty {
                Button(action: { showDevices = true }) {
                    HStack(spacing: 12) {
                        Image(systemName: "iphone")
                        Text("Manage Paired Devices (\(server.pairedDevicesList.count))")
                    }
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(manageFocused ? .black : .white.opacity(0.7))
                    .padding(.horizontal, 32)
                    .padding(.vertical, 18)
                    .background(manageFocused ? Color.white : Color.white.opacity(0.12))
                    .cornerRadius(14)
                    .scaleEffect(manageFocused ? 1.06 : 1.0)
                    .animation(.interactiveSpring(), value: manageFocused)
                }
                .buttonStyle(.plain)
                .focused($manageFocused)
                .sheet(isPresented: $showDevices) {
                    DevicesSheetView()
                        .environmentObject(server)
                }
            }
        }
    }
}

// ─── Devices sheet ─────────────────────────────────────────────────────────────

private struct DevicesSheetView: View {
    @EnvironmentObject var server: WebSocketServer
    @Environment(\.dismiss) private var dismiss
    @FocusState private var removeAllFocused: Bool
    @FocusState private var doneFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 16) {
                Text("Paired Devices")
                    .font(.system(size: 48, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
                if !server.pairedDevicesList.isEmpty {
                    Button(action: { server.forgetAllDevices() }) {
                        Text("Remove All")
                            .font(.system(size: 22, weight: .semibold))
                            .foregroundColor(removeAllFocused ? .white : .red)
                            .padding(.horizontal, 24)
                            .padding(.vertical, 14)
                            .background(removeAllFocused ? Color.red : Color.red.opacity(0.15))
                            .cornerRadius(10)
                    }
                    .buttonStyle(.plain)
                    .focused($removeAllFocused)
                }
                Button(action: { dismiss() }) {
                    Text("Done")
                        .font(.system(size: 24, weight: .semibold))
                        .foregroundColor(doneFocused ? .black : .white)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 14)
                        .background(doneFocused ? Color.white : Color.white.opacity(0.12))
                        .cornerRadius(10)
                }
                .buttonStyle(.plain)
                .focused($doneFocused)
            }
            .padding(.horizontal, 60)
            .padding(.top, 60)
            .padding(.bottom, 40)

            if server.pairedDevicesList.isEmpty {
                Spacer()
                HStack {
                    Spacer()
                    Text("No paired devices.")
                        .font(.system(size: 28))
                        .foregroundColor(.secondary)
                    Spacer()
                }
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 16) {
                        ForEach(server.pairedDevicesList) { device in
                            SheetDeviceRow(device: device) {
                                server.forgetDevice(device)
                            }
                        }
                    }
                    .padding(.horizontal, 60)
                    .padding(.bottom, 40)
                }
            }
        }
    }
}

private struct SheetDeviceRow: View {
    let device: PairedDevice
    let onForget: () -> Void

    private var lastConnectedText: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return "Last connected: \(formatter.string(from: device.lastConnected))"
    }

    var body: some View {
        HStack(spacing: 24) {
            VStack(alignment: .leading, spacing: 8) {
                Text(device.deviceName)
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundColor(.white)
                Text(lastConnectedText)
                    .font(.system(size: 20))
                    .foregroundColor(.secondary)
                Text(device.deviceUUID)
                    .font(.system(size: 16, design: .monospaced))
                    .foregroundColor(.white.opacity(0.35))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer()
            DangerButton(title: "Forget", icon: "trash", action: onForget)
                .frame(width: 220)
        }
        .padding(.horizontal, 32)
        .padding(.vertical, 24)
        .background(
            RoundedRectangle(cornerRadius: 20)
                .fill(Color.white.opacity(0.08))
        )
    }
}
