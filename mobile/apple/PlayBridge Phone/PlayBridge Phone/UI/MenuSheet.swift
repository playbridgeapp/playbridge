import SwiftUI

struct MenuSheet: View {
    @ObservedObject var tab: BrowserTab
    @ObservedObject var store: BrowserStore
    @Binding var isPresented: Bool

    @State private var showAdblockSettings = false
    @State private var showComingSoonAlert = false
    @State private var comingSoonFeatureName = ""

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
                        comingSoon: true
                    )
                    menuGridItem(
                        icon: "clock.arrow.circlepath",
                        label: "History",
                        comingSoon: true
                    )
                    menuGridItem(
                        icon: "arrow.down.circle",
                        label: "Downloads",
                        comingSoon: true
                    )
                    menuGridItem(
                        icon: "star",
                        label: "Add Bookmark",
                        comingSoon: true
                    )
                    menuGridItem(
                        icon: "magnifyingglass",
                        label: "Find in Page",
                        comingSoon: true
                    )
                }

                // Row 2
                HStack(spacing: 0) {
                    menuGridItem(
                        icon: "puzzlepiece",
                        label: "Extensions",
                        comingSoon: true
                    )
                    menuGridItem(
                        icon: "gearshape",
                        label: "Settings",
                        comingSoon: true
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
                    
                    // Spacer grid cell to match layout of 5 items
                    Spacer()
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 30)
        }
        .background(Theme.surfaceContainerLow.ignoresSafeArea())
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
                        .font(.system(size: 20))
                        .foregroundColor(selected ? Theme.primary : Theme.onSurfaceVariant)
                }
                
                Text(label)
                    .font(.system(size: 11, weight: .regular))
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
