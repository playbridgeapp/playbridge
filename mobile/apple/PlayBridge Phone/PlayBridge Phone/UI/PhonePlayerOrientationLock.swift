// SPDX-License-Identifier: MPL-2.0
import UIKit

/// The phone player's rotate button: switches between portrait and landscape and holds
/// it while the player is open, so tilting the phone doesn't undo it. Outside the
/// player the app supports its normal orientations.
@MainActor enum PhonePlayerOrientationLock {
    static var appDefault: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
    private(set) static var mask: UIInterfaceOrientationMask?
    /// The orientation before the first tap, restored when the player closes.
    private static var start: UIInterfaceOrientationMask?
    static var supported: UIInterfaceOrientationMask { mask ?? appDefault }

    private static var scene: UIWindowScene? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    }

    static var isLandscape: Bool { scene?.interfaceOrientation.isLandscape ?? false }

    /// Portrait ⇄ landscape, held until the player closes.
    static func toggle() {
        guard let scene else { return }
        let target: UIInterfaceOrientationMask = scene.interfaceOrientation.isLandscape ? .portrait : .landscape
        if start == nil { start = mask(for: scene.interfaceOrientation) }
        mask = target
        refresh(scene)
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: target)) { _ in }
    }

    /// Called when the player closes; free rotation resumes.
    static func release() {
        guard mask != nil else { return }
        mask = nil
        let previous = start
        start = nil
        guard let scene else { return }
        refresh(scene)
        if let previous, previous != mask(for: scene.interfaceOrientation) {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: previous)) { _ in }
        }
    }

    private static func mask(for orientation: UIInterfaceOrientation) -> UIInterfaceOrientationMask {
        switch orientation {
        case .landscapeLeft: return .landscapeLeft
        case .landscapeRight: return .landscapeRight
        case .portraitUpsideDown: return .portraitUpsideDown
        default: return .portrait
        }
    }

    private static func refresh(_ scene: UIWindowScene) {
        for window in scene.windows {
            var controller = window.rootViewController
            while let current = controller {
                current.setNeedsUpdateOfSupportedInterfaceOrientations()
                controller = current.presentedViewController
            }
        }
    }
}

final class PlayBridgeAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated { PhonePlayerOrientationLock.supported }
    }
}
