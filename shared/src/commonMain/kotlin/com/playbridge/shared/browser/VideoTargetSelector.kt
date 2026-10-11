package com.playbridge.shared.browser

/**
 * Selects the frame controlled by browser video commands. Scores are visible CSS pixel areas,
 * comparable across frames. Insertion order defines manual cycling order.
 *
 * Size wins first; playback breaks ties at >=60% of the largest area. An automatic incumbent
 * survives until a challenger is >=1.25 times larger or plays while the incumbent is paused.
 * A manual pin lasts until removal or reset on top-level navigation.
 */
class VideoTargetSelector<K : Any> {
    private data class FrameVideo(val score: Double, val playing: Boolean)
    private val frames = LinkedHashMap<K, FrameVideo>()
    private var sticky: K? = null
    private var override: K? = null

    val isActive: Boolean get() = frames.isNotEmpty()

    fun update(key: K, score: Double, playing: Boolean) {
        frames[key] = FrameVideo(score, playing)
    }

    fun remove(key: K) {
        frames.remove(key)
        if (key == override) override = null
        if (key == sticky) sticky = null
    }

    fun reset() {
        frames.clear()
        sticky = null
        override = null
    }

    fun cycle() {
        if (frames.isEmpty()) { override = null; return }
        val keys = frames.keys.toList()
        val current = override ?: current()
        override = keys[(keys.indexOf(current) + 1).mod(keys.size)]
    }

    fun current(): K? {
        if (frames.isEmpty()) { sticky = null; override = null; return null }
        override?.let { if (frames.containsKey(it)) return it else override = null }
        val incumbent = sticky?.takeIf { frames.containsKey(it) }
        val largest = frames.entries.maxByOrNull { it.value.score } ?: return incumbent
        val best = if (!largest.value.playing) {
            frames.entries
                .filter { it.value.playing && it.value.score >= largest.value.score * 0.6 }
                .maxByOrNull { it.value.score } ?: largest
        } else largest
        if (incumbent != null && best.key != incumbent) {
            val inc = frames[incumbent]
            val clearlyBigger = inc != null && best.value.score >= inc.score * 1.25
            val newlyPlaying = inc != null && best.value.playing && !inc.playing
            if (inc != null && !clearlyBigger && !newlyPlaying) return incumbent
        }
        sticky = best.key
        return best.key
    }
}
