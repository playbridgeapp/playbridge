import Foundation
import Combine

/// Installation is local to PlayBridge and never grants website casting permission.
final class BridgedAppStore: ObservableObject {
    @Published private(set) var apps: [BridgedApp]
    private let defaults: UserDefaults
    private let key = "bridged_apps_v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let saved = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([BridgedApp].self, from: $0) } ?? []
        var origins = Set<URL>()
        apps = saved.filter { $0.isValid && origins.insert($0.origin).inserted }
    }

    func install(_ app: BridgedApp) {
        guard app.isValid else { return }
        apps = apps.filter { $0.origin != app.origin } + [app]
        save()
    }

    func remove(_ origin: URL) {
        apps.removeAll { $0.origin == origin }
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(apps) { defaults.set(data, forKey: key) }
    }
}
