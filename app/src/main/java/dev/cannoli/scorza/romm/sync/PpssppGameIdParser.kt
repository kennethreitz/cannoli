package dev.cannoli.scorza.romm.sync

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * Reads PPSSPP's DISC_ID from the PARAM.SFO embedded in a PSP ISO, CSO, or PBP.
 *
 * PPSSPP uses this identifier as the stable prefix for directories below
 * memstick/PSP/SAVEDATA. Parsing the image keeps save routing independent of
 * user-facing ROM filenames.
 */
object PpssppGameIdParser {
    fun gameId(rom: File): String? {
        val parsed = runCatching {
            RandomAccessFile(rom, "r").use { file ->
                if (file.length() < 4) return@use null
                val magic = file.readBytesAt(0, 4)
                when {
                    magic.contentEquals(PBP_MAGIC) -> readPbp(file)
                    magic.contentEquals(CSO_MAGIC) -> readIso(CsoReader(file))
                    else -> readIso(RawReader(file))
                }
            }
        }.getOrNull()
        return PpssppParamSfo.normalizeGameId(parsed)
            ?: PSP_GAME_ID.find(rom.nameWithoutExtension)?.value
                ?.let(PpssppParamSfo::normalizeGameId)
    }

    private fun readPbp(file: RandomAccessFile): String? {
        if (file.length() < PBP_HEADER_SIZE) return null
        val paramOffset = file.readUInt32Le(8)
        val nextOffset = file.readUInt32Le(12)
        val size = nextOffset - paramOffset
        if (paramOffset < PBP_HEADER_SIZE || size !in 1..MAX_PARAM_SFO_BYTES.toLong()) return null
        if (nextOffset > file.length()) return null
        return PpssppParamSfo.discId(file.readBytesAt(paramOffset, size.toInt()))
    }

    private fun readIso(reader: RandomReader): String? {
        val primary = (FIRST_VOLUME_DESCRIPTOR..LAST_VOLUME_DESCRIPTOR)
            .firstNotNullOfOrNull { sector ->
                val descriptor = reader.read(sector.toLong() * ISO_SECTOR_SIZE, ISO_SECTOR_SIZE)
                if (!descriptor.copyOfRange(1, 6).contentEquals(ISO_MAGIC)) return@firstNotNullOfOrNull null
                when (descriptor[0].toInt() and 0xFF) {
                    1 -> descriptor
                    255 -> return null
                    else -> null
                }
            } ?: return null
        val root = directoryRecord(primary, ISO_ROOT_RECORD_OFFSET) ?: return null
        val pspGame = findDirectoryEntry(reader, root, "PSP_GAME", requireDirectory = true) ?: return null
        val param = findDirectoryEntry(reader, pspGame, "PARAM.SFO", requireDirectory = false) ?: return null
        if (param.size !in 1..MAX_PARAM_SFO_BYTES) return null
        return PpssppParamSfo.discId(
            reader.read(param.extent.toLong() * ISO_SECTOR_SIZE, param.size),
        )
    }

    private fun findDirectoryEntry(
        reader: RandomReader,
        directory: IsoRecord,
        expectedName: String,
        requireDirectory: Boolean,
    ): IsoRecord? {
        if (directory.size !in 1..MAX_DIRECTORY_BYTES) return null
        val bytes = reader.read(directory.extent.toLong() * ISO_SECTOR_SIZE, directory.size)
        var offset = 0
        while (offset < bytes.size) {
            val recordLength = bytes[offset].toInt() and 0xFF
            if (recordLength == 0) {
                offset = ((offset / ISO_SECTOR_SIZE) + 1) * ISO_SECTOR_SIZE
                continue
            }
            if (offset + recordLength > bytes.size) return null
            val record = directoryRecord(bytes, offset)
            if (record != null &&
                record.isDirectory == requireDirectory &&
                record.name.substringBefore(';').equals(expectedName, ignoreCase = true)
            ) {
                return record
            }
            offset += recordLength
        }
        return null
    }

    private fun directoryRecord(bytes: ByteArray, offset: Int): IsoRecord? {
        if (offset < 0 || offset + 34 > bytes.size) return null
        val length = bytes[offset].toInt() and 0xFF
        if (length < 34 || offset + length > bytes.size) return null
        val nameLength = bytes[offset + 32].toInt() and 0xFF
        if (offset + 33 + nameLength > offset + length) return null
        val nameBytes = bytes.copyOfRange(offset + 33, offset + 33 + nameLength)
        val name = when {
            nameBytes.size == 1 && nameBytes[0].toInt() == 0 -> "."
            nameBytes.size == 1 && nameBytes[0].toInt() == 1 -> ".."
            else -> nameBytes.toString(Charsets.US_ASCII)
        }
        return IsoRecord(
            extent = bytes.uint32Le(offset + 2).toInt(),
            size = bytes.uint32Le(offset + 10).toInt(),
            isDirectory = bytes[offset + 25].toInt() and 0x02 != 0,
            name = name,
        ).takeIf { it.extent >= 0 && it.size >= 0 }
    }

    private data class IsoRecord(
        val extent: Int,
        val size: Int,
        val isDirectory: Boolean,
        val name: String,
    )

    private interface RandomReader {
        fun read(offset: Long, length: Int): ByteArray
    }

    private class RawReader(private val file: RandomAccessFile) : RandomReader {
        override fun read(offset: Long, length: Int): ByteArray =
            file.readBytesAt(offset, length)
    }

    private class CsoReader(private val file: RandomAccessFile) : RandomReader {
        private val totalBytes = file.readUInt64Le(8)
        private val blockSize = file.readUInt32Le(16).toInt()
        private val version = file.readBytesAt(20, 1)[0].toInt() and 0xFF
        private val alignment = file.readBytesAt(21, 1)[0].toInt() and 0xFF
        private val frameCount: Long
        private var cachedFrame = -1L
        private var cachedBytes = ByteArray(0)

        init {
            require(version <= 1) { "unsupported CSO version" }
            require(totalBytes > 0) { "empty CSO" }
            require(blockSize >= ISO_SECTOR_SIZE && blockSize and (blockSize - 1) == 0) {
                "invalid CSO block size"
            }
            require(alignment in 0..31) { "invalid CSO alignment" }
            frameCount = (totalBytes + blockSize - 1) / blockSize
            // PPSSPP deliberately ignores header_size for CSO v1 because many
            // tools leave it unset even though the index still begins at 0x18.
            require(CSO_HEADER_SIZE + (frameCount + 1) * 4 <= file.length()) {
                "truncated CSO index"
            }
        }

        override fun read(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0 && offset + length <= totalBytes) {
                "CSO read outside image"
            }
            val output = ByteArray(length)
            var sourceOffset = offset
            var outputOffset = 0
            while (outputOffset < length) {
                val frame = sourceOffset / blockSize
                val withinFrame = (sourceOffset % blockSize).toInt()
                val bytes = readFrame(frame)
                val count = minOf(length - outputOffset, bytes.size - withinFrame)
                require(count > 0) { "invalid CSO frame" }
                bytes.copyInto(output, outputOffset, withinFrame, withinFrame + count)
                sourceOffset += count
                outputOffset += count
            }
            return output
        }

        private fun readFrame(frame: Long): ByteArray {
            if (cachedFrame == frame) return cachedBytes
            require(frame in 0 until frameCount) { "invalid CSO frame" }
            val indexOffset = CSO_HEADER_SIZE + frame * 4
            val current = file.readUInt32Le(indexOffset)
            val next = file.readUInt32Le(indexOffset + 4)
            val start = (current and 0x7FFFFFFF) shl alignment
            val end = (next and 0x7FFFFFFF) shl alignment
            require(end >= start && end <= file.length()) { "invalid CSO frame bounds" }
            val storedSize = (end - start).toInt()
            val expectedSize = minOf(blockSize.toLong(), totalBytes - frame * blockSize).toInt()
            val output = if (current and 0x80000000L != 0L) {
                file.readBytesAt(start, expectedSize)
            } else {
                val compressed = file.readBytesAt(start, storedSize)
                val padded = ByteArray(blockSize)
                val inflater = Inflater(true)
                try {
                    inflater.setInput(compressed)
                    var written = 0
                    while (!inflater.finished() && written < padded.size) {
                        val count = inflater.inflate(padded, written, padded.size - written)
                        if (count == 0 && inflater.needsInput()) break
                        written += count
                    }
                    require(inflater.finished() && written >= expectedSize) { "invalid compressed CSO frame" }
                } finally {
                    inflater.end()
                }
                if (expectedSize == padded.size) padded else padded.copyOf(expectedSize)
            }
            cachedFrame = frame
            cachedBytes = output
            return output
        }
    }

    private fun RandomAccessFile.readBytesAt(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= this.length()) {
            "read outside file"
        }
        seek(offset)
        return ByteArray(length).also(::readFully)
    }

    private fun RandomAccessFile.readUInt32Le(offset: Long): Long =
        readBytesAt(offset, 4).uint32Le(0)

    private fun RandomAccessFile.readUInt64Le(offset: Long): Long {
        val bytes = readBytesAt(offset, 8)
        var value = 0L
        for (index in 7 downTo 0) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        return value
    }

    private fun ByteArray.uint32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)

    private val PBP_MAGIC = byteArrayOf(0, 'P'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    private val CSO_MAGIC = "CISO".toByteArray(Charsets.US_ASCII)
    private val ISO_MAGIC = "CD001".toByteArray(Charsets.US_ASCII)
    private val PSP_GAME_ID = Regex("""(?i)(?<![A-Z0-9])[A-Z0-9]{4}-?[A-Z0-9]{5}(?![A-Z0-9])""")
    private const val PBP_HEADER_SIZE = 40L
    private const val CSO_HEADER_SIZE = 24L
    private const val ISO_SECTOR_SIZE = 2048
    private const val FIRST_VOLUME_DESCRIPTOR = 16
    private const val LAST_VOLUME_DESCRIPTOR = 31
    private const val ISO_ROOT_RECORD_OFFSET = 156
    private const val MAX_PARAM_SFO_BYTES = 1024 * 1024
    private const val MAX_DIRECTORY_BYTES = 16 * 1024 * 1024
}

object PpssppParamSfo {
    fun discId(bytes: ByteArray): String? = runCatching {
        if (bytes.size < HEADER_SIZE || !bytes.copyOfRange(0, 4).contentEquals(SFO_MAGIC)) return null
        val keyTableOffset = bytes.uint32Le(8).toInt()
        val dataTableOffset = bytes.uint32Le(12).toInt()
        val entryCount = bytes.uint32Le(16).toInt()
        if (keyTableOffset !in HEADER_SIZE..bytes.size ||
            dataTableOffset !in HEADER_SIZE..bytes.size ||
            entryCount !in 1..MAX_ENTRIES
        ) return null
        for (index in 0 until entryCount) {
            val entryOffset = HEADER_SIZE + index * INDEX_SIZE
            if (entryOffset + INDEX_SIZE > bytes.size) return null
            val keyOffset = keyTableOffset + bytes.uint16Le(entryOffset)
            val valueLength = bytes.uint32Le(entryOffset + 4).toInt()
            val valueOffset = dataTableOffset + bytes.uint32Le(entryOffset + 12).toInt()
            val key = bytes.nullTerminatedString(keyOffset, bytes.size - keyOffset) ?: continue
            if (key != "DISC_ID") continue
            if (valueLength !in 1..MAX_VALUE_BYTES || valueOffset !in bytes.indices ||
                valueOffset + valueLength > bytes.size
            ) return null
            return normalizeGameId(bytes.nullTerminatedString(valueOffset, valueLength))
        }
        null
    }.getOrNull()

    fun normalizeGameId(value: String?): String? =
        value?.trim()?.uppercase()?.replace("-", "")
            ?.takeIf { NORMALIZED_GAME_ID.matches(it) }

    private fun ByteArray.uint16Le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.uint32Le(offset: Int): Long =
        (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)

    private fun ByteArray.nullTerminatedString(offset: Int, limit: Int): String? {
        if (offset !in indices || limit <= 0) return null
        val endLimit = minOf(size, offset + limit)
        var end = offset
        while (end < endLimit && this[end].toInt() != 0) end++
        if (end == offset) return null
        return copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private val SFO_MAGIC = byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())
    private val NORMALIZED_GAME_ID = Regex("""[A-Z0-9]{9}""")
    private const val HEADER_SIZE = 20
    private const val INDEX_SIZE = 16
    private const val MAX_ENTRIES = 4096
    private const val MAX_VALUE_BYTES = 1024
}
