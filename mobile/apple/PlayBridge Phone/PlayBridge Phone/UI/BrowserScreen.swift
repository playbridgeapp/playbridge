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
            // window.playbridge.cast(payload) from a page → cast directly if it carries a URL.
            store.onPageCast = { payload in
                guard let url = payload["url"] as? String, !url.isEmpty else { return }
                let v = DetectedVideo(url: url, contentType: payload["contentType"] as? String,
                                      detectedBy: "page_bridge",
                                      originUrl: store.activeTab?.urlString, headers: [:],
                                      kind: DetectedVideo.classify(url: url, contentType: payload["contentType"] as? String))
                vm.castStream(v)
                nav.navigate(to: .remote)
            }
        }
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
    @State private var showDeviceSheet = false
    @FocusState private var addressFocused: Bool

    private var streams: [DetectedVideo] { tab.detector.videos.filter { !$0.isSubtitle } }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if tab.isLoading && tab.progress < 1 {
                ProgressView(value: tab.progress).tint(Theme.primary)
                    .scaleEffect(x: 1, y: 0.6, anchor: .center)
            }
            ZStack(alignment: .top) {
                if tab.isHome {
                    BrowserHomeView { url in tab.load(url) }
                } else {
                    WebViewContainer(tab: tab)
                }
                if addressFocused && !address.trimmingCharacters(in: .whitespaces).isEmpty {
                    SuggestionsView(query: address) { url in
                        address = url
                        addressFocused = false
                        tab.load(url)
                    }
                }
            }
            toolbar
        }
        .sheet(isPresented: $showDetected) {
            CastSheet(videos: tab.detector.videos, tab: tab, store: store)
                .environmentObject(vm)
                .environmentObject(nav)
        }
        .sheet(isPresented: $showMenu) {
            MenuSheet(tab: tab, store: store, isPresented: $showMenu)
        }
        .sheet(isPresented: $showDeviceSheet) {
            DeviceConnectionSheet()
                .environmentObject(vm)
                .environmentObject(nav)
        }
        .onAppear { address = tab.urlString }
        .onChange(of: tab.urlString) { newValue in
            if !addressFocused { address = newValue }
        }
        .overlay(alignment: .bottom) {
            if let msg = tab.blockedAdMessage {
                HStack(spacing: 8) {
                    Image(systemName: "shield.fill")
                        .foregroundColor(Theme.primary)
                        .font(.system(size: 14, weight: .bold))
                    Text(msg)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
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
                .onAppear {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                        withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                            tab.blockedAdMessage = nil
                        }
                    }
                }
            }
        }
    }

    // MARK: - Top bar (URL + Remote / TV / Play, matching Android)

    private var topBar: some View {
        HStack(spacing: 4) {
            Button { nav.navigate(to: .dashboard) } label: {
                Image(systemName: "square.grid.2x2.fill")
                    .font(.system(size: 16)).foregroundColor(Theme.primary)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)

            // URL pill
            HStack(spacing: 6) {
                Image(systemName: tab.urlString.hasPrefix("https") ? "lock.fill" : "globe")
                    .font(.caption).foregroundColor(Theme.onSurfaceVariant)
                TextField("Search or enter address", text: $address)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.webSearch)
                    .submitLabel(.go)
                    .focused($addressFocused)
                    .foregroundColor(Theme.onSurface)
                    .onSubmit { tab.load(address); addressFocused = false }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(Theme.surfaceContainer)
            .cornerRadius(20)

            if vm.isConnected {
                topAction("av.remote", tint: Theme.primary) { nav.navigate(to: .remote) }
            }
            tvButton
            playButton
        }
        .padding(.horizontal, 8).padding(.top, 6).padding(.bottom, 4)
    }

    private func topAction(_ systemImage: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage).font(.system(size: 20)).foregroundColor(tint)
                .frame(width: 34, height: 36)
        }
        .buttonStyle(.plain)
    }

    private var tvButton: some View {
        Button { showDeviceSheet = true } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "tv")
                    .font(.system(size: 20))
                    .foregroundColor(vm.isConnected ? Theme.primary : Theme.onSurface)
                    .frame(width: 34, height: 36)
                if vm.isConnected {
                    Circle().fill(Color(hex: 0x4CAF50)).frame(width: 8, height: 8)
                        .overlay(Circle().stroke(Theme.surface, lineWidth: 1.5))
                        .offset(x: -2, y: 2)
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var playButton: some View {
        let count = streams.count
        let enabled = count > 0 || vm.isConnected
        return Button { showDetected = true } label: {
            ZStack(alignment: .topTrailing) {
                Image(systemName: "play.fill")
                    .font(.system(size: 20))
                    .foregroundColor(enabled ? Theme.primary : Theme.onSurfaceVariant.opacity(0.4))
                    .frame(width: 34, height: 36)
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 9, weight: .bold)).foregroundColor(.white)
                        .frame(minWidth: 13).padding(2)
                        .background(Theme.danger).clipShape(Circle())
                        .offset(x: 4, y: -2)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    // MARK: - Bottom bar (back / forward / refresh, matching Android)

    private var toolbar: some View {
        HStack(spacing: 0) {
            toolButton("chevron.backward", enabled: tab.canGoBack) { tab.goBack() }
            toolButton("chevron.forward", enabled: tab.canGoForward) { tab.goForward() }
            if tab.isLoading {
                toolButton("xmark", enabled: true) { tab.stop() }
            } else {
                toolButton("arrow.clockwise", enabled: !tab.isHome) { tab.reload() }
            }
            Spacer()
            toolButton("square.on.square", enabled: true, badge: store.tabs.count) { showTabs = true }
            toolButton("ellipsis", enabled: true) { showMenu = true }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Theme.surfaceContainerLow)
    }

    private func toolButton(_ systemImage: String, enabled: Bool, badge: Int? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundColor(enabled ? Theme.onSurface : Theme.onSurfaceVariant.opacity(0.4))
                    .frame(width: 44, height: 36)
                if let badge, badge > 1 {
                    Text("\(badge)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(Theme.onPrimary)
                        .frame(minWidth: 14)
                        .padding(2)
                        .background(Theme.primaryDim)
                        .clipShape(Circle())
                        .offset(x: 4, y: -2)
                }
            }
        }
        .disabled(!enabled)
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
                                .font(.system(size: 13)).foregroundColor(Theme.onSurfaceVariant).frame(width: 20)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(s.title).font(.system(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                                Text(s.url).font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
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
                    .font(.system(size: 26, weight: .bold, design: .rounded))
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
                                        .font(.system(size: 14)).foregroundColor(Theme.onSurfaceVariant).frame(width: 22)
                                    VStack(alignment: .leading, spacing: 1) {
                                        Text(h.title).font(.system(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                                        Text(URL(string: h.url)?.host ?? h.url).font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
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
                        .font(.system(size: 14)).foregroundColor(Theme.onSurfaceVariant)
                        .frame(maxWidth: .infinity).padding(.top, 40)
                }
                Spacer(minLength: 20)
            }
            .padding(.horizontal, 16)
        }
        .background(Theme.surface)
    }

    private func sectionTitle(_ t: String) -> some View {
        Text(t).font(.system(size: 13, weight: .bold)).foregroundColor(Theme.onSurfaceVariant)
    }

    private func grid(items: [(String, String)]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 10)], spacing: 10) {
            ForEach(items.indices, id: \.self) { i in
                let item = items[i]
                Button { onOpen(item.1) } label: {
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainerHigh).frame(height: 56)
                            Image(systemName: "globe").font(.system(size: 20)).foregroundColor(Theme.primary)
                        }
                        Text(item.0).font(.system(size: 11)).foregroundColor(Theme.onSurface).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}
