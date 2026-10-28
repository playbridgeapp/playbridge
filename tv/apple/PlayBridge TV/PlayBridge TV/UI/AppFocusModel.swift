import Foundation
import CoreGraphics

struct AppFocusID: Hashable {
    let value: UUID
    init(_ value: UUID = UUID()) { self.value = value }
    var logValue: String { value.uuidString }
}

/// Each SwiftUI FocusState owner has its own selection and registered rectangles.
struct AppFocusModel {
    var focused: AppFocusID?

    mutating func move(_ direction: AppFocusDirection, frames: [AppFocusID: CGRect]) -> AppFocusID? {
        let frames = frames.filter { _, rect in
            [rect.minX, rect.minY, rect.maxX, rect.maxY, rect.width, rect.height].allSatisfy(\.isFinite) && rect.width > 0 && rect.height > 0
        }
        let ids = frames.keys.sorted { $0.logValue < $1.logValue }
        let target: AppFocusID?
        if let focused, let source = frames[focused] {
            let targets = ids.enumerated().compactMap { index, id in
                id == focused ? nil : AppFocusTarget(id: index, rect: frames[id]!)
            }
            target = AppFocusGeometry.nearest(from: source, direction: direction, targets: targets).map { ids[$0] }
        } else {
            target = ids.min { a, b in
                let first = frames[a]!, second = frames[b]!
                if first.minY != second.minY { return first.minY < second.minY }
                if first.minX != second.minX { return first.minX < second.minX }
                return a.logValue < b.logValue
            }
        }
        if let target { focused = target }
        return target
    }

    func activation(registered: Set<AppFocusID>) -> AppFocusID? {
        focused.flatMap { registered.contains($0) ? $0 : nil }
    }
}

struct AppFocusScopeStack {
    private(set) var ids: [UUID] = []
    mutating func register(_ id: UUID) { if !ids.contains(id) { ids.append(id) } }
    mutating func remove(_ id: UUID) { ids.removeAll { $0 == id } }
    func active(eligible: Set<UUID>) -> UUID? { ids.last(where: eligible.contains) }
}
