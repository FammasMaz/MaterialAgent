package com.materialagent.ui.components.media

/**
 * A byte-budgeted LRU map.
 *
 * A scrolling transcript re-composes rows that were thrown away, so every image
 * row needs somewhere to find its already-decoded bitmap; without one, flicking
 * back up the conversation re-fetches and re-decodes every picture it passes.
 * The budget is measured in bytes rather than entries because decoded bitmaps
 * differ by two orders of magnitude — counting entries would let four
 * full-screen photos evict an entire screenful of thumbnails, or let a hundred
 * thumbnails pin far more memory than the heap can afford.
 *
 * Reads mark entries fresh (access-order iteration), so what gets evicted is the
 * least recently *seen* row rather than the least recently inserted one, which
 * is precisely the row furthest off screen.
 *
 * Deliberately Android-free and untyped in its contents, so the eviction rules
 * are testable on the JVM.
 */
class BoundedCache<T>(
    private val maxBytes: Long,
    private val sizeOf: (T) -> Long,
) {

    private val entries = object : LinkedHashMap<String, T>(16, 0.75f, true) {}

    private var heldBytes = 0L

    /** What the cache is currently holding, in whatever unit [sizeOf] reports. */
    val usedBytes: Long get() = heldBytes

    @Synchronized
    fun get(key: String): T? = entries[key]

    /**
     * Stores [value], evicting the coldest entries until the budget fits.
     *
     * A value larger than the whole budget is refused rather than stored and
     * immediately evicted: it would otherwise clear the cache on the way in and
     * be gone on the way out, so every screen showing it would pay the decode
     * cost again.
     */
    @Synchronized
    fun put(key: String, value: T) {
        val size = sizeOf(value)
        if (size <= 0L || size > maxBytes) return

        entries.remove(key)?.let { heldBytes -= sizeOf(it) }
        entries[key] = value
        heldBytes += size

        val coldestFirst = entries.entries.iterator()
        while (heldBytes > maxBytes && coldestFirst.hasNext()) {
            val cold = coldestFirst.next()
            heldBytes -= sizeOf(cold.value)
            coldestFirst.remove()
        }
    }

    /** Drops one entry, so a stale decode can be discarded without clearing the rest. */
    @Synchronized
    fun remove(key: String) {
        entries.remove(key)?.let { heldBytes -= sizeOf(it) }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        heldBytes = 0L
    }
}
