package dev.cannoli.scorza.libretro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldFfTest {

    @Test
    fun holdsWhileWholeChordStaysPressed() {
        assertFalse(shouldReleaseHoldShortcut(setOf(102, 103), setOf(102, 103, 96)))
    }

    @Test
    fun releasesOnceAChordKeyLifts() {
        assertTrue(shouldReleaseHoldShortcut(setOf(102, 103), setOf(102)))
    }

    @Test
    fun releasesWhenBindingClearedWhileHeld() {
        assertTrue(shouldReleaseHoldShortcut(emptySet(), setOf(102, 103)))
    }

    @Test
    fun releasesWhenBindingRemovedWhileHeld() {
        assertTrue(shouldReleaseHoldShortcut(null, setOf(102, 103)))
    }
}
