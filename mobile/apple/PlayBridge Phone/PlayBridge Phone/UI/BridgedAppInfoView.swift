import SwiftUI

/// Local installation settings; website storage and casting grants are not modified.
struct BridgedAppInfoView: View {
    let app: BridgedApp
    @EnvironmentObject private var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var homeURL: String
    @State private var showRemove = false
    @State private var saveFailed = false

    init(app: BridgedApp) {
        self.app = app
        _name = State(initialValue: app.name)
        _homeURL = State(initialValue: app.startURL.absoluteString)
    }

    private var edited: BridgedApp? { app.editing(name: name, homeURL: homeURL) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                        .accessibilityIdentifier("bridged-app-name")
                    TextField("Home URL", text: $homeURL, axis: .vertical)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("bridged-app-home-url")
                } header: {
                    Text("Bridged App")
                } footer: {
                    Text("After a fresh PlayBridge launch, this app opens at its home URL. Use a URL on the same website origin and a name of 1–60 characters.")
                }
                Section("Website origin") {
                    Text(app.origin.absoluteString).textSelection(.enabled)
                }
                if edited == nil || saveFailed {
                    Text(saveFailed ? "This app could not be saved. It may have been removed." : "Enter a name of 1–60 characters and a valid home URL on this website origin.")
                        .foregroundStyle(.red)
                }
                Section {
                    Button("Remove Bridged App", role: .destructive) { showRemove = true }
                } footer: {
                    Text("Removal closes the app session. Website data and casting permissions are managed separately.")
                }
            }
            .navigationTitle("Bridged App Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if store.editBridgedApp(app, name: name, homeURL: homeURL) { dismiss() }
                        else { saveFailed = true }
                    }.disabled(edited == nil)
                }
            }
            .alert("Remove Bridged App?", isPresented: $showRemove) {
                Button("Remove", role: .destructive) { store.removeBridgedApp(app); dismiss() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Its dashboard tile and app session will be removed. Website data and casting permissions are kept.")
            }
        }
    }
}
