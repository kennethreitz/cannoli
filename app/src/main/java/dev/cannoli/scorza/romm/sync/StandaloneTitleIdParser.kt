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
        StandaloneSaveKind.VITA3K -> vita3kTitleId(rom)
        StandaloneSaveKind.DOLPHIN -> dolphinGameId(rom)
        StandaloneSaveKind.PPSSPP -> PpssppGameIdParser.gameId(rom)
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

    fun vita3kTitleId(rom: File): String? = runCatching {
        rom.useLines { lines ->
            lines.firstNotNullOfOrNull { line ->
                VITA_TITLE_ID.matchEntire(line.trim())?.value?.uppercase()
            }
        }
    }.getOrNull()

    fun dolphinGameId(rom: File): String? = runCatching {
        RandomAccessFile(rom, "r").use { raf ->
            val offset = when {
                rom.extension.equals("rvz", ignoreCase = true) ||
                    rom.extension.equals("wia", ignoreCase = true) -> {
                    val magic = ByteArray(4).also {
                        raf.seek(0L)
                        raf.readFully(it)
                    }
                    if (!magic.contentEquals(byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 1)) &&
                        !magic.contentEquals(byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 1))
                    ) return null
                    WIA_DISC_HEADER_OFFSET
                }
                rom.extension.equals("wbfs", ignoreCase = true) -> {
                    val magic = ByteArray(4).also {
                        raf.seek(0L)
                        raf.readFully(it)
                    }
                    if (!magic.contentEquals("WBFS".toByteArray(Charsets.US_ASCII))) return null
                    raf.seek(WBFS_HD_SECTOR_SHIFT_OFFSET)
                    val shift = raf.readUnsignedByte()
                    if (shift !in 9..30) return null
                    1L shl shift
                }
                else -> 0L
            }
            if (raf.length() < offset + DOLPHIN_GAME_ID_LENGTH) return null
            raf.seek(offset)
            val id = ByteArray(DOLPHIN_GAME_ID_LENGTH).also(raf::readFully)
                .toString(Charsets.US_ASCII)
                .uppercase()
            id.takeIf(DOLPHIN_GAME_ID::matches)
        }
    }.getOrNull()

    fun dolphinGciGameId(save: File): String? = runCatching {
        if (!save.isFile || save.length() < DOLPHIN_GAME_ID_LENGTH) return null
        save.inputStream().use { input ->
            val id = ByteArray(DOLPHIN_GAME_ID_LENGTH)
            var offset = 0
            while (offset < id.size) {
                val count = input.read(id, offset, id.size - offset)
                if (count < 0) return null
                offset += count
            }
            id.toString(Charsets.US_ASCII).uppercase().takeIf(DOLPHIN_GAME_ID::matches)
        }
    }.getOrNull()

    private val VITA_TITLE_ID = Regex("""[A-Za-z]{4}[0-9]{5}""")
    private val DOLPHIN_GAME_ID = Regex("""[A-Z0-9]{6}""")
    private const val DOLPHIN_GAME_ID_LENGTH = 6
    private const val WIA_DISC_HEADER_OFFSET = 0x58L
    private const val WBFS_HD_SECTOR_SHIFT_OFFSET = 8L
}
