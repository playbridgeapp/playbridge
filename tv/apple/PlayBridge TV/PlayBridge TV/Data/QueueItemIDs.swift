import Foundation

enum QueueItemIDs {
    static func isValid(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.utf16.count <= 128
    }

    /// Validate the entire mutation before allocating missing IDs or changing the queue.
    static func resolve(_ supplied: [String?], existing: [String] = []) -> [String]? {
        var used = Set(existing)
        for case let id? in supplied {
            guard isValid(id), used.insert(id).inserted else { return nil }
        }
        return supplied.map { id in
            if let id { return id }
            var generated = UUID().uuidString
            while !used.insert(generated).inserted { generated = UUID().uuidString }
            return generated
        }
    }
}
