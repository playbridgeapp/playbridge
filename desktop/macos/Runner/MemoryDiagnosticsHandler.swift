import Darwin
import FlutterMacOS

/// Process footprint includes compressed memory that an RSS reading misses.
enum MemoryDiagnosticsHandler {
  static func register(with messenger: FlutterBinaryMessenger) {
    let channel = FlutterMethodChannel(
      name: "com.playbridge.desktop/memory",
      binaryMessenger: messenger
    )
    channel.setMethodCallHandler { call, result in
      guard call.method == "snapshot" else {
        result(FlutterMethodNotImplemented)
        return
      }
      result(ProcessMemorySnapshot.read())
    }
  }
}

enum ProcessMemorySnapshot {
  static func read() -> [String: UInt64]? {
    var usage = rusage_info_v4()
    let status = withUnsafeMutablePointer(to: &usage) { pointer in
      pointer.withMemoryRebound(to: rusage_info_t?.self, capacity: 1) {
        proc_pid_rusage(getpid(), RUSAGE_INFO_V4, $0)
      }
    }
    guard status == 0 else { return nil }
    return [
      "footprintBytes": usage.ri_phys_footprint,
      "peakFootprintBytes": usage.ri_lifetime_max_phys_footprint,
    ]
  }
}
