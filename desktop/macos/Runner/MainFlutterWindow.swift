import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  /// Observers for fullscreen enter/exit (menu-bar / Control Center access).
  private var fullscreenObservers: [NSObjectProtocol] = []

  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)

    // ----- Glassmorphism: transparent titlebar + NSVisualEffectView backdrop -----
    self.titleVisibility = .hidden
    self.titlebarAppearsTransparent = true
    self.styleMask.insert(.fullSizeContentView)
    self.isOpaque = false
    self.backgroundColor = .clear
    self.appearance = NSAppearance(named: .darkAqua)

    // Make the green traffic-light button enter native fullscreen (not just
    // "zoom to screen size"). Without this the green button is a zoom toggle.
    self.collectionBehavior.insert(.fullScreenPrimary)
    self.collectionBehavior.insert(.fullScreenAllowsTiling)

    // Make the Flutter view itself non-opaque so the blur shows through wherever
    // Dart paints with a transparent / translucent color.
    if let flutterView = flutterViewController.view as? NSView {
      flutterView.wantsLayer = true
      flutterView.layer?.backgroundColor = NSColor.clear.cgColor
    }

    let visualEffect = NSVisualEffectView()
    visualEffect.translatesAutoresizingMaskIntoConstraints = false
    visualEffect.blendingMode = .behindWindow
    visualEffect.material = .hudWindow      // dark, vibrant — pairs well with video
    visualEffect.state = .active

    if let contentView = self.contentView {
      contentView.addSubview(visualEffect, positioned: .below, relativeTo: nil)
      NSLayoutConstraint.activate([
        visualEffect.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
        visualEffect.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
        visualEffect.topAnchor.constraint(equalTo: contentView.topAnchor),
        visualEffect.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
      ])
    }

    RegisterGeneratedPlugins(registry: flutterViewController)

    // Bluetooth headset / media-key remote control (Now Playing + togglePlayPause).
    MediaRemoteHandler.shared.register(
      with: flutterViewController.engine.binaryMessenger
    )

    // Agent apps (LSUIElement) run as .accessory and permanently suppress the
    // menu bar in fullscreen. Promote to .regular while fullscreen so the
    // system menu bar / Control Center / Notification Center can auto-show
    // when the cursor hits the top of the screen (like Safari, IINA, etc.).
    registerFullscreenMenuBarFix()

    super.awakeFromNib()
  }

  deinit {
    for o in fullscreenObservers {
      NotificationCenter.default.removeObserver(o)
    }
  }

  private func registerFullscreenMenuBarFix() {
    let nc = NotificationCenter.default
    fullscreenObservers.append(
      nc.addObserver(
        forName: NSWindow.willEnterFullScreenNotification,
        object: self,
        queue: .main
      ) { _ in
        // Become a normal app for this Space so macOS auto-hides/shows the
        // menu bar on the top edge (and unlocks Control Center / NC).
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
      }
    )
    fullscreenObservers.append(
      nc.addObserver(
        forName: NSWindow.didExitFullScreenNotification,
        object: self,
        queue: .main
      ) { [weak self] _ in
        // Back to tray/agent behavior (no Dock icon) matching LSUIElement.
        // setActivationPolicy(.accessory) can re-key the previously front app;
        // re-assert focus after the policy settles so playback keeps the window.
        NSApp.setActivationPolicy(.accessory)
        DispatchQueue.main.async {
          NSApp.activate(ignoringOtherApps: true)
          self?.makeKeyAndOrderFront(nil)
        }
      }
    )
  }
}
