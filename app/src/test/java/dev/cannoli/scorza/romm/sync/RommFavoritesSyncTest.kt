package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RommFavoritesSyncTest {
    @Test
    fun `first sync merges without removing either side`() {
        val plan = reconcileFavorites(
            baseline = null,
            local = setOf(1, 2),
            server = setOf(2, 3, 99),
            mappable = setOf(1, 2, 3),
        )

        assertEquals(setOf(1), plan.addToServer)
        assertEquals(emptySet<Int>(), plan.removeFromServer)
        assertEquals(setOf(3), plan.addToLocal)
        assertEquals(emptySet<Int>(), plan.removeFromLocal)
    }

    @Test
    fun `later local additions and removals are sent to server`() {
        val plan = reconcileFavorites(
            baseline = setOf(1, 2),
            local = setOf(2, 3),
            server = setOf(1, 2),
            mappable = setOf(1, 2, 3),
        )

        assertEquals(setOf(3), plan.addToServer)
        assertEquals(setOf(1), plan.removeFromServer)
        assertEquals(emptySet<Int>(), plan.addToLocal)
        assertEquals(emptySet<Int>(), plan.removeFromLocal)
    }

    @Test
    fun `later server additions and removals are applied locally`() {
        val plan = reconcileFavorites(
            baseline = setOf(1, 2),
            local = setOf(1, 2),
            server = setOf(2, 3),
            mappable = setOf(1, 2, 3),
        )

        assertEquals(emptySet<Int>(), plan.addToServer)
        assertEquals(emptySet<Int>(), plan.removeFromServer)
        assertEquals(setOf(3), plan.addToLocal)
        assertEquals(setOf(1), plan.removeFromLocal)
    }

    @Test
    fun `unmapped server favorites are never mistaken for local removals`() {
        val plan = reconcileFavorites(
            baseline = setOf(99),
            local = emptySet(),
            server = setOf(99),
            mappable = setOf(1, 2),
        )

        assertEquals(emptySet<Int>(), plan.removeFromServer)
        assertEquals(emptySet<Int>(), plan.removeFromLocal)
    }
}
