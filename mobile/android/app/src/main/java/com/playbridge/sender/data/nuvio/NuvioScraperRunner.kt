package com.playbridge.sender.data.nuvio

import android.content.Context
import android.util.Log
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
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

        private val quickJsDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "QuickJS-Worker").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }



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
        withContext(quickJsDispatcher) {
            withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                quickJs(jobDispatcher = quickJsDispatcher) {
                    asyncFunction("__httpRequest") { args ->
                        httpRequest(args.firstOrNull() as? String ?: "{}")
                    }
                    function("__log") { args ->
                        Log.d(TAG, "[$scraperName] " + args.joinToString(" ") { it?.toString() ?: "null" })
                    }
                    evaluate<Any?>(PRELUDE_JS)

                    registerCryptoBridge()
                    DomBridge().register(this)

                    // Expose the user's per-scraper settings to the scraper.
                    evaluate<Any?>("globalThis.settings = JSON.parse(${json.encodeToString(settingsJson)});")

                    evaluate<Any?>(RESET_MODULE_JS)
                    evaluate<Any?>(scraperCode)
                    evaluate<String>(finalScript)
                }
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

// URL and URLSearchParams polyfill
if (typeof globalThis.URL === 'undefined') {
    var URLSearchParams = function(init) {
        this._params = {};
        var self = this;
        if (init && typeof init === 'object' && !Array.isArray(init)) {
            Object.keys(init).forEach(function(key) { self._params[key] = String(init[key]); });
        } else if (typeof init === 'string') {
            init.replace(/^\?/, '').split('&').forEach(function(pair) {
                var parts = pair.split('=');
                if (parts[0]) self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
            });
        }
    };
    URLSearchParams.prototype.toString = function() {
        var self = this;
        return Object.keys(this._params).map(function(key) {
            return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
        }).join('&');
    };
    URLSearchParams.prototype.get = function(key) { return this._params.hasOwnProperty(key) ? this._params[key] : null; };
    URLSearchParams.prototype.set = function(key, value) { this._params[key] = String(value); };
    URLSearchParams.prototype.append = function(key, value) { this._params[key] = String(value); };
    URLSearchParams.prototype.has = function(key) { return this._params.hasOwnProperty(key); };
    URLSearchParams.prototype.delete = function(key) { delete this._params[key]; };
    URLSearchParams.prototype.keys = function() { return Object.keys(this._params); };
    URLSearchParams.prototype.values = function() {
        var self = this;
        return Object.keys(this._params).map(function(k) { return self._params[k]; });
    };
    URLSearchParams.prototype.entries = function() {
        var self = this;
        return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
    };
    URLSearchParams.prototype.forEach = function(callback) {
        var self = this;
        Object.keys(this._params).forEach(function(key) { callback(self._params[key], key, self); });
    };
    globalThis.URLSearchParams = URLSearchParams;

    var URL = function(urlString, base) {
        var fullUrl = urlString;
        if (base && !/^https?:\/\//i.test(urlString)) {
            var b = typeof base === 'string' ? base : base.href;
            if (urlString.charAt(0) === '/') {
                var m = b.match(/^(https?:\/\/[^\/]+)/);
                fullUrl = m ? m[1] + urlString : urlString;
            } else {
                fullUrl = b.replace(/\/[^\/]*${'$'}/, '/') + urlString;
            }
        }
        this.href = fullUrl;
        var parser = /^(https?:)\/\/(([^:\/\?#]+)(?::([0-9]+))?)([\/][^?#]*)?([?][^#]*)?(#.*)?/i;
        var match = fullUrl.match(parser);
        if (match) {
            this.protocol = match[1] || "";
            this.host = match[2] || "";
            this.hostname = match[3] || "";
            this.port = match[4] || "";
            this.pathname = match[5] || "/";
            this.search = match[6] || "";
            this.hash = match[7] || "";
        } else {
            this.protocol = "";
            this.host = "";
            this.hostname = "";
            this.port = "";
            this.pathname = "/";
            this.search = "";
            this.hash = "";
        }
        this.origin = this.protocol + '//' + this.host;
        this.searchParams = new URLSearchParams(this.search || '');
    };
    URL.prototype.toString = function() { return this.href; };
    globalThis.URL = URL;
}

// AbortSignal and AbortController polyfill
if (typeof globalThis.AbortSignal === 'undefined') {
    var AbortSignal = function() { this.aborted = false; this.reason = undefined; this._listeners = []; };
    AbortSignal.prototype.addEventListener = function(type, listener) {
        if (type !== 'abort' || typeof listener !== 'function') return;
        this._listeners.push(listener);
    };
    AbortSignal.prototype.removeEventListener = function(type, listener) {
        if (type !== 'abort') return;
        this._listeners = this._listeners.filter(function(l) { return l !== listener; });
    };
    AbortSignal.prototype.dispatchEvent = function(event) {
        if (!event || event.type !== 'abort') return true;
        for (var i = 0; i < this._listeners.length; i++) {
            try { this._listeners[i].call(this, event); } catch (e) {}
        }
        return true;
    };
    globalThis.AbortSignal = AbortSignal;
}

if (typeof globalThis.AbortController === 'undefined') {
    var AbortController = function() { this.signal = new AbortSignal(); };
    AbortController.prototype.abort = function(reason) {
        if (this.signal.aborted) return;
        this.signal.aborted = true;
        this.signal.reason = reason;
        this.signal.dispatchEvent({ type: 'abort' });
    };
    globalThis.AbortController = AbortController;
}

// TextEncoder and TextDecoder polyfill
if (typeof TextEncoder === 'undefined') {
    globalThis.TextEncoder = function() {};
    TextEncoder.prototype.encode = function(str) {
        var hex = __crypto_utf8_to_hex(str);
        var bytes = new Uint8Array(hex.length / 2);
        for (var i = 0; i < hex.length; i += 2) {
            bytes[i / 2] = parseInt(hex.substring(i, i + 2), 16);
        }
        return bytes;
    };
}
if (typeof TextDecoder === 'undefined') {
    globalThis.TextDecoder = function() {};
    TextDecoder.prototype.decode = function(data) {
        var bytes = data;
        if (data instanceof ArrayBuffer) bytes = new Uint8Array(data);
        var hex = '';
        for (var i = 0; i < bytes.length; i++) {
            hex += bytes[i].toString(16).padStart(2, '0');
        }
        return __crypto_hex_to_utf8(hex);
    };
}

// CryptoJS native-backed bridge polyfill
var WordArray = {
    init: function(words, sigBytes) {
        this.words = words || [];
        this.sigBytes = sigBytes != undefined ? sigBytes : this.words.length * 4;
    },
    toString: function(encoder) {
        return (encoder || CryptoJS.enc.Hex).stringify(this);
    },
    concat: function(wordArray) {
        var thisWords = this.words;
        var thatWords = wordArray.words;
        var thisSigBytes = this.sigBytes;
        var thatSigBytes = wordArray.sigBytes;

        this.clamp();

        for (var i = 0; i < thatSigBytes; i++) {
            var thatByte = (thatWords[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
            thisWords[(thisSigBytes + i) >>> 2] |= thatByte << (24 - ((thisSigBytes + i) % 4) * 8);
        }
        this.sigBytes += thatSigBytes;
        return this;
    },
    clamp: function() {
        var words = this.words;
        var sigBytes = this.sigBytes;
        if (sigBytes % 4) {
            words[sigBytes >>> 2] &= 0xffffffff << (32 - (sigBytes % 4) * 8);
        }
        words.length = Math.ceil(sigBytes / 4);
        return this;
    },
    clone: function() {
        return __wordArrayCreate(this.words.slice(0), this.sigBytes);
    }
};

function __wordArrayCreate(words, sigBytes) {
    var wa = Object.create(WordArray);
    wa.init(words, sigBytes);
    return wa;
}

function __isWordArray(value) {
    return value && typeof value === 'object' && Array.isArray(value.words) && typeof value.sigBytes === 'number';
}

function __copyUint8Array(bytes) {
    bytes = __toUint8Array(bytes);
    var copy = new Uint8Array(bytes.length);
    copy.set(bytes);
    return copy;
}

function __toUint8Array(data) {
    if (!data) return new Uint8Array(0);
    if (data instanceof Uint8Array) return data;
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(data)) {
        return new Uint8Array(data.buffer, data.byteOffset || 0, data.byteLength);
    }
    if (Array.isArray(data)) return new Uint8Array(data);
    if (typeof data.length === 'number') return new Uint8Array(Array.prototype.slice.call(data));
    return new Uint8Array(0);
}

function __bytesToArrayBuffer(bytes) {
    return __copyUint8Array(bytes).buffer;
}

function __wordArrayToBytes(wordArray) {
    if (!__isWordArray(wordArray)) return typeof wordArray === 'string' ? new TextEncoder().encode(wordArray) : __toUint8Array(wordArray);
    var bytes = new Uint8Array(wordArray.sigBytes);
    for (var i = 0; i < wordArray.sigBytes; i++) {
        bytes[i] = (wordArray.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
    }
    return bytes;
}

function __bytesToWordArray(bytes) {
    bytes = __toUint8Array(bytes);
    var words = [];
    for (var i = 0; i < bytes.length; i++) {
        words[i >>> 2] |= (bytes[i] & 0xff) << (24 - (i % 4) * 8);
    }
    return __wordArrayCreate(words, bytes.length);
}

function __normalizeWordArrayInput(value) {
    if (__isWordArray(value)) return __wordArrayToBytes(value);
    if (typeof value === 'string') return new TextEncoder().encode(value);
    return __toUint8Array(value);
}

function __bytesToHex(bytes) {
    bytes = __toUint8Array(bytes);
    var out = [];
    for (var i = 0; i < bytes.length; i++) {
        var hex = bytes[i].toString(16);
        out.push(hex.length < 2 ? '0' + hex : hex);
    }
    return out.join('');
}

function __hexToBytes(hex) {
    hex = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
    if (hex.length % 2) hex = '0' + hex;
    var bytes = new Uint8Array(hex.length / 2);
    for (var i = 0; i < hex.length; i += 2) {
        bytes[i / 2] = parseInt(hex.substr(i, 2), 16) & 0xff;
    }
    return bytes;
}

function __concatBytes() {
    var total = 0;
    var parts = [];
    for (var i = 0; i < arguments.length; i++) {
        var part = __toUint8Array(arguments[i]);
        parts.push(part);
        total += part.length;
    }
    var out = new Uint8Array(total);
    var offset = 0;
    for (var j = 0; j < parts.length; j++) {
        out.set(parts[j], offset);
        offset += parts[j].length;
    }
    return out;
}

function __normalizeHashName(hash) {
    var name = hash && hash.name ? hash.name : hash;
    name = String(name || 'SHA-256').toUpperCase().replace(/[^A-Z0-9]/g, '');
    if (name === 'SHA1' || name === 'SHA256' || name === 'SHA384' || name === 'SHA512' || name === 'MD5') return name;
    throw new Error('Unsupported hash algorithm: ' + name);
}

function __normalizeAlgorithmName(algo) {
    var name = algo && algo.name ? algo.name : algo;
    name = String(name || '').toUpperCase();
    if (name.indexOf('AES-GCM') >= 0) return 'AES-GCM';
    if (name.indexOf('AES-CBC') >= 0) return 'AES-CBC';
    if (name.indexOf('AES-ECB') >= 0 || name === 'ECB') return 'AES-ECB';
    if (name.indexOf('PBKDF2') >= 0) return 'PBKDF2';
    if (name.indexOf('HMAC') >= 0) return 'HMAC';
    if (name.indexOf('RSASSA-PKCS1') >= 0) return 'RSASSA-PKCS1-V1_5';
    if (name.indexOf('ECDSA') >= 0) return 'ECDSA';
    return name;
}

function __aesModeName(mode, padding) {
    var normalized = __normalizeAlgorithmName(mode || 'AES-CBC');
    if (padding === 'NoPadding') normalized += '-NoPadding';
    return normalized;
}

function __nativeDigestBytes(hash, dataBytes) {
    if (typeof __crypto_digest_hex_raw === 'undefined') throw new Error('Native digest bridge is unavailable');
    return __hexToBytes(__crypto_digest_hex_raw(__normalizeHashName(hash), __bytesToHex(dataBytes)));
}

function __nativeHmacBytes(hash, keyBytes, dataBytes) {
    if (typeof __crypto_hmac_hex_raw === 'undefined') throw new Error('Native HMAC bridge is unavailable');
    return __hexToBytes(__crypto_hmac_hex_raw(__normalizeHashName(hash), __bytesToHex(keyBytes), __bytesToHex(dataBytes)));
}

var CryptoJS = {
    enc: {
        Hex: {
            stringify: function(wordArray) {
                return __bytesToHex(__wordArrayToBytes(wordArray));
            },
            parse: function(hexStr) {
                return __bytesToWordArray(__hexToBytes(hexStr));
            }
        },
        Utf8: {
            stringify: function(wordArray) {
                return new TextDecoder('utf-8').decode(__wordArrayToBytes(wordArray));
            },
            parse: function(utf8Str) {
                return __bytesToWordArray(new TextEncoder().encode(String(utf8Str)));
            }
        },
        Latin1: {
            stringify: function(wordArray) {
                var bytes = __wordArrayToBytes(wordArray);
                var out = '';
                for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
                return out;
            },
            parse: function(str) {
                str = String(str || '');
                var bytes = new Uint8Array(str.length);
                for (var i = 0; i < str.length; i++) bytes[i] = str.charCodeAt(i) & 0xff;
                return __bytesToWordArray(bytes);
            }
        },
        Base64: {
            stringify: function(wordArray) {
                var bytes = __wordArrayToBytes(wordArray);
                var binaryStr = '';
                for (var j = 0; j < bytes.length; j++) binaryStr += String.fromCharCode(bytes[j]);
                return btoa(binaryStr);
            },
            parse: function(base64Str) {
                var binaryStr = atob(String(base64Str || ''));
                var bytes = new Uint8Array(binaryStr.length);
                for (var i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i) & 0xff;
                return __bytesToWordArray(bytes);
            }
        }
    },
    lib: {
        WordArray: {
            create: function(words, sigBytes) {
                if (words == null) return __wordArrayCreate([], sigBytes || 0);
                if (__isWordArray(words)) return words.clone();
                if (typeof words === 'string') return CryptoJS.enc.Utf8.parse(words);
                if (words instanceof ArrayBuffer || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(words))) {
                    var bytes = __toUint8Array(words);
                    return __bytesToWordArray(sigBytes != undefined ? bytes.subarray(0, sigBytes) : bytes);
                }
                return __wordArrayCreate(words, sigBytes);
            }
        }
    },
    MD5: function(m) { return __bytesToWordArray(__nativeDigestBytes('MD5', __normalizeWordArrayInput(m))); },
    SHA1: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA1', __normalizeWordArrayInput(m))); },
    SHA256: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA256', __normalizeWordArrayInput(m))); },
    SHA384: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA384', __normalizeWordArrayInput(m))); },
    SHA512: function(m) { return __bytesToWordArray(__nativeDigestBytes('SHA512', __normalizeWordArrayInput(m))); },
    HmacMD5: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('MD5', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
    HmacSHA1: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA1', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
    HmacSHA256: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA256', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
    HmacSHA384: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA384', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
    HmacSHA512: function(m, k) { return __bytesToWordArray(__nativeHmacBytes('SHA512', __normalizeWordArrayInput(k), __normalizeWordArrayInput(m))); },
    PBKDF2: function(pass, salt, options) {
        options = options || {};
        var pBytes = __normalizeWordArrayInput(pass);
        var sBytes = __normalizeWordArrayInput(salt);
        var iter = options.iterations || 1000;
        var kSize = options.keySize || 8;
        var algo = options.hasher || 'SHA1';
        if (typeof __crypto_pbkdf2_hex === 'undefined') throw new Error('Native PBKDF2 bridge is unavailable');
        return __bytesToWordArray(__hexToBytes(__crypto_pbkdf2_hex(__bytesToHex(pBytes), __bytesToHex(sBytes), iter, kSize * 32, algo)));
    },
    AES: {
        encrypt: function(message, key, options) {
            options = options || {};
            var data = __normalizeWordArrayInput(message);
            var kBytes = __wordArrayToBytes(key);
            var ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
            var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
            if (typeof __crypto_aes_encrypt_hex === 'undefined') throw new Error('Native AES bridge is unavailable');
            var resHex = __crypto_aes_encrypt_hex(mode, __bytesToHex(kBytes), __bytesToHex(ivBytes), __bytesToHex(data));
            var ciphertext = __hexToBytes(resHex);
            return {
                ciphertext: __bytesToWordArray(ciphertext),
                toString: function() {
                    return CryptoJS.enc.Base64.stringify(this.ciphertext);
                }
            };
        },
        decrypt: function(cipher, key, options) {
            options = options || {};
            var data = cipher.ciphertext ? __wordArrayToBytes(cipher.ciphertext) : __toUint8Array(cipher);
            var kBytes = __wordArrayToBytes(key);
            var ivBytes = options.iv ? __wordArrayToBytes(options.iv) : new Uint8Array(0);
            var mode = __aesModeName(options.mode || 'AES-CBC', options.padding);
            if (typeof __crypto_aes_decrypt_hex === 'undefined') throw new Error('Native AES bridge is unavailable');
            var resHex = __crypto_aes_decrypt_hex(mode, __bytesToHex(kBytes), __bytesToHex(ivBytes), __bytesToHex(data));
            return __bytesToWordArray(__hexToBytes(resHex));
        }
    }
};
globalThis.CryptoJS = CryptoJS;

// WebCrypto SubtleCrypto native-backed polyfill
globalThis.crypto.subtle = {
    digest: async function(algo, data) {
        return __bytesToArrayBuffer(__nativeDigestBytes(algo, __toUint8Array(data)));
    }
};

// Cheerio Jsoup-backed polyfill
var cheerio = {
    load: function(html) {
        var docId = __cheerio_load(html);
        var $ = function(selector, context) {
            if (selector && selector._elementIds) return selector;
            if (context && context._elementIds && context._elementIds.length > 0) {
                var allIds = [];
                for (var i = 0; i < context._elementIds.length; i++) {
                    var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                    var childIds = JSON.parse(childIdsJson);
                    allIds = allIds.concat(childIds);
                }
                return createCheerioWrapperFromIds(docId, allIds);
            }
            return createCheerioWrapper(docId, selector);
        };
        $.html = function(el) {
            if (el && el._elementIds && el._elementIds.length > 0) {
                return __cheerio_html(docId, el._elementIds[0]);
            }
            return __cheerio_html(docId, '');
        };
        return $;
    }
};

function createCheerioWrapper(docId, selector) {
    var elementIds;
    if (typeof selector === 'string') {
        var idsJson = __cheerio_select(docId, selector);
        elementIds = JSON.parse(idsJson);
    } else {
        elementIds = [];
    }
    return createCheerioWrapperFromIds(docId, elementIds);
}

function createCheerioWrapperFromIds(docId, ids) {
    var wrapper = {
        _docId: docId,
        _elementIds: ids,
        length: ids.length,
        each: function(callback) {
            for (var i = 0; i < ids.length; i++) {
                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                callback.call(elWrapper, i, elWrapper);
            }
            return wrapper;
        },
        find: function(sel) {
            var allIds = [];
            for (var i = 0; i < ids.length; i++) {
                var childIdsJson = __cheerio_find(docId, ids[i], sel);
                var childIds = JSON.parse(childIdsJson);
                allIds = allIds.concat(childIds);
            }
            return createCheerioWrapperFromIds(docId, allIds);
        },
        text: function() {
            if (ids.length === 0) return '';
            return __cheerio_text(docId, ids.join(','));
        },
        html: function() {
            if (ids.length === 0) return '';
            return __cheerio_inner_html(docId, ids[0]);
        },
        attr: function(name) {
            if (ids.length === 0) return undefined;
            var val = __cheerio_attr(docId, ids[0], name);
            return val === '__UNDEFINED__' ? undefined : val;
        },
        first: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []); },
        last: function() { return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []); },
        next: function() {
            var nextIds = [];
            for (var i = 0; i < ids.length; i++) {
                var nextId = __cheerio_next(docId, ids[i]);
                if (nextId && nextId !== '__NONE__') nextIds.push(nextId);
            }
            return createCheerioWrapperFromIds(docId, nextIds);
        },
        prev: function() {
            var prevIds = [];
            for (var i = 0; i < ids.length; i++) {
                var prevId = __cheerio_prev(docId, ids[i]);
                if (prevId && prevId !== '__NONE__') prevIds.push(prevId);
            }
            return createCheerioWrapperFromIds(docId, prevIds);
        },
        eq: function(index) {
            if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
            return createCheerioWrapperFromIds(docId, []);
        },
        get: function(index) {
            if (typeof index === 'number') {
                if (index >= 0 && index < ids.length) return createCheerioWrapperFromIds(docId, [ids[index]]);
                return undefined;
            }
            return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); });
        },
        map: function(callback) {
            var results = [];
            for (var i = 0; i < ids.length; i++) {
                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                var result = callback.call(elWrapper, i, elWrapper);
                if (result !== undefined && result !== null) results.push(result);
            }
            return {
                length: results.length,
                get: function(index) { return typeof index === 'number' ? results[index] : results; },
                toArray: function() { return results; }
            };
        },
        filter: function(selectorOrCallback) {
            if (typeof selectorOrCallback === 'function') {
                var filteredIds = [];
                for (var i = 0; i < ids.length; i++) {
                    var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                    var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                    if (result) filteredIds.push(ids[i]);
                }
                return createCheerioWrapperFromIds(docId, filteredIds);
            }
            return wrapper;
        },
        children: function(sel) { return this.find(sel || '*'); },
        parent: function() { return createCheerioWrapperFromIds(docId, []); },
        toArray: function() { return ids.map(function(id) { return createCheerioWrapperFromIds(docId, [id]); }); }
    };
    return wrapper;
}

// Require polyfill map
var require = function(moduleName) {
    if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
        return cheerio;
    }
    if (moduleName === 'crypto-js') {
        return CryptoJS;
    }
    throw new Error("Module '" + moduleName + "' is not available");
};
globalThis.require = require;

// Array Polyfills
if (!Array.prototype.flat) {
    Array.prototype.flat = function(depth) {
        depth = depth === undefined ? 1 : Math.floor(depth);
        if (depth < 1) return Array.prototype.slice.call(this);
        return (function flatten(arr, d) {
            return d > 0
                ? arr.reduce(function(acc, val) { return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val); }, [])
                : arr.slice();
        })(this, depth);
    };
}

if (!Array.prototype.flatMap) {
    Array.prototype.flatMap = function(callback, thisArg) { return this.map(callback, thisArg).flat(); };
}

// Object Polyfills
if (!Object.entries) {
    Object.entries = function(obj) {
        var result = [];
        for (var key in obj) {
            if (obj.hasOwnProperty(key)) result.push([key, obj[key]]);
        }
        return result;
    };
}

if (!Object.fromEntries) {
    Object.fromEntries = function(entries) {
        var result = {};
        for (var i = 0; i < entries.length; i++) {
            result[entries[i][0]] = entries[i][1];
        }
        return result;
    };
}

// String Polyfills
if (!String.prototype.replaceAll) {
    String.prototype.replaceAll = function(search, replace) {
        if (search instanceof RegExp) {
            if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
            return this.replace(search, replace);
        }
        return this.split(search).join(replace);
    };
}

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
