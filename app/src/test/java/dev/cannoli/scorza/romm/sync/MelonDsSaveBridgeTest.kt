package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MelonDsSaveBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bridge(packageName: String? = "me.magnum.melonds.nightly"): MelonDsSaveBridge {
        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns tmp.root
        every { paths.romDir } returns File(tmp.root, "ROMS")
        val platforms = mockk<PlatformConfig>(relaxed = true)
        every { platforms.getSelectedStandaloneAppPackage("NDS") } returns packageName
        return MelonDsSaveBridge(paths, platforms)
    }

    private fun zippedRom(gameKey: String, entryName: String): File =
        File(tmp.root, "ROMS/$gameKey").apply {
            parentFile?.mkdirs()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write("ROM".toByteArray())
                zip.closeEntry()
            }
        }

    @Test fun `zipped rom cartridge save is mirrored under the archive base`() {
        val bridge = bridge()
        val gameKey = "NDS/Korg DS-10+ Synthesizer.zip"
        zippedRom(gameKey, "Korg DS-10+ Synthesizer (USA) (NDSi Enhanced)_apfix.nds")
        val saves = File(tmp.root, "Saves/NDS").apply { mkdirs() }
        File(saves, "Korg DS-10+ Synthesizer (USA) (NDSi Enhanced)_apfix.sav")
            .writeText("CARTRIDGE")

        val result = bridge.refresh("NDS", "Korg DS-10+ Synthesizer", gameKey)

        assertTrue(result is RetroArchMirrorResult.Ready)
        assertEquals("CARTRIDGE", File(saves, "Korg DS-10+ Synthesizer.sav").readText())
    }

    @Test fun `downloaded save is applied to the name inside the zip`() {
        val bridge = bridge()
        val gameKey = "NDS/Korg DS-10+ Synthesizer.zip"
        zippedRom(gameKey, "nested/Korg DS-10+ Synthesizer (USA)_apfix.nds")
        val saves = File(tmp.root, "Saves/NDS").apply { mkdirs() }
        File(saves, "Korg DS-10+ Synthesizer.srm").writeText("SERVER")

        bridge.apply("NDS", "Korg DS-10+ Synthesizer", gameKey)

        assertEquals(
            "SERVER",
            File(saves, "Korg DS-10+ Synthesizer (USA)_apfix.sav").readText(),
        )
        assertEquals("SERVER", File(saves, "Korg DS-10+ Synthesizer.sav").readText())
        assertFalse(File(saves, "Korg DS-10+ Synthesizer.srm").exists())
    }

    @Test fun `bridge ignores save states and unrelated emulators`() {
        val bridge = bridge(packageName = "com.dsemu.drastic")
        val gameKey = "NDS/Game.nds"
        File(tmp.root, "ROMS/$gameKey").apply {
            parentFile?.mkdirs()
            writeText("ROM")
        }
        val saves = File(tmp.root, "Saves/NDS").apply { mkdirs() }
        File(saves, "Game.state").writeText("STATE")

        assertFalse(bridge.supports("NDS", "DraStic", gameKey))
        assertTrue(bridge.refresh("NDS", "Game", gameKey) is RetroArchMirrorResult.Missing)
        assertFalse(File(saves, "Game.sav").exists())
    }
}
