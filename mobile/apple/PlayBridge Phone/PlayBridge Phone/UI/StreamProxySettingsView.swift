import SwiftUI

struct StreamProxySettingsView: View {
    @State var configuration: RemoteProxyConfiguration
    let onSave: (RemoteProxyConfiguration) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Remote proxy") {
                    TextField("Server URL", text: $configuration.baseURL)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $configuration.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section {
                    Text("Via proxy uses your self-hosted PlayBridge stream proxy. Via phone uses the proxy built into this app.")
                        .foregroundStyle(.secondary)
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("Stream proxy")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        do {
                            try StreamProxySettingsStore.save(configuration)
                            onSave(configuration)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }
                }
            }
        }
    }
}
