import Foundation

@main struct DashboardTileOrderTests {
    static func main() {
        let defaults = ["browser", "phone-files", "iptv", "collections", "cast-history", "app:https://streams.example/", "app:https://jellyfin.example/"]
        let order = DashboardTileOrder.move(defaults, id: defaults[5], to: 1)
        precondition(Array(order.prefix(3)) == ["browser", "app:https://streams.example/", "phone-files"])
        precondition(Set(order) == Set(defaults))
        precondition(DashboardTileOrder.reconcile(saved: order, available: defaults.reversed()) == order)
        precondition(DashboardTileOrder.reconcile(saved: [], available: defaults) == defaults)
        precondition(DashboardTileOrder.reconcile(
            saved: [defaults[5], "browser", "browser", "removed", "debrid"],
            available: ["browser", "phone-files", defaults[5], defaults[6]]
        ) == [defaults[5], "browser", "phone-files", defaults[6]])
        precondition(DashboardTileOrder.move(["a", "b", "c"], id: "a", to: 2) == ["b", "c", "a"])
        precondition(DashboardTileOrder.move(["a", "b", "c"], id: "c", to: 0) == ["c", "a", "b"])
        precondition(DashboardTileOrder.move(defaults, id: "missing", to: 0) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: -1) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: defaults.count) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: 0) == defaults)
        // Old saved orders drop the removed Play on and Remote tiles but keep the user's order.
        precondition(DashboardTileOrder.reconcile(saved: ["remote", "iptv", "connection", "browser"], available: defaults)
            == ["iptv", "browser", "phone-files", "collections", "cast-history", defaults[5], defaults[6]])
        // Browser can't be hidden; every other tile can.
        precondition(DashboardTileOrder.visible(defaults, hidden: Set(defaults)) == ["browser"])
        precondition(DashboardTileOrder.visible(defaults, hidden: ["iptv", "unknown"]) == defaults.filter { $0 != "iptv" })
        precondition(DashboardTileOrder.decode("not JSON") == [])
        precondition(DashboardTileOrder.decode("{\"browser\":1}") == [])
        precondition(DashboardTileOrder.decode(DashboardTileOrder.encode(order)) == order)
        let suite = "playbridge.dashboard-order-tests.\(UUID().uuidString)"
        let preferences = UserDefaults(suiteName: suite)!
        defer { preferences.removePersistentDomain(forName: suite) }
        preferences.set(DashboardTileOrder.encode(order), forKey: "dashboard_tile_order")
        let restored = UserDefaults(suiteName: suite)!.string(forKey: "dashboard_tile_order")!
        precondition(DashboardTileOrder.reconcile(saved: DashboardTileOrder.decode(restored), available: defaults) == order)
        print("Dashboard tile ordering checks passed")
    }
}
