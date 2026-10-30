import Foundation
import Combine

/// Installation is local to PlayBridge and never grants website casting permission.
final class BridgedAppStore: ObservableObject {
    @Published private(set) var apps: [BridgedApp]
    private let defaults: UserDefaults
    private let iconCache: BridgedAppIconCache?
    private var iconTasks: [URL: Task<Void, Never>] = [:]
    private var declarationSubscription: AnyCancellable?
    private let key = "bridged_apps_v1"

    init(defaults: UserDefaults = .standard, iconCache: BridgedAppIconCache? = .shared) {
        self.iconCache = iconCache
        self.defaults = defaults
        let saved = defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([BridgedApp].self, from: $0) } ?? []
        var origins = Set<URL>()
        apps = saved.filter { $0.isValid && origins.insert($0.origin).inserted }
        declarationSubscription = NotificationCenter.default.publisher(for: BridgedAppDeclarationCache.didRefresh)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] note in
                if let app = note.object as? BridgedApp { self?.refreshDeclaration(app) }
            }
    }

    func install(_ app: BridgedApp) {
        guard app.isValid else { return }
        var app = app
        if let previous = apps.first(where: { $0.origin == app.origin }) { app.tileColorID = previous.tileColorID }
        apps = apps.filter { $0.origin != app.origin } + [app]
        iconTasks.removeValue(forKey: app.origin)?.cancel()
        if let iconCache { iconTasks[app.origin] = Task { _ = await iconCache.load(app) } }
        save()
    }

    @discardableResult
    func edit(_ origin: URL, name: String, homeURL: String) -> Bool {
        edit(origin, name: name, homeURL: homeURL, tileColorID: apps.first { $0.origin == origin }?.tileColorID)
    }

    @discardableResult
    func edit(_ origin: URL, name: String, homeURL: String, tileColorID: String?) -> Bool {
        guard let index = apps.firstIndex(where: { $0.origin == origin }),
              let edited = apps[index].editing(name: name, homeURL: homeURL, tileColorID: tileColorID) else { return false }
        apps[index] = edited
        save()
        return true
    }

    func remove(_ origin: URL) {
        iconTasks.removeValue(forKey: origin)?.cancel()
        apps.removeAll { $0.origin == origin }
        if let iconCache { Task { await iconCache.remove(origin) } }
        save()
    }

    func refreshDeclaration(_ declaration: BridgedApp) {
        guard let index = apps.firstIndex(where: { $0.origin == declaration.origin }) else { return }
        let refreshed = BridgedApp(origin: apps[index].origin, name: apps[index].name,
                                startURL: apps[index].startURL, iconURL: declaration.iconURL,
                                tileColorID: apps[index].tileColorID, themeColor: declaration.themeColor)
        guard apps[index] != refreshed else { return }
        apps[index] = refreshed
        save()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(apps) { defaults.set(data, forKey: key) }
    }
}
