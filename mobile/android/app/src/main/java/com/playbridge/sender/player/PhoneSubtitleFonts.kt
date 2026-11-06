package com.playbridge.sender.player

import android.content.Context
import com.playbridge.shared.player.SubtitleFontLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The phone player's custom subtitle fonts, shared by Settings and the player. */
internal object PhoneSubtitleFonts {
    @Volatile private var library: SubtitleFontLibrary? = null

    private val mutableFamilies = MutableStateFlow<List<String>>(emptyList())
    /** Installed families; current after [load]. */
    val families: StateFlow<List<String>> = mutableFamilies.asStateFlow()

    private val mutableRevision = MutableStateFlow(0)
    /** Changes whenever a font file is added or removed, so typefaces are loaded again. */
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    private fun library(context: Context): SubtitleFontLibrary = library ?: synchronized(this) {
        library ?: SubtitleFontLibrary(context.applicationContext.filesDir).also { library = it }
    }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        mutableFamilies.value = library(context).families()
    }

    suspend fun add(context: Context, bytes: ByteArray): SubtitleFontLibrary.AddResult = withContext(Dispatchers.IO) {
        library(context).add(bytes).also { changed(context) }
    }

    suspend fun remove(context: Context, family: String) = withContext(Dispatchers.IO) {
        library(context).remove(family)
        changed(context)
    }

    /** Links [family] (or none) for mpv and returns its typeface for ExoPlayer and the preview. */
    suspend fun select(context: Context, family: String?): android.graphics.Typeface? = withContext(Dispatchers.IO) {
        val library = library(context)
        library.select(family)
        family?.let(library::file)?.let { runCatching { android.graphics.Typeface.createFromFile(it) }.getOrNull() }
    }

    private fun changed(context: Context) {
        mutableFamilies.value = library(context).families()
        mutableRevision.value++
    }
}
