package com.playbridge.sender.data.nuvio

import android.util.Log
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.ResolvedStream
import com.playbridge.sender.data.library.StremioStream
import com.playbridge.sender.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Installs and resolves Nuvio scraper-plugin repositories.
 *
 * Unlike Stremio addons (external HTTP services), Nuvio plugins are client-side
 * JavaScript scrapers downloaded and executed on-device via [NuvioScraperRunner]
 * inside a QuickJS sandbox. A repository is stored as an `installed_addons` row
 * carrying the pseudo-resource [NUVIO_RESOURCE], so it reuses the existing addon
 * enable/disable/remove plumbing; per-scraper metadata lives in `nuvio_scrapers`
 * and the actual `.js` files on disk under `filesDir/nuvio/`.
 */
class NuvioRepository(
    private val addonDao: AddonDao,
    private val scraperDao: NuvioScraperDao,
    private val runner: NuvioScraperRunner,
    private val settingsRepository: SettingsRepository,
    private val filesDir: File,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "NuvioRepository"
        /** Marks an `installed_addons` row as a Nuvio plugin repo rather than a Stremio addon. */
        const val NUVIO_RESOURCE = "nuvio"
        private const val SCRAPER_CACHE_TTL_MS = 60 * 60 * 1000L // 60 min, matches AddonRepository
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nuvioDir: File get() = File(filesDir, "nuvio").apply { mkdirs() }
    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        // Refresh installed repos once per process (non-blocking), so scraper code and
        // the scraper list track upstream changes. Only when the user has opted in.
        ioScope.launch {
            try {
                if (!settingsRepository.enableLocalScrapers.first()) return@launch
                val repos = addonDao.getAllSync()
                    .filter { it.resources.contains(NUVIO_RESOURCE) && it.isEnabled }
                repos.map { async { installRepo(it.manifestUrl) } }.awaitAll()
            } catch (e: Exception) {
                Log.w(TAG, "Startup refresh failed: ${e.message}")
            }
        }
    }

    // (type:tmdbId:s:e) -> resolved streams
    private data class CacheEntry(val timestamp: Long, val streams: List<ResolvedStream>)
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    /** True when [body] parses as a Nuvio repo manifest (has a non-empty `scrapers` array). */
    fun looksLikeNuvioManifest(body: String): Boolean = try {
        json.decodeFromString<NuvioManifest>(body).scrapers.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    private fun repoDirName(repoUrl: String): String =
        repoUrl.hashCode().toUInt().toString(16)

    /**
     * Install (or refresh) a Nuvio plugin repository from its raw manifest URL.
     * Downloads every Android-compatible scraper's code to disk and records rows in
     * both `installed_addons` (the repo) and `nuvio_scrapers` (its scrapers).
     * Preserves each scraper's prior enabled state across refreshes.
     *
     * @param manifestUrl The raw GitHub manifest URL (already fetched or not).
     * @param preFetchedBody Optional manifest JSON already downloaded by the caller
     *   (avoids a second network round-trip during install detection).
     */
    suspend fun installRepo(manifestUrl: String, preFetchedBody: String? = null): Boolean {
        // Play flavor: scraper plugins are disabled entirely (see FlavorConfig).
        if (!com.playbridge.sender.FlavorConfig.SCRAPER_PLUGINS_SUPPORTED) return false
        return installRepoInternal(manifestUrl, preFetchedBody)
    }

    private suspend fun installRepoInternal(manifestUrl: String, preFetchedBody: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = preFetchedBody ?: fetchText(manifestUrl) ?: run {
                    Log.e(TAG, "Manifest fetch failed: $manifestUrl")
                    return@withContext false
                }
                val manifest = json.decodeFromString<NuvioManifest>(body)
                if (manifest.scrapers.isEmpty()) return@withContext false

                val baseUrl = manifestUrl.substringBeforeLast('/', manifestUrl)
                val previous = scraperDao.getForRepo(manifestUrl).associateBy { it.scraperId }

                // Repo row in installed_addons — reuses addon enable/remove UI.
                // New repos go last (lowest priority); refresh preserves order/settings.
                val existingAddons = addonDao.getAllSync()
                val existingMatch = existingAddons.find { it.manifestUrl == manifestUrl }
                val sortOrder = existingMatch?.sortOrder
                    ?: ((existingAddons.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                addonDao.insert(
                    InstalledAddonEntity(
                        manifestUrl = manifestUrl,
                        name = manifest.name.ifBlank { "Nuvio Plugins" },
                        description = manifest.description,
                        baseUrl = baseUrl,
                        version = manifest.version,
                        types = "movie,series",
                        resources = json.encodeToString(listOf(NUVIO_RESOURCE)),
                        resourceDetailsJson = "",
                        sortOrder = sortOrder,
                        isEnabled = existingMatch?.isEnabled ?: true,
                        disabledFeatures = existingMatch?.disabledFeatures ?: "",
                        installedAt = existingMatch?.installedAt ?: System.currentTimeMillis(),
                    )
                )

                val dir = File(nuvioDir, repoDirName(manifestUrl)).apply { mkdirs() }
                val entities = mutableListOf<NuvioScraperEntity>()
                for (s in manifest.scrapers) {
                    if (s.id.isBlank() || s.filename.isBlank()) continue
                    if (!s.isAndroidCompatible) {
                        Log.d(TAG, "Skipping ${s.id}: not Android-compatible")
                        continue
                    }
                    val codeUrl = "$baseUrl/${s.filename}"
                    val code = fetchText(codeUrl)
                    if (code == null) {
                        Log.w(TAG, "Failed to download scraper ${s.id} from $codeUrl")
                        continue
                    }
                    File(dir, "${s.id}.js").writeText(code)
                    entities.add(
                        NuvioScraperEntity(
                            repoUrl = manifestUrl,
                            scraperId = s.id,
                            name = s.name.ifBlank { s.id },
                            description = s.description,
                            version = s.version,
                            filename = s.filename,
                            supportedTypes = s.supportedTypes.joinToString(",").ifBlank { "movie,tv" },
                            contentLanguage = s.contentLanguage.joinToString(","),
                            logo = s.logo,
                            // Respect prior user choice; new scrapers follow manifest default.
                            isEnabled = previous[s.id]?.isEnabled ?: s.enabled,
                            hasSettings = s.hasSettings,
                            // Preserve user's saved settings across refresh.
                            settingsJson = previous[s.id]?.settingsJson ?: "{}"
                        )
                    )
                }
                if (entities.isEmpty()) {
                    Log.w(TAG, "No installable scrapers in $manifestUrl")
                    return@withContext false
                }
                scraperDao.insertAll(entities)
                scraperDao.deleteStale(manifestUrl, entities.map { it.scraperId })
                Log.i(TAG, "Installed ${entities.size} Nuvio scrapers from ${manifest.name}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install Nuvio repo", e)
                false
            }
        }

    /**
     * Detect-and-install entry point for the shared "Add Addon" field.
     * Fetches [url] once: if the body is a Nuvio plugin manifest, installs it and
     * returns true/false (install outcome); if it is NOT a Nuvio manifest, returns
     * null so the caller can fall back to the standard Stremio addon install.
     */
    suspend fun tryInstall(url: String): Boolean? = withContext(Dispatchers.IO) {
        // Play flavor: never recognize scraper manifests; the URL falls through to the
        // regular addon installer (no scraper-plugin branding surfaces in this build).
        if (!com.playbridge.sender.FlavorConfig.SCRAPER_PLUGINS_SUPPORTED) return@withContext null
        val body = fetchText(url) ?: return@withContext null
        if (!looksLikeNuvioManifest(body)) return@withContext null
        installRepo(url, preFetchedBody = body)
    }

    /** Remove a repo's scraper rows and on-disk code. The `installed_addons` row is
     *  deleted by [com.playbridge.sender.data.library.AddonRepository.removeAddon]. */
    suspend fun removeRepo(repoUrl: String) = withContext(Dispatchers.IO) {
        scraperDao.deleteForRepo(repoUrl)
        File(nuvioDir, repoDirName(repoUrl)).deleteRecursively()
        streamCache.clear()
    }

    suspend fun setScraperEnabled(scraper: NuvioScraperEntity, enabled: Boolean) {
        scraperDao.update(scraper.copy(isEnabled = enabled))
    }

    /** Persist the user's chosen settings values (JSON object string) for a scraper. */
    suspend fun setScraperSettings(scraper: NuvioScraperEntity, settingsJson: String) {
        scraperDao.update(scraper.copy(settingsJson = settingsJson))
    }

    /**
     * Load a scraper's settings schema by invoking its exported `onSettings()`.
     * Returns an empty list when the scraper has no settings or fails to produce a schema.
     */
    suspend fun getSettingsSchema(scraper: NuvioScraperEntity): List<NuvioSettingField> {
        val code = readCode(scraper) ?: return emptyList()
        val schemaJson = runner.getSettingsSchema(scraper.name, code) ?: return emptyList()
        return try {
            json.decodeFromString<List<NuvioSettingField>>(schemaJson)
        } catch (e: Exception) {
            Log.w(TAG, "Bad settings schema for ${scraper.name}: ${e.message}")
            emptyList()
        }
    }

    fun observeScrapers(repoUrl: String) = scraperDao.observeForRepo(repoUrl)

    /**
     * Resolve streams from all enabled Nuvio scrapers for the given content.
     *
     * @param stremioType "movie" or "series" (as used across the addon pipeline).
     * @param tmdbId TMDB numeric id as a string (scrapers key on TMDB, not IMDb).
     */
    suspend fun resolveStreams(
        stremioType: String,
        tmdbId: String,
        season: Int?,
        episode: Int?
    ): List<ResolvedStream> {
        // Play flavor: scraper plugins are disabled entirely (see FlavorConfig).
        if (!com.playbridge.sender.FlavorConfig.SCRAPER_PLUGINS_SUPPORTED) return emptyList()
        // Master opt-in gate — scrapers run third-party JS, so they stay off by default.
        if (!settingsRepository.enableLocalScrapers.first()) return emptyList()

        val nuvioType = if (stremioType == "series" || stremioType == "tv") "tv" else "movie"
        val cacheKey = "$nuvioType:$tmdbId:${season ?: ""}:${episode ?: ""}"
        streamCache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.timestamp < SCRAPER_CACHE_TTL_MS) return it.streams
        }

        // Only run scrapers whose parent repo row is enabled.
        val enabledRepos = addonDao.getAllSync()
            .filter { it.resources.contains(NUVIO_RESOURCE) && it.isEnabled }
            .map { it.manifestUrl }
            .toSet()
        if (enabledRepos.isEmpty()) return emptyList()

        val scrapers = enabledRepos
            .flatMap { scraperDao.getForRepo(it) }
            .filter { it.isEnabled && it.supportsType(nuvioType) }
        if (scrapers.isEmpty()) return emptyList()

        val results = coroutineScope {
            scrapers.map { scraper ->
                async(Dispatchers.IO) {
                    val code = readCode(scraper) ?: return@async emptyList()
                    runner.getStreams(
                        scraperName = scraper.name,
                        scraperCode = code,
                        tmdbId = tmdbId,
                        nuvioType = nuvioType,
                        season = season,
                        episode = episode,
                        settingsJson = scraper.settingsJson
                    ).map { it.toResolvedStream(scraper.name) }
                }
            }.awaitAll()
        }.flatten()

        if (results.isNotEmpty()) {
            streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), results)
        }
        return results
    }

    /**
     * Resolve streams from all enabled Nuvio scrapers progressively as a Flow.
     */
    fun resolveStreamsFlow(
        stremioType: String,
        tmdbId: String,
        season: Int?,
        episode: Int?
    ): kotlinx.coroutines.flow.Flow<List<ResolvedStream>> =
        kotlinx.coroutines.flow.channelFlow {
            // Play flavor: scraper plugins are disabled entirely (see FlavorConfig).
            if (!com.playbridge.sender.FlavorConfig.SCRAPER_PLUGINS_SUPPORTED) {
                send(emptyList())
                return@channelFlow
            }
            // Master opt-in gate — scrapers run third-party JS, so they stay off by default.
            if (!settingsRepository.enableLocalScrapers.first()) {
                send(emptyList())
                return@channelFlow
            }

            val nuvioType = if (stremioType == "series" || stremioType == "tv") "tv" else "movie"
            val cacheKey = "$nuvioType:$tmdbId:${season ?: ""}:${episode ?: ""}"
            streamCache[cacheKey]?.let {
                if (System.currentTimeMillis() - it.timestamp < SCRAPER_CACHE_TTL_MS) {
                    send(it.streams)
                    return@channelFlow
                }
            }

            send(emptyList()) // Immediate clear for UI feedback

            // Only run scrapers whose parent repo row is enabled.
            val enabledRepos = addonDao.getAllSync()
                .filter { it.resources.contains(NUVIO_RESOURCE) && it.isEnabled }
                .map { it.manifestUrl }
                .toSet()
            if (enabledRepos.isEmpty()) {
                send(emptyList())
                return@channelFlow
            }

            val scrapers = enabledRepos
                .flatMap { scraperDao.getForRepo(it) }
                .filter { it.isEnabled && it.supportsType(nuvioType) }
            if (scrapers.isEmpty()) {
                send(emptyList())
                return@channelFlow
            }

            val accumulated = mutableListOf<ResolvedStream>()
            val lock = Any()

            coroutineScope {
                scrapers.forEach { scraper ->
                    launch(Dispatchers.IO) {
                        val code = readCode(scraper)
                        if (code != null) {
                            val results = runCatching {
                                runner.getStreams(
                                    scraperName = scraper.name,
                                    scraperCode = code,
                                    tmdbId = tmdbId,
                                    nuvioType = nuvioType,
                                    season = season,
                                    episode = episode,
                                    settingsJson = scraper.settingsJson
                                ).map { it.toResolvedStream(scraper.name) }
                            }.getOrDefault(emptyList())
                            if (results.isNotEmpty()) {
                                val latest = synchronized(lock) {
                                    accumulated.addAll(results)
                                    accumulated.sortedByDescending { it.stream.isDirectUrl }.toList()
                                }
                                send(latest)
                            }
                        }
                    }
                }
            }

            val finalStreams = synchronized(lock) {
                accumulated.sortedByDescending { it.stream.isDirectUrl }.toList()
            }
            if (finalStreams.isEmpty()) {
                send(emptyList())
            } else {
                streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), finalStreams)
            }
        }

    private fun readCode(scraper: NuvioScraperEntity): String? {
        val file = File(File(nuvioDir, repoDirName(scraper.repoUrl)), "${scraper.scraperId}.js")
        return if (file.exists()) file.readText() else null
    }

    private fun fetchText(url: String): String? = try {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "fetch failed for $url: ${e.message}")
        null
    }
}

/**
 * Adapt a Nuvio scraper result into the addon pipeline's [ResolvedStream] so it
 * flows through the existing picker, quality sorting, caching and queue coordinators
 * unchanged. Scraper-provided headers ride along on [StremioStream.headers] and are
 * plumbed into the play command / DLNA proxy at the send sites.
 */
private fun NuvioStreamResult.toResolvedStream(scraperName: String): ResolvedStream {
    val label = buildString {
        append(name ?: scraperName)
        quality?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
        size?.takeIf { it.isNotBlank() }?.let { append(" • ").append(it) }
    }
    return ResolvedStream(
        addonName = provider ?: scraperName,
        stream = StremioStream(
            url = url,
            name = label,
            title = title ?: quality,
            headers = headers
        )
    )
}
