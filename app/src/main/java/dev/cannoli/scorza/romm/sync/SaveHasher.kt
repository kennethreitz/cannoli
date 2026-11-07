package dev.cannoli.scorza.romm.sync

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

object SaveHasher {
    const val EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e"

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).toHex()

    fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    fun hashBundle(entries: Map<String, File>): String {
        val combined = entries.keys.sorted()
            .joinToString("\n") { name -> "$name:${hashFile(entries.getValue(name))}" }
        return md5Hex(combined.toByteArray(Charsets.UTF_8))
    }

    fun hashZipBundle(file: File): String? = runCatching {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
                .filterNot { it.isDirectory }
                .sortedBy { it.name }
            if (entries.isEmpty()) return null
            val combined = entries.joinToString("\n") { entry ->
                val hash = zip.getInputStream(entry).use(::hashStream)
                "${entry.name}:$hash"
            }
            md5Hex(combined.toByteArray(Charsets.UTF_8))
        }
    }.getOrNull()

    private fun hashStream(input: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            md.update(buffer, 0, count)
        }
        return md.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
