package com.playbridge.sender.data.nuvio

import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NuvioRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repoUrl = "https://plugins.example/manifest.json"

    private class Addons : AddonDao {
        val rows = MutableStateFlow<List<InstalledAddonEntity>>(emptyList())
        override fun getAll(): Flow<List<InstalledAddonEntity>> = rows
        override suspend fun getAllSync() = rows.value
        override suspend fun insert(addon: InstalledAddonEntity) { rows.value = rows.value.filterNot { it.manifestUrl == addon.manifestUrl } + addon }
        override suspend fun update(addon: InstalledAddonEntity) = insert(addon)
        override suspend fun delete(addon: InstalledAddonEntity) = deleteByUrl(addon.manifestUrl)
        override suspend fun deleteByUrl(manifestUrl: String) { rows.value = rows.value.filterNot { it.manifestUrl == manifestUrl } }
        override suspend fun count() = rows.value.size
    }

    private class Scrapers : NuvioScraperDao {
        val rows = MutableStateFlow<List<NuvioScraperEntity>>(emptyList())
        val queriedRepos = mutableListOf<String>()
        override suspend fun getForRepo(repoUrl: String): List<NuvioScraperEntity> {
            synchronized(queriedRepos) { queriedRepos += repoUrl }
            return rows.value.filter { it.repoUrl == repoUrl }
        }
        override fun observeForRepo(repoUrl: String) = rows.map { values -> values.filter { it.repoUrl == repoUrl } }
        override fun observeAll(): Flow<List<NuvioScraperEntity>> = rows
        override suspend fun insertAll(scrapers: List<NuvioScraperEntity>) {
            val keys = scrapers.map { it.repoUrl to it.scraperId }.toSet()
            rows.value = rows.value.filterNot { (it.repoUrl to it.scraperId) in keys } + scrapers
        }
        override suspend fun update(scraper: NuvioScraperEntity) = insertAll(listOf(scraper))
        override suspend fun deleteForRepo(repoUrl: String) { rows.value = rows.value.filterNot { it.repoUrl == repoUrl } }
        override suspend fun deleteStale(repoUrl: String, keepIds: List<String>) {
            rows.value = rows.value.filterNot { it.repoUrl == repoUrl && it.scraperId !in keepIds }
        }
    }

    private class Engine : NuvioScraperEngine {
        override val canExecute = true
        val requests = mutableListOf<NuvioEvalRequest>()
        var suspendExecution = false
        var executionDelayMs = 0L
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        override suspend fun getStreams(request: NuvioEvalRequest): NuvioEngineOutcome {
            synchronized(requests) { requests += request }
            started.complete(Unit)
            if (suspendExecution) try { awaitCancellation() } finally { cancelled = true }
            delay(executionDelayMs)
            return NuvioEngineOutcome(streams = listOf(NuvioStreamResult(url = "https://cdn.example/movie.mp4")))
        }
        override suspend fun getSettingsSchema(request: NuvioEvalRequest): String? = null
    }

    private class Fixture(val root: File, val addons: Addons, val scrapers: Scrapers, val engine: Engine, val repository: NuvioRepository) {
        var enabled = false
        val scripts = NuvioScriptStore(File(root, "nuvio"))
    }

    private suspend fun fixture(): Fixture {
        val addons = Addons()
        val scrapers = Scrapers()
        val engine = Engine()
        val root = temporary.newFolder()
        lateinit var fixture: Fixture
        val repository = NuvioRepository(addons, scrapers, engine, root, { fixture.enabled }, pluginsSupported = true)
        fixture = Fixture(root, addons, scrapers, engine, repository)
        addons.insert(InstalledAddonEntity(repoUrl, "Plugins", baseUrl = "https://plugins.example", resources = "[\"nuvio\"]"))
        scrapers.insertAll(listOf(NuvioScraperEntity(repoUrl, "castle", "Castle", filename = "castle.js", settingsJson = "{\"token\":\"device-secret\"}")))
        assertTrue(fixture.scripts.writeActive(repoUrl, "castle", "approved-original-code"))
        return fixture
    }

    private suspend fun resolve(f: Fixture, ids: List<String> = listOf("castle")) =
        f.repository.resolveNativePlugins(repoUrl, ids, "60625", "tv", 8, 2)

    @Test fun `shared resolver requires opt-in and code approval and does not disclose settings in status`() = runBlocking {
        val f = fixture()
        assertFalse(f.repository.nativePluginStatus().enabled)
        assertTrue(resolve(f).streams.isEmpty())
        f.enabled = true
        assertTrue(resolve(f).streams.isEmpty())
        assertTrue(f.repository.nativePluginStatus().providers.single().requiresApproval)
        assertTrue(f.repository.approveInstalledCode(f.scrapers.rows.value.single()))
        assertEquals(1, resolve(f).streams.size)
        assertEquals("60625", f.engine.requests.single().tmdbId)
        assertEquals(8, f.engine.requests.single().season)
        assertEquals(2, f.engine.requests.single().episode)
        assertEquals("{\"token\":\"device-secret\"}", f.engine.requests.single().settingsJson)
        assertFalse(f.repository.nativePluginStatus().toString().contains("device-secret"))
    }

    @Test fun `pending update preserves approved bytes until explicit approval`() = runBlocking {
        val f = fixture()
        f.enabled = true
        val scraper = f.scrapers.rows.value.single()
        assertTrue(f.repository.approveInstalledCode(scraper))
        assertTrue(f.scripts.writePending(repoUrl, "castle", "updated-code"))
        NuvioApprovalStore(File(f.root, "nuvio")).update(repoUrl, "castle") {
            it.copy(pendingCodeSha256 = NuvioDestinationPolicy.sha256Hex("updated-code"))
        }
        assertFalse(f.repository.nativePluginStatus().providers.single().requiresApproval)
        resolve(f)
        assertEquals("approved-original-code", f.engine.requests.last().scraperCode)
        assertTrue(f.repository.approvePendingUpdate(scraper))
        resolve(f)
        assertEquals("updated-code", f.engine.requests.last().scraperCode)
        assertEquals("{\"token\":\"device-secret\"}", f.scrapers.rows.value.single().settingsJson)
        // Editing the on-disk active bytes never inherits approval.
        assertTrue(f.scripts.writeActive(repoUrl, "castle", "tampered-code"))
        assertTrue(f.repository.nativePluginStatus().providers.single().requiresApproval)
        assertTrue(resolve(f).streams.isEmpty())
        assertEquals(2, f.engine.requests.size)
    }

    @Test fun `unknown identifiers invalid episode and disabled repository cannot execute`() = runBlocking {
        val f = fixture()
        f.enabled = true
        assertTrue(f.repository.approveInstalledCode(f.scrapers.rows.value.single()))
        assertTrue(resolve(f, listOf("missing")).streams.isEmpty())
        assertTrue(resolve(f, listOf("castle", "castle")).streams.isEmpty())
        assertTrue(resolve(f, List(33) { "scraper$it" }).streams.isEmpty())
        assertTrue(f.repository.resolveNativePlugins(repoUrl, listOf("castle"), "60625", "tv", 8, null).streams.isEmpty())
        f.addons.update(f.addons.rows.value.single().copy(isEnabled = false))
        assertTrue(resolve(f).streams.isEmpty())
        assertTrue(f.engine.requests.isEmpty())
    }

    @Test fun `Library cache rechecks addon toggles and uses the same approved code as Streams`() = runBlocking {
        val f = fixture()
        f.enabled = true
        assertTrue(f.repository.approveInstalledCode(f.scrapers.rows.value.single()))
        assertEquals(1, f.repository.resolveStreams("series", "60625", 8, 2).size)
        assertEquals(1, f.repository.resolveStreams("series", "60625", 8, 2).size)
        assertEquals(1, f.engine.requests.size)
        f.addons.update(f.addons.rows.value.single().copy(isEnabled = false))
        assertTrue(f.repository.resolveStreams("series", "60625", 8, 2).isEmpty())
        f.addons.update(f.addons.rows.value.single().copy(isEnabled = true))
        assertEquals(1, resolve(f).streams.size)
        assertEquals("approved-original-code", f.engine.requests.last().scraperCode)
    }

    @Test fun `document cancellation reaches running engine`() = runBlocking {
        val f = fixture()
        f.enabled = true
        assertTrue(f.repository.approveInstalledCode(f.scrapers.rows.value.single()))
        f.engine.suspendExecution = true
        val lookup = async { resolve(f) }
        f.engine.started.await()
        lookup.cancelAndJoin()
        assertTrue(f.engine.cancelled)
    }

    @Test fun `status stops reading repositories when the provider limit is reached`() = runBlocking {
        val f = fixture()
        val secondRepo = "https://other.example/manifest.json"
        f.addons.insert(f.addons.rows.value.single().copy(manifestUrl = secondRepo))
        val original = f.scrapers.rows.value.single()
        f.scrapers.rows.value = List(256) { original.copy(scraperId = "provider$it") } +
            original.copy(repoUrl = secondRepo)
        assertEquals(256, f.repository.nativePluginStatus().providers.size)
        assertEquals(listOf(repoUrl), f.scrapers.queriedRepos)
    }

    @Test fun `queued providers retain their execution budget after acquiring a slot`() = runBlocking {
        val f = fixture()
        f.enabled = true
        val original = f.scrapers.rows.value.single()
        f.scrapers.rows.value = List(3) { original.copy(scraperId = "provider$it") }
        for (scraper in f.scrapers.rows.value) {
            assertTrue(f.scripts.writeActive(repoUrl, scraper.scraperId, "approved-original-code"))
            assertTrue(f.repository.approveInstalledCode(scraper))
        }
        // The third provider waits 11 seconds, then runs for 11 seconds. Its
        // execution timeout must start after the wait, within the 60s operation.
        f.engine.executionDelayMs = 11_000L
        val result = resolve(f, f.scrapers.rows.value.map { it.scraperId })
        assertEquals(3, result.streams.size)
        assertTrue(result.warnings.isEmpty())
    }
}
