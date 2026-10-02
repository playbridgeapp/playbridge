import SwiftUI
import UIKit

/// Browser controls for the app surface, hidden while Movi owns fullscreen.
struct BridgedAppEdgeMenu: View {
    @ObservedObject var tab: BrowserTab
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showMenu = false
    @EnvironmentObject private var store: BrowserStore
    @State private var showSettings = false
    @State private var showConnection = false
    @State private var pendingAction: (() -> Void)?
    @State private var interfaceOrientation: UIInterfaceOrientation = .unknown

    // Interface landscapeLeft places the iPhone's front camera on the right.
    private var usesLeftEdge: Bool { interfaceOrientation == .landscapeLeft }

    var body: some View {
        Button { showMenu = true } label: {
            Image(systemName: usesLeftEdge ? "chevron.right" : "chevron.left")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Theme.primary)
                .frame(width: 28, height: 56)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Bridged app menu")
        .frame(maxWidth: .infinity, alignment: usesLeftEdge ? .leading : .trailing)
        .background(BridgedAppOrientationReader(orientation: $interfaceOrientation))
        .sheet(isPresented: $showMenu, onDismiss: {
            let action = pendingAction
            pendingAction = nil
            action?()
        }) {
            VStack(alignment: .leading, spacing: 12) {
                Text(tab.title).font(Theme.font(.title3).bold()).lineLimit(1)
                action("Back", icon: "chevron.left") {
                    if tab.canGoBack { tab.goBack() }
                    else { nav.navigate(to: .dashboard) }
                }
                action("Dashboard", icon: "square.grid.2x2.fill") { nav.navigate(to: .dashboard) }
                action("Remote", icon: "av.remote") { nav.navigate(to: .remote) }
                action("Connect TV", icon: "tv") { showConnection = true }
                action("Reload", icon: "arrow.clockwise") { tab.reload() }
                action("App Settings", icon: "gearshape") { showSettings = true }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $showConnection) { DeviceConnectionSheet() }
        .sheet(isPresented: $showSettings) {
            BrowserAppSettingsSheet(tab: tab, store: store)
        }
    }

    private func action(_ title: String, icon: String, perform: @escaping () -> Void) -> some View {
        Button {
            pendingAction = perform
            showMenu = false
        } label: {
            Label(title, systemImage: icon)
                .font(Theme.font(.body))
                .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                .contentShape(Rectangle())
        }
    }
}

/// Read the owning window's displayed orientation, including when entering an app
/// already in landscape. Physical device orientation can differ under rotation lock.
private struct BridgedAppOrientationReader: UIViewControllerRepresentable {
    @Binding var orientation: UIInterfaceOrientation

    func makeUIViewController(context: Context) -> OrientationController {
        let controller = OrientationController()
        controller.view.backgroundColor = .clear
        controller.view.isUserInteractionEnabled = false
        controller.onChange = { orientation = $0 }
        return controller
    }

    func updateUIViewController(_ controller: OrientationController, context: Context) {
        controller.onChange = { orientation = $0 }
    }

    final class OrientationController: UIViewController {
        var onChange: ((UIInterfaceOrientation) -> Void)?
        private var lastOrientation: UIInterfaceOrientation = .unknown

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            reportOrientation()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            reportOrientation()
        }

        override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
            super.viewWillTransition(to: size, with: coordinator)
            coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.reportOrientation() }
        }

        private func reportOrientation() {
            guard let orientation = view.window?.windowScene?.interfaceOrientation,
                  orientation != .unknown, orientation != lastOrientation else { return }
            lastOrientation = orientation
            DispatchQueue.main.async { [weak self] in self?.onChange?(orientation) }
        }
    }
}
