import Foundation

struct ResumeInfo: Codable {
    let positionMs: Int64
    let durationMs: Int64
    var preferredAudioLang: String?
    var preferredSubtitleLang: String?
    var playbackRate: Float?
    var filterPreset: FilterPreset?
    var filterSettings: ColorFilterSettings?
    let lastPlayedAt: Date
}

class ResumeStore {
    private let fileName = "resume_history.json"
    private let maxItems = 50
    private var history: [String: ResumeInfo] = [:]
    private let queue = DispatchQueue(label: "com.playbridge.ResumeStore")

    static let shared = ResumeStore()

    private init() {
        load()
    }

    private var fileURL: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent(fileName)
    }

    func save(
        url: String,
        position: Int64,
        duration: Int64,
        audioLang: String? = nil,
        subLang: String? = nil,
        rate: Float? = nil,
        filterPreset: FilterPreset? = nil,
        filterSettings: ColorFilterSettings? = nil
    ) {
        queue.async {
            let info = ResumeInfo(
                positionMs: position,
                durationMs: duration,
                preferredAudioLang: audioLang,
                preferredSubtitleLang: subLang,
                playbackRate: rate,
                filterPreset: filterPreset,
                filterSettings: filterSettings,
                lastPlayedAt: Date()
            )
            self.history[url] = info

            // Cap items
            if self.history.count > self.maxItems {
                let sortedKeys = self.history.keys.sorted {
                    self.history[$0]!.lastPlayedAt > self.history[$1]!.lastPlayedAt
                }
                let keysToRemove = sortedKeys.suffix(from: self.maxItems)
                keysToRemove.forEach { self.history.removeValue(forKey: $0) }
            }

            self.persist()
        }
    }

    func getResumeInfo(for url: String) -> ResumeInfo? {
        queue.sync {
            return history[url]
        }
    }

    private func load() {
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return }

        let decoder = JSONDecoder()
        if let decoded = try? decoder.decode([String: ResumeInfo].self, from: data) {
            history = decoded
        }
    }

    private func persist() {
        guard let url = fileURL else { return }

        // Ensure directory exists
        try? FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)

        let encoder = JSONEncoder()
        if let data = try? encoder.encode(history) {
            try? data.write(to: url)
        }
    }
}
