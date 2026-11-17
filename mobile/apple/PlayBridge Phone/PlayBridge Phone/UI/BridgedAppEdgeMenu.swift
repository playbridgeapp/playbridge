import SwiftUI
import UIKit

/// Browser controls for the app surface, hidden while Movi owns fullscreen.
struct BridgedAppEdgeMenu: View {
    @ObservedObject var tab: BrowserTab
    @EnvironmentObject private var nav: NavigationViewModel
    @State private var showMenu = false
    @EnvironmentObject private var store: BrowserStore
    @State private var showSettings = false
    @State private var pendingAction: (() -> Void)?
    @State private var interfaceOrientation: UIInterfaceOrientation = .unknown
    @State private var safeInsets = BridgedAppEdgeHandleLayout.SafeInsets()
    @State private var savedPosition = BridgedAppEdgeHandleLayout.load()
    @State private var dragStartCenter: CGPoint?
    @State private var dragCenter: CGPoint?

    // Interface landscapeLeft places the iPhone's front camera on the right.
    private var cameraSide: BridgedAppEdgeHandleLayout.CameraSide {
        switch interfaceOrientation {
        case .landscapeLeft: return .right
        case .landscapeRight: return .left
        default: return .top
        }
    }

    private var effectiveEdge: BridgedAppEdgeHandleLayout.Edge {
        BridgedAppEdgeHandleLayout.effectiveEdge(for: savedPosition, cameraSide: cameraSide)
    }

    var body: some View {
        GeometryReader { proxy in
            let restingCenter = BridgedAppEdgeHandleLayout.center(
                for: savedPosition,
                in: proxy.size,
                safeInsets: safeInsets,
                cameraSide: cameraSide
            )
            let displayCenter = dragCenter ?? restingCenter
            Button { showMenu = true } label: {
                Image(systemName: effectiveEdge == .left ? "chevron.right" : "chevron.left")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.primary)
                    .frame(
                        width: BridgedAppEdgeHandleLayout.handleSize.width,
                        height: BridgedAppEdgeHandleLayout.handleSize.height
                    )
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Bridged app menu")
            .position(displayCenter)
            .highPriorityGesture(
                DragGesture(minimumDistance: 8, coordinateSpace: .named("bridged-edge-space"))
                    .onChanged { value in
                        let start = dragStartCenter ?? restingCenter
                        if dragStartCenter == nil { dragStartCenter = start }
                        let raw = CGPoint(
                            x: start.x + value.translation.width,
                            y: start.y + value.translation.height
                        )
                        dragCenter = BridgedAppEdgeHandleLayout.clampedCenter(
                            raw,
                            in: proxy.size,
                            safeInsets: safeInsets,
                            cameraSide: cameraSide
                        )
                    }
                    .onEnded { value in
                        let start = dragStartCenter ?? restingCenter
                        let raw = CGPoint(
                            x: start.x + value.translation.width,
                            y: start.y + value.translation.height
                        )
                        let snapped = BridgedAppEdgeHandleLayout.snapOnRelease(
                            rawCenter: raw,
                            in: proxy.size,
                            safeInsets: safeInsets,
                            cameraSide: cameraSide
                        )
                        dragStartCenter = nil
                        dragCenter = nil
                        withAnimation(.interactiveSpring(response: 0.25, dampingFraction: 0.85)) {
                            savedPosition = snapped
                        }
                        BridgedAppEdgeHandleLayout.save(snapped)
                    }
            )
            .background(
                ZStack {
                    BridgedAppOrientationReader(orientation: $interfaceOrientation)
                    BridgedAppSafeAreaReader(insets: $safeInsets)
                }
                .allowsHitTesting(false)
            )
        }
        .coordinateSpace(name: "bridged-edge-space")
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
                action("Play on", icon: "tv") { nav.openPlayOn() }
                action("Reload", icon: "arrow.clockwise") { tab.reload() }
                action("App Settings", icon: "gearshape") { showSettings = true }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .presentationDetents([.medium])
            .presentationDragIndicator(.visible)
        }
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

private struct BridgedAppSafeAreaReader: UIViewControllerRepresentable {
    @Binding var insets: BridgedAppEdgeHandleLayout.SafeInsets

    func makeUIViewController(context: Context) -> SafeAreaController {
        let controller = SafeAreaController()
        controller.view.backgroundColor = .clear
        controller.view.isUserInteractionEnabled = false
        controller.onChange = { insets = $0 }
        return controller
    }

    func updateUIViewController(_ controller: SafeAreaController, context: Context) {
        controller.onChange = { insets = $0 }
    }

    final class SafeAreaController: UIViewController {
        var onChange: ((BridgedAppEdgeHandleLayout.SafeInsets) -> Void)?
        private var lastInsets = BridgedAppEdgeHandleLayout.SafeInsets()

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            reportInsets()
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            reportInsets()
        }

        override func viewSafeAreaInsetsDidChange() {
            super.viewSafeAreaInsetsDidChange()
            reportInsets()
        }

        override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
            super.viewWillTransition(to: size, with: coordinator)
            coordinator.animate(alongsideTransition: nil) { [weak self] _ in self?.reportInsets() }
        }

        private func reportInsets() {
            let raw = view.window?.safeAreaInsets ?? view.safeAreaInsets
            let next = BridgedAppEdgeHandleLayout.SafeInsets(
                top: raw.top, left: raw.left, bottom: raw.bottom, right: raw.right
            )
            guard next != lastInsets else { return }
            lastInsets = next
            DispatchQueue.main.async { [weak self] in self?.onChange?(next) }
        }
    }
}
