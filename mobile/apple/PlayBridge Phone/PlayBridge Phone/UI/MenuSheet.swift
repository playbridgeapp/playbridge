import SwiftUI

struct MenuSheet: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @Binding var isPresented: Bool

    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var data: BrowserDataStore

    @State private var showAdblockSettings = false
    @State private var showDownloads = false
    @State private var showNetworkLogs = false
    @State private var allowPopups = false
    @State private var showComingSoonAlert = false
    @State private var comingSoonFeatureName = ""

    private func go(_ screen: AppScreen) {
        isPresented = false
        nav.navigate(to: screen)
    }

    var body: some View {
        VStack(spacing: 0) {
            // Drag handle indicator
            Capsule()
                .fill(Theme.onSurfaceVariant.opacity(0.3))
                .frame(width: 36, height: 5)
                .padding(.top, 10)
                .padding(.bottom, 20)

            // Grid content
            VStack(spacing: 20) {
                // Row 1
                HStack(spacing: 0) {
                    menuGridItem(
                        icon: "bookmark",
                        label: "Bookmarks",
                        action: { go(.bookmarks) }
                    )
                    menuGridItem(
                        icon: "clock.arrow.circlepath",
                        label: "History",
                        action: { go(.history) }
                    )
                    menuGridItem(
                        icon: data.isBookmarked(tab.urlString) ? "star.fill" : "star",
                        label: data.isBookmarked(tab.urlString) ? "Bookmarked" : "Add Bookmark",
                        selected: data.isBookmarked(tab.urlString),
                        action: {
                            data.toggleBookmark(url: tab.urlString, title: tab.title)
                            isPresented = false
                        }
                    )
                    menuGridItem(
                        icon: "magnifyingglass",
                        label: "Find in Page",
                        action: {
                            isPresented = false
                            tab.findInPage()
                        }
                    )
                    menuGridItem(
                        icon: "scope",
                        label: "Block Element",
                        action: {
                            isPresented = false
                            tab.startElementPicker()
                        }
                    )
                }

                // Row 2
                HStack(spacing: 0) {
                    menuGridItem(
                        icon: "network",
                        label: "Network logs",
                        action: { showNetworkLogs = true }
                    )
                    menuGridItem(
                        icon: "gearshape",
                        label: "Settings",
                        action: { go(.browserSettings) }
                    )
                    menuGridItem(
                        icon: "desktopcomputer",
                        label: "Desktop Site",
                        selected: tab.isDesktopMode,
                        action: {
                            tab.toggleDesktopMode()
                            isPresented = false
                        }
                    )
                    menuGridItem(
                        icon: "shield",
                        label: "Adblock",
                        selected: store.adBlockEnabled,
                        action: {
                            showAdblockSettings = true
                        }
                    )
                    
                    menuGridItem(icon: "arrow.down.circle", label: "Downloads", action: { showDownloads = true })
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
            if BrowserSitePolicy.origin(URL(string: tab.urlString)) != nil {
                Toggle("Allow popups for this site", isOn: $allowPopups)
                    .padding(.horizontal, 20)
                    .onChange(of: allowPopups) { value in
                        BrowserSitePolicy.setPopupsAllowed(value, url: URL(string: tab.urlString))
                    }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Theme.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .onAppear { allowPopups = BrowserSitePolicy.popupsAllowed(URL(string: tab.urlString)) }
        .sheet(isPresented: $showNetworkLogs) { BrowserNetworkLogView(tab: tab, store: store) }
        .sheet(isPresented: $showDownloads) { BrowserDownloadsView(downloads: store.downloads) }
        .sheet(isPresented: $showAdblockSettings) {
            AdblockSettingsSheet(store: store)
        }
        .alert("\(comingSoonFeatureName) Coming Soon", isPresented: $showComingSoonAlert) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("This feature is not yet available on iOS. The browser core is fully functional.")
        }
    }

    // MARK: - Helper Views

    @ViewBuilder
    private func menuGridItem(
        icon: String,
        label: String,
        selected: Bool = false,
        comingSoon: Bool = false,
        action: (() -> Void)? = nil
    ) -> some View {
        Button {
            if comingSoon {
                comingSoonFeatureName = label
                showComingSoonAlert = true
            } else {
                action?()
            }
        } label: {
            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(selected ? Theme.primaryDim.opacity(0.25) : Color.clear)
                        .frame(width: 48, height: 48)
                    
                    Image(systemName: icon)
                        .font(Theme.font(size: 20))
                        .foregroundColor(selected ? Theme.primary : Theme.onSurfaceVariant)
                }
                
                Text(label)
                    .font(Theme.font(size: 11, weight: .regular))
                    .foregroundColor(selected ? Theme.primary : Theme.onSurface.opacity(0.7))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .frame(height: 28)
            }
            .frame(maxWidth: .infinity)
            .opacity(comingSoon ? 0.4 : 1.0)
        }
        .buttonStyle(.plain)
    }
}
