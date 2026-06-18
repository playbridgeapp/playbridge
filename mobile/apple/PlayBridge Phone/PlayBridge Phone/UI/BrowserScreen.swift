import SwiftUI

/// The Browse tab: a multi-tab WKWebView with an address bar, nav controls, a detected-stream
/// badge, and tab switching. Detection runs automatically via the injected user script.
struct BrowserScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var store: BrowserStore
    @State private var showTabs = false

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            if let tab = store.activeTab {
                ActiveTabView(tab: tab, store: store, showTabs: $showTabs)
                    .id(tab.id)
            }
        }
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
    @Binding var showTabs: Bool

    @State private var address = ""
    @State private var showDetected = false
    @State private var showMenu = false
    @FocusState private var addressFocused: Bool

    private var streams: [DetectedVideo] { tab.detector.videos.filter { !$0.isSubtitle } }

    var body: some View {
        VStack(spacing: 0) {
            addressBar
            if tab.isLoading && tab.progress < 1 {
                ProgressView(value: tab.progress).tint(Theme.primary)
                    .scaleEffect(x: 1, y: 0.6, anchor: .center)
            }
            WebViewContainer(tab: tab)
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

    private var addressBar: some View {
        HStack(spacing: 8) {
            Button {
                nav.navigate(to: .dashboard)
            } label: {
                Image(systemName: "house.fill")
                    .font(.system(size: 14))
                    .foregroundColor(Theme.primary)
                    .frame(width: 24, height: 24)
                    .background(Theme.surfaceContainerHigh)
                    .clipShape(Circle())
            }
            .buttonStyle(.plain)

            Image(systemName: tab.urlString.hasPrefix("https") ? "lock.fill" : "globe")
                .font(.caption).foregroundColor(Theme.onSurfaceVariant)
            TextField("Search or enter address", text: $address)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.webSearch)
                .submitLabel(.go)
                .focused($addressFocused)
                .foregroundColor(Theme.onSurface)
                .onSubmit {
                    tab.load(address)
                    addressFocused = false
                }
            if tab.isLoading {
                Button { tab.stop() } label: { Image(systemName: "xmark") }
                    .foregroundColor(Theme.onSurfaceVariant)
            } else {
                Button { tab.reload() } label: { Image(systemName: "arrow.clockwise") }
                    .foregroundColor(Theme.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Theme.surfaceContainer)
        .cornerRadius(12)
        .padding(.horizontal, 10).padding(.top, 8).padding(.bottom, 6)
    }

    private var toolbar: some View {
        HStack(spacing: 0) {
            toolButton("chevron.backward", enabled: tab.canGoBack) { tab.goBack() }
            toolButton("chevron.forward", enabled: tab.canGoForward) { tab.goForward() }
            Spacer()
            detectedButton
            Spacer()
            if vm.isConnected {
                toolButton("gamecontroller.fill", enabled: true) { nav.navigate(to: .remote) }
            }
            toolButton("square.on.square", enabled: true, badge: store.tabs.count) { showTabs = true }
            toolButton("ellipsis", enabled: true) { showMenu = true }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Theme.surfaceContainerLow)
    }

    @ViewBuilder private var detectedButton: some View {
        let count = streams.count
        let canTap = count > 0 || vm.isConnected
        Button { showDetected = true } label: {
            HStack(spacing: 6) {
                Image(systemName: count > 0 ? "play.tv.fill" : "play.tv")
                Text(count > 0 ? "\(count) stream\(count == 1 ? "" : "s")" : "Cast")
                    .font(.caption.bold())
            }
            .foregroundColor(count > 0 ? Theme.onPrimary : Theme.onSurfaceVariant)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(count > 0 ? AnyShapeStyle(Theme.ctaGradient) : AnyShapeStyle(Theme.surfaceContainerHigh))
            .clipShape(Capsule())
        }
        .disabled(!canTap)
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
