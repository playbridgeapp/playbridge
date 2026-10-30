import SwiftUI

/// Web app details: local installation settings; website storage and casting grants are not modified.
struct BridgedAppInfoView: View {
    let app: BridgedApp
    @EnvironmentObject private var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var homeURL: String
    @State private var tileColorID: String?
    @State private var showRemove = false
    @State private var saveFailed = false

    init(app: BridgedApp) {
        self.app = app
        _name = State(initialValue: app.name)
        _homeURL = State(initialValue: app.startURL.absoluteString)
        _tileColorID = State(initialValue: BridgedAppAppearance.paletteID(app.tileColorID))
    }

    private var edited: BridgedApp? { app.editing(name: name, homeURL: homeURL, tileColorID: tileColorID) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    header
                    field("Name") {
                        TextField("Name", text: $name)
                            .accessibilityIdentifier("bridged-app-name")
                    }
                    field("Home page") {
                        TextField("Home page", text: $homeURL, axis: .vertical)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .accessibilityIdentifier("bridged-app-home-url")
                    }
                    Text("Opens here after a fresh launch. Must stay on \(app.origin.absoluteString).")
                        .font(Theme.font(size: 12))
                        .foregroundColor(Theme.onSurfaceVariant)
                    tileColorPicker
                    if edited == nil || saveFailed {
                        Text(saveFailed ? "This app could not be saved. It may have been removed."
                             : "Enter a name of 1–60 characters and a valid home page on this website.")
                            .font(Theme.font(size: 13))
                            .foregroundColor(Theme.danger)
                    }
                    HStack(spacing: 8) {
                        Button { showRemove = true } label: {
                            Label("Remove", systemImage: "trash")
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .foregroundColor(Theme.danger)
                                .overlay(Capsule().stroke(Theme.danger.opacity(0.5), lineWidth: 1))
                        }
                        Button {
                            if store.editBridgedApp(app, name: name, homeURL: homeURL, tileColorID: tileColorID) { dismiss() }
                            else { saveFailed = true }
                        } label: {
                            Text("Save")
                                .font(Theme.font(size: 15, weight: .bold))
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .foregroundColor(Theme.onPrimary)
                                .background(Capsule().fill(Theme.primary.opacity(edited == nil ? 0.4 : 1)))
                        }
                        .disabled(edited == nil)
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }
                .padding(16)
            }
            .background(Theme.surfaceContainerLow.ignoresSafeArea())
            .navigationTitle("Web app details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .alert("Remove \(app.name)?", isPresented: $showRemove) {
                Button("Remove", role: .destructive) { store.removeBridgedApp(app); dismiss() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Its dashboard tile and app session will be removed. Website data and casting permissions are kept.")
            }
        }
        .presentationDetents([.medium, .large])
    }

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 14).fill(LinearGradient(
                    colors: BridgedAppAppearance.gradient(edited ?? app).map { Color(hex: $0) },
                    startPoint: .topLeading, endPoint: .bottomTrailing))
                BridgedAppIcon(app: app).frame(width: 40, height: 40)
            }
            .frame(width: 52, height: 52)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(app.name)
                    .font(Theme.font(size: 19, weight: .bold))
                    .foregroundColor(Theme.onSurface)
                    .lineLimit(1)
                Text(app.origin.host ?? app.origin.absoluteString)
                    .font(Theme.font(size: 13))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .lineLimit(1)
                    .textSelection(.enabled)
            }
        }
    }

    private var automaticGradient: [UInt32] {
        var automatic = app
        automatic.tileColorID = nil
        return BridgedAppAppearance.gradient(automatic)
    }

    private var tileColorPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Tile color").font(Theme.font(size: 13)).foregroundColor(Theme.onSurfaceVariant)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 76))], spacing: 8) {
                colorSwatch("Automatic", stops: automaticGradient, id: nil)
                ForEach(BridgedAppAppearance.palette, id: \.id) { color in
                    colorSwatch(color.name, stops: [color.dark, color.light], id: color.id)
                }
            }
        }
    }

    private func colorSwatch(_ name: String, stops: [UInt32], id: String?) -> some View {
        Button { tileColorID = id } label: {
            VStack(spacing: 4) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8).fill(LinearGradient(
                        colors: stops.map { Color(hex: $0) }, startPoint: .topLeading, endPoint: .bottomTrailing))
                    if tileColorID == id { Image(systemName: "checkmark").foregroundColor(.white) }
                }
                .frame(width: 32, height: 32)
                Text(name).font(Theme.font(size: 11)).foregroundColor(Theme.onSurface)
            }
            .frame(maxWidth: .infinity, minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(name) tile color")
        .accessibilityAddTraits(tileColorID == id ? .isSelected : [])
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(Theme.font(size: 13))
                .foregroundColor(Theme.onSurfaceVariant)
            content()
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainer))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Theme.outlineVariant, lineWidth: 1))
        }
    }
}
