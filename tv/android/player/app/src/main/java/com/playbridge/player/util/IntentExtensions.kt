package com.playbridge.player.util

import android.content.Intent
import android.os.Build

/**
 * Reads a `Map<String, String>` previously stored via `putExtra(name, HashMap)`.
 * Uses the typed `getSerializableExtra(name, Class)` on API 33+ and falls back to
 * the deprecated overload below that. Centralized here so the call sites stay
 * deprecation-warning-free.
 */
@Suppress("DEPRECATION", "UNCHECKED_CAST")
fun Intent.getStringMapExtra(name: String): Map<String, String>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getSerializableExtra(name, HashMap::class.java) as? Map<String, String>
    } else {
        getSerializableExtra(name) as? Map<String, String>
    }
}
