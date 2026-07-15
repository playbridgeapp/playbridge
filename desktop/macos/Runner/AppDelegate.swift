import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    // PlayBridge keeps running in the menu bar after the window is closed.
    // Real exit happens via the tray "Quit" item.
    return false
  }

  override func applicationShouldHandleReopen(
    _ sender: NSApplication,
    hasVisibleWindows flag: Bool
  ) -> Bool {
    // Clicking the Dock/Finder app while PlayBridge is hidden should activate
    // the existing process and reveal its window, never create another window.
    if !flag {
      mainFlutterWindow?.makeKeyAndOrderFront(self)
    }
    sender.activate(ignoringOtherApps: true)
    return true
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }
}
