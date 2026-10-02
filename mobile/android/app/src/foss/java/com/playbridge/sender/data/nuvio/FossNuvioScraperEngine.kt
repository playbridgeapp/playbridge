package com.playbridge.sender.data.nuvio

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors

internal class FossNuvioScraperEngine(
    private val evaluationTimeoutMs: Long = EVALUATION_TIMEOUT_MS,
    private val overallBudgetMs: Long = OVERALL_BUDGET_MS,
) : NuvioScraperEngine {
    override val canExecute: Boolean = true

    companion object {
        private const val TAG = "FossNuvioScraperEngine"
        private const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024 // 16 MiB
        private const val MAX_STACK_SIZE_BYTES = 256L * 1024 // 256 KiB
        const val EVALUATION_TIMEOUT_MS = 15_000L // 15s evaluation timeout interrupts synchronous loops
        const val OVERALL_BUDGET_MS = 60_000L // 60s overall budget
        private const val MAX_STREAMS_PER_SCRAPER = 50
        private const val MAX_OUTPUT_BYTES = 512 * 1024 // 512 KiB bound before JSON decoding

        private val quickJsDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FossQuickJS-Worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun getStreams(request: NuvioEvalRequest): NuvioEngineOutcome {
        val tmdbJs = json.encodeToString(request.tmdbId)
        val typeJs = json.encodeToString(request.nuvioType)
        val seasonJs = request.season?.toString() ?: "null"
        val episodeJs = request.episode?.toString() ?: "null"
        val invokeScript = """
            await (async function () {
                var gs = null;
                if (typeof module !== "undefined" && module.exports) {
                    if (typeof module.exports.getStreams === "function") gs = module.exports.getStreams;
                    else if (module.exports.default && typeof module.exports.default.getStreams === "function") gs = module.exports.default.getStreams;
                }
                if (!gs && typeof globalThis.getStreams === "function") gs = globalThis.getStreams;
                if (!gs) return "[]";
                var res = await gs($tmdbJs, $typeJs, $seasonJs, $episodeJs);
                return JSON.stringify(Array.isArray(res) ? res : []);
            })()
        """.trimIndent()

        val outcome = evalInSandbox(request, invokeScript)
        if (outcome == null) {
            return NuvioEngineOutcome(
                streams = emptyList(),
                warnings = listOf("${request.scraperName}: Execution timed out or failed.")
            )
        }

        val utf8Bytes = outcome.toByteArray(Charsets.UTF_8)
        if (utf8Bytes.size > MAX_OUTPUT_BYTES) {
            NuvioLog.w(TAG, "[${request.scraperName}] output exceeded size limit: ${utf8Bytes.size} bytes")
            return NuvioEngineOutcome(
                streams = emptyList(),
                warnings = listOf("${request.scraperName}: Stream result exceeded maximum size limit.")
            )
        }

        return try {
            val decoded = json.decodeFromString<List<NuvioStreamResult>>(outcome)
                .filter { item ->
                    val u = item.url
                    u != null && (u.startsWith("http://") || u.startsWith("https://"))
                }
                .take(MAX_STREAMS_PER_SCRAPER)
            NuvioEngineOutcome(streams = decoded, warnings = emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NuvioLog.w(TAG, "[${request.scraperName}] unparseable result")
            NuvioEngineOutcome(
                streams = emptyList(),
                warnings = listOf("${request.scraperName}: Invalid stream result returned.")
            )
        }
    }

    override suspend fun getSettingsSchema(request: NuvioEvalRequest): String? {
        if (!request.scraperCode.contains("onSettings")) return null
        val schemaScript = """
            await (async function () {
                var os = null;
                if (typeof module !== "undefined" && module.exports && typeof module.exports.onSettings === "function") {
                    os = module.exports.onSettings;
                }
                if (!os && typeof globalThis.onSettings === "function") os = globalThis.onSettings;
                if (!os) return "[]";
                var r = await os();
                return JSON.stringify(Array.isArray(r) ? r : []);
            })()
        """.trimIndent()
        val schema = evalInSandbox(request, schemaScript) ?: return null
        if (schema.toByteArray(Charsets.UTF_8).size > MAX_OUTPUT_BYTES) return null
        return schema
    }

    private suspend fun evalInSandbox(request: NuvioEvalRequest, finalScript: String): String? {
        return try {
            withContext(quickJsDispatcher) {
                withTimeoutOrNull(overallBudgetMs) {
                    quickJs(jobDispatcher = quickJsDispatcher) {
                        memoryLimit = MEMORY_LIMIT_BYTES
                        maxStackSize = MAX_STACK_SIZE_BYTES
                        evaluationTimeoutMillis = evaluationTimeoutMs

                        asyncFunction("__httpRequest") { args ->
                            val reqJson = args.firstOrNull() as? String ?: "{}"
                            request.fetch(reqJson)
                        }
                        function("__log") { _ ->
                            // No-op to avoid exposing provider credentials or raw URLs to logcat
                        }

                        evaluate<Any?>(PRELUDE_JS)
                        registerCryptoBridge()
                        DomBridge().register(this)

                        evaluate<Any?>("globalThis.settings = JSON.parse(${json.encodeToString(request.settingsJson)});")
                        evaluate<Any?>(RESET_MODULE_JS)
                        evaluate<Any?>(request.scraperCode)
                        evaluate<String>(finalScript)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            NuvioLog.w(TAG, "[${request.scraperName}] evaluation error")
            null
        }
    }
}
