import SwiftUI

struct BrowserNetworkLogView: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @ObservedObject private var log: BrowserNetworkLog
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var filter: NetworkLogFilter = .all
#if PB_DEVELOPER_TOOLS
    @AppStorage(BrowserDeveloperTools.key) private var developerTools = false
#endif
    @State private var domains = ContentBlocker.userBlockedDomains()
    @State private var selectedDomain = ""
    @State private var removing = false
    @State private var confirm = false
    @State private var applying = false
    @State private var failure: String?

    init(tab: BrowserTab, store: BrowserStore) {
        self.tab = tab; self.store = store; self.log = tab.networkLog
    }

    private var entries: [BrowserNetworkLog.Entry] {
        log.entries.reversed().filter { filter.matches($0) &&
            (search.isEmpty || "\($0.url) \($0.kind) \($0.state)".localizedCaseInsensitiveContains(search)) }
    }
    /// All, then each group that has requests (plus the selected one), like Safari's Network tab.
    private var chips: [(filter: NetworkLogFilter, count: Int)] {
        NetworkLogFilter.allCases.compactMap { chip in
            let count = chip == .all ? log.entries.count : log.entries.filter(chip.matches).count
            return chip == .all || count > 0 || chip == filter ? (chip, count) : nil
        }
    }
    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(chips, id: \.filter) { chip in
                    let selected = chip.filter == filter
                    Button { filter = chip.filter } label: {
                        Text("\(chip.filter.title) \(chip.count)")
                            .font(Theme.font(size: 13, weight: selected ? .semibold : .regular))
                            .foregroundColor(selected ? Theme.surface : Theme.onSurface)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(selected ? Theme.primary : Theme.surfaceContainerHigh, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(chip.filter.title), \(chip.count) requests")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                    .accessibilityIdentifier("network-filter-\(chip.filter.title)")
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
        .background(Theme.surface)
    }
    private func blockedDomain(_ host: String) -> String? {
        domains.first { NavigationAdRules.host(host, matches: $0) }
    }
    private func choose(_ entry: BrowserNetworkLog.Entry) {
        let blocked = blockedDomain(entry.host)
        selectedDomain = blocked ?? entry.host
        removing = blocked != nil
        confirm = true
    }

    private func applyDomain(_ domain: String, removing: Bool) {
        let previousIdentifier = ContentBlocker.userDomainRuleIdentifier
        if removing { ContentBlocker.removeUserBlockedDomain(domain) }
        else if !ContentBlocker.addUserBlockedDomain(domain) { failure = "This domain could not be added."; return }
        domains = ContentBlocker.userBlockedDomains()
        applying = true; failure = nil
        Task { @MainActor in
            do { try await store.updateUserDomainRules(replacing: previousIdentifier) }
            catch {
                if removing { ContentBlocker.addUserBlockedDomain(domain) }
                else { ContentBlocker.removeUserBlockedDomain(domain) }
                domains = ContentBlocker.userBlockedDomains()
                failure = "The domain rule could not be applied. Please try again."
            }
            applying = false
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Requests observed in this tab, including frames and navigation events. WebKit may omit some blocked, cached, or background requests. Full URLs are kept only in memory on this phone; headers and bodies are not recorded.")
                        .font(Theme.font(.caption)).foregroundStyle(.secondary)
                    if tab.networkCaptureEnabled {
                        Button("Stop detailed capture") { tab.setNetworkCaptureEnabled(false) }
                        Text("Stopping capture reloads this page. Recorded entries remain until cleared or the tab closes.")
                            .font(Theme.font(.caption)).foregroundStyle(.secondary)
                    } else {
                        Button("Start detailed capture and reload") { tab.setNetworkCaptureEnabled(true) }
                        Text("Detailed fetch, XHR, and resource capture is off during normal browsing. Starting it reloads this page.")
                            .font(Theme.font(.caption)).foregroundStyle(.secondary)
                    }
                    if !store.adBlockEnabled {
                        Button("Enable ad blocking") { store.toggleAdBlock() }
                    }
                    if applying { ProgressView("Applying domain rules…") }
                    if let failure { Text(failure).foregroundStyle(.red) }
                } footer: {
                    Text("Latest \(log.entries.count) events\(log.discarded > 0 ? "; \(log.discarded) older events discarded" : ""). Kept in memory until cleared or this tab closes.")
                }
                ForEach(entries) { entry in
                    NavigationLink {
                        Form {
                            Section("Request") {
                                Text(entry.url).font(.footnote.monospaced()).textSelection(.enabled)
                                LabeledContent("Type", value: entry.kind)
                                if !entry.method.isEmpty { LabeledContent("Method", value: entry.method) }
                                LabeledContent("State", value: entry.state)
                                if let status = entry.status { LabeledContent("HTTP status", value: String(status)) }
                                Text(entry.date.formatted(date: .omitted, time: .standard))
                            }
                            Section("Page / frame") {
                                LabeledContent("Context", value: entry.isSubframe ? "Embedded frame" : "Main page")
                                Text(entry.page).font(Theme.font(.footnote)).textSelection(.enabled)
                            }
                            Section {
                                NetworkDomainButton(domain: blockedDomain(entry.host) ?? entry.host,
                                    removing: blockedDomain(entry.host) != nil, applying: applying, action: applyDomain)
                                if applying { ProgressView("Applying domain rules…") }
                                if let failure { Text(failure).foregroundStyle(.red) }
                                Button("Copy URL") { UIPasteboard.general.string = entry.url }
                            } footer: { Text("Domain rules apply to that host and its subdomains on all pages. Reload the page to retry existing resources.") }
                        }.navigationTitle("Request details")
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(entry.host).font(Theme.font(.subheadline).weight(.medium))
                                Spacer()
                                if blockedDomain(entry.host) != nil { Image(systemName: "shield.slash").accessibilityLabel("Domain blocked") }
                            }
                            Text(entry.url).font(Theme.font(.caption)).lineLimit(2).foregroundStyle(.secondary)
                            Text("\(entry.isSubframe ? "Frame · " : "")\(entry.method) \(entry.kind) · \(entry.status.map { "HTTP \($0)" } ?? entry.state)")
                                .font(Theme.font(.caption2)).foregroundStyle(.secondary)
                        }
                    }
                    .contextMenu {
                        Button(blockedDomain(entry.host).map { "Unblock \($0)" } ?? "Block \(entry.host)") { choose(entry) }
                            .disabled(applying)
                        Button("Copy URL") { UIPasteboard.general.string = entry.url }
                    }
                }
                if entries.isEmpty { Text(search.isEmpty && filter == .all ? "No requests observed yet. Reload the page to capture activity." : "No matching requests.").foregroundStyle(.secondary) }
            }
            .safeAreaInset(edge: .top, spacing: 0) { filterBar }
            .searchable(text: $search, prompt: "Filter domain, URL or type")
            .navigationTitle("Network logs")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button("Reload page") { tab.reload() }.disabled(applying)
                        Button("Copy log (URLs redacted)") { UIPasteboard.general.string = log.exportText }
#if PB_DEVELOPER_TOOLS
                        if developerTools {
                            Button("Copy full log (may contain private tokens)") { UIPasteboard.general.string = log.fullExportText }
                        }
#endif
                        Button("Clear log", role: .destructive) { log.clear() }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .confirmationDialog("\(removing ? "Unblock" : "Block") \(selectedDomain)?", isPresented: $confirm, titleVisibility: .visible) {
                Button(removing ? "Unblock domain" : "Block domain", role: removing ? nil : .destructive) {
                    applyDomain(selectedDomain, removing: removing)
                }
            } message: { Text("Applies to this domain and its subdomains on all pages. Existing loaded resources remain until you reload.") }
        }
    }
}

/// The confirmation belongs to the pushed details view, not the hidden list.
private struct NetworkDomainButton: View {
    let domain: String
    let removing: Bool
    let applying: Bool
    let action: (String, Bool) -> Void
    @State private var confirm = false

    var body: some View {
        Button("\(removing ? "Unblock" : "Block") \(domain)") { confirm = true }
            .accessibilityIdentifier("network-domain-action")
            .disabled(applying)
            .confirmationDialog("\(removing ? "Unblock" : "Block") \(domain)?", isPresented: $confirm, titleVisibility: .visible) {
                Button(removing ? "Unblock domain" : "Block domain", role: removing ? nil : .destructive) { action(domain, removing) }
            } message: { Text("Applies to this domain and its subdomains on all pages. Existing loaded resources remain until you reload.") }
    }
}

/// Filter chips for the network log: All, each request group, and Blocked.
enum NetworkLogFilter: Hashable, CaseIterable {
    case all, category(BrowserNetworkLog.Category), blocked
    static var allCases: [NetworkLogFilter] { [.all] + BrowserNetworkLog.Category.allCases.map { .category($0) } + [.blocked] }
    var title: String {
        switch self {
        case .all: return "All"
        case .category(let category): return category.rawValue
        case .blocked: return "Blocked"
        }
    }
    func matches(_ entry: BrowserNetworkLog.Entry) -> Bool {
        switch self {
        case .all: return true
        case .category(let category): return entry.category == category
        case .blocked: return entry.isBlocked
        }
    }
}
