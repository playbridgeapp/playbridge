import SwiftUI

struct AtvAppLaunchSheet: View {
    let launch: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var other = ""
    static let apps = [("YouTube", "com.google.android.youtube.tv"), ("Netflix", "com.netflix.ninja"),
        ("Prime Video", "com.amazon.amazonvideo.livingroom"), ("Disney+", "com.disney.disneyplus"),
        ("Plex", "com.plexapp.android"), ("PlayBridge", "com.playbridge.player")]
    var body: some View {
        NavigationStack {
            Form {
                Section("Apps") {
                    ForEach(Self.apps, id: \.1) { label, package in Button(label) { launch(package); dismiss() } }
                }
                Section("Other app") {
                    TextField("App package or URI", text: $other)
                        .autocorrectionDisabled().textInputAutocapitalization(.never)
                        .accessibilityLabel("App package or URI")
                    Button("Launch") { launch(other.trimmingCharacters(in: .whitespacesAndNewlines)); dismiss() }
                        .disabled(other.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || other.utf8.count > 4096)
                }
            }.navigationTitle("Open app").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}
