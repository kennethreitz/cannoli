package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperMarioWorldSnapshotTest {
    @Test fun `matches the original game without matching sequels or collections`() {
        assertTrue(SuperMarioWorldReader.matches("Super Mario World", null))
        assertTrue(SuperMarioWorldReader.matches(null, "Super Mario World (U) [!].sfc"))
        assertFalse(SuperMarioWorldReader.matches("Super Mario World 2: Yoshi's Island", null))
        assertFalse(SuperMarioWorldReader.matches("Super Mario 3D World", null))
        assertFalse(SuperMarioWorldReader.matches("Super Mario All-Stars + Super Mario World", null))
    }

    @Test fun `checklist contains all 96 exits including 24 secret exits`() {
        val snapshot = SuperMarioWorldReader.parse(ByteArray(14))

        assertNotNull(snapshot)
        assertEquals(96, snapshot!!.totalExitCount)
        assertEquals(24, snapshot.worlds.flatMap { it.levels }.count { it.secretExit != null })
        val exitEvents = snapshot.worlds.flatMap { world ->
            world.levels.flatMap { level ->
                listOfNotNull(level.normalExit.event, level.secretExit?.event)
            }
        }
        assertEquals(96, exitEvents.distinct().size)
        assertEquals(0, snapshot.completedExitCount)
    }

    @Test fun `event bits use the games most significant bit first ordering`() {
        val flags = ByteArray(14)
        flags[0] = 0x40
        flags[1] = 0x80.toByte()

        val snapshot = SuperMarioWorldReader.parse(flags, currentTranslevel = 0x29)!!
        val levels = snapshot.worlds.flatMap { it.levels }

        assertTrue(levels.single { it.name == "Yoshi's Island 1" }.normalExit.completed)
        val donutPlains1 = levels.single { it.name == "Donut Plains 1" }
        assertTrue(donutPlains1.secretExit!!.completed)
        assertFalse(donutPlains1.normalExit.completed)
        assertEquals(0x29, snapshot.currentTranslevel)
        assertEquals(2, snapshot.completedExitCount)
    }

    @Test fun `short memory reads are rejected`() {
        assertNull(SuperMarioWorldReader.parse(ByteArray(13)))
    }

    @Test fun `overworld coordinates resolve to the games level lookup tile`() {
        assertEquals(0x16A, SuperMarioWorldReader.mapTileIndex(mapId = 0, gridX = 26, gridY = 6))
        assertEquals(0x56A, SuperMarioWorldReader.mapTileIndex(mapId = 1, gridX = 26, gridY = 6))
        assertEquals(0x77F, SuperMarioWorldReader.mapTileIndex(mapId = 6, gridX = 31, gridY = 23))
        assertEquals(-1, SuperMarioWorldReader.mapTileIndex(mapId = 0, gridX = 32, gridY = 0))
    }

    @Test fun `load and fade modes hold the last stable checklist row`() {
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x0C))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x0D))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x0F))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x10))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x11))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x12))
        assertTrue(SuperMarioWorldReader.holdsPreviousTranslevel(0x13))
        assertFalse(SuperMarioWorldReader.holdsPreviousTranslevel(0x0E))
        assertFalse(SuperMarioWorldReader.holdsPreviousTranslevel(0x14))
    }
}
