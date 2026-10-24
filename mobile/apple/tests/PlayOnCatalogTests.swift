import Foundation

@main struct PlayOnCatalogTests {
    static func main() {
        grouping()
        subtitles()
        networkAndStatus()
        relativeTime()
        preferences()
        print("PASS: Play on grouping, subtitles, network label, and auto-connect default")
    }

    private static func device(
        _ id: String,
        name: String,
        kind: PlayOnListedDevice.Kind,
        address: String = "",
        reachable: Bool,
        saved: Bool,
        lastConnected: Date? = nil,
        needsPairing: Bool = false,
        matchKeys: Set<String> = []
    ) -> PlayOnListedDevice {
        PlayOnListedDevice(
            id: id, name: name, kind: kind, address: address, reachable: reachable, saved: saved,
            lastConnected: lastConnected, needsPairing: needsPairing,
            matchKeys: matchKeys.isEmpty ? [id] : matchKeys
        )
    }

    private static func grouping() {
        let older = Date(timeIntervalSince1970: 1_000)
        let newer = Date(timeIntervalSince1970: 2_000)
        let saved = [
            device("pb-old", name: "Apple TV", kind: .playBridge, reachable: true, saved: true, lastConnected: older, matchKeys: ["pb-old", "10.0.0.2:8080"]),
            device("pb-new", name: "Living Room", kind: .playBridge, reachable: true, saved: true, lastConnected: newer),
            device("cast-offline", name: "Bedroom", kind: .googleCast, reachable: false, saved: true, lastConnected: newer),
            device("roku-offline", name: "Den", kind: .roku, reachable: false, saved: true, lastConnected: older),
        ]
        let discovered = [
            device("roku-live", name: "Roku Ultra", kind: .roku, reachable: true, saved: false),
            device("pb-old", name: "Apple TV", kind: .playBridge, reachable: true, saved: false, needsPairing: true, matchKeys: ["pb-old"]),
            device("10.0.0.8:8080", name: "Sony", kind: .playBridge, reachable: true, saved: false, needsPairing: true, matchKeys: ["sony-uuid", "10.0.0.8:8080"]),
            device("cast-live", name: "Kitchen", kind: .googleCast, reachable: true, saved: false),
            device("dlna-live", name: "Speaker", kind: .dlna, reachable: true, saved: false),
            device("browser-1", name: "Browser TV", kind: .browser, reachable: true, saved: false),
            device("alias", name: "Apple TV alias", kind: .playBridge, reachable: true, saved: false, needsPairing: true, matchKeys: ["10.0.0.2:8080"]),
        ]
        let groups = PlayOnCatalog.group(saved: saved, discovered: discovered)
        precondition(groups.reachableSaved.map(\.id) == ["pb-new", "pb-old"], "reachable saved follow lastConnected, newest first")
        precondition(groups.unavailable.map(\.id) == ["cast-offline", "roku-offline"], "unreachable saved stay out of the ready list and keep recency")
        precondition(groups.nearby.map(\.id) == ["10.0.0.8:8080", "cast-live", "dlna-live", "roku-live", "browser-1"],
                     "nearby drops saved matches and orders PlayBridge, Cast, DLNA, Roku, browser")
        precondition(groups.nearby.allSatisfy { !$0.saved }, "nearby rows are discoveries")

        let tied = PlayOnCatalog.group(saved: [
            device("b", name: "Beta", kind: .playBridge, reachable: true, saved: true, lastConnected: newer),
            device("a", name: "Alpha", kind: .playBridge, reachable: true, saved: true, lastConnected: newer),
        ], discovered: [])
        precondition(tied.reachableSaved.map(\.name) == ["Alpha", "Beta"], "equal recency falls back to name")
    }

    private static func subtitles() {
        precondition(PlayOnCatalog.savedSubtitle(protocolName: "PlayBridge", reachable: true, used: "Used today") == "PlayBridge · Ready")
        precondition(PlayOnCatalog.savedSubtitle(protocolName: "Google Cast", reachable: false, used: "Used yesterday") == "Google Cast · Used yesterday")
        precondition(PlayOnCatalog.nearbySubtitle(kind: .playBridge, needsPairing: true, protocolName: "PlayBridge") == "PlayBridge · Tap to pair")
        precondition(PlayOnCatalog.nearbySubtitle(kind: .dlna, needsPairing: false, protocolName: "DLNA") == "DLNA")
        precondition(PlayOnCatalog.actionsSubtitle(protocolName: "PlayBridge", address: "192.168.0.41", used: "Used today") == "PlayBridge · 192.168.0.41 · Used today")
        precondition(PlayOnCatalog.actionsSubtitle(protocolName: "Roku", address: "", used: nil) == "Roku")
        precondition(PlayOnPlayerCopy.detail(for: "exo") == "Default player, smooth on most streams")
        precondition(PlayOnPlayerCopy.detail(for: "mpv") == "Plays more formats and subtitle styles")
    }

    private static func networkAndStatus() {
        precondition(PlayOnCatalog.networkSubtitle(onWiFi: true, wifiName: nil) == "Wi-Fi")
        precondition(PlayOnCatalog.networkSubtitle(onWiFi: true, wifiName: "  ") == "Wi-Fi")
        precondition(PlayOnCatalog.networkSubtitle(onWiFi: true, wifiName: "Home") == "Home")
        precondition(PlayOnCatalog.networkSubtitle(onWiFi: false, wifiName: "Home") == "Mobile data")
        precondition(PlayOnCatalog.nowStatus(connecting: true, failed: false, castingTitle: "Show", protocolName: "PlayBridge") == "Connecting…")
        precondition(PlayOnCatalog.nowStatus(connecting: false, failed: true, castingTitle: nil, protocolName: "PlayBridge") == "Couldn’t connect")
        precondition(PlayOnCatalog.nowStatus(connecting: false, failed: false, castingTitle: "Episode", protocolName: "PlayBridge") == "Casting Episode")
        precondition(PlayOnCatalog.nowStatus(connecting: false, failed: false, castingTitle: nil, protocolName: "Google Cast") == "Connected · Google Cast")
        let report = PlayOnCatalog.connectionDiagnostics(state: "error", destination: "Living Room", protocolName: "PlayBridge", scanning: true)
        precondition(report.contains("State: error") && report.contains("Destination: Living Room") && !report.contains("http"), "diagnostics stay free of URLs")
    }

    private static func relativeTime() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-30), now: now, calendar: calendar) == "Used just now")
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-120), now: now, calendar: calendar) == "Used 2m ago")
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-7_200), now: now, calendar: calendar) == "Used today")
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-86_400), now: now, calendar: calendar) == "Used yesterday")
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-3 * 86_400), now: now, calendar: calendar) == "Used 3d ago")
        precondition(PlayOnRelativeTime.used(now.addingTimeInterval(-14 * 86_400), now: now, calendar: calendar) == "Used earlier")
    }

    private static func preferences() {
        let name = "playon-catalog-tests"
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        precondition(PlayOnPreferences.autoConnectEnabled(defaults), "missing preference stays on")
        defaults.set(false, forKey: PlayOnPreferences.autoConnectKey)
        precondition(!PlayOnPreferences.autoConnectEnabled(defaults), "explicit off is honored")
        defaults.set(true, forKey: PlayOnPreferences.autoConnectKey)
        precondition(PlayOnPreferences.autoConnectEnabled(defaults))
        defaults.removePersistentDomain(forName: name)
    }
}
