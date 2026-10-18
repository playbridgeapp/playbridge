import SwiftUI

struct AdblockSettingsSheet: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss

    @AppStorage("pb_block_popups") private var blockPopups = true
    @State private var filterLists: [URL] = ContentBlocker.filterListURLs
    @State private var disabledLists: Set<URL> = Set(ContentBlocker.filterListURLs.filter { !ContentBlocker.isListEnabled(url: $0) })
    @State private var newUrlString: String = ""
    @State private var isUpdating = false
    @State private var updateMessage = ""
    @State private var showResultAlert = false
    @State private var rulesCountText = ""
    @State private var userRules: [ContentBlocker.UserCosmeticRule] = ContentBlocker.userRules()
    @State private var blockedSources: [String] = ContentBlocker.userBlockedDomains()
    @State private var sitesOff: [String] = AdblockSiteSettings.disabledSites()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("Enable Adblock", isOn: Binding(
                        get: { store.adBlockEnabled },
                        set: { _ in store.toggleAdBlock() }
                    ))
                    .tint(Theme.primary)
                    Toggle("Block pop-ups", isOn: $blockPopups).tint(Theme.primary)
                } header: {
                    Text("Adblock Status")
                }

                if !sitesOff.isEmpty {
                    Section {
                        ForEach(sitesOff, id: \.self) { site in
                            Text(site).font(Theme.font(size: 13)).foregroundColor(Theme.onSurface)
                        }
                        .onDelete { offsets in
                            offsets.map { sitesOff[$0] }.forEach { AdblockSiteSettings.setDisabled(false, site: $0) }
                            sitesOff = AdblockSiteSettings.disabledSites()
                        }
                    } header: {
                        Text("Sites with ad blocking off")
                    } footer: {
                        Text("Swipe left to turn ad blocking back on for a site.")
                    }
                }

                if !userRules.isEmpty {
                    Section {
                        ForEach(userRules) { rule in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(rule.domain.isEmpty ? "All sites" : rule.domain)
                                    .font(Theme.font(size: 13, weight: .medium)).foregroundColor(Theme.onSurface)
                                Text(rule.selector)
                                    .font(Theme.font(size: 11, design: .monospaced)).foregroundColor(Theme.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                        }
                        .onDelete { offsets in
                            offsets.map { userRules[$0].id }.forEach { ContentBlocker.removeUserRule($0) }
                            userRules = ContentBlocker.userRules()
                            Task { await store.recompileAndApply() }
                        }
                    } header: {
                        Text("Blocked elements")
                    } footer: {
                        Text("Elements you blocked with the picker. Swipe to delete.")
                    }
                }

                if !blockedSources.isEmpty {
                    Section {
                        ForEach(blockedSources, id: \.self) { host in
                            Text(host).font(Theme.font(size: 13)).foregroundColor(Theme.onSurface)
                        }
                        .onDelete { offsets in
                            offsets.map { blockedSources[$0] }.forEach { ContentBlocker.removeUserBlockedDomain($0) }
                            blockedSources = ContentBlocker.userBlockedDomains()
                            Task { await store.recompileAndApply() }
                        }
                    } header: {
                        Text("Blocked sources")
                    } footer: {
                        Text("Image/resource domains you blocked from the picker. Swipe to delete.")
                    }
                }

                Section {
                    ForEach(filterLists, id: \.self) { url in
                        HStack(alignment: .top, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(getListName(for: url))
                                    .font(Theme.font(size: 15, weight: .medium))
                                    .foregroundColor(isEnabled(url) ? Theme.onSurface : Theme.onSurfaceVariant)
                                Text(url.absoluteString)
                                    .font(Theme.font(size: 11))
                                    .foregroundColor(Theme.onSurfaceVariant)
                                    .lineLimit(1)
                                Text(status(for: url))
                                    .font(Theme.font(size: 11, weight: .semibold))
                                    .foregroundColor(statusColor(for: url))
                                    .padding(.top, 2)
                                if isEnabled(url), let date = ContentBlocker.lastUpdated(url: url) {
                                    Text("Updated \(date.formatted(date: .abbreviated, time: .shortened))")
                                        .font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant)
                                }
                            }
                            Spacer(minLength: 0)
                            Toggle(getListName(for: url), isOn: Binding(
                                get: { isEnabled(url) },
                                set: { setEnabled($0, url: url) }
                            ))
                            .labelsHidden()
                            .tint(Theme.primary)
                            .disabled(isUpdating)
                        }
                    }
                    .onDelete(perform: deleteList)
                    if !missingDefaults.isEmpty {
                        Button {
                            restoreDefaults()
                        } label: {
                            Label("Restore default lists (\(missingDefaults.count))", systemImage: "arrow.uturn.backward")
                                .font(Theme.font(size: 14, weight: .medium))
                                .foregroundColor(Theme.primary)
                        }
                        .disabled(isUpdating)
                    }
                } header: {
                    Text("Filter Lists")
                } footer: {
                    Text("Turn lists on or off. Swipe left to remove a list.")
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
                        .font(Theme.font(size: 15, weight: .semibold))
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

    private func isEnabled(_ url: URL) -> Bool { !disabledLists.contains(url) }

    private func status(for url: URL) -> String {
        if !isEnabled(url) { return "Off" }
        if ContentBlocker.listUpdateFailed(url: url) { return "Couldn’t update" }
        return ContentBlocker.isListDownloaded(url: url) ? "On" : "Downloads on next update"
    }

    private func statusColor(for url: URL) -> Color {
        if !isEnabled(url) { return Theme.onSurfaceVariant }
        return !ContentBlocker.listUpdateFailed(url: url) && ContentBlocker.isListDownloaded(url: url)
            ? Color(hex: 0x4CAF50) : Color(hex: 0xFFA000)
    }

    private func setEnabled(_ enabled: Bool, url: URL) {
        if enabled { disabledLists.remove(url) } else { disabledLists.insert(url) }
        ContentBlocker.setListEnabled(enabled, url: url)
        Task {
            // A list switched on for the first time needs its file before compiling.
            if enabled && !ContentBlocker.isListDownloaded(url: url) {
                try? await ContentBlocker.download(url: url)
            }
            await store.recompileAndApply()
        }
    }

    private func getListName(for url: URL) -> String {
        let absoluteString = url.absoluteString.lowercased()
        if absoluteString.contains("easyprivacy.txt") {
            return "EasyPrivacy"
        } else if absoluteString.contains("easylist.txt") {
            return "EasyList"
        } else if absoluteString.contains("antiadblockfilters.txt") {
            return "Adblock Warning Removal"
        } else if absoluteString.contains("fanboy-annoyance.txt") {
            return "Website Annoyances"
        }
        let known = ["filters.txt": "uBlock filters", "badware.txt": "uBlock badware", "privacy.txt": "uBlock privacy",
                     "quick-fixes.txt": "uBlock quick fixes", "unbreak.txt": "uBlock unbreak",
                     "urlhaus-filter-online.txt": "URLhaus online"]
        if let name = known[url.lastPathComponent] { return name }
        if url.host == "pgl.yoyo.org" { return "Peter Lowe’s Ad and tracking list" }
        if url.host == "playbridge.app" { return "PlayBridge Extra Rules" }
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
            Task {
                try? await ContentBlocker.download(url: url)
                await store.recompileAndApply()
            }
        }
    }

    private var missingDefaults: [URL] {
        ContentBlocker.defaultFilterListURLs.filter { !filterLists.contains($0) }
    }

    private func restoreDefaults() {
        let missing = missingDefaults
        filterLists += missing
        ContentBlocker.filterListURLs = filterLists
        Task {
            for url in missing where !ContentBlocker.isListDownloaded(url: url) {
                try? await ContentBlocker.download(url: url)
            }
            await store.recompileAndApply()
        }
    }

    private func deleteList(at offsets: IndexSet) {
        let removed = offsets.map { filterLists[$0] }
        filterLists.remove(atOffsets: offsets)
        ContentBlocker.filterListURLs = filterLists
        for url in removed {
            disabledLists.remove(url)
            ContentBlocker.setListEnabled(true, url: url)
        }
        Task { await store.recompileAndApply() }
    }

    private func updateFilterLists() {
        isUpdating = true
        Task {
            var successCount = 0
            var failCount = 0
            
            for url in filterLists where isEnabled(url) {
                do {
                    try await ContentBlocker.download(url: url)
                    successCount += 1
                } catch {
                    failCount += 1
                }
            }

            do {
                try await store.forceUpdateAdBlockRules()
                
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
