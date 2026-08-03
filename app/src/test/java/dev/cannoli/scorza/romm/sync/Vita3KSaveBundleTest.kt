package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Vita3KSaveBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `restores Cannoli portable bundle`() {
        val archive = zip(
            Vita3KSaveBundle.MANIFEST_NAME to portableManifest(TITLE_ID),
            "save/SlotParam_0.bin" to "slot",
            "save/save.dat" to "portable-save",
        )
        val stage = tmp.newFolder("portable")

        Vita3KSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals("slot", File(stage, "SlotParam_0.bin").readText())
        assertEquals("portable-save", File(stage, "save.dat").readText())
        assertFalse(File(stage, Vita3KSaveBundle.MANIFEST_NAME).exists())
    }

    @Test fun `restores RetroVault direct title directory bundle`() {
        val archive = zip(
            "SlotParam_0.bin" to "slot",
            "save.dat" to "retrovault-save",
        )
        val stage = tmp.newFolder("direct")

        Vita3KSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals("slot", File(stage, "SlotParam_0.bin").readText())
        assertEquals("retrovault-save", File(stage, "save.dat").readText())
    }

    @Test fun `restores expected title from native Vita3K savedata export`() {
        val archive = zip(
            "ux0/user/00/savedata/$TITLE_ID/save.dat" to "expected",
            "ux0/user/00/savedata/PCSA99999/save.dat" to "other-game",
        )
        val stage = tmp.newFolder("native")

        Vita3KSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals("expected", File(stage, "save.dat").readText())
        assertFalse(File(stage, "PCSA99999").exists())
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects portable bundle for another title`() {
        Vita3KSaveBundle.extractAndValidate(
            zip(
                Vita3KSaveBundle.MANIFEST_NAME to portableManifest("PCSA99999"),
                "save/save.dat" to "wrong-game",
            ),
            tmp.newFolder("wrong-portable"),
            TITLE_ID,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects native export without the requested title`() {
        Vita3KSaveBundle.extractAndValidate(
            zip("vita/ux0/user/00/savedata/PCSA99999/save.dat" to "wrong-game"),
            tmp.newFolder("wrong-native"),
            TITLE_ID,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects unsafe paths even when outside the requested title`() {
        Vita3KSaveBundle.extractAndValidate(
            zip(
                "ux0/user/00/savedata/$TITLE_ID/save.dat" to "save",
                "../escape" to "unsafe",
            ),
            tmp.newFolder("unsafe"),
            TITLE_ID,
        )
    }

    private fun portableManifest(titleId: String): String =
        "format=1\nemulator=VITA3K\ntitle_id=${titleId.uppercase()}\n"

    private fun zip(vararg files: Pair<String, String>): File =
        tmp.newFile("vita-${UUID.randomUUID()}.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((path, contents) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private companion object {
        const val TITLE_ID = "PCSA00006"
    }
}
