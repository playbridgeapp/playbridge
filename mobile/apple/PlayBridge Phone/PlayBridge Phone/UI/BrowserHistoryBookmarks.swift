// SPDX-License-Identifier: MPL-2.0
import SwiftUI

private func openLibraryURL(_ url: String, newTab: Bool = false, store: BrowserStore, nav: NavigationViewModel) {
    if store.activeTab?.isBridgedApp == true { store.showBrowser() }
    if newTab || store.activeTab == nil { store.newTab(loading: url) }
    else { store.activeTab?.load(url) }
    nav.navigate(to: .browser)
}

struct HistoryScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var data: BrowserDataStore
    @Environment(\.calendar) private var calendar
    @Environment(\.locale) private var locale
    @State private var query = ""
    @State private var days: [BrowserHistoryDay] = []
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            Group {
                if data.history.isEmpty { libraryEmpty("No history yet", symbol: "clock.arrow.circlepath") }
                else if days.isEmpty { libraryEmpty("No search results", symbol: "magnifyingglass") }
                else {
                    List {
                        ForEach(days) { day in
                            Section(day.title) {
                                ForEach(day.entries) { entry in
                                    Button { openLibraryURL(entry.url, store: store, nav: nav) } label: {
                                        libraryRow(entry.title, url: entry.url)
                                    }
                                    .contextMenu {
                                        Button("Open in New Tab") { openLibraryURL(entry.url, newTab: true, store: store, nav: nav) }
                                        Button("Copy Link") { UIPasteboard.general.string = entry.url }
                                        Button("Delete", role: .destructive) { data.removeHistory(entry.id) }
                                    }
                                    .listRowBackground(Theme.surfaceContainer)
                                }
                                .onDelete { offsets in offsets.map { day.entries[$0].id }.forEach(data.removeHistory) }
                            }
                        }
                    }.listStyle(.plain).scrollContentBackground(.hidden)
                }
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("History").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search History")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) { ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser") }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !data.history.isEmpty { Button("Clear…", role: .destructive) { confirmClear = true } }
                }
            }
            .alert("Clear all history?", isPresented: $confirmClear) {
                Button("Clear", role: .destructive) { data.clearHistory() }
                Button("Cancel", role: .cancel) {}
            }
        }
        .onAppear(perform: refresh)
        .onChange(of: query) { _ in refresh() }
        .onChange(of: data.history) { _ in refresh() }
        .onChange(of: calendar) { _ in refresh() }
        .onChange(of: locale) { _ in refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in refresh() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in refresh() }
    }
    private func refresh() { days = BrowserLibraryQuery.history(data.history, query: query, calendar: calendar, locale: locale) }
}

struct BookmarksScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @EnvironmentObject private var data: BrowserDataStore
    @Environment(\.locale) private var locale
    @State private var query = ""
    @State private var matches: [Bookmark] = []
    @State private var editing: Bookmark?

    var body: some View {
        NavigationStack {
            Group {
                if data.bookmarks.isEmpty { libraryEmpty("No bookmarks yet", symbol: "bookmark") }
                else if matches.isEmpty { libraryEmpty("No search results", symbol: "magnifyingglass") }
                else {
                    List {
                        ForEach(matches) { bookmark in
                            Button { openLibraryURL(bookmark.url, store: store, nav: nav) } label: {
                                libraryRow(bookmark.title, url: bookmark.url)
                            }
                            .contextMenu {
                                Button("Open in New Tab") { openLibraryURL(bookmark.url, newTab: true, store: store, nav: nav) }
                                Button("Copy Link") { UIPasteboard.general.string = bookmark.url }
                                Button("Edit") { editing = bookmark }
                                Button("Delete", role: .destructive) { data.removeBookmark(bookmark.id) }
                            }
                            .swipeActions(edge: .trailing) {
                                Button("Delete", role: .destructive) { data.removeBookmark(bookmark.id) }
                                Button("Edit") { editing = bookmark }.tint(Theme.primary)
                            }
                            .listRowBackground(Theme.surfaceContainer)
                        }
                    }.listStyle(.plain).scrollContentBackground(.hidden)
                }
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Bookmarks").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search Bookmarks")
            .toolbar { ToolbarItem(placement: .navigationBarLeading) { ScreenBackButton(destination: .browser, accessibilityLabel: "Back to Browser") } }
        }
        .sheet(item: $editing) { bookmark in BookmarkEditSheet(bookmark: bookmark, data: data) }
        .onAppear(perform: refresh)
        .onChange(of: query) { _ in refresh() }
        .onChange(of: data.bookmarks) { _ in refresh() }
        .onChange(of: locale) { _ in refresh() }
    }
    private func refresh() { matches = BrowserLibraryQuery.bookmarks(data.bookmarks, query: query, locale: locale) }
}

struct BookmarkEditSheet: View {
    let bookmark: Bookmark
    @ObservedObject var data: BrowserDataStore
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var address: String
    @State private var showError = false
    init(bookmark: Bookmark, data: BrowserDataStore) {
        self.bookmark = bookmark; self.data = data
        _title = State(initialValue: bookmark.title); _address = State(initialValue: bookmark.url)
    }
    var body: some View {
        NavigationStack {
            Form {
                TextField("Title", text: $title).accessibilityIdentifier("bookmarkEditTitle")
                TextField("Address", text: $address).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .keyboardType(.URL).accessibilityIdentifier("bookmarkEditAddress")
                if showError { Text("Enter a valid HTTP or HTTPS address.").foregroundColor(Theme.danger) }
            }
            .navigationTitle("Edit Bookmark").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if data.updateBookmark(bookmark.id, title: title, address: address) { dismiss() }
                        else { showError = true }
                    }
                }
            }
        }.presentationDetents([.medium, .large])
    }
}

private func libraryRow(_ title: String, url: String) -> some View {
    VStack(alignment: .leading, spacing: 2) {
        Text(title).font(Theme.font(size: 15)).foregroundColor(Theme.onSurface).lineLimit(1)
        Text(URL(string: url)?.host ?? url).font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
    }
}
private func libraryEmpty(_ text: String, symbol: String) -> some View {
    VStack(spacing: 12) {
        Spacer()
        Image(systemName: symbol).font(Theme.font(size: 40)).foregroundColor(Theme.onSurfaceVariant)
        Text(text).font(Theme.font(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface)
        Spacer(); Spacer()
    }.frame(maxWidth: .infinity, maxHeight: .infinity)
}
