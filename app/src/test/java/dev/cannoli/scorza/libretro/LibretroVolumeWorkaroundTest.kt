package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroVolumeWorkaroundTest {
    @Test
    fun `only applies to AYN Thor`() {
        assertTrue(LibretroVolumeWorkaround.applies("AYN", "AYN Thor"))
        assertTrue(LibretroVolumeWorkaround.applies("ayn", "ayn thor"))
        assertFalse(LibretroVolumeWorkaround.applies("AYN", "Odin 2"))
        assertFalse(LibretroVolumeWorkaround.applies("Anbernic", "RG477M"))
    }

    @Test
    fun `maps media volume to bounded native gain`() {
        assertEquals(0f, LibretroVolumeWorkaround.gain(0, 15), 0f)
        assertEquals(0.5f, LibretroVolumeWorkaround.gain(5, 10), 0f)
        assertEquals(1f, LibretroVolumeWorkaround.gain(15, 15), 0f)
        assertEquals(1f, LibretroVolumeWorkaround.gain(20, 15), 0f)
        assertEquals(0f, LibretroVolumeWorkaround.gain(5, 0), 0f)
    }
}
