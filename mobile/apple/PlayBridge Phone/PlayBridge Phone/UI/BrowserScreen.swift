import SwiftUI

/// The Browse tab: a multi-tab WKWebView with an address bar, nav controls, a detected-stream
/// badge, and tab switching. Detection runs automatically via the injected user script.
struct BrowserScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @StateObject private var store = BrowserStore()
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
            }
        }
    }
}

/// Renders one active tab (toolbar + web content). Keyed by tab id so it rebuilds on switch.
private struct ActiveTabView: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @EnvironmentObject private var vm: ConnectionViewModel
    @Binding var showTabs: Bool

    @State private var address = ""
    @State private var showDetected = false
    @State private var previewVideo: DetectedVideo?
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
            DetectedStreamsSheet(videos: tab.detector.videos) { video in
                showDetected = false
                previewVideo = video
            }
            .environmentObject(vm)
        }
        .sheet(item: $previewVideo) { video in
            StreamPreviewSheet(video: video, detected: tab.detector.videos).environmentObject(vm)
        }
        .onAppear { address = tab.urlString }
        .onChange(of: tab.urlString) { newValue in
            if !addressFocused { address = newValue }
        }
    }

    private var addressBar: some View {
        HStack(spacing: 8) {
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
            adBlockButton
            toolButton("square.on.square", enabled: true, badge: store.tabs.count) { showTabs = true }
            toolButton("plus", enabled: true) { store.newTab() }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Theme.surfaceContainerLow)
    }

    private var adBlockButton: some View {
        Button { store.toggleAdBlock() } label: {
            Image(systemName: store.adBlockEnabled ? "shield.fill" : "shield.slash")
                .font(.title3)
                .foregroundColor(store.adBlockEnabled ? Theme.primary : Theme.onSurfaceVariant)
                .frame(width: 44, height: 36)
        }
    }

    @ViewBuilder private var detectedButton: some View {
        let count = streams.count
        Button { if count > 0 { showDetected = true } } label: {
            HStack(spacing: 6) {
                Image(systemName: count > 0 ? "play.tv.fill" : "play.tv")
                Text(count > 0 ? "\(count) stream\(count == 1 ? "" : "s")" : "No streams")
                    .font(.caption.bold())
            }
            .foregroundColor(count > 0 ? Theme.onPrimary : Theme.onSurfaceVariant)
            .padding(.horizontal, 14).padding(.vertical, 8)
            .background(count > 0 ? AnyShapeStyle(Theme.ctaGradient) : AnyShapeStyle(Theme.surfaceContainerHigh))
            .clipShape(Capsule())
        }
        .disabled(count == 0)
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
