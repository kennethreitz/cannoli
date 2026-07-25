package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class StandaloneTitleIdParserTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `reads little endian title id from 3ds NCSD header`() {
        val rom = tmp.newFile("game.3ds")
        RandomAccessFile(rom, "rw").use { raf ->
            raf.setLength(0x200)
            raf.seek(0x100)
            raf.write("NCSD".toByteArray())
            raf.write(byteArrayOf(0, 0, 0, 0))
            raf.write(byteArrayOf(0x00, 0xC3.toByte(), 0x0E, 0x00, 0x00, 0x00, 0x04, 0x00))
        }

        assertEquals("00040000000EC300", StandaloneTitleIdParser.citraTitleId(rom))
    }

    @Test fun `rejects a non NCSD 3ds file`() {
        assertNull(StandaloneTitleIdParser.citraTitleId(tmp.newFile("bad.3ds")))
    }

    @Test fun `reads Cemu title id from decompressed meta xml`() {
        val xml = """<menu><title_id type="hexBinary" length="8">0005000010145c00</title_id></menu>"""
        assertEquals("0005000010145C00", StandaloneTitleIdParser.parseCemuMeta(xml))
    }

    @Test fun `reads Cemu title id before the WUA container data after its first zstd frame`() {
        val wua = tmp.newFile("game.wua")
        val metaFrame = java.util.Base64.getDecoder().decode(
                "KLUv/QRYTQIAYgQQF4A1bhFSPO8M4SiIQU+ht2M8i0XI0mS46nibSTX5Eyg4P/+P/" +
                    "isKmFQjvKzgAxi8hsAqTJu8LscqlvXqVMfbMgEArgkFBWV8vc0=",
            )
        wua.outputStream().use {
            it.write(metaFrame)
            it.write("WUA-CONTAINER-DATA".toByteArray())
        }

        assertEquals("0005000010145C00", StandaloneTitleIdParser.cemuTitleId(wua))
    }

    @Test fun `reads Vita3K title id from psvita launcher file`() {
        val launcher = tmp.newFile("Undertale.psvita").apply {
            writeText("\nPCSE01116\n")
        }

        assertEquals("PCSE01116", StandaloneTitleIdParser.vita3kTitleId(launcher))
        assertEquals(
            "PCSE01116",
            StandaloneTitleIdParser.titleId(StandaloneSaveKind.VITA3K, launcher),
        )
    }

    @Test fun `rejects malformed Vita3K launcher file`() {
        val launcher = tmp.newFile("Broken.psvita").apply { writeText("undertale") }
        assertNull(StandaloneTitleIdParser.vita3kTitleId(launcher))
    }
}
