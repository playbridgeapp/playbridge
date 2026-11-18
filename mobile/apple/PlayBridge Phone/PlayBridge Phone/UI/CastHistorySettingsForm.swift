import SwiftUI

/// Settings › Cast history: whether casts are kept, how many, pictures, bridged apps, and the
/// TV's own history. Also opened from the history screen.
struct CastHistorySettingsForm: View {
    @ObservedObject var store: CastHistoryStore
    @AppStorage(CastHistorySettings.enabledKey) private var enabled = true
    @AppStorage(CastHistorySettings.limitKey) private var limit = CastHistorySettings.defaultLimit
    @AppStorage(CastHistorySettings.keepPreviewsKey) private var keepPreviews = true
    @AppStorage(CastHistorySettings.rememberPositionKey) private var rememberPosition = true
    @AppStorage(CastHistorySettings.checkLinksKey) private var checkLinks = false
    @AppStorage(CastHistorySettings.excludeBridgedKey) private var excludeBridged = true
    @AppStorage("cast_prevent_receiver_history") private var preventReceiverHistory = false
    @State private var pendingLimit: Int?
    @State private var confirmClear = false
    @State private var confirmPictures = false

    var body: some View {
        Form {
            Section {
                Toggle("Keep cast history", isOn: $enabled).tint(Theme.primary)
            } footer: {
                Text("Off: new casts aren't remembered and can't be cast again from history. What's already kept stays until you clear it.")
            }
            Section {
                Picker("Keep the newest", selection: Binding(get: { CastHistorySettings.coerceLimit(limit) }, set: choose)) {
                    ForEach(CastHistorySettings.limits, id: \.self) { Text("\($0) casts").tag($0) }
                }
                Toggle("Remember where you stopped", isOn: $rememberPosition).tint(Theme.primary)
                Toggle("Keep preview pictures", isOn: $keepPreviews).tint(Theme.primary)
                    .onChange(of: keepPreviews) { _ in store.reconcile() }
                Toggle("Leave out bridged apps", isOn: $excludeBridged).tint(Theme.primary)
                Toggle("Check if links still work", isOn: $checkLinks).tint(Theme.primary)
            } footer: {
                Text("While the phone is connected, where a cast stopped is saved about every 10 seconds and when it pauses or stops, so it can resume. Pictures are the ones the cast sheet already made; nothing extra is downloaded. Casts started by a bridged app, or by a website PlayBridge can't place, aren't remembered: no row, no picture and nothing to cast again. Checking a link asks its site when you open the cast, sending its saved sign-in only to that same site; home-network links aren't checked.")
            }
            .disabled(!enabled)
            Section {
                Toggle("Ask TVs not to keep a history", isOn: $preventReceiverHistory).tint(Theme.primary)
            } footer: {
                Text("Applies to future casts on PlayBridge TVs that support it. Other devices may ignore it. Their existing history is unchanged.")
            }
            Section {
                let storage = store.storage
                LabeledContent("Kept now", value: CastHistoryPresentation.storageLine(casts: storage.casts, pictures: storage.pictures)
                    + (storage.pictures > 0 ? " (\(CastHistoryPresentation.bytes(storage.bytes)))" : ""))
                Button("Remove pictures", role: .destructive) { confirmPictures = true }
                    .disabled(storage.pictures == 0)
                Button("Clear history", role: .destructive) { confirmClear = true }
                    .disabled(storage.casts == 0)
            }
        }
        .confirmationDialog(pendingLimit.map { "Remove the \(store.removedBy(limit: $0)) oldest casts?" } ?? "",
                            isPresented: Binding(get: { pendingLimit != nil }, set: { if !$0 { pendingLimit = nil } }),
                            titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let pendingLimit { store.setLimit(pendingLimit); limit = pendingLimit }
                pendingLimit = nil
            }
        } message: {
            Text("History keeps the newest \(pendingLimit ?? limit) casts.")
        }
        .confirmationDialog("Remove all preview pictures?", isPresented: $confirmPictures, titleVisibility: .visible) {
            Button("Remove pictures", role: .destructive) { store.removeAllPreviews() }
        }
        .confirmationDialog("Clear all cast history?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear history", role: .destructive) { store.clear() }
        } message: {
            Text("Every kept cast and picture is removed from this phone.")
        }
    }

    /// Lowering the limit below what's kept asks first and says how many go.
    private func choose(_ next: Int) {
        if store.removedBy(limit: next) > 0 { pendingLimit = next }
        else { store.setLimit(next); limit = next }
    }
}
