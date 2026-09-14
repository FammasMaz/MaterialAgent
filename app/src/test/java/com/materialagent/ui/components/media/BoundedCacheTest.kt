package com.materialagent.ui.components.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards [BoundedCache], the budget the image rows share.
 *
 * The invariants: the budget is never exceeded, eviction takes the coldest entry
 * rather than the oldest one, and an entry too large to ever fit is refused
 * instead of being admitted and immediately evicted — which would clear the
 * cache on the way past and still leave the caller without the picture.
 */
class BoundedCacheTest {

    private fun cacheOf(budget: Long) = BoundedCache<ByteArray>(budget) { it.size.toLong() }

    @Test
    fun storesWhatFitsAndReportsWhatItHolds() {
        val cache = cacheOf(100)

        cache.put("a", ByteArray(60))

        assertEquals(60L, cache.usedBytes)
        assertEquals(60, cache.get("a")?.size)
    }

    @Test
    fun theColdestEntryIsEvictedFirst() {
        val cache = cacheOf(100)

        cache.put("a", ByteArray(60))
        cache.put("b", ByteArray(60))

        // Only one 60-byte entry fits, so the first one went.
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertEquals(60L, cache.usedBytes)
    }

    @Test
    fun readingAnEntryKeepsItAlive() {
        val cache = cacheOf(120)

        cache.put("a", ByteArray(60))
        cache.put("b", ByteArray(60))
        // Touching "a" makes "b" the coldest, even though "a" is older.
        cache.get("a")
        cache.put("c", ByteArray(60))

        assertNotNull("the recently read entry must survive", cache.get("a"))
        assertNull("the untouched entry must be the one evicted", cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(120L, cache.usedBytes)
    }

    @Test
    fun anEntryLargerThanTheBudgetIsNotStored() {
        val cache = cacheOf(100)

        cache.put("a", ByteArray(60))
        cache.put("huge", ByteArray(200))

        assertNull(cache.get("huge"))
        assertEquals("the oversized entry must not clear the cache", 60L, cache.usedBytes)
    }

    @Test
    fun replacingAKeyDoesNotDoubleCountIt() {
        val cache = cacheOf(100)

        cache.put("a", ByteArray(40))
        cache.put("a", ByteArray(70))

        assertEquals(70L, cache.usedBytes)
        assertEquals(70, cache.get("a")?.size)
    }

    @Test
    fun removeAndClearReleaseTheirBytes() {
        val cache = cacheOf(100)
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))

        cache.remove("a")
        assertEquals(40L, cache.usedBytes)

        cache.clear()
        assertEquals(0L, cache.usedBytes)
        assertNull(cache.get("b"))
    }
}
