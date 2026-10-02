import SwiftUI
import WebKit

struct BrowserMediaDetectionSheet: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var advanced = false

    private func option(_ label: String, _ key: WritableKeyPath<BrowserMediaDetectionSettings, Bool>,
                        enabled: Bool = true) -> some View {
        Toggle(label, isOn: Binding(
            get: { store.mediaDetectionSettings[keyPath: key] },
            set: { store.mediaDetectionSettings[keyPath: key] = $0 }
        )).disabled(!enabled)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    option("Automatic detection", \.enabled)
                } footer: {
                    Text("Applies to all browser tabs. Website cast buttons remain available.")
                }
                Section {
                    option("Video detection", \.videos, enabled: store.mediaDetectionSettings.enabled)
                    option("Images", \.images, enabled: store.mediaDetectionSettings.enabled)
                    option("Audio", \.audio, enabled: store.mediaDetectionSettings.enabled)
                    option("Subtitles", \.subtitles, enabled: store.mediaDetectionSettings.enabled)
                }
                Section {
                    DisclosureGroup("Advanced", isExpanded: $advanced) {
                        option("Page scanning", \.domScanning, enabled: store.mediaDetectionSettings.enabled)
                        option("Network detection", \.networkDetection, enabled: store.mediaDetectionSettings.enabled)
                        option("Response scanning", \.responseScanning, enabled: store.mediaDetectionSettings.enabled)
                        option("Scan on page changes", \.navigationRescans, enabled: store.mediaDetectionSettings.enabled)
                        option("Keep page visible", \.visibilityOverrides, enabled: store.mediaDetectionSettings.enabled)
                        option("Detect on bridged sites", \.detectInBridgedSites, enabled: store.mediaDetectionSettings.enabled)
                    }
                } footer: {
                    Text("Changes apply immediately. Reload to rediscover earlier network requests. Detection on bridged sites is off by default.")
                }
            }
            .navigationTitle("Media detect").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.large])
    }
}

struct BrowserSiteSettingsSheet: View {
    @ObservedObject var tab: BrowserTab
    @Environment(\.dismiss) private var dismiss
    @State private var allowPopups = false
    @State private var showPermissions = false
    private var url: URL? { URL(string: tab.urlString) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(BrowserSitePolicy.origin(url) ?? "Open a website to view its settings")
                    Toggle("Allow popups for this site", isOn: Binding(
                        get: { allowPopups },
                        set: { allowPopups = $0; BrowserSitePolicy.setPopupsAllowed($0, url: url) }
                    )).disabled(BrowserSitePolicy.origin(url) == nil)
                }
                Section {
                    Button("Website casting permissions") { showPermissions = true }
                } footer: { Text("Review or reset websites allowed to start a cast.") }
            }
            .navigationTitle("Site Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onAppear { allowPopups = BrowserSitePolicy.popupsAllowed(url) }
        .onChange(of: tab.urlString) { _ in allowPopups = BrowserSitePolicy.popupsAllowed(url) }
        .sheet(isPresented: $showPermissions) { PageCastPermissionsView() }
        .presentationDetents([.medium, .large])
    }
}

struct BrowserAppSettingsSheet: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var showDetection = false
    @State private var showSite = false
    var body: some View {
        NavigationStack {
            List {
                Button("Media detect") { showDetection = true }
                Button("Site Settings") { showSite = true }
            }
            .navigationTitle("App Settings").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .sheet(isPresented: $showDetection) { BrowserMediaDetectionSheet(store: store) }
        .sheet(isPresented: $showSite) { BrowserSiteSettingsSheet(tab: tab) }
        .presentationDetents([.medium, .large])
    }
}

/// WebKit groups website storage separately from caches. App-managed permissions
/// are listed explicitly; iOS camera/microphone/location grants remain in Settings.
enum BrowserDataCategory: String, CaseIterable, Identifiable {
    case tabs, history, websiteData, cache, permissions, downloads
    var id: Self { self }
    var label: String {
        switch self {
        case .tabs: return "Open browser tabs"
        case .history: return "Browsing history"
        case .websiteData: return "Cookies and site data"
        case .cache: return "Cached images and files"
        case .permissions: return "Website casting and popup permissions"
        case .downloads: return "Downloads"
        }
    }
    var detail: String {
        switch self {
        case .tabs: return "Closes regular browser tabs. Installed bridged apps stay available."
        case .history: return "Clears the browser's visit history."
        case .websiteData: return "Clears cookies and website storage, including bridged apps. You may be logged out."
        case .cache: return "Frees cached website resources without clearing cookies."
        case .permissions: return "Websites will ask before casting again. Active website links end. Device permissions stay in iOS Settings."
        case .downloads: return "Cancels active downloads and removes PlayBridge's downloaded files. Files exported to Files stay available."
        }
    }

    static var cacheTypes: Set<String> {
        [WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache, WKWebsiteDataTypeFetchCache]
    }
    static func websiteTypes(for selection: Set<Self>) -> Set<String> {
        var types = Set<String>()
        if selection.contains(.cache) { types.formUnion(cacheTypes) }
        if selection.contains(.websiteData) { types.formUnion(WKWebsiteDataStore.allWebsiteDataTypes().subtracting(cacheTypes)) }
        return types
    }
}

struct BrowserClearDataSheet: View {
    @ObservedObject var store: BrowserStore
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<BrowserDataCategory> = [.history, .websiteData, .cache]
    @State private var confirming = false
    @State private var clearing = false

    var body: some View {
        NavigationStack {
            Form {
                ForEach(BrowserDataCategory.allCases) { category in
                    Toggle(isOn: Binding(
                        get: { selected.contains(category) },
                        set: { if $0 { selected.insert(category) } else { selected.remove(category) } }
                    )) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(category.label)
                            Text(category.detail).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    Button(clearing ? "Clearing…" : "Clear Browsing Data", role: .destructive) { confirming = true }
                        .disabled(selected.isEmpty || clearing)
                }
            }
            .disabled(clearing)
            .navigationTitle("Clear Browsing Data").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Cancel") { dismiss() }.disabled(clearing) } }
            .alert("Clear selected browsing data?", isPresented: $confirming) {
                Button("Clear", role: .destructive) { clear() }
                Button("Cancel", role: .cancel) {}
            } message: { Text(selected.map(\.label).sorted().joined(separator: "\n")) }
        }
        .interactiveDismissDisabled(clearing)
        .presentationDetents([.large])
    }

    private func clear() {
        clearing = true
        if selected.contains(.history) { store.data.clearHistory() }
        if selected.contains(.tabs) { store.closeTabs(Set(store.browserTabs.map(\.id))) }
        if selected.contains(.permissions) {
            BrowserSitePolicy.clearPopupPermissions()
            PageCastPermissions.shared.clear()
        }
        if selected.contains(.downloads) { store.downloads.clear() }
        let types = BrowserDataCategory.websiteTypes(for: selected)
        guard !types.isEmpty else { dismiss(); return }
        WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {
            DispatchQueue.main.async { dismiss() }
        }
    }
}
