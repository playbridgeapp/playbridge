package com.playbridge.sender.cast

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Local, bundled-model identification of subtitle dialogue. Uncertain samples remain unlabeled. */
object SubtitleLanguageDetector {
    suspend fun detect(text: String): String? {
        val sample = text.take(200)
        if (sample.count { it.isLetter() } < 60) return null
        val candidates = try {
            suspendCancellableCoroutine<List<IdentifiedLanguage>?> { continuation ->
                val identifier = LanguageIdentification.getClient()
                identifier.identifyPossibleLanguages(sample).addOnCompleteListener { task ->
                    identifier.close()
                    if (continuation.isActive) {
                        continuation.resume(if (task.isSuccessful) task.result else null)
                    }
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null
        val code = likelySubtitleLanguageCode(candidates.map { it.languageTag to it.confidence })
            ?: return null
        val locale = Locale.forLanguageTag(code)
        return locale.getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

internal fun likelySubtitleLanguageCode(candidates: List<Pair<String, Float>>): String? {
    val ranked = candidates.sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    if (best.first == "und" || best.second < 0.75f ||
        best.second - (ranked.getOrNull(1)?.second ?: 0f) < 0.15f
    ) {
        return null
    }
    return best.first
}
