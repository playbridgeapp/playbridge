import SwiftUI
import WebKit

// MARK: - Shared

private func openInBrowser(_ url: String, store: BrowserStore, nav: NavigationViewModel) {
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
                    Button { nav.navigate(to: .browser) } label: {
                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
                    }
                    Text("History").font(.system(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                    Spacer()
                    if !data.history.isEmpty {
                        Button { confirmClear = true } label: {
                            Image(systemName: "trash").foregroundColor(Theme.danger)
                        }
                    }
                }
                .padding(20)

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
                    Button { nav.navigate(to: .browser) } label: {
                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
                    }
                    Text("Bookmarks").font(.system(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                    Spacer()
                }
                .padding(20)

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
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var data: BrowserDataStore
    @State private var engine = SearchEngine.current
    @State private var confirmClearHistory = false
    @State private var clearedMessage: String?

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Button { nav.navigate(to: .browser) } label: {
                        Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
                    }
                    Text("Browser settings").font(.system(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                    Spacer()
                }
                .padding(20)

                Form {
                    Section("Search engine") {
                        Picker("Search engine", selection: $engine) {
                            ForEach(SearchEngine.allCases) { e in Text(e.label).tag(e) }
                        }
                        .onChange(of: engine) { newValue in SearchEngine.current = newValue }
                    }
                    Section("Privacy") {
                        Button("Clear history") { confirmClearHistory = true }
                            .foregroundColor(Theme.danger)
                        Button("Clear cookies & website data") { clearWebsiteData() }
                            .foregroundColor(Theme.danger)
                    }
                    if let clearedMessage {
                        Section { Text(clearedMessage).font(.system(size: 13)).foregroundColor(Color(hex: 0x4CAF50)) }
                    }
                }
                .scrollContentBackground(.hidden)
            }
        }
        .alert("Clear all history?", isPresented: $confirmClearHistory) {
            Button("Clear", role: .destructive) { data.clearHistory(); flash("History cleared.") }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func clearWebsiteData() {
        let types = WKWebsiteDataStore.allWebsiteDataTypes()
        WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {
            flash("Cookies & website data cleared.")
        }
    }

    private func flash(_ text: String) {
        clearedMessage = text
        Task { try? await Task.sleep(nanoseconds: 2_500_000_000); if clearedMessage == text { clearedMessage = nil } }
    }
}

// MARK: - Shared row / empty

private func rowView(title: String, subtitle: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(title).font(.system(size: 15)).foregroundColor(Theme.onSurface).lineLimit(1)
        Text(subtitle).font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
    }
}

private func emptyState(_ text: String, systemImage: String) -> some View {
    VStack(spacing: 12) {
        Spacer()
        Image(systemName: systemImage).font(.system(size: 40)).foregroundColor(Theme.onSurfaceVariant)
        Text(text).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface)
        Spacer(); Spacer()
    }
    .frame(maxWidth: .infinity)
}
