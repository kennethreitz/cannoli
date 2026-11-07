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

class CemuSaveBundleTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `restores Cannoli portable bundle`() {
        val archive = zip(
            CemuSaveBundle.MANIFEST_NAME to portableManifest(TITLE_ID),
            "save/80000001/user/80000001/save.dat" to "portable-save",
        )
        val stage = tmp.newFolder("portable")

        CemuSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals(
            "portable-save",
            File(stage, "80000001/user/80000001/save.dat").readText(),
        )
    }

    @Test fun `restores RetroVault native MLC bundle`() {
        val archive = zip(
            "usr/save/00050000/10145c00/user/common.dat" to "native-save",
            "sys/title/0005001b/10056000/content/cafe.xml" to "MLC housekeeping",
        )
        val stage = tmp.newFolder("native")

        CemuSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals("native-save", File(stage, "user/common.dat").readText())
        assertFalse(File(stage, "sys").exists())
    }

    @Test fun `restores legacy mlc01 rooted native bundle`() {
        val archive = zip(
            "mlc01/usr/save/00050000/10145c00/meta/saveinfo.xml" to "legacy-save",
        )
        val stage = tmp.newFolder("mlc01")

        CemuSaveBundle.extractAndValidate(archive, stage, TITLE_ID)

        assertEquals("legacy-save", File(stage, "meta/saveinfo.xml").readText())
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects native bundle for another title`() {
        CemuSaveBundle.extractAndValidate(
            zip("usr/save/00050000/10143500/user/save.dat" to "wrong-game"),
            tmp.newFolder("wrong-native"),
            TITLE_ID,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects portable bundle for another title`() {
        CemuSaveBundle.extractAndValidate(
            zip(
                CemuSaveBundle.MANIFEST_NAME to portableManifest("0005000010143500"),
                "save/user/save.dat" to "wrong-game",
            ),
            tmp.newFolder("wrong-portable"),
            TITLE_ID,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `rejects unsafe paths even when they are outside the title tree`() {
        CemuSaveBundle.extractAndValidate(
            zip(
                "usr/save/00050000/10145c00/user/save.dat" to "save",
                "../escape" to "unsafe",
            ),
            tmp.newFolder("unsafe"),
            TITLE_ID,
        )
    }

    private fun portableManifest(titleId: String): String =
        "format=1\nemulator=CEMU\ntitle_id=${titleId.uppercase()}\n"

    private fun zip(vararg files: Pair<String, String>): File =
        tmp.newFile("cemu-${UUID.randomUUID()}.zip").also { archive ->
            ZipOutputStream(archive.outputStream()).use { zip ->
                for ((path, contents) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(contents.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private companion object {
        const val TITLE_ID = "0005000010145C00"
    }
}
