import SwiftUI
import UIKit

/// Requests orientation only when a local player opens. It does not restrict
/// subsequent rotation and restores the preceding page when the player closes.
@MainActor struct PhonePlayerOpeningOrientationView: UIViewControllerRepresentable {
    let orientation: String?
    var onUnavailable: () -> Void = {}

    func makeUIViewController(context: Context) -> Controller {
        Controller(orientation: orientation, onUnavailable: onUnavailable)
    }
    func updateUIViewController(_ controller: Controller, context: Context) {}
    static func dismantleUIViewController(_ controller: Controller, coordinator: ()) { controller.restore() }

    final class Controller: UIViewController {
        private static var owner: UUID?
        private let id = UUID()
        private let orientation: String?
        private let onUnavailable: () -> Void
        private weak var scene: UIWindowScene?
        private var previous: UIInterfaceOrientationMask?
        private var opened = false

        init(orientation: String?, onUnavailable: @escaping () -> Void) {
            self.orientation = orientation; self.onUnavailable = onUnavailable
            super.init(nibName: nil, bundle: nil)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func loadView() { view = UIView(); view.isUserInteractionEnabled = false }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !opened, let scene = view.window?.windowScene else { return }
            opened = true; self.scene = scene; Self.owner = id
            let desired: UIInterfaceOrientationMask
            switch orientation {
            case "portrait": desired = .portrait
            case "landscape": desired = .landscape
            default: return
            }
            switch scene.interfaceOrientation {
            case .portrait: previous = .portrait
            case .portraitUpsideDown: previous = .portraitUpsideDown
            case .landscapeLeft: previous = .landscapeLeft
            case .landscapeRight: previous = .landscapeRight
            default: break
            }
            // The supported mask remains unchanged: this is an opening request,
            // not an orientation lock. Never reload the playback engine here.
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: desired)) { [weak self] _ in
                Task { @MainActor in
                    guard let self, Self.owner == self.id else { return }
                    self.onUnavailable()
                }
            }
        }
        func restore() {
            guard Self.owner == id else { return }
            Self.owner = nil
            guard let scene, let previous else { return }
            // Wait for the presentation to leave the view hierarchy, and do not
            // let an old dismissal rotate a newly opened player.
            DispatchQueue.main.async { [weak scene] in
                guard Self.owner == nil else { return }
                scene?.requestGeometryUpdate(.iOS(interfaceOrientations: previous)) { _ in }
            }
        }
    }
}
