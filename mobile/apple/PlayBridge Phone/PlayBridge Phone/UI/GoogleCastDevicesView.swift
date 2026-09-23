import SwiftUI

struct ExternalReceiverDevicesView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    var savedOnly = false
    var dlnaOnly = false
    var rokuOnly = false
    private var selectedProtocol: String { rokuOnly ? "roku" : (dlnaOnly ? "dlna" : "google_cast") }
    private var ssdpBrowser: DLNABrowser { rokuOnly ? vm.rokuBrowser : vm.dlnaBrowser }

    private var devices: [ExternalReceiverDevice] {
        if savedOnly {
            return vm.savedExternalReceiverDevices.filter { !(vm.isConnected && vm.externalReceiver?.identity == $0.identity) }
        }
        let found = (dlnaOnly || rokuOnly) ? ssdpBrowser.devices : vm.googleCastBrowser.devices
        let ids = Set(found.map(\.identity))
        return found + vm.savedExternalReceiverDevices.filter { !ids.contains($0.identity) && $0.protocolID == selectedProtocol }
    }

    var body: some View {
        if !savedOnly || !devices.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text(savedOnly ? "Recent other" : (rokuOnly ? "Roku" : (dlnaOnly ? "DLNA" : "Google Cast"))).font(Theme.font(.headline)).foregroundColor(Theme.primary)
                    Spacer()
                    if !savedOnly && ((dlnaOnly || rokuOnly) ? ssdpBrowser.isScanning : vm.googleCastBrowser.isScanning) { ProgressView().tint(Theme.primary) }
                }
                if !savedOnly, let error = ((dlnaOnly || rokuOnly) ? ssdpBrowser.error : vm.googleCastBrowser.error) {
                    Text(error).font(Theme.font(.subheadline)).foregroundColor(Theme.danger)
                }
                if !savedOnly && devices.isEmpty {
                    Text(rokuOnly ? "No Roku receivers found. Check Wi-Fi or enter its IP address." : dlnaOnly ? "No DLNA receivers found. Check Wi-Fi or enter a device description URL." : "Looking for Google Cast devices on your Wi-Fi…")
                        .font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
                }
                ForEach(devices, id: \.identity) { device in
                    HStack {
                        Button { vm.connectExternalReceiver(device) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "tv").foregroundColor(Theme.primary)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(device.name).font(Theme.font(.headline)).foregroundColor(Theme.onSurface)
                                    Text(savedOnly ? device.protocolName + " · " + device.model : device.model + ((vm.googleCastBrowser.devices + vm.dlnaBrowser.devices + vm.rokuBrowser.devices).contains(where: { $0.identity == device.identity }) ? "" : " · Saved"))
                                        .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
                                }
                                Spacer()
                                if vm.externalReceiver?.identity == device.identity && vm.isConnected {
                                    Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.primary)
                                } else if vm.externalReceiver?.identity == device.identity && vm.state == .connecting {
                                    ProgressView()
                                } else {
                                    Image(systemName: "chevron.right").foregroundColor(Theme.onSurfaceVariant)
                                }
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(vm.externalReceiver?.identity == device.identity && (vm.isConnected || vm.state == .connecting))
                        if vm.savedExternalReceiverDevices.contains(where: { $0.identity == device.identity }) {
                            Button { vm.forgetExternalReceiver(device) } label: { Image(systemName: "trash") }
                                .foregroundColor(Theme.onSurfaceVariant)
                                .accessibilityLabel("Forget " + device.name)
                        }
                    }
                    .padding(12)
                    .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                }
            }
        }
    }
}
