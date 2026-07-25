package dev.cannoli.scorza.romm.sync

import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

object StandaloneTitleIdParser {
    private const val NCSD_HEADER_OFFSET = 0x100L
    private const val NCSD_MEDIA_ID_OFFSET = NCSD_HEADER_OFFSET + 8
    private const val CEMU_META_LIMIT = 128 * 1024
    private val cemuTitleId = Regex("""<title_id[^>]*>\s*([0-9A-Fa-f]{16})\s*</title_id>""")

    fun titleId(kind: StandaloneSaveKind, rom: File): String? = when (kind) {
        StandaloneSaveKind.CITRA_MMJ -> citraTitleId(rom)
        StandaloneSaveKind.CEMU -> cemuTitleId(rom)
    }

    fun citraTitleId(rom: File): String? = runCatching {
        RandomAccessFile(rom, "r").use { raf ->
            if (raf.length() < NCSD_MEDIA_ID_OFFSET + 8) return null
            raf.seek(NCSD_HEADER_OFFSET)
            val magic = ByteArray(4).also(raf::readFully)
            if (!magic.contentEquals("NCSD".toByteArray(Charsets.US_ASCII))) return null
            raf.seek(NCSD_MEDIA_ID_OFFSET)
            val littleEndian = ByteArray(8).also(raf::readFully)
            littleEndian.reversedArray().joinToString("") { "%02X".format(it) }
        }
    }.getOrNull()

    fun cemuTitleId(rom: File): String? = runCatching {
        FileInputStream(rom).use fileUse@ { file ->
            ZstdInputStream(file).use zstdUse@ { zstd ->
                val out = java.io.ByteArrayOutputStream(4096)
                while (out.size() < CEMU_META_LIMIT) {
                    val next = try {
                        zstd.read()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    if (next < 0) break
                    out.write(next)
                    // A WUA begins with a standalone zstd-compressed meta.xml frame and then
                    // continues with its own container structures. Aircompressor reports that
                    // following data as another malformed zstd frame, so return as soon as the
                    // title ID appears rather than reading past the metadata frame.
                    if (out.size() % 256 == 0) {
                        parseCemuMeta(out.toString(Charsets.UTF_8.name()))?.let { return@zstdUse it }
                    }
                }
                parseCemuMeta(out.toString(Charsets.UTF_8.name()))
            }
        }
    }.getOrNull()

    internal fun parseCemuMeta(xml: String): String? =
        cemuTitleId.find(xml)?.groupValues?.get(1)?.uppercase()
}
