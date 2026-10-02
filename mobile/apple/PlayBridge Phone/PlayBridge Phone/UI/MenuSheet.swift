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
    @State private var showMediaDetection = false
    @State private var showSiteSettings = false
    @State private var showContentBlocking = false
    @State private var showRemoveBookmarkConfirmation = false
    @State private var feedback: String?
    @State private var availableBridgedApp: BridgedApp?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var bookmarkRemovalURL: String?

    private var pageURL: URL? {
        guard !tab.isHome, let url = URL(string: tab.urlString), BrowserSitePolicy.origin(url) != nil else { return nil }
        return url
    }
    private var isBookmarked: Bool { data.isBookmarked(tab.urlString) }
    private func go(_ screen: AppScreen) { dismissThen { nav.navigate(to: screen) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(pageURL?.host ?? "Browser")
                        .font(Theme.font(.subheadline)).foregroundColor(Theme.onSurfaceVariant)
                        .lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Button { isPresented = false } label: {
                        Image(systemName: "xmark").font(.system(size: 15, weight: .medium))
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Close menu")
                }
                HStack(spacing: 0) {
                    shortcut(icon: isBookmarked ? "star.fill" : "star",
                             label: isBookmarked ? "Bookmarked" : "Bookmark", selected: isBookmarked) {
                        if isBookmarked { bookmarkRemovalURL = tab.urlString; showRemoveBookmarkConfirmation = true }
                        else { data.addBookmark(url: tab.urlString, title: tab.title); feedback = "Bookmark added" }
                    }
                    shortcut(icon: "magnifyingglass", label: "Find in page") {
                        dismissThen { tab.findInPage() }
                    }
                    shortcut(icon: "arrow.up.left.and.arrow.down.right", label: "Full screen") {
                        dismissThen { tab.isBrowserChromeHidden = true }
                    }
                }
                if let feedback {
                    Label(feedback, systemImage: "checkmark.circle.fill")
                        .font(Theme.font(.footnote)).padding(.vertical, 8)
                        .accessibilityAddTraits(.updatesFrequently)
                }
                Divider().padding(.vertical, 8)
                menuRow(icon: "play.circle", label: "Media detect",
                        status: store.mediaDetectionSettings.enabled ? (tab.detectionEnabled ? "On" : "Auto off") : "Off") {
                    showMediaDetection = true
                }
                Toggle(isOn: Binding(get: { tab.isDesktopMode }, set: { value in
                    if value != tab.isDesktopMode { dismissThen { tab.toggleDesktopMode() } }
                })) {
                    Label {
                        Text("Desktop Site").font(Theme.font(size: 15))
                    } icon: {
                        Image(systemName: "desktopcomputer").font(.system(size: 20))
                            .foregroundColor(Theme.onSurfaceVariant).frame(width: 24)
                    }
                }
                .disabled(pageURL == nil).opacity(pageURL == nil ? 0.4 : 1)
                .padding(.horizontal, 8).frame(minHeight: 48)
                menuRow(icon: "slider.horizontal.3", label: "Site Settings", enabled: pageURL != nil) { showSiteSettings = true }
                menuRow(icon: "shield", label: "Content Blocking", status: store.adBlockEnabled ? "On" : "Off") { showContentBlocking = true }
                if let app = installableApp {
                    menuRow(icon: "plus.app", label: "Add Bridged App") {
                        store.bridgedApps.install(app)
                        feedback = "\(app.name) added to Dashboard"
                    }
                }
                Divider().padding(.vertical, 8)
                menuRow(icon: "bookmark", label: "Bookmarks") { go(.bookmarks) }
                menuRow(icon: "clock.arrow.circlepath", label: "History") { go(.history) }
                menuRow(icon: "arrow.down.circle", label: "Downloads") { showDownloads = true }
                menuRow(icon: "gearshape", label: "Settings") { go(.browserSettings) }
            }
            .foregroundColor(Theme.onSurface)
            .frame(maxWidth: 500).padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 16)
            .frame(maxWidth: .infinity)
        }
        .tint(Theme.primary)
        .task(id: tab.urlString) {
            availableBridgedApp = nil
            guard !tab.isBridgedApp, let url = pageURL else { return }
            let app = await BridgedAppDeclarationCache.shared.discover(url)
            guard !Task.isCancelled, pageURL.flatMap(BridgedAppDeclaration.origin(of:)) == app?.origin else { return }
            availableBridgedApp = app
        }
        .background(Theme.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.height(installableApp == nil ? 560 : 608), .large]).presentationDragIndicator(.visible)
        .sheet(isPresented: $showDownloads) { BrowserDownloadsView(downloads: store.downloads) }
        .sheet(isPresented: $showMediaDetection) { BrowserMediaDetectionSheet(store: store) }
        .sheet(isPresented: $showSiteSettings) { BrowserSiteSettingsSheet(tab: tab) }
        .sheet(isPresented: $showContentBlocking, onDismiss: {
            if pendingPicker { pendingPicker = false; dismissThen { tab.startElementPicker() } }
        }) {
            NavigationStack {
                List {
                    Button("Adblock Settings") { showAdblockSettings = true }
                    Button("Block Element") { pendingPicker = true; showContentBlocking = false }
                        .disabled(pageURL == nil)
                }
                .navigationTitle("Content Blocking").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showContentBlocking = false } } }
                .sheet(isPresented: $showAdblockSettings) { AdblockSettingsSheet(store: store) }
            }
            .presentationDetents([.medium, .large])
        }
        .confirmationDialog("Remove this bookmark?", isPresented: $showRemoveBookmarkConfirmation, titleVisibility: .visible) {
            Button("Remove Bookmark", role: .destructive) {
                if let url = bookmarkRemovalURL { data.removeBookmark(url: url) }
                bookmarkRemovalURL = nil
                feedback = "Bookmark removed"
            }
            Button("Cancel", role: .cancel) { bookmarkRemovalURL = nil }
        }
    }

    @State private var pendingPicker = false

    private var installableApp: BridgedApp? {
        guard let app = availableBridgedApp,
              !store.bridgedApps.apps.contains(where: { $0.origin == app.origin }),
              pageURL.flatMap(BridgedAppDeclaration.origin(of:)) == app.origin else { return nil }
        return app
    }

    private func menuRow(icon: String, label: String, status: String = "", enabled: Bool = true,
                         action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.system(size: 20)).foregroundColor(Theme.onSurfaceVariant).frame(width: 24)
                Text(label).font(Theme.font(size: 15))
                Spacer(minLength: 8)
                if !status.isEmpty { Text(status).font(Theme.font(.caption)).foregroundColor(Theme.onSurfaceVariant) }
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(.horizontal, 8).padding(.vertical, 10).frame(minHeight: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(label).accessibilityValue(status)
    }

    private func shortcut(icon: String, label: String, selected: Bool = false,
                          action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.system(size: 22))
                    .foregroundColor(selected ? Theme.primary : Theme.onSurfaceVariant)
                Text(label).font(Theme.font(size: 12)).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 8).padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 64).contentShape(Rectangle())
        }
        .buttonStyle(.plain).disabled(pageURL == nil).opacity(pageURL == nil ? 0.4 : 1)
        .accessibilityLabel(label)
    }
}

struct BrowserUserAgentSheet: View {
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
