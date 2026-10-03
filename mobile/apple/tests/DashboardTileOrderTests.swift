import Foundation

@main struct DashboardTileOrderTests {
    static func main() {
        let defaults = ["browser", "connection", "phone-files", "iptv", "collections", "remote", "cast-history", "app:https://streams.example/", "app:https://jellyfin.example/"]
        let order = DashboardTileOrder.move(defaults, id: defaults[7], to: 1)
        precondition(Array(order.prefix(3)) == ["browser", "app:https://streams.example/", "connection"])
        precondition(Set(order) == Set(defaults))
        precondition(DashboardTileOrder.reconcile(saved: order, available: defaults.reversed()) == order)
        precondition(DashboardTileOrder.reconcile(saved: [], available: defaults) == defaults)
        precondition(DashboardTileOrder.reconcile(
            saved: [defaults[7], "browser", "browser", "removed", "debrid"],
            available: ["browser", "phone-files", defaults[7], defaults[8]]
        ) == [defaults[7], "browser", "phone-files", defaults[8]])
        precondition(DashboardTileOrder.move(["a", "b", "c"], id: "a", to: 2) == ["b", "c", "a"])
        precondition(DashboardTileOrder.move(["a", "b", "c"], id: "c", to: 0) == ["c", "a", "b"])
        precondition(DashboardTileOrder.move(defaults, id: "missing", to: 0) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: -1) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: defaults.count) == defaults)
        precondition(DashboardTileOrder.move(defaults, id: "browser", to: 0) == defaults)
        precondition([0, 1, 8, 9, 16, 17].map(DashboardTileOrder.pageCount) == [1, 1, 1, 2, 2, 3])
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
