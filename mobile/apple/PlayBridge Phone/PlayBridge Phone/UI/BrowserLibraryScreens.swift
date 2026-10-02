import SwiftUI
import WebKit

// MARK: - Shared

private func openInBrowser(_ url: String, store: BrowserStore, nav: NavigationViewModel) {
    if store.activeTab?.isBridgedApp == true { store.showBrowser() }
    if let tab = store.activeTab {
        tab.load(url)
    } else {
        store.newTab(loading: url)
    }
    nav.navigate(to: .browser)
}

private func hostLabel(_ url: String) -> String { URL(string: url)?.host ?? url }

// MARK: - History

struct HistoryScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var data: BrowserDataStore
    @State private var confirmClear = false

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser")
                    Text("History").font(Theme.font(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                    Spacer()
                    if !data.history.isEmpty {
                        Button { confirmClear = true } label: {
                            Image(systemName: "trash").foregroundColor(Theme.danger)
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if data.history.isEmpty {
                    emptyState("No history yet", systemImage: "clock.arrow.circlepath")
                } else {
                    List {
                        ForEach(data.history) { entry in
                            Button { openInBrowser(entry.url, store: store, nav: nav) } label: {
                                rowView(title: entry.title, subtitle: hostLabel(entry.url))
                            }
                            .listRowBackground(Theme.surfaceContainer)
                        }
                        .onDelete { offsets in
                            offsets.map { data.history[$0].id }.forEach { data.removeHistory($0) }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
        }
        .alert("Clear all history?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { data.clearHistory() }
            Button("Cancel", role: .cancel) {}
        }
    }
}

// MARK: - Bookmarks

struct BookmarksScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var data: BrowserDataStore

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser")
                    Text("Bookmarks").font(Theme.font(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if data.bookmarks.isEmpty {
                    emptyState("No bookmarks yet", systemImage: "bookmark")
                } else {
                    List {
                        ForEach(data.bookmarks) { bm in
                            Button { openInBrowser(bm.url, store: store, nav: nav) } label: {
                                rowView(title: bm.title, subtitle: hostLabel(bm.url))
                            }
                            .listRowBackground(Theme.surfaceContainer)
                        }
                        .onDelete { offsets in
                            offsets.map { data.bookmarks[$0].id }.forEach { data.removeBookmark($0) }
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                }
            }
        }
    }
}

// MARK: - Browser settings

struct BrowserSettingsScreen: View {
    @EnvironmentObject private var store: BrowserStore
    @State private var showClearData = false
    @State private var showMediaDetection = false
    @State private var advanced = false
    @State private var showUserAgent = false
    @State private var showNetworkLogs = false
    @State private var engine = SearchEngine.current
    @State private var showCastPermissions = false

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser")
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
                    Section("Search engine") {
                        Picker("Search engine", selection: $engine) {
                            ForEach(SearchEngine.allCases) { e in Text(e.label).tag(e) }
                        }
                        .onChange(of: engine) { newValue in SearchEngine.current = newValue }
                    }
                    Section("Privacy") {
                        Button("Website casting permissions") { showCastPermissions = true }
                        Link("Privacy policy", destination: URL(string: "https://playbridge.app/privacy")!)
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
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .sheet(isPresented: $showCastPermissions) { PageCastPermissionsView() }
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

// MARK: - Shared row / empty

private func rowView(title: String, subtitle: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(title).font(Theme.font(size: 15)).foregroundColor(Theme.onSurface).lineLimit(1)
        Text(subtitle).font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
    }
}

private func emptyState(_ text: String, systemImage: String) -> some View {
    VStack(spacing: 12) {
        Spacer()
        Image(systemName: systemImage).font(Theme.font(size: 40)).foregroundColor(Theme.onSurfaceVariant)
        Text(text).font(Theme.font(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface)
        Spacer(); Spacer()
    }
    .frame(maxWidth: .infinity)
}
