import SwiftUI

/// Lists the text files in the app bundle's Licenses folder.
struct OpenSourceLicencesScreen: View {
    @Environment(\.dismiss) private var dismiss
    @State private var files: [URL] = []
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Text("PlayBridge's own code is licensed under MPL-2.0. The text is in this list.")
                        .font(Theme.font(size: 15))
                        .foregroundColor(Theme.onSurface)
                        .listRowBackground(Theme.surfaceContainer)
                    Link(destination: URL(string: "https://github.com/playbridgeapp/playbridge")!) {
                        Text("github.com/playbridgeapp/playbridge")
                            .font(Theme.font(size: 15))
                    }
                    .listRowBackground(Theme.surfaceContainer)
                    Text("Version \(versionLabel)")
                        .font(Theme.font(size: 15))
                        .foregroundColor(Theme.onSurfaceVariant)
                        .listRowBackground(Theme.surfaceContainer)
                }
                Section("Bundled notices") {
                    if files.isEmpty {
                        Text("No licence files were found in this build.")
                            .font(Theme.font(size: 15))
                            .foregroundColor(Theme.onSurfaceVariant)
                            .listRowBackground(Theme.surfaceContainer)
                    } else {
                        ForEach(files, id: \.lastPathComponent) { url in
                            Button {
                                path.append(url.lastPathComponent)
                            } label: {
                                HStack {
                                    Text(url.lastPathComponent)
                                    Spacer()
                                    Image(systemName: "chevron.right")
                                        .font(Theme.font(size: 13, weight: .semibold))
                                        .foregroundColor(Theme.onSurfaceVariant)
                                }
                            }
                            .font(Theme.font(size: 15))
                            .listRowBackground(Theme.surfaceContainer)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface)
            .navigationTitle("Open source licences")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { name in
                if let url = files.first(where: { $0.lastPathComponent == name }) {
                    PhoneLicenceText(url: url)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .toolbarBackground(Theme.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
        .tint(Theme.primary)
        .onAppear { files = BundledLicenceFiles.urls() }
    }

    private var versionLabel: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

private struct PhoneLicenceText: View {
    let url: URL
    @State private var text = ""

    var body: some View {
        ScrollView {
            Text(text)
                .font(Theme.font(size: 12, design: .monospaced))
                .foregroundColor(Theme.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .textSelection(.enabled)
        }
        .background(Theme.surface)
        .navigationTitle(url.lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if text.isEmpty { text = BundledLicenceFiles.text(at: url) }
        }
    }
}

enum BundledLicenceFiles {
    static func urls() -> [URL] {
        var found: [URL] = []
        if let listed = Bundle.main.urls(forResourcesWithExtension: "txt", subdirectory: "Licenses") {
            found.append(contentsOf: listed)
        }
        if let root = Bundle.main.resourceURL {
            let folder = root.appendingPathComponent("Licenses", isDirectory: true)
            if let listed = try? FileManager.default.contentsOfDirectory(
                at: folder,
                includingPropertiesForKeys: nil,
                options: [.skipsHiddenFiles]
            ) {
                found.append(contentsOf: listed.filter { $0.pathExtension.lowercased() == "txt" })
            }
        }
        if found.isEmpty, let root = Bundle.main.resourceURL,
           let listed = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
           ) {
            found.append(contentsOf: listed.filter { $0.pathExtension.lowercased() == "txt" })
        }
        var seen = Set<String>()
        return found
            .filter { seen.insert($0.lastPathComponent).inserted }
            .sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }

    static func text(at url: URL) -> String {
        if let text = try? String(contentsOf: url, encoding: .utf8) { return text }
        if let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .isoLatin1) {
            return text
        }
        return "This file could not be read."
    }
}
