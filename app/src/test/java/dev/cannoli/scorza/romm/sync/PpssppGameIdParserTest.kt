package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PpssppGameIdParserTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `reads DISC_ID from PARAM SFO`() {
        assertEquals("UCUS98662", PpssppParamSfo.discId(paramSfo("UCUS98662")))
        assertEquals("ULUS12345", PpssppParamSfo.discId(paramSfo("ULUS-12345")))
        assertNull(PpssppParamSfo.discId(ByteArray(32)))
    }

    @Test fun `reads PSP game ID from ISO`() {
        val iso = tmp.newFile("LocoRoco.iso").apply { writeBytes(pspIso("UCUS98662")) }

        assertEquals("UCUS98662", PpssppGameIdParser.gameId(iso))
        assertEquals(
            "UCUS98662",
            StandaloneTitleIdParser.titleId(StandaloneSaveKind.PPSSPP, iso),
        )
    }

    @Test fun `reads PSP game ID from compressed CSO`() {
        val cso = tmp.newFile("LocoRoco.cso").apply {
            writeBytes(cso(pspIso("UCUS98662")))
        }

        assertEquals("UCUS98662", PpssppGameIdParser.gameId(cso))
    }

    @Test fun `reads PSP game ID from PBP`() {
        val sfo = paramSfo("NPUH10027")
        val bytes = ByteArray(PBP_HEADER_SIZE + sfo.size)
        bytes[0] = 0
        "PBP".toByteArray().copyInto(bytes, 1)
        for (index in 0 until 8) {
            bytes.putLe32(8 + index * 4, if (index == 0) PBP_HEADER_SIZE else bytes.size)
        }
        sfo.copyInto(bytes, PBP_HEADER_SIZE)
        val pbp = tmp.newFile("game.pbp").apply { writeBytes(bytes) }

        assertEquals("NPUH10027", PpssppGameIdParser.gameId(pbp))
    }

    @Test fun `falls back to a game ID embedded in the filename`() {
        val chd = tmp.newFile("Game (ULUS-12345).chd")
        assertEquals("ULUS12345", PpssppGameIdParser.gameId(chd))
    }

    private fun pspIso(gameId: String): ByteArray {
        val image = ByteArray(24 * ISO_SECTOR_SIZE)
        val pvd = 16 * ISO_SECTOR_SIZE
        image[pvd] = 1
        "CD001".toByteArray().copyInto(image, pvd + 1)
        image[pvd + 6] = 1
        writeIsoRecord(image, pvd + 156, ROOT_SECTOR, ISO_SECTOR_SIZE, true, byteArrayOf(0))

        val terminator = 17 * ISO_SECTOR_SIZE
        image[terminator] = 0xFF.toByte()
        "CD001".toByteArray().copyInto(image, terminator + 1)
        image[terminator + 6] = 1

        var offset = ROOT_SECTOR * ISO_SECTOR_SIZE
        offset += writeIsoRecord(image, offset, ROOT_SECTOR, ISO_SECTOR_SIZE, true, byteArrayOf(0))
        offset += writeIsoRecord(image, offset, ROOT_SECTOR, ISO_SECTOR_SIZE, true, byteArrayOf(1))
        writeIsoRecord(
            image,
            offset,
            PSP_GAME_SECTOR,
            ISO_SECTOR_SIZE,
            true,
            "PSP_GAME".toByteArray(),
        )

        offset = PSP_GAME_SECTOR * ISO_SECTOR_SIZE
        offset += writeIsoRecord(image, offset, PSP_GAME_SECTOR, ISO_SECTOR_SIZE, true, byteArrayOf(0))
        offset += writeIsoRecord(image, offset, ROOT_SECTOR, ISO_SECTOR_SIZE, true, byteArrayOf(1))
        val sfo = paramSfo(gameId)
        writeIsoRecord(
            image,
            offset,
            PARAM_SFO_SECTOR,
            sfo.size,
            false,
            "PARAM.SFO;1".toByteArray(),
        )
        sfo.copyInto(image, PARAM_SFO_SECTOR * ISO_SECTOR_SIZE)
        return image
    }

    private fun writeIsoRecord(
        destination: ByteArray,
        offset: Int,
        extent: Int,
        size: Int,
        directory: Boolean,
        name: ByteArray,
    ): Int {
        val unpadded = 33 + name.size
        val length = if (unpadded % 2 == 0) unpadded else unpadded + 1
        destination[offset] = length.toByte()
        destination.putLe32(offset + 2, extent)
        destination.putLe32(offset + 10, size)
        destination[offset + 25] = if (directory) 0x02 else 0
        destination[offset + 32] = name.size.toByte()
        name.copyInto(destination, offset + 33)
        return length
    }

    private fun cso(iso: ByteArray): ByteArray {
        require(iso.size % ISO_SECTOR_SIZE == 0)
        val frames = iso.asList().chunked(ISO_SECTOR_SIZE).map { values ->
            val source = values.toByteArray()
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                deflater.setInput(source)
                deflater.finish()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                deflater.end()
            }
        }
        val indexBytes = (frames.size + 1) * 4
        val dataOffset = CSO_HEADER_SIZE + indexBytes
        val output = ByteArray(dataOffset + frames.sumOf { it.size })
        "CISO".toByteArray().copyInto(output, 0)
        output.putLe32(4, CSO_HEADER_SIZE)
        output.putLe64(8, iso.size.toLong())
        output.putLe32(16, ISO_SECTOR_SIZE)
        output[20] = 1
        var position = dataOffset
        for ((index, frame) in frames.withIndex()) {
            output.putLe32(CSO_HEADER_SIZE + index * 4, position)
            frame.copyInto(output, position)
            position += frame.size
        }
        output.putLe32(CSO_HEADER_SIZE + frames.size * 4, position)
        return output
    }

    private fun paramSfo(gameId: String): ByteArray {
        val key = "DISC_ID\u0000".toByteArray()
        val value = "$gameId\u0000".toByteArray()
        val keyOffset = SFO_HEADER_SIZE + SFO_INDEX_SIZE
        val dataOffset = keyOffset + key.size
        return ByteArray(dataOffset + value.size).also { bytes ->
            bytes[0] = 0
            "PSF".toByteArray().copyInto(bytes, 1)
            bytes.putLe32(4, 0x00000101)
            bytes.putLe32(8, keyOffset)
            bytes.putLe32(12, dataOffset)
            bytes.putLe32(16, 1)
            bytes.putLe16(SFO_HEADER_SIZE, 0)
            bytes.putLe16(SFO_HEADER_SIZE + 2, 0x0204)
            bytes.putLe32(SFO_HEADER_SIZE + 4, value.size)
            bytes.putLe32(SFO_HEADER_SIZE + 8, value.size)
            bytes.putLe32(SFO_HEADER_SIZE + 12, 0)
            key.copyInto(bytes, keyOffset)
            value.copyInto(bytes, dataOffset)
        }
    }

    private fun ByteArray.putLe16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putLe32(offset: Int, value: Int) {
        for (index in 0 until 4) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun ByteArray.putLe64(offset: Int, value: Long) {
        for (index in 0 until 8) this[offset + index] = (value ushr (index * 8)).toByte()
    }

    private companion object {
        const val ISO_SECTOR_SIZE = 2048
        const val ROOT_SECTOR = 20
        const val PSP_GAME_SECTOR = 21
        const val PARAM_SFO_SECTOR = 22
        const val PBP_HEADER_SIZE = 40
        const val CSO_HEADER_SIZE = 24
        const val SFO_HEADER_SIZE = 20
        const val SFO_INDEX_SIZE = 16
    }
}
