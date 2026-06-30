import SwiftUI

struct AdblockSettingsSheet: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss

    @State private var filterLists: [URL] = ContentBlocker.filterListURLs
    @State private var newUrlString: String = ""
    @State private var isUpdating = false
    @State private var updateMessage = ""
    @State private var showResultAlert = false
    @State private var rulesCountText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Enable Adblock", isOn: Binding(
                        get: { store.adBlockEnabled },
                        set: { _ in store.toggleAdBlock() }
                    ))
                    .tint(Theme.primary)
                } header: {
                    Text("Adblock Status")
                }

                Section {
                    if let extra = ContentBlocker.remoteExtraListURL {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text("PlayBridge Extra Rules")
                                    .font(.system(size: 15, weight: .medium))
                                    .foregroundColor(Theme.onSurface)
                                Spacer()
                                Text("Built-in")
                                    .font(.system(size: 10, weight: .bold))
                                    .foregroundColor(Theme.primary)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Theme.primary.opacity(0.15))
                                    .clipShape(Capsule())
                            }
                            Text(extra.absoluteString)
                                .font(.system(size: 11))
                                .foregroundColor(Theme.onSurfaceVariant)
                                .lineLimit(1)
                            Text(ContentBlocker.isListDownloaded(url: extra) ? "Active" : "Downloads on launch")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(ContentBlocker.isListDownloaded(url: extra) ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000))
                                .padding(.top, 2)
                        }
                    }
                } header: {
                    Text("Built-in list")
                } footer: {
                    Text("Always on and updated automatically. Maintained by PlayBridge — can't be removed.")
                }

                Section {
                    ForEach(filterLists, id: \.self) { url in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(getListName(for: url))
                                .font(.system(size: 15, weight: .medium))
                                .foregroundColor(Theme.onSurface)
                            Text(url.absoluteString)
                                .font(.system(size: 11))
                                .foregroundColor(Theme.onSurfaceVariant)
                                .lineLimit(1)
                            
                            HStack {
                                Text(ContentBlocker.isListDownloaded(url: url) ? "Downloaded" : "Not downloaded")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundColor(ContentBlocker.isListDownloaded(url: url) ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000))
                            }
                            .padding(.top, 2)
                        }
                    }
                    .onDelete(perform: deleteList)
                } header: {
                    Text("Filter Lists")
                } footer: {
                    Text("Swipe left to delete custom lists. Default filter lists are enabled automatically.")
                }

                Section {
                    HStack {
                        TextField("https://example.com/list.txt", text: $newUrlString)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .foregroundColor(Theme.onSurface)
                        
                        Button("Add") {
                            addCustomList()
                        }
                        .disabled(newUrlString.trimmingCharacters(in: .whitespaces).isEmpty)
                        .foregroundColor(Theme.primary)
                    }
                } header: {
                    Text("Add Custom List")
                }

                Section {
                    Button {
                        updateFilterLists()
                    } label: {
                        HStack {
                            Spacer()
                            if isUpdating {
                                ProgressView()
                                    .tint(.white)
                                    .padding(.trailing, 8)
                                Text("Downloading & Compiling…")
                            } else {
                                Image(systemName: "arrow.clockwise")
                                Text("Update Filter Lists")
                            }
                            Spacer()
                        }
                        .foregroundColor(.white)
                        .font(.system(size: 15, weight: .semibold))
                    }
                    .listRowBackground(Theme.primaryDim)
                    .disabled(isUpdating)
                }
            }
            .background(Theme.surface.ignoresSafeArea())
            .scrollContentBackground(.hidden)
            .navigationTitle("Adblock settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Close") { dismiss() }
                }
            }
            .alert("Adblock Update", isPresented: $showResultAlert) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(updateMessage)
            }
        }
    }

    // MARK: - Logic

    private func getListName(for url: URL) -> String {
        let absoluteString = url.absoluteString.lowercased()
        if absoluteString.contains("easyprivacy.txt") {
            return "EasyPrivacy"
        } else if absoluteString.contains("easylist.txt") {
            return "EasyList"
        } else if absoluteString.contains("antiadblockfilters.txt") {
            return "Adblock Warning Removal"
        } else if absoluteString.contains("fanboy-annoyances.txt") || absoluteString.contains("fanboy-annoyance.txt") {
            return "Website Annoyances"
        }
        return url.host ?? "Custom Filter List"
    }

    private func addCustomList() {
        let trimmed = newUrlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), url.scheme == "http" || url.scheme == "https" else {
            updateMessage = "Invalid URL. Please enter a valid HTTP/HTTPS URL."
            showResultAlert = true
            return
        }
        
        if !filterLists.contains(url) {
            filterLists.append(url)
            ContentBlocker.filterListURLs = filterLists
            newUrlString = ""
        }
    }

    private func deleteList(at offsets: IndexSet) {
        // Prevent deleting all lists if they want, but let them delete custom ones
        filterLists.remove(atOffsets: offsets)
        ContentBlocker.filterListURLs = filterLists
    }

    private func updateFilterLists() {
        isUpdating = true
        Task {
            var successCount = 0
            var failCount = 0
            
            for url in filterLists {
                do {
                    try await ContentBlocker.download(url: url)
                    successCount += 1
                } catch {
                    print("Failed to download filter list: \(url.absoluteString), error: \(error)")
                    failCount += 1
                }
            }

            // Refresh the always-on built-in list too.
            if let extra = ContentBlocker.remoteExtraListURL {
                try? await ContentBlocker.download(url: extra)
            }

            do {
                let lists = try await ContentBlocker.forceCompileAll()
                await store.updateAdBlockRules()
                
                await MainActor.run {
                    isUpdating = false
                    updateMessage = "Successfully updated \(successCount) list(s)."
                    if failCount > 0 {
                        updateMessage += " Failed to update \(failCount) list(s)."
                    }
                    updateMessage += " Filter rules compiled successfully."
                    showResultAlert = true
                }
            } catch {
                await MainActor.run {
                    isUpdating = false
                    let detailedError = ContentBlocker.lastCompilationError ?? error.localizedDescription
                    updateMessage = "Failed to compile filter rules: \(detailedError)"
                    showResultAlert = true
                }
            }
        }
    }
}
