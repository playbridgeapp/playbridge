package com.playbridge.sender.data.nuvio

import android.content.Context
import android.util.Log
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Executes a single Nuvio scraper script inside a sandboxed QuickJS context.
 *
 * The engine has no I/O of its own — scripts can only reach the network through the
 * injected `fetch`/`fetchv2` bridge, which routes through the app's OkHttpClient.
 * Every run gets a fresh context (no state leaks between scrapers) and is guarded by
 * [SCRAPER_TIMEOUT_MS]; a hung or crashing scraper returns an empty list instead of
 * stalling stream resolution.
 *
 * Contract (de-facto Nuvio provider API): the script defines
 * `getStreams(tmdbId, mediaType, season, episode)` returning a Promise of
 * `[{name, title, url, quality, size, headers, provider}]`, exported via
 * `module.exports = { getStreams }` or `global.getStreams = getStreams`.
 * Repo scripts are pre-transpiled to ES5+generators (for Hermes), which QuickJS
 * evaluates without issue.
 */
class NuvioScraperRunner(
    private val context: Context,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "NuvioScraperRunner"
        private const val SCRAPER_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_BYTES = 10L * 1024 * 1024 // 10 MB per fetch
        private const val MAX_STREAMS_PER_SCRAPER = 50
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Bundled JS libraries scrapers require() — read from assets once, on first use.
    // Both verified to load and execute under QuickJS.
    private val cryptoJsSource: String? by lazy { readAsset("nuvio/crypto-js.js") }
    private val cheerioSource: String? by lazy { readAsset("nuvio/cheerio.js") }

    private fun readAsset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read asset $path: ${e.message}")
        null
    }

    // Compiled QuickJS bytecode for the bundled libraries, cached across runs so the
    // ~400 KB sources are parsed at most once per process instead of every scraper run.
    // Portable across contexts of the same QuickJS version; source is kept as a fallback.
    @Volatile private var cryptoJsBytecode: ByteArray? = null
    @Volatile private var cheerioBytecode: ByteArray? = null

    /**
     * Run [scraperCode] and return its streams. Never throws — failures are logged
     * and yield an empty list.
     *
     * @param nuvioType "movie" or "tv" (Nuvio's naming; Stremio "series" must be
     *   mapped to "tv" by the caller).
     * @param settingsJson The user's saved per-scraper settings as a JSON object string
     *   (exposed to the scraper as `globalThis.settings`). Defaults to an empty object.
     */
    suspend fun getStreams(
        scraperName: String,
        scraperCode: String,
        tmdbId: String,
        nuvioType: String,
        season: Int?,
        episode: Int?,
        settingsJson: String = "{}"
    ): List<NuvioStreamResult> {
        val resultJson = evalInScraperContext(
            scraperName = scraperName,
            scraperCode = scraperCode,
            settingsJson = settingsJson,
            finalScript = buildInvokeScript(tmdbId, nuvioType, season, episode)
        )
        if (resultJson == null) {
            Log.w(TAG, "[$scraperName] no result (timeout or error)")
            return emptyList()
        }
        return parseResults(scraperName, resultJson)
    }

    /**
     * Invoke the scraper's exported `onSettings()` (if any) and return its settings-schema
     * JSON array, or null when the scraper declares no settings. Used to render the
     * per-scraper settings UI.
     */
    suspend fun getSettingsSchema(scraperName: String, scraperCode: String): String? {
        if (!scraperCode.contains("onSettings")) return null
        return evalInScraperContext(
            scraperName = scraperName,
            scraperCode = scraperCode,
            settingsJson = "{}",
            finalScript = SETTINGS_SCHEMA_SCRIPT
        )
    }

    /**
     * Sets up a sandboxed QuickJS context (bindings + prelude + required modules +
     * injected settings + the scraper), then evaluates [finalScript] and returns its
     * String result. Guarded by [SCRAPER_TIMEOUT_MS]; never throws.
     */
    private suspend fun evalInScraperContext(
        scraperName: String,
        scraperCode: String,
        settingsJson: String,
        finalScript: String
    ): String? = try {
        withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
            quickJs(jobDispatcher = Dispatchers.IO) {
                asyncFunction("__httpRequest") { args ->
                    httpRequest(args.firstOrNull() as? String ?: "{}")
                }
                function("__log") { args ->
                    Log.d(TAG, "[$scraperName] " + args.joinToString(" ") { it?.toString() ?: "null" })
                }
                evaluate<Any?>(PRELUDE_JS)

                // Lazily register CommonJS modules the scraper require()s. Loading these
                // ~400 KB bundles is skipped entirely for scrapers that don't need them
                // (many are pure-regex). Each module is loaded with a fresh module/exports
                // pair so their exports never cross-contaminate. Bytecode is compiled once
                // and reused; on any read error we fall back to evaluating the source.
                if (scraperCode.contains("crypto-js")) {
                    cryptoJsSource?.let { src ->
                        evaluate<Any?>(RESET_MODULE_JS)
                        val bc = cryptoJsBytecode ?: compile(src, "crypto-js.js").also { cryptoJsBytecode = it }
                        try { evaluate<Any?>(bc) } catch (e: Exception) { evaluate<Any?>(src) }
                        evaluate<Any?>("globalThis.__modules['crypto-js']=globalThis.module.exports;")
                    }
                }
                if (scraperCode.contains("cheerio")) {
                    cheerioSource?.let { src ->
                        evaluate<Any?>(RESET_MODULE_JS)
                        val bc = cheerioBytecode ?: compile(src, "cheerio.js").also { cheerioBytecode = it }
                        try { evaluate<Any?>(bc) } catch (e: Exception) { evaluate<Any?>(src) }
                        evaluate<Any?>(
                            "(function(){var c=globalThis.module.exports;" +
                                "globalThis.__modules['cheerio']=c;" +
                                "globalThis.__modules['cheerio-without-node-native']=c;})();"
                        )
                    }
                }

                // Expose the user's per-scraper settings to the scraper.
                evaluate<Any?>("globalThis.settings = JSON.parse(${json.encodeToString(settingsJson)});")

                evaluate<Any?>(RESET_MODULE_JS)
                evaluate<Any?>(scraperCode)
                evaluate<String>(finalScript)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "[$scraperName] scraper failed: ${e.message}")
        null
    }

    private fun parseResults(scraperName: String, resultJson: String): List<NuvioStreamResult> {
        return try {
            json.decodeFromString<List<NuvioStreamResult>>(resultJson)
                .filter {
                    val u = it.url
                    u != null && (u.startsWith("http://") || u.startsWith("https://"))
                }
                .take(MAX_STREAMS_PER_SCRAPER)
        } catch (e: Exception) {
            Log.w(TAG, "[$scraperName] unparseable result: ${e.message}")
            emptyList()
        }
    }

    private fun buildInvokeScript(tmdbId: String, nuvioType: String, season: Int?, episode: Int?): String {
        val tmdbJs = json.encodeToString(tmdbId)
        val typeJs = json.encodeToString(nuvioType)
        val seasonJs = season?.toString() ?: "null"
        val episodeJs = episode?.toString() ?: "null"
        return """
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
    }

    // ==================== Native HTTP bridge ====================

    /**
     * Executes one HTTP request on behalf of the sandbox. Request/response cross the
     * bridge as JSON strings to keep the type mapping trivial.
     */
    private suspend fun httpRequest(requestJson: String): String = withContext(Dispatchers.IO) {
        try {
            val req = json.parseToJsonElement(requestJson).jsonObject
            val url = req["url"]?.jsonPrimitive?.contentOrNull
                ?: return@withContext errorResponse("missing url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext errorResponse("unsupported scheme")
            }
            val method = (req["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
            val headers = req["headers"]?.jsonObject
            val bodyStr = req["body"]?.jsonPrimitive?.contentOrNull

            val builder = Request.Builder().url(url)
            var hasUserAgent = false
            headers?.forEach { (name, value) ->
                val v = value.jsonPrimitive.contentOrNull ?: return@forEach
                if (name.equals("user-agent", ignoreCase = true)) hasUserAgent = true
                builder.header(name, v)
            }
            if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)

            if (method == "GET" || method == "HEAD") {
                builder.method(method, null)
            } else {
                val contentType = headers?.entries
                    ?.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                    ?.value?.jsonPrimitive?.contentOrNull
                    ?: "application/json"
                builder.method(method, (bodyStr ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
            }

            client.newCall(builder.build()).execute().use { response ->
                val rb = response.body
                if ((rb?.contentLength() ?: -1) > MAX_RESPONSE_BYTES) {
                    return@withContext errorResponse("response too large")
                }
                // Buffer up to the cap; reject bodies that exceed it (unknown-length case).
                val body = rb?.let {
                    val source = it.source()
                    source.request(MAX_RESPONSE_BYTES + 1)
                    if (source.buffer.size > MAX_RESPONSE_BYTES) {
                        return@withContext errorResponse("response too large")
                    }
                    source.buffer.snapshot().string(it.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
                } ?: ""

                buildJsonObject {
                    put("ok", response.isSuccessful)
                    put("status", response.code)
                    put("statusText", response.message)
                    put("url", response.request.url.toString())
                    put("headers", buildJsonObject {
                        // Last value wins for repeated headers — sufficient for scrapers.
                        for (i in 0 until response.headers.size) {
                            put(response.headers.name(i).lowercase(), response.headers.value(i))
                        }
                    })
                    put("body", body)
                }.toString()
            }
        } catch (e: Exception) {
            errorResponse(e.message ?: "request failed")
        }
    }

    private fun errorResponse(message: String): String = buildJsonObject {
        put("ok", false)
        put("status", 0)
        put("statusText", message)
        put("url", "")
        put("headers", buildJsonObject { })
        put("body", "")
        put("error", message)
    }.toString()
}

/**
 * Environment injected before scraper code: CommonJS shims, console, fetch/fetchv2,
 * base64 helpers, and no-op timers. Written in ES5 style (no template literals) so
 * it never fights Kotlin string interpolation.
 */
private val PRELUDE_JS = """
"use strict";
globalThis.global = globalThis;
globalThis.module = { exports: {} };
globalThis.exports = globalThis.module.exports;
globalThis.process = { env: {}, platform: "android" };

// Minimal WebCrypto RNG so crypto-js finds a random source (it otherwise throws
// "Native crypto module could not be used"). Not cryptographically strong, but
// scrapers use crypto-js almost entirely for decryption with known keys/IVs.
if (typeof globalThis.crypto === "undefined" || !globalThis.crypto.getRandomValues) {
    globalThis.crypto = {
        getRandomValues: function (arr) {
            for (var i = 0; i < arr.length; i++) arr[i] = (Math.random() * 256) | 0;
            return arr;
        }
    };
}

// CommonJS module registry populated by the host before the scraper runs.
globalThis.__modules = {};
globalThis.require = function (name) {
    if (Object.prototype.hasOwnProperty.call(globalThis.__modules, name)) {
        return globalThis.__modules[name];
    }
    throw new Error("Cannot find module '" + name + "'");
};

globalThis.console = (function () {
    function fmt(a) {
        if (a === null) return "null";
        if (a === undefined) return "undefined";
        if (typeof a === "object") { try { return JSON.stringify(a); } catch (e) { return String(a); } }
        return String(a);
    }
    function emit(level, args) {
        var parts = [];
        for (var i = 0; i < args.length; i++) parts.push(fmt(args[i]));
        __log(level + ": " + parts.join(" "));
    }
    return {
        log: function () { emit("log", arguments); },
        info: function () { emit("info", arguments); },
        warn: function () { emit("warn", arguments); },
        error: function () { emit("error", arguments); },
        debug: function () { emit("debug", arguments); }
    };
})();

// Timers: scrapers only use these for throttling/retries; run callbacks on the
// microtask queue instead of waiting (delays are meaningless inside the sandbox).
globalThis.setTimeout = function (fn) { if (typeof fn === "function") Promise.resolve().then(fn); return 0; };
globalThis.clearTimeout = function () {};
globalThis.setInterval = function () { return 0; };
globalThis.clearInterval = function () {};

// Base64 (Latin-1 semantics, same as the browser's atob/btoa).
globalThis.btoa = function (input) {
    var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    var str = String(input), output = "";
    for (var block = 0, charCode, idx = 0, map = chars;
         str.charAt(idx | 0) || (map = "=", idx % 1);
         output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
        charCode = str.charCodeAt(idx += 3 / 4);
        if (charCode > 0xFF) throw new Error("btoa: invalid character");
        block = block << 8 | charCode;
    }
    return output;
};
globalThis.atob = function (input) {
    var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    var str = String(input).replace(/=+${'$'}/, ""), output = "";
    for (var bc = 0, bs, buffer, idx = 0;
         (buffer = str.charAt(idx++));
         ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4)
             ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
        buffer = chars.indexOf(buffer);
    }
    return output;
};
globalThis.base64Encode = globalThis.btoa;
globalThis.base64Decode = globalThis.atob;

function __makeResponse(r) {
    var headerMap = r.headers || {};
    return {
        ok: !!r.ok,
        status: r.status | 0,
        statusText: r.statusText || "",
        url: r.url || "",
        headers: {
            get: function (name) {
                var v = headerMap[String(name).toLowerCase()];
                return v === undefined ? null : v;
            },
            has: function (name) { return headerMap[String(name).toLowerCase()] !== undefined; },
            map: headerMap
        },
        text: function () { return Promise.resolve(r.body || ""); },
        json: function () {
            try { return Promise.resolve(JSON.parse(r.body)); }
            catch (e) { return Promise.reject(e); }
        }
    };
}

globalThis.fetch = function (url, opts) {
    opts = opts || {};
    var body = opts.body;
    if (body !== null && body !== undefined && typeof body !== "string") {
        try { body = JSON.stringify(body); } catch (e) { body = String(body); }
    }
    var req = {
        url: String(url),
        method: opts.method || "GET",
        headers: opts.headers || {},
        body: body === undefined ? null : body
    };
    return __httpRequest(JSON.stringify(req)).then(function (s) {
        return __makeResponse(JSON.parse(s));
    });
};

// Nuvio's native fetch variant: fetchv2(url, headers, method, body, redirect, encoding).
globalThis.fetchv2 = function (url, headers, method, body) {
    return globalThis.fetch(url, { headers: headers || {}, method: method || "GET", body: body });
};
""".trimIndent()

/** Fresh, in-sync module/exports pair so each require()d library loads cleanly. */
private const val RESET_MODULE_JS =
    "globalThis.module={exports:{}};globalThis.exports=globalThis.module.exports;"

/** Invokes the scraper's exported `onSettings()` and returns its schema as a JSON array string. */
private val SETTINGS_SCHEMA_SCRIPT = """
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
