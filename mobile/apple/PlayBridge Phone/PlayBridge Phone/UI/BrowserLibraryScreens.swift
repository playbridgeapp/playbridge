import SwiftUI
import WebKit

// MARK: - Browser settings

struct BrowserSettingsScreen: View {
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showClearData = false
    @State private var showMediaDetection = false
    @State private var advanced = false
    @State private var showUserAgent = false
    @State private var showNetworkLogs = false
    @State private var engine = SearchEngine.current
    @State private var showCastPermissions = false
    @State private var showLicences = false
#if PB_DEVELOPER_TOOLS
    @AppStorage(BrowserDeveloperTools.key) private var developerTools = false
#endif
    @State private var autoConnect = PlayOnPreferences.autoConnectEnabled()

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    // Settings opens from the Dashboard's top bar too; Back returns there.
                    if nav.settingsOrigin == .dashboard {
                        ScreenBackButton(destination: .dashboard, accessibilityLabel: "Back to Dashboard")
                    } else {
                        ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser")
                    }
                    Text("Browser settings")
                        .font(Theme.font(size: 22, weight: .bold))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                Form {
                    Section("Casting") {
                        Toggle("Auto-connect to this TV", isOn: $autoConnect)
                            .tint(Theme.primary)
                            .onChange(of: autoConnect) { enabled in
                                UserDefaults.standard.set(enabled, forKey: PlayOnPreferences.autoConnectKey)
                            }
                    }
                    Section("Search engine") {
                        Picker("Search engine", selection: $engine) {
                            ForEach(SearchEngine.allCases) { e in Text(e.label).tag(e) }
                        }
                        .onChange(of: engine) { newValue in SearchEngine.current = newValue }
                    }
                    Section("Privacy") {
                        Button("Website casting permissions") { showCastPermissions = true }
                        Link("Privacy policy", destination: URL(string: "https://playbridge.app/privacy")!)
                        Button("Open source licences") { showLicences = true }
                        Button("Media detect") { showMediaDetection = true }
                        Button("Clear Browsing Data") { showClearData = true }
                            .foregroundColor(Theme.danger)
                    }
                    Section {
                        DisclosureGroup("Advanced", isExpanded: $advanced) {
                            Button { showUserAgent = true } label: {
                                HStack {
                                    Text("User Agent")
                                    Spacer()
                                    Text(store.activeTab?.userAgentPreset.label ?? "Automatic")
                                        .foregroundColor(Theme.onSurfaceVariant)
                                }
                            }
                            .disabled(store.activeTab == nil)
                            Button("Network Logs") { showNetworkLogs = true }
                                .disabled(store.activeTab == nil)
#if PB_DEVELOPER_TOOLS
                            Toggle("Developer tools", isOn: $developerTools)
                                .tint(Theme.primary)
                                .accessibilityIdentifier("browserDeveloperTools")
                            Text("Adds JSON and Copy as cURL for detected media, and a full, unredacted copy of network logs. These can include cookies and tokens.")
                                .font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant)
#endif
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .sheet(isPresented: $showCastPermissions) { PageCastPermissionsView() }
        .sheet(isPresented: $showLicences) { OpenSourceLicencesScreen() }
        .sheet(isPresented: $showClearData) { BrowserClearDataSheet(store: store) }
        .sheet(isPresented: $showMediaDetection) { BrowserMediaDetectionSheet(store: store) }
        .sheet(isPresented: $showUserAgent) {
            if let tab = store.activeTab { BrowserUserAgentSheet(tab: tab) }
        }
        .sheet(isPresented: $showNetworkLogs) {
            if let tab = store.activeTab { BrowserNetworkLogView(tab: tab, store: store) }
        }
    }
}
