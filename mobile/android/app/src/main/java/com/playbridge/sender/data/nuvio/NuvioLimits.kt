package com.playbridge.sender.data.nuvio

internal object NuvioLimits {
    const val OPERATION_BUDGET_MS = 60_000L
    const val EVALUATION_TIMEOUT_MS = 15_000L
    const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024
    const val MAX_STACK_BYTES = 256L * 1024
    const val MAX_SCRAPER_IDS = 32
    const val MAX_CONCURRENT_REQUESTS = 4
    const val MAX_CONCURRENT_ENGINES = 2
    const val MAX_REQUESTS_PER_EVAL = 24
    const val MAX_INSTALL_REQUESTS = 256
    const val MAX_REDIRECTS = 3
    const val MAX_CODE_BYTES = 1_048_576
    const val MAX_MANIFEST_BYTES = 262_144
    const val MAX_RESPONSE_BYTES = 2_097_152
    const val MAX_REQUEST_BODY_BYTES = 262_144
    const val MAX_REQUEST_JSON_BYTES = 300_000
    const val MAX_SETTINGS_JSON_BYTES = 16_384
    const val MAX_STREAMS = 50
    const val MAX_WARNINGS = 64
    const val MAX_BLOCKED_HOSTS = 32
    const val MAX_APPROVED_HOSTS = 64
    const val DNS_TIMEOUT_MS = 2_000L
    const val MAX_HEADER_VALUE_BYTES = 1_024
}
