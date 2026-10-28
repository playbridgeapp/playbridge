import SwiftUI
import Combine
import UIKit

final class AppRemoteFocusDriver: ObservableObject {
    private struct Scope {
        let id: UUID
        var back: () -> Void
        var home: () -> Void
    }
    private var actions: [ObjectIdentifier: WeakAction] = [:]
    private var scopes: [Scope] = []
    weak var anchor: UIView?
    var enabled = false
    var rootBack: () -> Void = {}
    var rootHome: () -> Void = {}
    private var navigationRevision = 0

    fileprivate func register(_ view: AppRemoteActionView) {
        let id = ObjectIdentifier(view)
        if actions[id]?.view !== view { actions[id] = WeakAction(view) }
    }

    fileprivate func unregister(_ view: AppRemoteActionView) { actions.removeValue(forKey: ObjectIdentifier(view)) }

    fileprivate func scope(_ id: UUID, back: @escaping () -> Void, home: @escaping () -> Void) {
        if let index = scopes.firstIndex(where: { $0.id == id }) { scopes[index] = Scope(id: id, back: back, home: home) }
        else { scopes.append(Scope(id: id, back: back, home: home)); navigationRevision &+= 1 }
    }

    fileprivate func removeScope(_ id: UUID) {
        scopes.removeAll { $0.id == id }
        navigationRevision &+= 1
    }

    func handle(_ key: String) {
        guard let context = context() else { return }
        navigationRevision &+= 1
        if key == "home" {
            let closing = scopes.reversed()
            scopes.removeAll()
            for scope in closing { scope.home() }
            rootHome()
        } else if key == "back" {
            if let top = scopes.popLast() { top.back() } else { rootBack() }
        } else if key == "dpad_center" {
            guard let focused = context.system.focusedItem, let rect = frame(focused, in: context.window) else { return }
            let registered = actions.values.compactMap(\.view).filter {
                $0.enabled && $0.isDescendant(of: context.root) && visible($0, in: context.window)
            }
            let targets = registered.enumerated().map { AppFocusTarget(id: $0.offset, rect: $0.element.convert($0.element.bounds, to: context.window)) }
            if let id = AppFocusGeometry.action(at: rect, targets: targets) { registered[id].action() }
        } else if let direction = AppFocusDirection(rawValue: key) {
            move(direction, context: context, allowScroll: true)
        }
    }

    private struct Context {
        let window: UIWindow
        let root: UIView
        let system: UIFocusSystem
    }

    private func context() -> Context? {
        guard enabled, UIApplication.shared.applicationState == .active,
              let window = anchor?.window, window.isKeyWindow,
              window.windowScene?.activationState == .foregroundActive,
              var controller = window.rootViewController else { return nil }
        while let presented = controller.presentedViewController {
            guard !presented.isBeingDismissed else { return nil }
            controller = presented
        }
        guard !(controller is UIAlertController), let root = controller.viewIfLoaded,
              let system = UIFocusSystem.focusSystem(for: root) else { return nil }
        if let focused = system.focusedItem,
           !root.contains(focused) { return nil }
        return Context(window: window, root: root, system: system)
    }

    private func visible(_ view: UIView, in window: UIWindow) -> Bool {
        guard view.window === window else { return false }
        var ancestor: UIView? = view
        while let current = ancestor {
            if current.isHidden || current.alpha < 0.01 { return false }
            ancestor = current.superview
        }
        return true
    }

    private func frame(_ item: UIFocusItem, in window: UIWindow) -> CGRect? {
        if let view = item as? UIView { return view.convert(view.bounds, to: window) }
        return item.parentFocusEnvironment?.focusItemContainer?.coordinateSpace.convert(item.frame, to: window)
    }

    private func items(in root: UIView, window: UIWindow) -> [UIFocusItem] {
        var result: [UIFocusItem] = []
        var seen: Set<ObjectIdentifier> = []
        func visit(_ environment: UIFocusEnvironment) {
            guard seen.insert(ObjectIdentifier(environment)).inserted else { return }
            if let view = environment as? UIView, !visible(view, in: window) { return }
            if let item = environment as? UIFocusItem, item.canBecomeFocused { result.append(item) }
            if let container = environment.focusItemContainer {
                let search = container.coordinateSpace.convert(window.bounds.insetBy(dx: -window.bounds.width, dy: -window.bounds.height), from: window)
                for item in container.focusItems(in: search) { visit(item) }
            }
            if let view = environment as? UIView { for child in view.subviews { visit(child) } }
        }
        visit(root)
        return result
    }

    private func move(_ direction: AppFocusDirection, context: Context, allowScroll: Bool) {
        let focused = context.system.focusedItem
        if let text = focused as? UITextView, scroll(text, direction: direction) { return }
        let candidates = items(in: context.root, window: context.window).filter { candidate in
            focused.map { ObjectIdentifier($0) != ObjectIdentifier(candidate) } ?? true
        }
        let targets = candidates.enumerated().compactMap { index, item in
            frame(item, in: context.window).map { AppFocusTarget(id: index, rect: $0) }
        }
        guard let focused, let source = frame(focused, in: context.window) else {
            if let first = targets.sorted(by: { $0.rect.minY == $1.rect.minY ? $0.rect.minX < $1.rect.minX : $0.rect.minY < $1.rect.minY }).first {
                context.system.requestFocusUpdate(to: candidates[first.id])
                context.system.updateFocusIfNeeded()
            }
            return
        }
        if let id = AppFocusGeometry.nearest(from: source, direction: direction, targets: targets) {
            let target = candidates[id]
            if let view = target as? UIView {
                var ancestor = view.superview
                while let current = ancestor {
                    if let scroll = current as? UIScrollView {
                        scroll.scrollRectToVisible(view.convert(view.bounds, to: scroll).insetBy(dx: -16, dy: -16), animated: false)
                        scroll.layoutIfNeeded()
                    }
                    ancestor = current.superview
                }
            }
            context.system.requestFocusUpdate(to: target)
            context.system.updateFocusIfNeeded()
        } else if allowScroll, let view = focused as? UIView {
            var ancestor = view.superview
            while let current = ancestor {
                if let scrollView = current as? UIScrollView, scroll(scrollView, direction: direction) {
                    let revision = navigationRevision
                    DispatchQueue.main.async { [weak self] in
                        guard let self, self.navigationRevision == revision, let fresh = self.context() else { return }
                        self.move(direction, context: fresh, allowScroll: false)
                    }
                    return
                }
                ancestor = current.superview
            }
        }
    }

    private func scroll(_ view: UIScrollView, direction: AppFocusDirection) -> Bool {
        guard direction == .up || direction == .down else { return false }
        let minimum = -view.adjustedContentInset.top
        let maximum = max(minimum, view.contentSize.height - view.bounds.height + view.adjustedContentInset.bottom)
        let next = min(maximum, max(minimum, view.contentOffset.y + (direction == .up ? -1 : 1) * view.bounds.height * 0.7))
        guard abs(next - view.contentOffset.y) > 1 else { return false }
        view.setContentOffset(CGPoint(x: view.contentOffset.x, y: next), animated: false)
        view.layoutIfNeeded()
        return true
    }
}

private final class WeakAction {
    weak var view: AppRemoteActionView?
    init(_ view: AppRemoteActionView) { self.view = view }
}

private struct AppRemoteDriverKey: EnvironmentKey {
    static let defaultValue: AppRemoteFocusDriver? = nil
}

extension EnvironmentValues {
    var appRemoteDriver: AppRemoteFocusDriver? {
        get { self[AppRemoteDriverKey.self] }
        set { self[AppRemoteDriverKey.self] = newValue }
    }
}

private final class AppRemoteActionView: UIView {
    weak var driver: AppRemoteFocusDriver?
    var enabled = true
    var action: () -> Void = {}
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { driver?.register(self) } else { driver?.unregister(self) }
    }
}

private struct AppRemoteAction: UIViewRepresentable {
    @Environment(\.appRemoteDriver) private var driver
    @Environment(\.isEnabled) private var enabled
    let action: () -> Void
    func makeUIView(context: Context) -> AppRemoteActionView {
        let view = AppRemoteActionView()
        view.isUserInteractionEnabled = false
        view.driver = driver
        return view
    }
    func updateUIView(_ view: AppRemoteActionView, context: Context) {
        view.enabled = enabled
        view.action = action
        view.driver = driver
        if view.window != nil { driver?.register(view) }
    }
    static func dismantleUIView(_ view: AppRemoteActionView, coordinator: ()) { view.driver?.unregister(view) }
}

struct AppRemoteAnchor: UIViewRepresentable {
    let driver: AppRemoteFocusDriver
    let enabled: Bool
    let onBack: () -> Void
    let onHome: () -> Void
    func makeUIView(context: Context) -> UIView { UIView() }
    func updateUIView(_ view: UIView, context: Context) {
        driver.anchor = view
        driver.enabled = enabled
        driver.rootBack = onBack
        driver.rootHome = onHome
    }
}

private struct AppRemoteScope: ViewModifier {
    @Environment(\.appRemoteDriver) private var driver
    @State private var id = UUID()
    let back: () -> Void
    let home: () -> Void
    func body(content: Content) -> some View {
        content.onAppear { driver?.scope(id, back: back, home: home) }
            .onDisappear { driver?.removeScope(id) }
    }
}

extension View {
    func appRemoteAction(_ action: @escaping () -> Void) -> some View {
        background(AppRemoteAction(action: action))
    }
    func appRemoteScope(back: @escaping () -> Void, home: @escaping () -> Void) -> some View {
        modifier(AppRemoteScope(back: back, home: home))
    }
    func appRemoteConfirmation(_ title: String, isPresented: Binding<Bool>, message: String,
                               confirm: String, action: @escaping () -> Void) -> some View {
        sheet(isPresented: isPresented) {
            AppRemoteConfirmation(title: title, message: message, confirm: confirm, action: action)
        }
    }
}

private struct AppRemoteConfirmation: View {
    let title: String
    let message: String
    let confirm: String
    let action: () -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var cancelFocused: Bool
    var body: some View {
        VStack(spacing: 32) {
            Text(title).font(.title)
            Text(message).font(.body).multilineTextAlignment(.center)
            HStack(spacing: 32) {
                Button("Cancel") { dismiss() }
                    .focused($cancelFocused)
                    .appRemoteAction { dismiss() }
                Button(confirm, role: .destructive) { dismiss(); action() }
                    .appRemoteAction { dismiss(); action() }
            }
        }
        .padding(80)
        .defaultFocus($cancelFocused, true)
        .appRemoteScope(back: { dismiss() }, home: { dismiss() })
    }
}
