import SwiftUI

struct MenuSheet: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @Binding var isPresented: Bool
    let dismissThen: (@escaping () -> Void) -> Void

    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var data: BrowserDataStore

    @State private var showAdblockSettings = false
    @State private var showDownloads = false
    @State private var showNetworkLogs = false
    @State private var showUserAgent = false
    @State private var showRemoveBookmarkConfirmation = false
    @State private var allowPopups = false
    @State private var feedback: String?

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)

    private var pageURL: URL? {
        guard !tab.isHome, let url = URL(string: tab.urlString),
              BrowserSitePolicy.origin(url) != nil else { return nil }
        return url
    }

    private var isBookmarked: Bool { data.isBookmarked(tab.urlString) }

    private func go(_ screen: AppScreen) {
        dismissThen { nav.navigate(to: screen) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Browser menu")
                        .font(Theme.font(size: 20, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                    Spacer()
                    Button("Done") { isPresented = false }
                        .font(Theme.font(size: 14, weight: .semibold))
                        .foregroundColor(Theme.primary)
                }

                if let feedback {
                    Label(feedback, systemImage: "checkmark.circle.fill")
                        .font(Theme.font(.footnote))
                        .foregroundColor(Theme.primary)
                }

                LazyVGrid(columns: columns, spacing: 10) {
                    menuGridItem(icon: "bookmark", label: "Bookmarks") { go(.bookmarks) }
                    menuGridItem(icon: "clock.arrow.circlepath", label: "History") { go(.history) }
                    menuGridItem(
                        icon: isBookmarked ? "star.fill" : "star",
                        label: isBookmarked ? "Bookmarked" : "Add Bookmark",
                        selected: isBookmarked,
                        enabled: pageURL != nil,
                        state: isBookmarked ? "Saved" : "Not saved",
                        hint: isBookmarked ? "Opens a confirmation before removal" : "Saves this page"
                    ) {
                        if isBookmarked { showRemoveBookmarkConfirmation = true }
                        else if pageURL != nil {
                            data.addBookmark(url: tab.urlString, title: tab.title)
                            feedback = "Bookmark added"
                        }
                    }

                    menuGridItem(icon: "magnifyingglass", label: "Find in Page", enabled: pageURL != nil) {
                        dismissThen { tab.findInPage() }
                    }
                    menuGridItem(icon: "desktopcomputer", label: "Desktop Site", selected: tab.isDesktopMode,
                                 state: tab.isDesktopMode ? "On" : "Off") {
                        dismissThen { tab.toggleDesktopMode() }
                    }
                    menuGridItem(icon: "person.text.rectangle", label: "User Agent",
                                 selected: tab.userAgentPreset != .automatic,
                                 state: tab.userAgentPreset.label,
                                 hint: "Choose the browser identity for this tab") {
                        showUserAgent = true
                    }

                    menuGridItem(icon: "scope", label: "Block Element", enabled: pageURL != nil) {
                        dismissThen { tab.startElementPicker() }
                    }
                    menuGridItem(icon: "shield", label: "Adblock Settings",
                                 state: store.adBlockEnabled ? "Blocking on" : "Blocking off",
                                 hint: "Opens ad blocking settings") {
                        showAdblockSettings = true
                    }
                    menuGridItem(icon: "network", label: "Network Logs",
                                 selected: tab.networkCaptureEnabled,
                                 state: tab.networkCaptureEnabled ? "Recording" : "Capture off") {
                        showNetworkLogs = true
                    }

                    menuGridItem(icon: "arrow.down.circle", label: "Downloads") {
                        showDownloads = true
                    }
                    menuGridItem(icon: "gearshape", label: "Browser Settings") {
                        go(.browserSettings)
                    }
                    menuGridItem(icon: "arrow.up.left.and.arrow.down.right", label: "Full Screen",
                                 enabled: pageURL != nil,
                                 hint: "Hides browser controls; an exit button remains visible") {
                        dismissThen { tab.isBrowserChromeHidden = true }
                    }
                }

                if let pageURL, let host = pageURL.host {
                    Divider().overlay(Theme.outlineVariant)
                    Toggle(isOn: $allowPopups) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Allow popups for this site")
                                .font(Theme.font(size: 14, weight: .medium))
                                .foregroundColor(Theme.onSurface)
                            Text(host)
                                .font(Theme.font(.caption))
                                .foregroundColor(Theme.onSurfaceVariant)
                                .lineLimit(1)
                        }
                    }
                    .tint(Theme.primary)
                    .onChange(of: allowPopups) { value in
                        BrowserSitePolicy.setPopupsAllowed(value, url: pageURL)
                    }
                }
            }
            .frame(maxWidth: 500)
            .padding(.horizontal, 20)
            .padding(.top, 18)
            .padding(.bottom, 28)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.fraction(0.7), .large])
        .presentationDragIndicator(.visible)
        .onAppear { allowPopups = BrowserSitePolicy.popupsAllowed(pageURL) }
        .onChange(of: tab.urlString) { value in
            allowPopups = BrowserSitePolicy.popupsAllowed(URL(string: value))
        }
        .sheet(isPresented: $showNetworkLogs) { BrowserNetworkLogView(tab: tab, store: store) }
        .sheet(isPresented: $showDownloads) { BrowserDownloadsView(downloads: store.downloads) }
        .sheet(isPresented: $showAdblockSettings) { AdblockSettingsSheet(store: store) }
        .sheet(isPresented: $showUserAgent) { BrowserUserAgentSheet(tab: tab) }
        .confirmationDialog("Remove this bookmark?", isPresented: $showRemoveBookmarkConfirmation,
                            titleVisibility: .visible) {
            Button("Remove Bookmark", role: .destructive) {
                data.removeBookmark(url: tab.urlString)
                feedback = "Bookmark removed"
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func menuGridItem(
        icon: String,
        label: String,
        selected: Bool = false,
        enabled: Bool = true,
        state: String = "",
        hint: String = "",
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 21, weight: .medium))
                    .foregroundColor(selected ? Theme.primary : Theme.onSurfaceVariant)
                    .frame(width: 48, height: 48)
                    .background(selected ? Theme.primaryDim.opacity(0.25) : Color.clear, in: Circle())
                Text(label)
                    .font(Theme.font(size: 12))
                    .foregroundColor(selected ? Theme.primary : Theme.onSurface)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(minHeight: 34, alignment: .top)
            }
            .frame(maxWidth: .infinity, minHeight: 92)
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label)
        .accessibilityValue(state)
        .accessibilityHint(enabled ? hint : "Open a website to use this action")
    }
}

private struct BrowserUserAgentSheet: View {
    @ObservedObject var tab: BrowserTab
    @Environment(\.dismiss) private var dismiss
    @State private var customValue = ""
    @State private var showInvalidAgent = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Changes how this tab identifies itself to websites. Automatic follows Desktop Site. The browser still uses WebKit, and casting identity is unchanged.")
                        .font(Theme.font(.footnote))
                        .foregroundColor(Theme.onSurfaceVariant)
                }
                Section("Presets") {
                    ForEach(BrowserUserAgentPreset.allCases.filter { $0 != .custom }) { preset in
                        Button {
                            tab.selectUserAgent(preset)
                            dismiss()
                        } label: {
                            HStack {
                                Text(preset.label).foregroundColor(Theme.onSurface)
                                Spacer()
                                if tab.userAgentPreset == preset {
                                    Image(systemName: "checkmark").foregroundColor(Theme.primary)
                                }
                            }
                        }
                        .accessibilityValue(tab.userAgentPreset == preset ? "Selected" : "")
                    }
                }
                Section("Custom") {
                    TextField("User agent string", text: $customValue, axis: .vertical)
                        .lineLimit(2...4)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if showInvalidAgent {
                        Text("Enter a user agent of 1–512 characters without line breaks or control characters.")
                            .font(Theme.font(.footnote))
                            .foregroundColor(Theme.danger)
                    }
                    Button("Use Custom User Agent") {
                        if tab.selectUserAgent(.custom, custom: customValue) { dismiss() }
                        else { showInvalidAgent = true }
                    }
                    .disabled(customValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .navigationTitle("User agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onAppear { customValue = tab.customUserAgent ?? "" }
        .presentationDetents([.large])
    }
}
