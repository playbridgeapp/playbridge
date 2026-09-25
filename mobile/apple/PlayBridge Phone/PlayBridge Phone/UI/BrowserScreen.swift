import SwiftUI

/// The Browse tab: a multi-tab WKWebView with an address bar, nav controls, a detected-stream
/// badge, and tab switching. Detection runs automatically via the injected user script.
struct BrowserScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @State private var showTabs = false

    var body: some View {
        Group {
            if let tab = store.activeTab {
                ActiveTabView(tab: tab, store: store, showTabs: $showTabs)
                    .id(tab.id)
            } else {
                Color.clear
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surface.ignoresSafeArea())
        .sheet(isPresented: $showTabs) {
            TabsScreen(store: store).environmentObject(vm)
        }
        .onAppear {
            store.browserVisible = true
            // Website requests arrive only after consent, with their original page context.
            store.onPageCast = { payload, origin in
                guard let url = payload["url"] as? String, !url.isEmpty else { return }
                let v = DetectedVideo(url: url, contentType: payload["contentType"] as? String,
                                      detectedBy: "page_bridge",
                                      originUrl: origin,
                                      headers: VideoDetector.requestHeaders(originUrl: origin),
                                      kind: DetectedVideo.classify(url: url, contentType: payload["contentType"] as? String))
                vm.castStream(v)
                nav.navigate(to: .remote)
            }
        }
        .onDisappear { store.browserVisible = false; store.activeTab?.cancelPrompt() }
    }
}

/// Renders one active tab (toolbar + web content). Keyed by tab id so it rebuilds on switch.
private struct ActiveTabView: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var data: BrowserDataStore
    @Binding var showTabs: Bool

    @State private var address = ""
    @State private var showDetected = false
    @State private var showMenu = false
    @State private var pendingMenuAction: (() -> Void)?
    @State private var showDeviceSheet = false
    @FocusState private var addressFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !tab.isBrowserChromeHidden { topBar }
            if !tab.isBrowserChromeHidden && tab.isLoading && tab.progress < 1 {
                ProgressView(value: tab.progress).tint(Theme.primary)
                    .scaleEffect(x: 1, y: 0.6, anchor: .center)
            }
            ZStack(alignment: .top) {
                if tab.isHome {
                    BrowserHomeView { url in tab.load(url) }
                } else {
                    WebViewContainer(tab: tab)
                    if tab.isPickingElement {
                        PickerTouchOverlay { point, size in
                            tab.pickElement(atNormalizedX: Double(point.x / size.width),
                                            y: Double(point.y / size.height))
                        }
                    }
                }
                if let failure = tab.navigationFailure {
                    BrowserFailureView(failure: failure, retry: { tab.reload() }, back: {
                        if tab.canGoBack { tab.goBack() }
                        else { tab.navigationFailure = nil; tab.isHome = tab.loadedWebView?.url == nil }
                    })
                }
                if addressFocused && !address.trimmingCharacters(in: .whitespaces).isEmpty {
                    SuggestionsView(query: address) { url in
                        address = url
                        addressFocused = false
                        tab.load(url)
                    }
                }
                if tab.isBrowserChromeHidden {
                    VStack {
                        HStack {
                            Spacer()
                            Button { tab.isBrowserChromeHidden = false } label: {
                                Image(systemName: "arrow.down.right.and.arrow.up.left")
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundColor(Theme.onSurface)
                                    .frame(width: 44, height: 44)
                                    .background(Theme.surfaceContainerHigh.opacity(0.92), in: Circle())
                                    .contentShape(Circle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Exit full screen")
                            .accessibilityHint("Shows browser controls")
                        }
                        Spacer()
                    }
                    .padding(12)
                }
            }
            if tab.isPickingElement {
                VStack(spacing: 8) {
                    HStack {
                        Text(tab.pickerSelector ?? "Tap an element to block")
                            .font(Theme.font(.caption))
                            .lineLimit(1)
                        Spacer()
                        Button("Cancel") { tab.stopElementPicker() }
                        Button("Block") { tab.blockPickedElement() }
                            .disabled(tab.pickerSelector == nil)
                    }
                    HStack(spacing: 16) {
                        Button("Up") { tab.pickerAction("up") }
                        Button("Down") { tab.pickerAction("down") }
                        Button("Preview") { tab.pickerAction("preview") }
                        Button("Block source") { tab.pickerAction("source") }
                            .disabled(!tab.pickerHasSource)
                    }
                    .font(Theme.font(.caption))
                    .frame(maxWidth: .infinity)
                }
                .padding(10)
                .background(Theme.surfaceContainer)
            }
            if tab.popupBlocked {
                HStack {
                    Text(tab.blockedPopupOrigin.map { "Popup blocked from \($0.host ?? $0.absoluteString)" } ?? "Popup blocked").font(Theme.font(.caption))
                    Spacer()
                    Button("Allow for this site") { tab.allowPopupsForSite() }
                        .disabled(tab.blockedPopupOrigin == nil)
                    Button { tab.popupBlocked = false } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Dismiss popup notice")
                }.padding(10).background(Theme.surfaceContainer)
            }
            if !tab.isBrowserChromeHidden { toolbar }
        }
        .statusBarHidden(tab.isBrowserChromeHidden)
        .sheet(isPresented: $showDetected) {
            CastSheet(detector: tab.detector, tab: tab, store: store)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .environmentObject(vm)
                .environmentObject(nav)
        }
        .onChange(of: showDetected) { isPresented in
            if isPresented { tab.pauseMedia() }
        }
        .sheet(isPresented: $showMenu, onDismiss: {
            let action = pendingMenuAction
            pendingMenuAction = nil
            action?()
        }) {
            MenuSheet(tab: tab, store: store, isPresented: $showMenu) { action in
                pendingMenuAction = action
                showMenu = false
            }
        }
        .sheet(isPresented: $showDeviceSheet) {
            DeviceConnectionSheet()
                .environmentObject(vm)
                .environmentObject(nav)
        }
        .sheet(item: $tab.prompt) { prompt in
            BrowserPromptView(prompt: prompt) { accepted, text in
                if tab.prompt?.id == prompt.id { tab.prompt = nil }
                prompt.finish(accepted, text: text)
            }
        }
        .onAppear { address = tab.urlString }
        .onDisappear { tab.cancelPrompt() }
        .onChange(of: tab.urlString) { newValue in
            if !addressFocused { address = newValue }
        }
        .overlay(alignment: .bottom) {
            if let msg = tab.blockedAdMessage {
                HStack(spacing: 8) {
                    Image(systemName: "shield.fill")
                        .foregroundColor(Theme.primary)
                        .font(Theme.font(size: 14, weight: .bold))
                    Text(msg)
                        .font(Theme.font(size: 13, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                    Button { tab.blockedAdMessage = nil } label: { Image(systemName: "xmark") }
                        .accessibilityLabel("Dismiss blocked navigation notice")
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(
                    Capsule()
                        .fill(Theme.surfaceContainerHigh.opacity(0.95))
                        .shadow(color: Color.black.opacity(0.25), radius: 6, x: 0, y: 3)
                )
                .overlay(
                    Capsule()
                        .stroke(Theme.primary.opacity(0.3), lineWidth: 1)
                )
                .padding(.bottom, 72) // position above the bottom toolbar
                .transition(.move(edge: .bottom).combined(with: .opacity))

            }
        }
    }

    // MARK: - Top bar (URL + Remote / TV / Play, matching Android)

    private var topBar: some View {
        HStack(spacing: 0) {
            if addressFocused {
                Button {
                    addressFocused = false
                    address = tab.urlString
                } label: {
                    Image(systemName: "arrow.backward")
                        .font(.system(size: 22)).foregroundColor(Theme.primary)
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Cancel editing address")
            } else {
                DashboardNavigationButton()
            }

            // URL pill
            HStack(spacing: 6) {
                Image(systemName: tab.urlString.hasPrefix("https") ? "lock.fill" : "globe")
                    .font(.system(size: 14)).foregroundColor(Theme.onSurfaceVariant)
                TextField("Search or enter address", text: $address)
                    .font(Theme.font(size: 13))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.webSearch)
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .foregroundColor(Theme.onSurface)
                    .onSubmit { tab.load(address); addressFocused = false }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .frame(minHeight: 40)
            .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 2)

            if !addressFocused {
                if vm.isConnected {
                    topAction("av.remote", tint: Theme.primary) { nav.navigate(to: .remote) }
                        .accessibilityLabel("Remote control")
                }
                tvButton
                playButton
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 2)
        .background(Theme.surfaceContainer.ignoresSafeArea(edges: .top))
    }

    private func topAction(_ systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 22)).foregroundColor(tint)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var tvButton: some View {
        Button { showDeviceSheet = true } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "tv")
                    .font(.system(size: 22))
                    .foregroundColor(vm.isConnected ? Theme.primary : Theme.onSurface)
                    .frame(width: 44, height: 44).contentShape(Rectangle())
                if vm.isConnected {
                    Circle().fill(Color(hex: 0x4CAF50)).frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Theme.surface, lineWidth: 1.5))
                        .offset(x: -5, y: 5)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(vm.isConnected ? "Connected TV" : "Connect TV")
    }

    private var playButton: some View {
        let count = tab.detector.videos.count
        let enabled = count > 0 || vm.isConnected
        return Button { showDetected = true } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "play.fill")
                    .font(.system(size: 22))
                    .foregroundColor(enabled ? Theme.primary : Theme.onSurfaceVariant.opacity(0.4))
                    .frame(width: 44, height: 44).contentShape(Rectangle())
                if count > 0 {
                    Text(count > 99 ? "99+" : "\(count)")
                        .font(.custom("Poppins-Regular", fixedSize: 9).bold()).foregroundColor(.white)
                        .frame(minWidth: 13).padding(2)
                        .background(Theme.danger).clipShape(Circle())
                        .offset(x: -1, y: 1)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel("Detected media, \(count) items")
    }

    // MARK: - Bottom bar (back / forward / refresh, matching Android)

    private var toolbar: some View {
        HStack(spacing: 0) {
            toolButton("arrow.backward", label: "Back", enabled: tab.canGoBack) { tab.goBack() }
            Spacer(minLength: 0)
            toolButton("arrow.forward", label: "Forward", enabled: tab.canGoForward) { tab.goForward() }
            Spacer(minLength: 0)
            if tab.isLoading {
                toolButton("xmark", label: "Stop loading", enabled: true) { tab.stop() }
            } else {
                toolButton("arrow.clockwise", label: "Reload", enabled: !tab.isHome) { tab.reload() }
            }
            Spacer(minLength: 0)
            Button { showTabs = true } label: {
                Text(store.tabs.count > 999 ? "999+" : "\(store.tabs.count)")
                    .font(.custom("Poppins-Regular", fixedSize: store.tabs.count >= 100 ? 8 : store.tabs.count >= 10 ? 10 : 12).bold())
                    .foregroundStyle(Theme.onSurface)
                    .frame(width: 24, height: 24)
                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(Theme.onSurface, lineWidth: 2))
                    .frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Tabs, \(store.tabs.count) open")
            Spacer(minLength: 0)
            toolButton("line.3.horizontal", label: "Browser menu", enabled: true) { showMenu = true }
        }
        .padding(.horizontal, 16).padding(.vertical, 2)
        .background(Theme.surfaceContainer.ignoresSafeArea(edges: .bottom))
    }

    private func toolButton(_ systemImage: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 24))
                .foregroundColor(enabled ? Theme.onSurface : Theme.onSurfaceVariant.opacity(0.4))
                .frame(width: 48, height: 48).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

}

/// Address-bar autocomplete drawn over the page while the field is focused.
private struct SuggestionsView: View {
    let query: String
    let onSelect: (String) -> Void
    @EnvironmentObject private var data: BrowserDataStore

    var body: some View {
        let items = data.suggestions(for: query)
        if !items.isEmpty {
            VStack(spacing: 0) {
                ForEach(items) { s in
                    Button { onSelect(s.url) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: s.isBookmark ? "bookmark.fill" : "clock.arrow.circlepath")
                                .font(Theme.font(size: 13)).foregroundColor(Theme.onSurfaceVariant).frame(width: 20)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(s.title).font(Theme.font(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                                Text(s.url).font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                        .padding(.horizontal, 14).padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                    Divider().overlay(Theme.outlineVariant.opacity(0.2))
                }
            }
            .background(Theme.surfaceContainer)
            .cornerRadius(12)
            .padding(.horizontal, 10)
            .shadow(color: Color.black.opacity(0.2), radius: 8, y: 4)
        }
    }
}

/// New-tab / home page: shows bookmarks and recent history.
private struct BrowserHomeView: View {
    let onOpen: (String) -> Void
    @EnvironmentObject private var data: BrowserDataStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("PlayBridge")
                    .font(Theme.font(size: 26, weight: .bold, design: .rounded))
                    .foregroundColor(Theme.onSurface)
                    .frame(maxWidth: .infinity).padding(.top, 24)

                if !data.bookmarks.isEmpty {
                    sectionTitle("Bookmarks")
                    grid(items: data.bookmarks.prefix(8).map { ($0.title, $0.url) })
                }
                if !data.history.isEmpty {
                    sectionTitle("Recent")
                    VStack(spacing: 8) {
                        ForEach(Array(data.history.prefix(8))) { h in
                            Button { onOpen(h.url) } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: "clock.arrow.circlepath")
                                        .font(Theme.font(size: 14)).foregroundColor(Theme.onSurfaceVariant).frame(width: 22)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(h.title).font(Theme.font(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                                        Text(URL(string: h.url)?.host ?? h.url).font(Theme.font(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                                    }
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                                .padding(12)
                                .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainer))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                if data.bookmarks.isEmpty && data.history.isEmpty {
                    Text("Search or enter an address above to get started.")
                        .font(Theme.font(size: 14)).foregroundColor(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity).padding(.top, 40)
                }
                Spacer(minLength: 20)
            }
            .padding(.horizontal, 16)
        }
        .background(Theme.surface)
    }

    private func sectionTitle(_ t: String) -> some View {
        Text(t).font(Theme.font(size: 13, weight: .bold)).foregroundColor(Theme.onSurfaceVariant)
    }

    private func grid(items: [(String, String)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 10)], spacing: 10) {
            ForEach(items.indices, id: \.self) { i in
                let item = items[i]
                Button { onOpen(item.1) } label: {
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainerHigh).frame(height: 56)
                            Image(systemName: "globe").font(.system(size: 22)).foregroundColor(Theme.primary)
                        }
                        Text(item.0).font(Theme.font(size: 11)).foregroundColor(Theme.onSurface).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}
