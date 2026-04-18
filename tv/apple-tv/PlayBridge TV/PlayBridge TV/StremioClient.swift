import Combine
import Foundation

// ==================== Response Data Classes ====================

struct StremioStreamItem: Codable, Equatable {
    let url: String?
    let name: String?
    let title: String?
    var addonName: String?
    let behaviorHints: StremioStreamBehaviorHints?

    var isDirectUrl: Bool {
        url?.hasPrefix("http") == true
    }
}

struct StremioStreamBehaviorHints: Codable, Equatable {
    let videoSize: Int64?
}

private struct StremioStreamsResponse: Codable {
    let streams: [StremioStreamItem]?
}

// ==================== Caching ====================

private struct StreamCacheEntry: Codable {
    let streams: [StremioStreamItem]
    let timestamp: Date
}

// ==================== Result Types ====================

struct ScoredStremioStream: Identifiable, Equatable {
    let id = UUID()
    let url: String
    let name: String?
    let title: String?
    let addonName: String?
    let score: Int
    let rank: Int
    let isSeasonPack: Bool
    let isExtras: Bool
    let isTargetTier: Bool

    var sizeFormatted: String? {
        // Find behavior hints if we had them (TODO: store them in ScoredStremioStream)
        return nil
    }
}

// ==================== Client ====================

class StremioClient {
    static let shared = StremioClient()

    private var cache: [String: StreamCacheEntry] = [:]
    private var cacheDurationHours: Int = 0
    private let queue = DispatchQueue(label: "com.playbridge.StremioClient")

    private var cacheURL: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("stremio_streams_cache.json")
    }

    private init() {
        loadCache()
    }

    private func loadCache() {
        guard let url = cacheURL, let data = try? Data(contentsOf: url) else { return }
        let decoder = JSONDecoder()
        if let decoded = try? decoder.decode([String: StreamCacheEntry].self, from: data) {
            let now = Date()
            cache = decoded.filter {
                cacheDurationHours > 0
                    && now.timeIntervalSince($0.value.timestamp) < Double(cacheDurationHours) * 3600
            }
        }
    }

    private func saveCache() {
        guard let url = cacheURL else { return }
        let encoder = JSONEncoder()
        if let data = try? encoder.encode(cache) {
            try? data.write(to: url)
        }
    }

    func resolveStreamsByContentId(
        addonBaseUrls: [String],
        addonNames: [String]? = nil,
        contentId: String,
        contentType: String,
        season: Int? = nil,
        episode: Int? = nil,
        qualityPreference: String? = nil,
        preferredAddonBaseUrl: String? = nil,
        preferredAddonName: String? = nil
    ) async throws -> [ScoredStremioStream] {
        let stremioId =
            (contentType == "series" && season != nil && episode != nil)
            ? "\(contentId):\(season!):\(episode!)" : contentId

        // 1. Check Cache
        if cacheDurationHours > 0, let entry = cache[stremioId] {
            if Date().timeIntervalSince(entry.timestamp) < Double(cacheDurationHours) * 3600 {
                return scoreStreams(
                    entry.streams, qualityPreference: qualityPreference,
                    preferredAddonBaseUrl: preferredAddonBaseUrl,
                    preferredAddonName: preferredAddonName)
            }
        }

        // 2. Fetch from Addons concurrently
        let allStreams = try await withThrowingTaskGroup(of: [StremioStreamItem].self) { group in
            for (index, baseUrl) in addonBaseUrls.enumerated() {
                let displayName = addonNames?[safe: index]
                group.addTask {
                    return await self.fetchFromAddon(
                        baseUrl: baseUrl, type: contentType, id: stremioId, displayName: displayName
                    )
                }
            }

            var collected: [StremioStreamItem] = []
            for try await streams in group {
                collected.append(contentsOf: streams)
            }
            return collected
        }

        // 3. Update Cache
        if !allStreams.isEmpty {
            queue.async {
                self.cache[stremioId] = StreamCacheEntry(streams: allStreams, timestamp: Date())
                self.saveCache()
            }
        }

        // 4. Score and Sort
        return scoreStreams(
            allStreams, qualityPreference: qualityPreference,
            preferredAddonBaseUrl: preferredAddonBaseUrl, preferredAddonName: preferredAddonName)
    }

    private func fetchFromAddon(baseUrl: String, type: String, id: String, displayName: String?)
        async -> [StremioStreamItem]
    {
        let urlString =
            "\(baseUrl.trimmingCharacters(in: .init(charactersIn: "/")))/stream/\(type)/\(id).json"
        guard let url = URL(string: urlString) else { return [] }

        do {
            let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url))
            guard (response as? HTTPURLResponse)?.statusCode == 200 else { return [] }

            let decoder = JSONDecoder()
            let result = try decoder.decode(StremioStreamsResponse.self, from: data)
            return result.streams?.filter { $0.isDirectUrl }.map {
                var item = $0
                item.addonName = displayName
                return item
            } ?? []
        } catch {
            return []
        }
    }

    private func scoreStreams(
        _ streams: [StremioStreamItem],
        qualityPreference: String?,
        preferredAddonBaseUrl: String?,
        preferredAddonName: String?
    ) -> [ScoredStremioStream] {
        let targetRank = QualityRanker.targetRank(qualityPreference)

        return streams.map { item in
            let text = "\(item.name ?? "") \(item.title ?? "")"
            let rank = QualityRanker.rankFromText(text)
            var score = rank * 100

            if targetRank > 0 {
                if rank == targetRank {
                    score += 1000
                } else if rank > targetRank {
                    score -= 500
                } else {
                    score -= 800
                }
            }

            if let prefName = preferredAddonName, item.addonName == prefName {
                score += 400
            } else if let prefUrl = preferredAddonBaseUrl,
                item.url?.hasPrefix(prefUrl.trimmingCharacters(in: .init(charactersIn: "/")))
                    == true
            {
                score += 400
            }

            let extras = isExtrasContent(text)
            if extras { score -= 2000 }

            let seasonPack = isSeasonPack(text)
            if seasonPack { score += 50 }

            return ScoredStremioStream(
                url: item.url!,
                name: item.name,
                title: item.title,
                addonName: item.addonName,
                score: score,
                rank: rank,
                isSeasonPack: seasonPack,
                isExtras: extras,
                isTargetTier: targetRank > 0 && rank == targetRank
            )
        }.sorted { $0.score > $1.score }
    }

    private func isSeasonPack(_ text: String) -> Bool {
        let lower = text.lowercased()
        // Simple heuristic for now
        return lower.contains("season") || lower.contains("complete") || lower.contains("batch")
    }

    private func isExtrasContent(_ text: String) -> Bool {
        let lower = text.lowercased()
        let keywords = [
            "extras", "bonus", "featurette", "behind the scenes", "deleted scenes", "making of",
            "interview", "trailer",
        ]
        return keywords.contains { lower.contains($0) }
    }
}

extension Array {
    subscript(safe index: Index) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
