import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
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

    super.awakeFromNib()
  }
}
