package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PpssppSaveBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `restores OpenVault PPSSPP bundle layout`() {
        val archive = zip(
            "PSP/SAVEDATA/UCUS98662_GameData0/DATA.BIN" to "remote-locoroco",
            "PSP/SAVEDATA/UCUS98662_GameData0/ICON0.PNG" to "icon",
        )
        val stage = tmp.newFolder("stage")

        val directories = PpssppSaveBundle.extractAndValidate(archive, stage, "UCUS98662")

        assertEquals(setOf("UCUS98662_GameData0"), directories)
        assertEquals(
            "remote-locoroco",
            File(stage, "PSP/SAVEDATA/UCUS98662_GameData0/DATA.BIN").readText(),
        )
    }

    @Test fun `accepts multiple save slots belonging to one game`() {
        val archive = zip(
            "PSP/SAVEDATA/ULUS1234500/DATA.BIN" to "slot-0",
            "PSP/SAVEDATA/ULUS1234501/DATA.BIN" to "slot-1",
        )

        val directories = PpssppSaveBundle.extractAndValidate(
            archive,
            tmp.newFolder("slots"),
            "ULUS-12345",
        )

        assertEquals(setOf("ULUS1234500", "ULUS1234501"), directories)
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects another games save directory`() {
        PpssppSaveBundle.extractAndValidate(
            zip("PSP/SAVEDATA/ULES00001/DATA.BIN" to "wrong"),
            tmp.newFolder("wrong"),
            "UCUS98662",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects files outside PSP SAVEDATA`() {
        PpssppSaveBundle.extractAndValidate(
            zip("PSP/PPSSPP_STATE/state.ppst" to "snapshot"),
            tmp.newFolder("states"),
            "UCUS98662",
        )
    }

    @Test fun `directory matching prefers embedded DISC_ID when available`() {
        assertTrue(PpssppSaveBundle.matchesSaveDirectory("UCUS98662_GameData0", "UCUS98662"))
        assertFalse(PpssppSaveBundle.matchesSaveDirectory("ULES00001", "UCUS98662"))
    }

    private fun zip(vararg files: Pair<String, String>): File =
        tmp.newFile("bundle-${java.util.UUID.randomUUID()}.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((path, contents) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
