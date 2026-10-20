import Cocoa
import CoreAudio
import AudioToolbox
import FlutterMacOS

/// HAL listeners and calls run on the main queue, as do Flutter channel callbacks.
final class SystemVolumeChannel: NSObject, FlutterStreamHandler {
  private var sink: FlutterEventSink?
  private var device = AudioObjectID(kAudioObjectUnknown)
  private var listeners: [(AudioObjectID, AudioObjectPropertyAddress, AudioObjectPropertyListenerBlock)] = []
  private var channel: FlutterMethodChannel?
  private var events: FlutterEventChannel?
  private let volumeSelector = kAudioHardwareServiceDeviceProperty_VirtualMainVolume

  func register(with messenger: FlutterBinaryMessenger) {
    channel = FlutterMethodChannel(name: "playbridge/system_volume", binaryMessenger: messenger)
    channel?.setMethodCallHandler { [weak self] call, result in
      guard let self else { return result(nil) }
      self.bindDevice()
      let args = call.arguments as? [String: Any] ?? [:]
      var applied: [String: Any]?
      switch call.method {
      case "get": result(self.state()); return
      case "set", "step":
        guard self.canSet(self.volumeSelector),
              let current = self.readFloat(self.volumeSelector) else { result(nil); return }
        let next: Float
        if call.method == "set", let level = args["level"] as? Double, level.isFinite, (0...1).contains(level) {
          next = Float(level)
        } else if call.method == "step", let steps = args["steps"] as? Int {
          next = min(1, max(0, current + Float(steps) / 16))
        } else { result(FlutterError(code: "invalid", message: "Invalid volume", details: nil)); return }
        if self.write(self.volumeSelector, value: next) { applied = self.state(level: next) }
      case "mute":
        if self.canSet(kAudioDevicePropertyMute), let current = self.readMute(),
           self.write(kAudioDevicePropertyMute, value: UInt32(current ? 0 : 1)) {
          applied = self.state(muted: !current)
        }
      default: result(FlutterMethodNotImplemented); return
      }
      result(applied)
    }
    events = FlutterEventChannel(name: "playbridge/system_volume/events", binaryMessenger: messenger)
    events?.setStreamHandler(self)
  }

  private func address(_ selector: AudioObjectPropertySelector, global: Bool = false) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector,
      mScope: global ? kAudioObjectPropertyScopeGlobal : kAudioDevicePropertyScopeOutput,
      mElement: kAudioObjectPropertyElementMain)
  }
  private func canSet(_ selector: AudioObjectPropertySelector) -> Bool {
    var addr = address(selector)
    var settable = DarwinBoolean(false)
    return device != kAudioObjectUnknown && AudioObjectHasProperty(device, &addr) &&
      AudioObjectIsPropertySettable(device, &addr, &settable) == noErr && settable.boolValue
  }
  private func readFloat(_ selector: AudioObjectPropertySelector) -> Float? {
    var addr = address(selector), value: Float = 0
    var size = UInt32(MemoryLayout<Float>.size)
    return AudioObjectGetPropertyData(device, &addr, 0, nil, &size, &value) == noErr ? value : nil
  }
  private func readMute() -> Bool? {
    var addr = address(kAudioDevicePropertyMute), value: UInt32 = 0
    var size = UInt32(MemoryLayout<UInt32>.size)
    return AudioObjectGetPropertyData(device, &addr, 0, nil, &size, &value) == noErr ? value != 0 : nil
  }
  private func write<T>(_ selector: AudioObjectPropertySelector, value: T) -> Bool {
    var addr = address(selector), value = value
    return withUnsafeBytes(of: &value) { bytes in
      AudioObjectSetPropertyData(device, &addr, 0, nil, UInt32(bytes.count), bytes.baseAddress!) == noErr
    }
  }
  private func state(level: Float? = nil, muted: Bool? = nil) -> [String: Any] {
    guard canSet(volumeSelector), let value = level ?? readFloat(volumeSelector), value.isFinite else {
      return ["available": false]
    }
    var state: [String: Any] = ["available": true, "level": Double(min(1, max(0, value)))]
    if let mute = muted ?? readMute() { state["muted"] = mute }
    return state
  }
  private func listen(_ object: AudioObjectID, _ addr: AudioObjectPropertyAddress, changed: @escaping () -> Void) {
    var addr = addr
    let block: AudioObjectPropertyListenerBlock = { _, _ in changed() }
    if AudioObjectAddPropertyListenerBlock(object, &addr, .main, block) == noErr {
      listeners.append((object, addr, block))
    }
  }
  private func removeListeners() {
    for (object, address, block) in listeners {
      var address = address
      AudioObjectRemovePropertyListenerBlock(object, &address, .main, block)
    }
    listeners.removeAll()
  }
  private func bindDevice() {
    var addr = address(kAudioHardwarePropertyDefaultOutputDevice, global: true)
    var next = AudioObjectID(kAudioObjectUnknown), size = UInt32(MemoryLayout<AudioObjectID>.size)
    guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &next) == noErr else { return }
    if next == device && !listeners.isEmpty { return }
    removeListeners()
    device = next
    if sink != nil {
      listen(AudioObjectID(kAudioObjectSystemObject), addr) { [weak self] in
        self?.bindDevice(); self?.emit()
      }
      for selector in [volumeSelector, kAudioDevicePropertyMute] {
        listen(device, address(selector)) { [weak self] in self?.emit() }
      }
    }
  }
  private func emit() { sink?(state()) }
  func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    sink = events; bindDevice(); emit(); return nil
  }
  func onCancel(withArguments arguments: Any?) -> FlutterError? {
    sink = nil; removeListeners(); return nil
  }
  deinit { removeListeners() }
}
