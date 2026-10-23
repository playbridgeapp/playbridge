import Foundation
import Combine

/// Installation is local to PlayBridge and never grants website casting permission.
final class BridgedAppStore: ObservableObject {
    @Published private(set) var apps: [BridgedApp]
    private let defaults: UserDefaults
    private let key = "bridged_apps_v1"
    private let lastOpenedOriginKey = "bridged_apps_last_opened_origin"
    private let lastOpenedAtKey = "bridged_apps_last_opened_at"

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

    @discardableResult
    func edit(_ origin: URL, name: String, homeURL: String) -> Bool {
        guard let index = apps.firstIndex(where: { $0.origin == origin }),
              let edited = apps[index].editing(name: name, homeURL: homeURL) else { return false }
        apps[index] = edited
        save()
        return true
    }

    func remove(_ origin: URL) {
        apps.removeAll { $0.origin == origin }
        save()
    }

    /// Remember the most recently opened app, for preloading it at the next launch.
    func markOpened(_ origin: URL, at date: Date = Date()) {
        defaults.set(origin.absoluteString, forKey: lastOpenedOriginKey)
        defaults.set(date.timeIntervalSince1970, forKey: lastOpenedAtKey)
    }

    /// The most recently opened app that is still installed, with when it was opened.
    func lastOpened() -> (app: BridgedApp, at: Date)? {
        guard let origin = defaults.string(forKey: lastOpenedOriginKey),
              let app = apps.first(where: { $0.origin.absoluteString == origin }) else { return nil }
        return (app, Date(timeIntervalSince1970: defaults.double(forKey: lastOpenedAtKey)))
    }

    private func save() {
        if let data = try? JSONEncoder().encode(apps) { defaults.set(data, forKey: key) }
    }
}
