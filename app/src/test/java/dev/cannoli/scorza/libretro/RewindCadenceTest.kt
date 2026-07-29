package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RewindCadenceTest {
    @Test fun `captures first frame and then ten snapshots per second at sixty fps`() {
        val cadence = RewindCadence()

        val captures = (0 until 60).filter { cadence.shouldCaptureForwardFrame() }

        assertEquals(listOf(0, 6, 12, 18, 24, 30, 36, 42, 48, 54), captures)
    }

    @Test fun `rewind speed remains a gameplay time multiplier`() {
        val cadence = RewindCadence()
        repeat(60) { cadence.shouldCaptureForwardFrame() }

        val restoredStates = (0 until 60).sumOf { cadence.statesForRewindTick(speed = 2) }

        // Ten stored points represent one second, so twenty points restore
        // approximately two seconds during one second of wall-clock time.
        assertEquals(20, restoredStates)
    }

    @Test fun `first forward frame after rewind anchors the new timeline`() {
        val cadence = RewindCadence()
        repeat(4) { cadence.shouldCaptureForwardFrame() }
        assertTrue(cadence.statesForRewindTick(speed = 4) > 0)

        assertTrue(cadence.shouldCaptureForwardFrame())
    }
}
