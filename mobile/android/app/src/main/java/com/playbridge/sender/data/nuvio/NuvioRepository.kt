package com.playbridge.sender.data.nuvio

import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.ResolvedStream
import com.playbridge.sender.data.library.StremioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * Installs and resolves Nuvio scraper plugins for Library and the Streams bridge.
 *
 * Native code is never installed or fetched from a website. [nativePluginStatus]
 * and [resolveNativePlugins] are the bridge contract: they identify providers by
 * the exact installed repo URL and scraper id, ignore caller-supplied code,
 * settings, and credentials, and return only safe warnings.
 *
 * Approved script bytes are retained until the user approves a refresh. A hash
 * detects changes; it does not authenticate the publisher.
 */
class NuvioRepository internal constructor(
    private val addonDao: AddonDao,
    private val scraperDao: NuvioScraperDao,
    private val engine: NuvioScraperEngine,
    private val filesDir: File,
    private val masterEnabled: suspend () -> Boolean,
    private val http: NuvioPluginHttp = NuvioPluginHttp(),
    private val pluginsSupported: Boolean = com.playbridge.sender.FlavorConfig.SCRAPER_PLUGINS_SUPPORTED,
    private val allowCleartextInstall: Boolean = false,
) {
    companion object {
        private const val TAG = "NuvioRepository"
        const val NUVIO_RESOURCE = "nuvio"
        private const val SCRAPER_CACHE_TTL_MS = 60 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val nuvioRoot = File(filesDir, "nuvio")
    private val scripts = NuvioScriptStore(nuvioRoot)
    private val approvals = NuvioApprovalStore(nuvioRoot)
    private val engineSlots = Semaphore(NuvioLimits.MAX_CONCURRENT_ENGINES)
    private val mutationMutex = Mutex()
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val timestamp: Long, val streams: List<ResolvedStream>)

    fun looksLikeNuvioManifest(body: String): Boolean = try {
        body.toByteArray(Charsets.UTF_8).size <= NuvioLimits.MAX_MANIFEST_BYTES &&
            json.decodeFromString<NuvioManifest>(body).scrapers.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    suspend fun installRepo(manifestUrl: String, preFetchedBody: String? = null): Boolean {
        if (!available()) return false
        return try {
            withTimeout(NuvioLimits.OPERATION_BUDGET_MS) { installRepoInternal(manifestUrl, preFetchedBody) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
    }

    suspend fun tryInstall(url: String): Boolean? = withContext(Dispatchers.IO) {
        if (!available()) return@withContext null
        val parsed = NuvioDestinationPolicy.parseInstallUrl(url, allowCleartextInstall) ?: return@withContext null
        val fetched = http.fetchText(
            url = parsed.toString(),
            budget = NuvioRequestBudget(4, http.requestSlots),
            maxBytes = NuvioLimits.MAX_MANIFEST_BYTES,
        )
        val body = fetched.body ?: return@withContext null
        if (!looksLikeNuvioManifest(body)) return@withContext null
        installRepo(parsed.toString(), body)
    }

    suspend fun removeRepo(repoUrl: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            scraperDao.deleteForRepo(repoUrl)
            scripts.deleteRepo(repoUrl)
            approvals.removeRepo(repoUrl)
            streamCache.clear()
        }
    }

    suspend fun setScraperEnabled(scraper: NuvioScraperEntity, enabled: Boolean) {
        mutationMutex.withLock {
            scraperDao.update(scraper.copy(isEnabled = enabled))
            streamCache.clear()
        }
    }

    suspend fun setScraperSettings(scraper: NuvioScraperEntity, settingsJson: String) {
        mutationMutex.withLock {
            scraperDao.update(scraper.copy(settingsJson = sanitizeSettings(settingsJson)))
            streamCache.clear()
        }
    }

    suspend fun getSettingsSchema(scraper: NuvioScraperEntity): List<NuvioSettingField> {
        if (!available() || !masterEnabled() || !installed(scraper) || !isApproved(scraper, siblingUrls())) return emptyList()
        val code = scripts.readActive(scraper.repoUrl, scraper.scraperId, siblingUrls()) ?: return emptyList()
        if (NuvioDestinationPolicy.sha256Hex(code) != approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256) return emptyList()
        val schemaJson = try {
            withTimeout(NuvioLimits.OPERATION_BUDGET_MS) {
                engineSlots.withPermit {
                    if (!masterEnabled() || !installed(scraper) ||
                        NuvioDestinationPolicy.sha256Hex(code) != approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256) null
                    else engine.getSettingsSchema(evalRequest(scraper, code, "0", "movie", null, null))
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } ?: return emptyList()
        if (schemaJson.toByteArray(Charsets.UTF_8).size > NuvioLimits.MAX_SETTINGS_JSON_BYTES) return emptyList()
        return try {
            json.decodeFromString<List<NuvioSettingField>>(schemaJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun observeScrapers(repoUrl: String) = scraperDao.observeForRepo(repoUrl)

    fun observeManagement(repoUrl: String): Flow<List<NativePluginManagementState>> =
        combine(scraperDao.observeForRepo(repoUrl), approvals.revisions) { scrapers, _ ->
            val siblings = siblingUrls()
            scrapers.map { managementState(it, siblings) }
        }.flowOn(Dispatchers.IO)

    suspend fun approveInstalledCode(scraper: NuvioScraperEntity): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!installed(scraper)) return@withContext false
            val siblings = siblingUrls()
            scripts.migrateLegacy(scraper.repoUrl, siblings)
            val code = scripts.readActive(scraper.repoUrl, scraper.scraperId, siblings) ?: return@withContext false
            val modern = scripts.writeActive(scraper.repoUrl, scraper.scraperId, code)
            if (!modern) return@withContext false
            val persisted = approvals.update(scraper.repoUrl, scraper.scraperId) {
                it.copy(approvedCodeSha256 = NuvioDestinationPolicy.sha256Hex(code))
            }
            streamCache.clear()
            persisted
        }
    }

    suspend fun approvePendingUpdate(scraper: NuvioScraperEntity): Boolean = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            if (!installed(scraper)) return@withContext false
            val pendingHash = approvals.get(scraper.repoUrl, scraper.scraperId).pendingCodeSha256 ?: return@withContext false
            if (!scripts.promotePending(scraper.repoUrl, scraper.scraperId, pendingHash)) return@withContext false
            val persisted = approvals.update(scraper.repoUrl, scraper.scraperId) {
                it.copy(approvedCodeSha256 = pendingHash, pendingCodeSha256 = null)
            }
            streamCache.clear()
            persisted
        }
    }

    suspend fun discardPendingUpdate(scraper: NuvioScraperEntity) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            scripts.deletePending(scraper.repoUrl, scraper.scraperId)
            approvals.update(scraper.repoUrl, scraper.scraperId) { it.copy(pendingCodeSha256 = null) }
        }
    }

    suspend fun approveHost(scraper: NuvioScraperEntity, host: String): Boolean = mutationMutex.withLock {
            if (!installed(scraper)) return@withLock false
            val ok = approvals.approveHost(scraper.repoUrl, scraper.scraperId, host)
            if (ok) streamCache.clear()
            ok
    }

    suspend fun revokeHost(scraper: NuvioScraperEntity, host: String) {
        mutationMutex.withLock {
            approvals.revokeHost(scraper.repoUrl, scraper.scraperId, host)
            streamCache.clear()
        }
    }

    suspend fun nativePluginStatus(): NativePluginStatus = withContext(Dispatchers.IO) {
        val available = available()
        val enabled = available && masterEnabled()
        if (!available) return@withContext NativePluginStatus(available = false, enabled = false)
        val repos = nuvioRepos()
        val siblings = repos.map { it.manifestUrl }
        val providers = mutableListOf<NativePluginProvider>()
        for (repo in repos) {
            if (providers.size >= 256) break
            for (scraper in scraperDao.getForRepo(repo.manifestUrl).take(256 - providers.size)) {
                val state = managementState(scraper, siblings)
                providers += NativePluginProvider(
                    repoUrl = scraper.repoUrl,
                    scraperId = scraper.scraperId,
                    name = scraper.name.replace(Regex("[\\r\\n]"), " ").take(120),
                    enabled = repo.isEnabled && scraper.isEnabled,
                    requiresApproval = state.requiresApproval,
                )
            }
        }
        NativePluginStatus(available = true, enabled = enabled, providers = providers)
    }

    suspend fun resolveNativePlugins(
        repoUrl: String,
        scraperIds: List<String>,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): NativePluginResolution {
        if (!available()) {
            return NativePluginResolution(warnings = listOf("Native plugins are not available in this build"))
        }
        val warnings = mutableListOf<String>()
        val streams = mutableListOf<NativePluginStream>()
        try {
            withTimeout(NuvioLimits.OPERATION_BUDGET_MS) {
                resolveNativePluginsInner(repoUrl, scraperIds, tmdbId, mediaType, season, episode, streams, warnings)
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            warnings += "Native plugin resolution timed out"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            warnings += "Native plugin resolution failed"
        }
        return NativePluginResolution(streams = streams.take(NuvioLimits.MAX_STREAMS), warnings = warnings.take(NuvioLimits.MAX_WARNINGS))
    }

    suspend fun resolveStreams(
        stremioType: String,
        tmdbId: String,
        season: Int?,
        episode: Int?,
    ): List<ResolvedStream> = resolveStreamsFlow(stremioType, tmdbId, season, episode).last()

    fun resolveStreamsFlow(
        stremioType: String,
        tmdbId: String,
        season: Int?,
        episode: Int?,
    ): Flow<List<ResolvedStream>> = channelFlow {
        if (!available() || !masterEnabled()) {
            send(emptyList())
            return@channelFlow
        }
        val nuvioType = nuvioType(stremioType)
        val selected = runnableScrapers(null, nuvioType)
        // Cache eligibility changes with repo/scraper toggles, settings and domain approvals.
        val stateKey = selected.joinToString("|") {
            "${it.repoUrl}:${it.scraperId}:${NuvioDestinationPolicy.sha256Hex(it.settingsJson)}:${approvals.get(it.repoUrl, it.scraperId)}"
        }
        val cacheKey = "$nuvioType:$tmdbId:${season ?: ""}:${episode ?: ""}:${NuvioDestinationPolicy.sha256Hex(stateKey)}"
        streamCache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.timestamp < SCRAPER_CACHE_TTL_MS) {
                send(it.streams)
                return@channelFlow
            }
        }
        send(emptyList())
        if (selected.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }
        val accumulated = mutableListOf<ResolvedStream>()
        val operationBudget = NuvioRequestBudget(64, Semaphore(64))
        try {
            withTimeout(NuvioLimits.OPERATION_BUDGET_MS) {
                supervisorScope {
                    selected.forEach { scraper ->
                        launch(Dispatchers.IO) {
                            val found = runScraper(scraper, tmdbId, nuvioType, season, episode, operationBudget).streams
                            if (found.isNotEmpty()) {
                                val latest = synchronized(accumulated) {
                                    accumulated.addAll(found.take((NuvioLimits.MAX_STREAMS - accumulated.size).coerceAtLeast(0)))
                                    accumulated.sortedByDescending { it.stream.isDirectUrl }.toList()
                                }
                                send(latest)
                            }
                        }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Retain completed providers when the shared lookup budget expires.
        }
        val finalStreams = synchronized(accumulated) {
            accumulated.sortedByDescending { it.stream.isDirectUrl }.toList()
        }
        send(finalStreams)
        if (finalStreams.isNotEmpty()) {
            if (streamCache.size >= 32) streamCache.clear()
            streamCache[cacheKey] = CacheEntry(System.currentTimeMillis(), finalStreams)
        }
    }

    private suspend fun resolveNativePluginsInner(
        repoUrl: String,
        scraperIds: List<String>,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        streams: MutableList<NativePluginStream>,
        warnings: MutableList<String>,
    ) {
        if (NuvioDestinationPolicy.parseInstallUrl(repoUrl, false) == null) {
            warnings += "Provider repository was not specified"
            return
        }
        if (!tmdbId.matches(Regex("^[1-9][0-9]{0,9}$"))) {
            warnings += "Invalid media id"
            return
        }
        if (mediaType !in setOf("movie", "tv")) {
            warnings += "Invalid media type"
            return
        }
        if ((mediaType == "movie" && (season != null || episode != null)) ||
            (mediaType == "tv" && (season == null || season !in 0..10_000 || episode == null || episode !in 1..10_000))) {
            warnings += "Invalid season or episode"
            return
        }
        if (!masterEnabled()) {
            warnings += "Native plugins are turned off"
            return
        }
        val ids = scraperIds
        if (ids.size !in 1..NuvioLimits.MAX_SCRAPER_IDS || ids.distinct().size != ids.size ||
            ids.any { NuvioDestinationPolicy.safeScraperId(it) == null }) {
            warnings += "Invalid provider identifiers"
            return
        }
        val nuvioType = nuvioType(mediaType)
        val repos = nuvioRepos()
        val repo = repos.firstOrNull { it.manifestUrl == repoUrl }
        if (repo == null) {
            warnings += "Provider is not installed"
            return
        }
        if (!repo.isEnabled) {
            warnings += "Provider is disabled"
            return
        }
        val installed = scraperDao.getForRepo(repoUrl).associateBy { it.scraperId }
        val selected = ids.take(NuvioLimits.MAX_SCRAPER_IDS).mapNotNull { id ->
            val safe = NuvioDestinationPolicy.safeScraperId(id)
            val scraper = if (safe == null) null else installed[safe]
            when {
                safe == null -> {
                    warnings += "Ignored an invalid provider id"
                    null
                }
                scraper == null || scraper.repoUrl != repoUrl -> {
                    warnings += "Provider is not installed"
                    null
                }
                !scraper.isEnabled || !scraper.supportsType(nuvioType) -> {
                    warnings += "Provider is disabled"
                    null
                }
                else -> scraper
            }
        }
        val requestBudget = NuvioRequestBudget(64, Semaphore(64))
        supervisorScope {
            selected.map { scraper ->
                async(Dispatchers.IO) {
                    val outcome = runScraper(scraper, tmdbId, nuvioType, season, episode, requestBudget)
                    synchronized(streams) { streams += outcome.nativeStreams.take((NuvioLimits.MAX_STREAMS - streams.size).coerceAtLeast(0)) }
                    synchronized(warnings) { warnings += outcome.warnings.take((NuvioLimits.MAX_WARNINGS - warnings.size).coerceAtLeast(0)) }
                }
            }.forEach { awaitOrEmpty(it) }
        }
    }

    private suspend fun runScraper(
        scraper: NuvioScraperEntity,
        tmdbId: String,
        nuvioType: String,
        season: Int?,
        episode: Int?,
        operationBudget: NuvioRequestBudget? = null,
    ): RunOutcome {
        val siblings = siblingUrls()
        if (!isApproved(scraper, siblings)) {
            return RunOutcome(warnings = listOf("Provider code requires approval"))
        }
        val code = scripts.readActive(scraper.repoUrl, scraper.scraperId, siblings)
        val approvedHash = approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256
        if (code == null || approvedHash == null || NuvioDestinationPolicy.sha256Hex(code) != approvedHash) {
            return RunOutcome(warnings = listOf("Provider code requires approval"))
        }
        val record = approvals.get(scraper.repoUrl, scraper.scraperId)
        val warnings = mutableListOf<String>()
        if (record.pendingCodeSha256 != null) warnings += "Provider update is waiting for approval"
        val budget = NuvioRequestBudget(NuvioLimits.MAX_REQUESTS_PER_EVAL, http.requestSlots)
        val outcome = try {
            engineSlots.withPermit {
                withTimeout(NuvioLimits.EVALUATION_TIMEOUT_MS + 5_000) {
                    val current = scraperDao.getForRepo(scraper.repoUrl).firstOrNull { it.scraperId == scraper.scraperId }
                    if (current?.isEnabled != true || !masterEnabled() ||
                        nuvioRepos().none { it.manifestUrl == scraper.repoUrl && it.isEnabled } ||
                        approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256 != approvedHash) {
                        return@withTimeout NuvioEngineOutcome(warnings = listOf("Provider is no longer enabled or approved"))
                    }
                    engine.getStreams(evalRequest(scraper, code, tmdbId, nuvioType, season, episode) { requestJson ->
                        if (!masterEnabled() || approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256 != approvedHash) {
                            throw CancellationException("Provider approval was revoked")
                        }
                        val gate = NuvioHostGate(approvals.get(scraper.repoUrl, scraper.scraperId).approvedHosts.toSet()) { host ->
                            approvals.addBlockedHost(scraper.repoUrl, scraper.scraperId, host)
                        }
                        if (operationBudget == null) http.pluginRequest(requestJson, gate, budget)
                        else operationBudget.use { http.pluginRequest(requestJson, gate, budget) }
                            ?: """{"ok":false,"status":0,"body":"","headers":{},"error":"request limit reached"}"""
                    })
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            NuvioEngineOutcome(warnings = listOf("Provider timed out"))
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            NuvioEngineOutcome(warnings = listOf("Provider resolution failed"))
        }
        if (!masterEnabled() || approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256 != approvedHash) {
            return RunOutcome(warnings = listOf("Provider is no longer enabled or approved"))
        }
        val blockedWarnings = gateBlockedWarnings(scraper)
        return RunOutcome(
            streams = outcome.streams.take(NuvioLimits.MAX_STREAMS).mapNotNull { it.toResolvedStream(scraper.name) },
            nativeStreams = outcome.streams.take(NuvioLimits.MAX_STREAMS).mapNotNull { it.toNativeOrNull(scraper) },
            warnings = (warnings + outcome.warnings + blockedWarnings).map { safeWarning(it) },
        )
    }

    private fun gateBlockedWarnings(scraper: NuvioScraperEntity): List<String> {
        return approvals.get(scraper.repoUrl, scraper.scraperId).blockedHosts
            .filter { NuvioDestinationPolicy.isRecordableHost(it) }
            .take(8)
            .map { "Blocked unapproved host $it" }
    }

    private fun evalRequest(
        scraper: NuvioScraperEntity,
        code: String,
        tmdbId: String,
        nuvioType: String,
        season: Int?,
        episode: Int?,
        fetch: suspend (String) -> String = { _ ->
            """{"ok":false,"status":0,"statusText":"blocked","url":"","headers":{},"body":"","error":"blocked"}"""
        },
    ) = NuvioEvalRequest(
        scraperName = scraper.name,
        scraperCode = code,
        tmdbId = tmdbId,
        nuvioType = nuvioType,
        season = season,
        episode = episode,
        settingsJson = sanitizeSettings(scraper.settingsJson),
        fetch = fetch,
    )

    private suspend fun installRepoInternal(manifestUrl: String, preFetchedBody: String?): Boolean =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
            val parsed = NuvioDestinationPolicy.parseInstallUrl(manifestUrl, allowCleartextInstall) ?: return@withContext false
            val body = when {
                preFetchedBody != null && preFetchedBody.toByteArray(Charsets.UTF_8).size <= NuvioLimits.MAX_MANIFEST_BYTES -> preFetchedBody
                preFetchedBody != null -> return@withContext false
                else -> http.fetchText(
                    parsed.toString(),
                    NuvioRequestBudget(4, http.requestSlots),
                    NuvioLimits.MAX_MANIFEST_BYTES,
                ).body ?: return@withContext false
            }
            val manifest = try {
                json.decodeFromString<NuvioManifest>(body)
            } catch (_: Exception) {
                return@withContext false
            }
            if (manifest.scrapers.isEmpty() || manifest.scrapers.size > 256 ||
                manifest.scrapers.none { it.isAndroidCompatible && NuvioDestinationPolicy.safeScraperId(it.id) != null &&
                    NuvioDestinationPolicy.safeScriptFilename(it.filename) != null }) return@withContext false
            val siblings = siblingUrls()
            scripts.migrateLegacy(parsed.toString(), siblings)
            val previous = scraperDao.getForRepo(parsed.toString()).associateBy { it.scraperId }
            val existingAddons = addonDao.getAllSync()
            val existingMatch = existingAddons.find { it.manifestUrl == parsed.toString() }
            val sortOrder = existingMatch?.sortOrder ?: ((existingAddons.maxOfOrNull { it.sortOrder } ?: -1) + 1)
            addonDao.insert(
                InstalledAddonEntity(
                    manifestUrl = parsed.toString(),
                    name = manifest.name.ifBlank { "Nuvio Plugins" }.take(120),
                    description = manifest.description.take(500),
                    baseUrl = parsed.newBuilder().query(null).fragment(null).build().toString().substringBeforeLast('/'),
                    version = manifest.version.take(40),
                    types = "movie,series",
                    resources = json.encodeToString(listOf(NUVIO_RESOURCE)),
                    resourceDetailsJson = "",
                    sortOrder = sortOrder,
                    isEnabled = existingMatch?.isEnabled ?: true,
                    disabledFeatures = existingMatch?.disabledFeatures ?: "",
                    installedAt = existingMatch?.installedAt ?: System.currentTimeMillis(),
                )
            )
            val budget = NuvioRequestBudget(NuvioLimits.MAX_INSTALL_REQUESTS, http.requestSlots)
            val keepIds = mutableListOf<String>()
            for (info in manifest.scrapers) {
                val id = NuvioDestinationPolicy.safeScraperId(info.id) ?: continue
                keepIds += id
                if (!info.isAndroidCompatible || NuvioDestinationPolicy.safeScriptFilename(info.filename) == null) continue
                val codeUrl = codeUrl(parsed, info.filename) ?: continue
                val prior = previous[id]
                val activeHash = scripts.hashActive(parsed.toString(), id, siblings)
                val approval = approvals.get(parsed.toString(), id)
                val downloaded = http.fetchText(codeUrl, budget, NuvioLimits.MAX_CODE_BYTES).body ?: continue
                if (downloaded.toByteArray(Charsets.UTF_8).size > NuvioLimits.MAX_CODE_BYTES) continue
                val newHash = NuvioDestinationPolicy.sha256Hex(downloaded)
                val retainApproved = approval.approvedCodeSha256 != null && activeHash == approval.approvedCodeSha256
                if (retainApproved) {
                    if (newHash != approval.approvedCodeSha256) {
                        if (!scripts.writePending(parsed.toString(), id, downloaded)) continue
                        approvals.update(parsed.toString(), id) { it.copy(pendingCodeSha256 = newHash) }
                    }
                } else {
                    if (!scripts.writeActive(parsed.toString(), id, downloaded)) continue
                    approvals.update(parsed.toString(), id) { it.copy(pendingCodeSha256 = null) }
                }
                scraperDao.insertAll(
                    listOf(
                        if (retainApproved && newHash != approval.approvedCodeSha256 && prior != null) prior else NuvioScraperEntity(
                            repoUrl = parsed.toString(),
                            scraperId = id,
                            name = info.name.ifBlank { id }.take(120),
                            description = info.description.take(500),
                            version = info.version.take(40),
                            filename = info.filename,
                            supportedTypes = info.supportedTypes.joinToString(",").ifBlank { "movie,tv" },
                            contentLanguage = info.contentLanguage.joinToString(",").take(80),
                            logo = info.logo.take(300),
                            isEnabled = prior?.isEnabled ?: info.enabled,
                            hasSettings = info.hasSettings,
                            settingsJson = sanitizeSettings(prior?.settingsJson ?: "{}"),
                            installedAt = prior?.installedAt ?: System.currentTimeMillis(),
                        )
                    )
                )
            }
            if (keepIds.isEmpty()) return@withContext false
            val removed = previous.keys - keepIds.toSet()
            removed.forEach {
                scripts.deleteScraper(parsed.toString(), it)
                approvals.remove(parsed.toString(), it)
            }
            scraperDao.deleteStale(parsed.toString(), keepIds)
            NuvioLog.i(TAG, "Installed ${keepIds.size} Nuvio scraper records")
            streamCache.clear()
            true
            }
        }

    private fun codeUrl(manifest: okhttp3.HttpUrl, filename: String): String? {
        val safe = NuvioDestinationPolicy.safeScriptFilename(filename) ?: return null
        val resolved = manifest.resolve(safe) ?: return null
        if (resolved.scheme != manifest.scheme || resolved.host != manifest.host) return null
        val dir = manifest.encodedPath.substringBeforeLast('/', "")
        val prefix = if (dir.isEmpty()) "/" else "$dir/"
        if (!resolved.encodedPath.startsWith(prefix) || resolved.encodedPath.contains("..")) return null
        if (NuvioDestinationPolicy.isBlockedHostname(resolved.host)) return null
        return resolved.toString()
    }

    private suspend fun runnableScrapers(repoUrl: String?, nuvioType: String): List<NuvioScraperEntity> {
        val repos = nuvioRepos().filter { it.isEnabled && (repoUrl == null || it.manifestUrl == repoUrl) }
        val siblings = repos.map { it.manifestUrl }
        return repos.flatMap { scraperDao.getForRepo(it.manifestUrl) }
            .filter { it.isEnabled && it.supportsType(nuvioType) && isApproved(it, siblings) }
    }

    private suspend fun nuvioRepos(): List<InstalledAddonEntity> =
        addonDao.getAllSync().filter { it.resources.contains(NUVIO_RESOURCE) }

    private suspend fun siblingUrls(): List<String> = nuvioRepos().map { it.manifestUrl }

    private suspend fun installed(scraper: NuvioScraperEntity): Boolean =
        nuvioRepos().any { it.manifestUrl == scraper.repoUrl } &&
            scraperDao.getForRepo(scraper.repoUrl).any { it.scraperId == scraper.scraperId }

    private suspend fun <T> awaitOrEmpty(deferred: kotlinx.coroutines.Deferred<T>): T? = try {
        deferred.await()
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private fun isApproved(scraper: NuvioScraperEntity, siblings: Collection<String>): Boolean {
        val approved = approvals.get(scraper.repoUrl, scraper.scraperId).approvedCodeSha256 ?: return false
        return scripts.hashActive(scraper.repoUrl, scraper.scraperId, siblings) == approved
    }

    private fun managementState(scraper: NuvioScraperEntity, siblings: Collection<String>): NativePluginManagementState {
        val record = approvals.get(scraper.repoUrl, scraper.scraperId)
        val approved = scripts.hashActive(scraper.repoUrl, scraper.scraperId, siblings) == record.approvedCodeSha256 &&
            record.approvedCodeSha256 != null
        return NativePluginManagementState(
            scraper = scraper,
            requiresApproval = !approved,
            updateAvailable = record.pendingCodeSha256 != null,
            approvedHosts = record.approvedHosts,
            blockedHosts = record.blockedHosts,
        )
    }

    private fun available(): Boolean = pluginsSupported && engine.canExecute

    private fun nuvioType(stremioType: String): String =
        if (stremioType == "series" || stremioType == "tv") "tv" else "movie"

    private fun sanitizeSettings(raw: String): String {
        if (raw.toByteArray(Charsets.UTF_8).size > NuvioLimits.MAX_SETTINGS_JSON_BYTES) return "{}"
        return try {
            val element = json.parseToJsonElement(raw)
            if (element !is JsonObject) "{}" else json.encodeToString(element.jsonObject)
        } catch (_: Exception) {
            "{}"
        }
    }

    private fun safeWarning(message: String): String =
        message.replace(Regex("[\\r\\n]"), " ").take(180)

    private data class RunOutcome(
        val streams: List<ResolvedStream> = emptyList(),
        val nativeStreams: List<NativePluginStream> = emptyList(),
        val warnings: List<String> = emptyList(),
    )
}

private fun NuvioStreamResult.toResolvedStream(scraperName: String): ResolvedStream? {
    val native = toPublicStream(scraperName, scraperName) ?: return null
    return ResolvedStream(
        addonName = scraperName,
        stream = StremioStream(
            url = native.url,
            name = native.name,
            title = native.title,
            headers = native.headers ?: emptyMap(),
        )
    )
}

private fun NuvioStreamResult.toNativeOrNull(scraper: NuvioScraperEntity): NativePluginStream? =
    toPublicStream(scraper.name, "${scraper.repoUrl}:${scraper.scraperId}")

private fun NuvioStreamResult.toPublicStream(addonName: String, addonUrl: String): NativePluginStream? {
    val raw = url ?: return null
    if (raw.length > 8192) return null
    val parsed = raw.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (NuvioDestinationPolicy.isBlockedHostname(parsed.host)) return null
    NuvioDestinationPolicy.literalAddress(parsed.host)?.let {
        if (NuvioDestinationPolicy.isBlockedAddress(it)) return null
    }
    val safeHeaders = headers.entries.mapNotNull { (name, value) ->
        if (name.length !in 1..64 || value.length > 2048) return@mapNotNull null
        if (!Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$").matches(name) || value.any { (it < ' ' && it != '\t') || it == '\u007f' }) return@mapNotNull null
        name to value
    }.take(16).toMap().ifEmpty { null }
    return NativePluginStream(
        addonName = addonName.replace(Regex("[\\r\\n]"), " ").take(120),
        addonUrl = addonUrl,
        url = raw,
        name = name?.replace(Regex("[\\r\\n]"), " ")?.take(180),
        title = title?.replace(Regex("[\\r\\n]"), " ")?.take(180),
        headers = safeHeaders,
    )
}
