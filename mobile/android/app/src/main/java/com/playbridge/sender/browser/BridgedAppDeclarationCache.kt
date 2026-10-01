package com.playbridge.sender.browser

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Origin-wide opt-out, independent of installation or the selected browser tab. */
internal class BridgedAppDeclarationCache(
    private val scope: CoroutineScope,
    private val probe: suspend (String) -> Boolean,
    private val onChanged: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val lifetimeMs: Long = 5 * 60 * 1000L,
) {
    private data class Entry(val declared: Boolean, val expires: Long)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val checking = ConcurrentHashMap.newKeySet<String>()

    fun isDeclared(origin: String?): Boolean = origin != null && entries[origin]?.declared == true

    fun declaredOrigins(): Set<String> = entries.filterValues { it.declared }.keys

    fun refresh(origin: String): Job? {
        if ((entries[origin]?.expires ?: Long.MIN_VALUE) > now() || !checking.add(origin)) return null
        return scope.launch {
            try {
                val declared = try {
                    probe(origin)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                val previous = entries.put(origin, Entry(declared, now() + lifetimeMs))
                if ((previous?.declared == true) != declared) onChanged()
            } finally {
                checking.remove(origin)
            }
        }
    }
}
