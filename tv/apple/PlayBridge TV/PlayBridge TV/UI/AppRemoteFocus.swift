import SwiftUI
import Combine
import UIKit

final class AppRemoteFocusDriver: ObservableObject {
    private struct Scope {
        weak var view: UIView?
        let read: () -> AppFocusID?
        let write: (AppFocusID?) -> Void
        let root: Bool
        let back: () -> Void
        let home: () -> Void
        var model = AppFocusModel()
    }
    private var actions: [AppFocusID: WeakAction] = [:]
    private var scopes: [UUID: Scope] = [:]
    private var stack = AppFocusScopeStack()
    weak var anchor: UIView?
    fileprivate weak var window: UIWindow?
    var acceptsInput: () -> Bool = { false }
    var rootBack: () -> Void = {}
    var rootHome: () -> Void = {}

    fileprivate func register(_ view: AppRemoteActionView) { actions[view.id] = WeakAction(view) }
    fileprivate func unregister(_ view: AppRemoteActionView) {
        if actions[view.id]?.view === view { actions.removeValue(forKey: view.id) }
    }
    fileprivate func scope(_ id: UUID, view: UIView, focus: FocusState<AppFocusID?>.Binding,
                           root: Bool, back: @escaping () -> Void, home: @escaping () -> Void) {
        var value = Scope(view: view, read: { focus.wrappedValue }, write: { focus.wrappedValue = $0 },
                          root: root, back: back, home: home)
        value.model = scopes[id]?.model ?? AppFocusModel(focused: focus.wrappedValue)
        scopes[id] = value
        stack.register(id)
    }
    fileprivate func removeScope(_ id: UUID) { scopes.removeValue(forKey: id); stack.remove(id) }
    fileprivate func observedFocus(_ id: AppFocusID?, scope: UUID) { scopes[scope]?.model.focused = id }

    func handle(_ key: String) {
        guard let (window, root) = context() else { log(key, target: nil, scope: nil); return }
        let eligible = Set(scopes.compactMap { id, scope in
            scope.view.flatMap { visible($0, in: window) && $0.isDescendant(of: root) ? id : nil }
        })
        guard let scopeID = stack.active(eligible: eligible), let scope = scopes[scopeID] else {
            log(key, target: nil, scope: nil)
            return
        }
        let registered = actions.values.compactMap(\.view).filter {
            $0.scope == scopeID && $0.enabled && visible($0, in: window) && $0.isDescendant(of: root)
        }
        let entries = Dictionary(uniqueKeysWithValues: registered.map { ($0.id, $0) })
        var target: AppFocusID?
        if key == "home" {
            let closing = stack.ids.reversed().compactMap { scopes[$0] }.filter { !$0.root }
            for id in stack.ids where scopes[id]?.root == false { removeScope(id) }
            for scope in closing { scope.home() }
            rootHome()
        } else if key == "back" {
            if scope.root { rootBack() }
            else { removeScope(scopeID); scope.back() }
        } else if key == "dpad_center" {
            // FocusState identity authorizes OK; never infer an action from overlapping rects.
            let focus = AppFocusModel(focused: scope.read())
            if let id = focus.activation(registered: Set(entries.keys)) { target = id; entries[id]?.action() }
        } else if let direction = AppFocusDirection(rawValue: key) {
            if let id = scope.read(), entries[id]?.direction?(direction) == true { target = id }
            else {
                if scopes[scopeID]?.model.focused == nil { scopes[scopeID]?.model.focused = scope.read() }
                let frames = entries.mapValues { $0.convert($0.bounds, to: window) }
                target = scopes[scopeID]?.model.move(direction, frames: frames)
                if let target, let entry = entries[target] {
                    entry.reveal?(target)
                    scope.write(target)
                }
            }
        }
        log(key, target: target, scope: scopeID)
    }

    private func log(_ key: String, target: AppFocusID?, scope: UUID?) {
#if DEBUG
        let key = ["dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home"].contains(key) ? key : "unknown"
        DispatchQueue.main.async { [weak self] in
            let focused = scope.flatMap { self?.scopes[$0]?.read() }
            print("[AppDpad] key=\(key) target=\(target?.logValue ?? "none") focused=\(focused?.logValue ?? "none")")
        }
#endif
    }

    /// UIKit supplies only foreground/owned-window checks, never focus movement or activation.
    private func context() -> (UIWindow, UIView)? {
        guard acceptsInput(), UIApplication.shared.applicationState == .active,
              let window = anchor?.window ?? window, window.isKeyWindow,
              window.windowScene?.activationState == .foregroundActive,
              var controller = window.rootViewController else { return nil }
        while let presented = controller.presentedViewController {
            guard !presented.isBeingDismissed else { return nil }
            controller = presented
        }
        guard !(controller is UIAlertController), let root = controller.viewIfLoaded,
              let system = UIFocusSystem.focusSystem(for: root) else { return nil }
        if let focused = system.focusedItem, !root.contains(focused) { return nil }
        return (window, root)
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
}

private final class WeakAction {
    weak var view: AppRemoteActionView?
    init(_ view: AppRemoteActionView) { self.view = view }
}
private struct AppRemoteDriverKey: EnvironmentKey { static let defaultValue: AppRemoteFocusDriver? = nil }
private struct AppScopeBinding {
    let id: UUID
    let focus: FocusState<AppFocusID?>.Binding
}
private struct AppScopeKey: EnvironmentKey { static let defaultValue: AppScopeBinding? = nil }
private struct AppRevealKey: EnvironmentKey { static let defaultValue: ((AppFocusID) -> Void)? = nil }

extension EnvironmentValues {
    var appRemoteDriver: AppRemoteFocusDriver? {
        get { self[AppRemoteDriverKey.self] }
        set { self[AppRemoteDriverKey.self] = newValue }
    }
}

private final class AppRemoteActionView: UIView {
    weak var driver: AppRemoteFocusDriver?
    var id = AppFocusID()
    var scope = UUID()
    var enabled = true
    var action: () -> Void = {}
    var direction: ((AppFocusDirection) -> Bool)?
    var reveal: ((AppFocusID) -> Void)?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { driver?.register(self) } else { driver?.unregister(self) }
    }
}
private struct AppActionRegistration: UIViewRepresentable {
    @Environment(\.appRemoteDriver) private var driver
    @Environment(\.isEnabled) private var enabled
    let id: AppFocusID
    let scope: UUID
    let action: () -> Void
    let direction: ((AppFocusDirection) -> Bool)?
    let reveal: ((AppFocusID) -> Void)?
    func makeUIView(context: Context) -> AppRemoteActionView {
        let view = AppRemoteActionView()
        view.isUserInteractionEnabled = false
        view.id = id; view.scope = scope; view.driver = driver
        return view
    }
    func updateUIView(_ view: AppRemoteActionView, context: Context) {
        view.enabled = enabled; view.action = action; view.direction = direction; view.reveal = reveal
        if view.window != nil { driver?.register(view) }
    }
    static func dismantleUIView(_ view: AppRemoteActionView, coordinator: ()) { view.driver?.unregister(view) }
}
private struct AppRemoteAction: ViewModifier {
    @Environment(\.appRemoteDriver) private var driver
    @Environment(\.appScopeBinding) private var scope
    @Environment(\.appRemoteReveal) private var reveal
    @State private var generatedID = AppFocusID()
    let suppliedID: AppFocusID?
    let action: () -> Void
    let focusChanged: (Bool) -> Void
    let direction: ((AppFocusDirection) -> Bool)?
    func body(content: Content) -> some View {
        let id = suppliedID ?? generatedID
        if let scope {
            content.focused(scope.focus, equals: id)
                .id(id)
                .background(AppActionRegistration(id: id, scope: scope.id, action: action, direction: direction, reveal: reveal))
                .onAppear { focusChanged(scope.focus.wrappedValue == id) }
                .onChange(of: scope.focus.wrappedValue) { old, new in
                    if old == id || new == id { focusChanged(new == id) }
                }
        } else { content }
    }
}

private extension EnvironmentValues {
    var appScopeBinding: AppScopeBinding? {
        get { self[AppScopeKey.self] }
        set { self[AppScopeKey.self] = newValue }
    }
    var appRemoteReveal: ((AppFocusID) -> Void)? {
        get { self[AppRevealKey.self] }
        set { self[AppRevealKey.self] = newValue }
    }
}
private final class AppRemoteScopeView: UIView {
    var register: () -> Void = {}
    var unregister: () -> Void = {}
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil { register() } else { unregister() }
    }
}

private struct AppScopeRegistration: UIViewRepresentable {
    @Environment(\.appRemoteDriver) private var driver
    let id: UUID
    let focus: FocusState<AppFocusID?>.Binding
    let root: Bool
    let back: () -> Void
    let home: () -> Void
    func makeUIView(context: Context) -> AppRemoteScopeView { AppRemoteScopeView() }
    func updateUIView(_ view: AppRemoteScopeView, context: Context) {
        let owner = driver, id = id, focus = focus, root = root, back = back, home = home
        view.register = { [weak owner, weak view] in
            guard let view else { return }
            owner?.scope(id, view: view, focus: focus, root: root, back: back, home: home)
        }
        view.unregister = { [weak owner] in owner?.removeScope(id) }
        view.register()
    }
    static func dismantleUIView(_ view: AppRemoteScopeView, coordinator: ()) { view.unregister() }
}
private struct AppRemoteScope: ViewModifier {
    @Environment(\.appRemoteDriver) private var driver
    @FocusState private var focused: AppFocusID?
    @State private var id = UUID()
    let root: Bool
    let initialFocus: AppFocusID?
    let back: () -> Void
    let home: () -> Void
    private func scoped(_ content: Content) -> some View {
        content.environment(\.appScopeBinding, AppScopeBinding(id: id, focus: $focused))
            .background(AppScopeRegistration(id: id, focus: $focused, root: root, back: back, home: home))
            .onChange(of: focused) { _, value in driver?.observedFocus(value, scope: id) }
            .onDisappear { driver?.removeScope(id) }
    }
    func body(content: Content) -> some View {
        if let initialFocus { scoped(content).defaultFocus($focused, initialFocus) }
        else { scoped(content) }
    }
}
private final class AppRemoteAnchorView: UIView {
    weak var driver: AppRemoteFocusDriver?
    override func didMoveToWindow() {
        super.didMoveToWindow()
        if let window { driver?.window = window }
    }
}
struct AppRemoteAnchor: UIViewRepresentable {
    let driver: AppRemoteFocusDriver
    let enabled: () -> Bool
    let onBack: () -> Void
    let onHome: () -> Void
    func makeUIView(context: Context) -> UIView {
        let view = AppRemoteAnchorView(); view.driver = driver; return view
    }
    func updateUIView(_ view: UIView, context: Context) {
        driver.anchor = view
        if let window = view.window { driver.window = window }
        driver.acceptsInput = enabled; driver.rootBack = onBack; driver.rootHome = onHome
    }
}
private struct AppRemoteScroll: ViewModifier {
    func body(content: Content) -> some View {
        ScrollViewReader { proxy in
            content.environment(\.appRemoteReveal, { id in proxy.scrollTo(id, anchor: .center) })
        }
    }
}
extension View {
    func appRemoteAction(_ action: @escaping () -> Void, id: AppFocusID? = nil,
                         focusChanged: @escaping (Bool) -> Void = { _ in },
                         direction: ((AppFocusDirection) -> Bool)? = nil) -> some View {
        modifier(AppRemoteAction(suppliedID: id, action: action, focusChanged: focusChanged, direction: direction))
    }
    func appRemoteScope(back: @escaping () -> Void = {}, home: @escaping () -> Void = {},
                        root: Bool = false, initialFocus: AppFocusID? = nil) -> some View {
        modifier(AppRemoteScope(root: root, initialFocus: initialFocus, back: back, home: home))
    }
    func appRemoteScroll() -> some View { modifier(AppRemoteScroll()) }
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
    @State private var cancelID = AppFocusID()
    var body: some View {
        VStack(spacing: 32) {
            Text(title).font(.title)
            Text(message).font(.body).multilineTextAlignment(.center)
            HStack(spacing: 32) {
                Button("Cancel") { dismiss() }.appRemoteAction({ dismiss() }, id: cancelID)
                Button(confirm, role: .destructive) { dismiss(); action() }
                    .appRemoteAction { dismiss(); action() }
            }
        }
        .padding(80)
        .appRemoteScope(back: { dismiss() }, home: { dismiss() }, initialFocus: cancelID)
    }
}
