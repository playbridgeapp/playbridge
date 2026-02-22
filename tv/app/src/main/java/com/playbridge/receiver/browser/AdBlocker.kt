package com.playbridge.receiver.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class AdBlocker(private val context: Context) {
    
    companion object {
        private const val TAG = "AdBlocker"
        
        // EasyList URLs
        private const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        private const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        private const val ADBLOCK_WARNING_URL = "https://easylist-downloads.adblockplus.org/antiadblockfilters.txt"
        
        // Cache file name
        private const val CACHE_FILE = "easylist_cache.txt"
        private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        // Resource type mapping
        const val TYPE_SCRIPT = 1
        const val TYPE_IMAGE = 2
        const val TYPE_STYLESHEET = 4
        const val TYPE_SUBDOCUMENT = 8
        const val TYPE_XMLHTTPREQUEST = 16
        const val TYPE_OTHER = 32
        const val TYPE_POPUP = 64
        const val TYPE_DOCUMENT = 128
        const val TYPE_ALL = 255
        
        // Singleton instance
        @Volatile
        private var instance: AdBlocker? = null
        
        fun getInstance(context: Context): AdBlocker {
            return instance ?: synchronized(this) {
                instance ?: AdBlocker(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Preload the AdBlocker in the background. Call from MainActivity.onCreate()
         * so filters are ready before the browser is opened.
         */
        fun preload(context: Context) {
            val blocker = getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                blocker.loadFilterLists()
                Log.d(TAG, blocker.getStats())
            }
        }
    }
    
    // Domain-based blocking (thread-safe for concurrent access)
    private val blockedDomains: MutableSet<String> = ConcurrentHashMap.newKeySet()
    
    // URL pattern rules (thread-safe for concurrent access)
    private val urlPatterns = CopyOnWriteArrayList<UrlPattern>()
    
    // Exception rules (thread-safe for concurrent access)
    private val exceptionDomains: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val exceptionPatterns = CopyOnWriteArrayList<UrlPattern>()
    
    // Cosmetic filters (element hiding CSS selectors)
    private val cosmeticFilters = CopyOnWriteArrayList<String>()
    
    // Stats
    private var totalRulesLoaded = 0
    @Volatile private var isFullyInitialized = false
    
    init {
        // Load built-in rules immediately so blocking works right away
        loadBuiltInRules()
        Log.d(TAG, "AdBlocker initialized with ${blockedDomains.size} built-in domains")
    }
    
    data class UrlPattern(
        val pattern: Regex,
        val originalRule: String,
        val resourceTypes: Int = TYPE_ALL,
        val thirdPartyOnly: Boolean = false,
        val allowedDomains: Set<String>? = null,  // Domains where this rule applies (null = all domains)
        val excludedDomains: Set<String>? = null  // Domains where this rule does NOT apply
    )
    
    /**
     * Load filter lists - tries cache first, then downloads from internet
     */
    suspend fun loadFilterLists() = withContext(Dispatchers.IO) {
        if (isFullyInitialized) return@withContext
        
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Loading filter lists...")
        
        // Try to load from cache first
        val cacheFile = File(context.cacheDir, CACHE_FILE)
        var loaded = false
        
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_MAX_AGE_MS) {
            Log.d(TAG, "Loading from cache...")
            try {
                cacheFile.bufferedReader().useLines { lines ->
                    lines.forEach { parseLine(it.trim()) }
                }
                loaded = true
                Log.d(TAG, "Loaded from cache in ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cache: ${e.message}")
            }
        }
        
        // If cache miss or expired, download fresh lists
        if (!loaded) {
            Log.d(TAG, "Downloading EasyList...")
            try {
                downloadAndParse(EASYLIST_URL, cacheFile)
                Log.d(TAG, "EasyList downloaded and parsed")
                
                // Also download EasyPrivacy for tracking protection
                Log.d(TAG, "Downloading EasyPrivacy...")
                downloadAndParse(EASYPRIVACY_URL, null)
                Log.d(TAG, "EasyPrivacy downloaded and parsed")
                
                // Download Adblock Warning Removal List (bypasses anti-adblock on YouTube etc.)
                Log.d(TAG, "Downloading Adblock Warning Removal List...")
                downloadAndParse(ADBLOCK_WARNING_URL, null)
                Log.d(TAG, "Adblock Warning Removal List downloaded and parsed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download filter lists: ${e.message}")
                // Continue with built-in rules only
            }
        }
        
        Log.d(TAG, "Loaded $totalRulesLoaded rules in ${System.currentTimeMillis() - startTime}ms")
        Log.d(TAG, "Blocked domains: ${blockedDomains.size}, URL patterns: ${urlPatterns.size}")
        Log.d(TAG, "Exception domains: ${exceptionDomains.size}, Exception patterns: ${exceptionPatterns.size}")
        
        isFullyInitialized = true
    }
    
    private fun downloadAndParse(urlString: String, cacheFile: File?) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.setRequestProperty("User-Agent", "PlayBridge AdBlocker/1.0")
        
        try {
            connection.connect()
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val cacheWriter = cacheFile?.bufferedWriter()
                
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmedLine = line!!.trim()
                        parseLine(trimmedLine)
                        cacheWriter?.appendLine(trimmedLine)
                    }
                }
                
                cacheWriter?.close()
            } else {
                throw Exception("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun parseLine(line: String) {
        // Skip comments and empty lines
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) {
            return
        }
        
        // Skip exception cosmetic rules and procedural cosmetic rules
        if (line.contains("#@#") || line.contains("#?#")) {
            return
        }
        
        // Parse element hiding rules (cosmetic filters)
        val elementHideIndex = line.indexOf("##")
        if (elementHideIndex >= 0) {
            // Only collect generic cosmetic filters (no domain restriction) to keep it manageable
            if (elementHideIndex == 0 && cosmeticFilters.size < 5000) {
                val selector = line.substring(2).trim()
                if (selector.isNotEmpty() && !selector.contains(":")) {
                    // Only simple CSS selectors (skip procedural/extended ones with ':')
                    cosmeticFilters.add(selector)
                }
            }
            totalRulesLoaded++
            return
        }
        
        totalRulesLoaded++
        
        // Check if it's an exception rule
        val isException = line.startsWith("@@")
        val rule = if (isException) line.substring(2) else line
        
        // Parse options if present
        val optionsIndex = rule.lastIndexOf('$')
        val (pattern, options) = if (optionsIndex > 0 && !rule.substring(optionsIndex).contains('/')) {
            Pair(rule.substring(0, optionsIndex), rule.substring(optionsIndex + 1))
        } else {
            Pair(rule, "")
        }
        
        // Parse resource types, third-party, and domain restrictions
        val parseResult = parseOptions(options)
        val resourceTypes = parseResult.resourceTypes
        val thirdPartyOnly = parseResult.thirdPartyOnly
        val allowedDomains = parseResult.allowedDomains
        val excludedDomains = parseResult.excludedDomains
        
        
        // Handle domain-anchored rules: ||domain
        if (pattern.startsWith("||")) {
            // Check if this is a FULL domain block or a specific path
            // Full domain blocks end with ^ or are just the domain
            // Rules with / (like ||example.com/ad.js) are NOT full domain blocks
            
            val cleanPattern = pattern.substring(2)
            val isFullDomain = cleanPattern.endsWith("^") || 
                              (!cleanPattern.contains("/") && !cleanPattern.contains("*") && !cleanPattern.contains("?"))
            
            if (isFullDomain) {
                val domain = cleanPattern.replace("^", "")
                
                if (domain.isNotEmpty()) {
                    if (isException) {
                        exceptionDomains.add(domain.lowercase())
                    } else {
                        blockedDomains.add(domain.lowercase())
                    }
                    return
                }
            }
            // If not a full domain block, fall through to pattern matching
        }
        
        // Skip overly broad patterns that would match too many URLs
        // These patterns break legitimate content
        if (isTooGenericPattern(pattern)) {
            return
        }
        
        // Handle URL pattern rules 
        // Use higher limits for exception patterns since they're critical for not breaking sites
        val canAddPattern = if (isException) {
            exceptionPatterns.size < 10000
        } else {
            urlPatterns.size < 30000
        }
        
        if (canAddPattern) {
            try {
                val regex = patternToRegex(pattern)
                if (regex != null) {
                    val urlPattern = UrlPattern(
                        pattern = regex,
                        originalRule = pattern,
                        resourceTypes = resourceTypes,
                        thirdPartyOnly = thirdPartyOnly,
                        allowedDomains = allowedDomains,
                        excludedDomains = excludedDomains
                    )
                    if (isException) {
                        exceptionPatterns.add(urlPattern)
                    } else {
                        urlPatterns.add(urlPattern)
                    }
                }
            } catch (e: Exception) {
                // Skip malformed patterns
            }
        }
    }
    
    data class ParseResult(
        val resourceTypes: Int,
        val thirdPartyOnly: Boolean,
        val allowedDomains: Set<String>?,
        val excludedDomains: Set<String>?
    )
    
    private fun parseOptions(options: String): ParseResult {
        if (options.isEmpty()) return ParseResult(TYPE_ALL, false, null, null)
        
        var resourceTypes = 0
        var excludedTypes = 0
        var thirdPartyOnly = false
        var allowedDomains: MutableSet<String>? = null
        var excludedDomains: MutableSet<String>? = null
        
        options.split(",").forEach { opt ->
            val option = opt.trim().lowercase()
            when {
                // Positive type options
                option == "script" -> resourceTypes = resourceTypes or TYPE_SCRIPT
                option == "image" -> resourceTypes = resourceTypes or TYPE_IMAGE
                option == "stylesheet" -> resourceTypes = resourceTypes or TYPE_STYLESHEET
                option == "subdocument" -> resourceTypes = resourceTypes or TYPE_SUBDOCUMENT
                option == "xmlhttprequest" -> resourceTypes = resourceTypes or TYPE_XMLHTTPREQUEST
                option == "other" -> resourceTypes = resourceTypes or TYPE_OTHER
                
                // Negated type options - exclude these types
                option == "~script" -> excludedTypes = excludedTypes or TYPE_SCRIPT
                option == "~image" -> excludedTypes = excludedTypes or TYPE_IMAGE
                option == "~stylesheet" -> excludedTypes = excludedTypes or TYPE_STYLESHEET
                option == "~subdocument" -> excludedTypes = excludedTypes or TYPE_SUBDOCUMENT
                option == "~xmlhttprequest" -> excludedTypes = excludedTypes or TYPE_XMLHTTPREQUEST
                option == "~other" -> excludedTypes = excludedTypes or TYPE_OTHER
                
                // Popup and document types
                option == "popup" -> resourceTypes = resourceTypes or TYPE_POPUP
                option == "document" || option == "doc" -> resourceTypes = resourceTypes or TYPE_DOCUMENT
                option == "all" -> resourceTypes = TYPE_ALL
                
                option == "third-party" || option == "3p" -> thirdPartyOnly = true
                
                // Parse domain= option
                option.startsWith("domain=") -> {
                    val domainsList = option.removePrefix("domain=").split("|")
                    domainsList.forEach { domain ->
                        if (domain.startsWith("~")) {
                            // Excluded domain (rule does NOT apply here)
                            if (excludedDomains == null) excludedDomains = mutableSetOf()
                            excludedDomains!!.add(domain.removePrefix("~"))
                        } else {
                            // Allowed domain (rule ONLY applies here)
                            if (allowedDomains == null) allowedDomains = mutableSetOf()
                            allowedDomains!!.add(domain)
                        }
                    }
                }
            }
        }
        
        // Calculate final resource types
        val finalTypes = if (resourceTypes == 0) {
            // No types specified, use all minus excluded
            TYPE_ALL and excludedTypes.inv()
        } else {
            // Types specified, use those minus excluded
            resourceTypes and excludedTypes.inv()
        }
        
        return ParseResult(
            resourceTypes = if (finalTypes == 0) TYPE_ALL else finalTypes,
            thirdPartyOnly = thirdPartyOnly,
            allowedDomains = allowedDomains,
            excludedDomains = excludedDomains
        )
    }
    
    private fun patternToRegex(pattern: String): Regex? {
        if (pattern.isEmpty()) return null
        
        val regexPattern = StringBuilder()
        
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> regexPattern.append(".*")
                '^' -> regexPattern.append("(?:[^\\w\\d_.%-]|$)")
                '|' -> {
                    if (i == 0) {
                        if (pattern.startsWith("||")) {
                            regexPattern.append("^(?:https?://)?(?:[\\w-]+\\.)*")
                            i++ // Skip the second |
                        } else {
                            regexPattern.append("^")
                        }
                    } else if (i == pattern.length - 1) {
                        regexPattern.append("$")
                    } else {
                        regexPattern.append("\\|")
                    }
                }
                '.' -> regexPattern.append("\\.")
                '?' -> regexPattern.append("\\?")
                '+' -> regexPattern.append("\\+")
                '[' -> regexPattern.append("\\[")
                ']' -> regexPattern.append("\\]")
                '(' -> regexPattern.append("\\(")
                ')' -> regexPattern.append("\\)")
                '{' -> regexPattern.append("\\{")
                '}' -> regexPattern.append("\\}")
                '\\' -> regexPattern.append("\\\\")
                else -> regexPattern.append(c)
            }
            i++
        }
        
        return try {
            Regex(regexPattern.toString(), RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if a pattern is too generic and would match too many legitimate URLs.
     * These patterns cause false positives and break normal content.
     */
    private fun isTooGenericPattern(pattern: String): Boolean {
        // Patterns that are just protocol wildcards
        if (pattern.matches(Regex("^\\|?https?\\*?://\\*.*$"))) {
            return true
        }
        
        // Pattern is too short to be meaningful (less than 4 non-wildcard chars)
        val nonWildcardChars = pattern.replace("*", "").replace("^", "").replace("|", "")
        if (nonWildcardChars.length < 5) {
            return true
        }
        
        // Patterns that are mostly wildcards
        val wildcardCount = pattern.count { it == '*' }
        val totalLength = pattern.length
        if (wildcardCount > 0 && (wildcardCount.toFloat() / totalLength) > 0.3) {
            // More than 30% wildcards - too generic
            return true
        }
        
        // Specific overly broad patterns from EasyList that cause issues
        val broadPatterns = listOf(
            "|http*://*?",
            "http*://*?",
            "*://*?",
            "|http://*",
            "|https://*",
            "http://*",
            "https://*"
        )
        if (pattern in broadPatterns) {
            return true
        }
        
        return false
    }
    
    /**
     * Load built-in fallback rules
     */
    private fun loadBuiltInRules() {
        // Common ad domains - these work immediately before EasyList downloads
        val domains = listOf(
            // Google Ads
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com",
            "pagead2.googlesyndication.com", "adservice.google.com",
            "partner.googleadservices.com", "tpc.googlesyndication.com",
            
            // Facebook
            "facebook.net", "connect.facebook.net",
            
            // Major ad networks
            "advertising.com", "adnxs.com", "adsrvr.org", "adcolony.com",
            "admob.com", "mopub.com", "moatads.com", "outbrain.com",
            "taboola.com", "criteo.com", "scorecardresearch.com",
            "quantserve.com", "pubmatic.com", "rubiconproject.com",
            "openx.net", "casalemedia.com", "amazon-adsystem.com",
            "serving-sys.com", "2mdn.net", "adsymptotic.com",
            
            // Popup/Popunder networks
            "popads.net", "popcash.net", "propellerads.com", "exoclick.com",
            "juicyads.com", "trafficjunky.com", "clickadu.com",
            "revcontent.com", "mgid.com", "zedo.com", "bidvertiser.com",
            "adcash.com", "popunderjs.com",
            
            // Link shorteners with ads
            "adf.ly", "sh.st", "bc.vc", "ouo.io", "shorte.st",
            "linkbucks.com", "adfoc.us",
            
            // Video ads
            "innovid.com", "spotxchange.com", "springserve.com",
            
            // Tracking
            "hotjar.com", "mixpanel.com", "segment.io", "amplitude.com",
            "fullstory.com", "mouseflow.com", "crazyegg.com",
            
            // Social tracking
            "addthis.com", "sharethis.com"
        )
        
        domains.forEach { blockedDomains.add(it.lowercase()) }
        totalRulesLoaded = domains.size
    }
    
    /**
     * Check if a URL should be blocked
     */
    fun shouldBlock(url: String, pageUrl: String? = null, resourceType: Int = TYPE_ALL): Boolean {
        val urlLower = url.lowercase()
        val host = extractHost(urlLower) ?: return false
        val pageHost = if (pageUrl != null) extractHost(pageUrl.lowercase()) else null
        
        // Check exceptions first (EasyList already has exception rules for video content)
        if (isException(urlLower, host, pageHost)) {
            return false
        }
        
        // Check domain blocking
        if (isDomainBlocked(host)) {
            Log.d(TAG, "Blocked by domain: $host")
            return true
        }
        
        // Check URL patterns
        val isThirdParty = pageHost != null && pageHost != host
        
        for (pattern in urlPatterns) {
            // Check resource type
            if ((pattern.resourceTypes and resourceType) == 0) continue
            
            // Check third-party
            if (pattern.thirdPartyOnly && !isThirdParty) continue
            
            // Check domain restrictions
            if (pageHost != null && !isDomainAllowed(pattern, pageHost)) continue
            
            // Check pattern
            if (pattern.pattern.containsMatchIn(urlLower)) {
                Log.d(TAG, "Blocked by pattern: ${pattern.originalRule}")
                return true
            }
        }
        
        return false
    }
    
    /**
     * Check if a top-level navigation should be blocked (popup/redirect)
     * This checks $popup and $document rules specifically
     */
    fun shouldBlockNavigation(url: String, pageUrl: String?): Boolean {
        return shouldBlock(url, pageUrl, TYPE_POPUP or TYPE_DOCUMENT or TYPE_SUBDOCUMENT)
    }
    
    /**
     * Get CSS to inject for element hiding (cosmetic filtering)
     */
    fun getCosmeticFilterCss(): String {
        if (cosmeticFilters.isEmpty()) return ""
        
        // Build a CSS stylesheet that hides matched elements
        val sb = StringBuilder()
        // Process in chunks to avoid overly long CSS strings
        val filters = cosmeticFilters.take(2000) // Limit to avoid performance issues
        filters.forEachIndexed { index, selector ->
            if (index > 0) sb.append(",")
            sb.append(selector)
        }
        sb.append("{display:none!important}")
        return sb.toString()
    }
    
    private fun isException(url: String, host: String, pageHost: String?): Boolean {
        // Check exception domains
        if (exceptionDomains.any { host.endsWith(it) }) {
            return true
        }
        
        // Check exception patterns
        for (pattern in exceptionPatterns) {
            // Check domain restrictions
            if (pageHost != null && !isDomainAllowed(pattern, pageHost)) continue
            
            if (pattern.pattern.containsMatchIn(url)) {
                return true
            }
        }
        
        return false
    }
    
    private fun isDomainAllowed(pattern: UrlPattern, pageHost: String): Boolean {
        // Check excluded domains (if listed, rule does NOT apply)
        if (pattern.excludedDomains != null) {
            for (excluded in pattern.excludedDomains) {
                if (pageHost == excluded || pageHost.endsWith(".$excluded")) {
                    return false
                }
            }
        }
        
        // Check allowed domains (if listed, rule ONLY applies on these domains)
        if (pattern.allowedDomains != null) {
            var matched = false
            for (allowed in pattern.allowedDomains) {
                if (pageHost == allowed || pageHost.endsWith(".$allowed")) {
                    matched = true
                    break
                }
            }
            if (!matched) return false
        }
        
        return true
    }
    
    private fun isDomainBlocked(host: String): Boolean {
        // Check exact match
        if (blockedDomains.contains(host)) return true
        
        // Check parent domains
        var domain = host
        while (domain.contains('.')) {
            val parentDomain = domain.substringAfter('.')
            if (blockedDomains.contains(parentDomain)) return true
            domain = parentDomain
        }
        
        return false
    }
    
    private fun extractHost(url: String): String? {
        return try {
            val withoutProtocol = url.removePrefix("http://").removePrefix("https://")
            withoutProtocol.substringBefore('/').substringBefore(':').substringBefore('?')
        } catch (e: Exception) {
            null
        }
    }
    
    fun getStats(): String {
        return "AdBlocker: ${blockedDomains.size} domains, ${urlPatterns.size} patterns, " +
               "${cosmeticFilters.size} cosmetic filters, " +
               "${exceptionDomains.size} exception domains, ${exceptionPatterns.size} exception patterns" +
               if (isFullyInitialized) " (EasyList loaded)" else " (built-in only)"
    }
}
