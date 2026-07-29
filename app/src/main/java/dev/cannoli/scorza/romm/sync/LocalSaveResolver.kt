package dev.cannoli.scorza.romm.sync

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class LocalSave(
    val files: List<File>,
    val isBundle: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val contentHash: String,
    val uploadFileName: String,
)

enum class LocalSaveMode {
    NORMAL,
    STANDALONE_ARCHIVE,
    PPSSPP_DIRECTORY_ARCHIVE,
}

class LocalSaveResolver(private val cannoliRoot: File) {

    private fun savesDir(tag: String) = File(File(cannoliRoot, "Saves"), tag)

    private fun matchingFiles(tag: String, base: String, mode: LocalSaveMode): List<File> {
        val dir = savesDir(tag)
        if (!dir.isDirectory) return emptyList()
        archiveFileName(base, mode)?.let { name ->
            return listOf(File(dir, name)).filter { it.isFile }
        }
        return dir.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    !it.name.endsWith(".cannoli-standalone.zip") &&
                    !it.name.endsWith(".cannoli-ppsspp.zip") &&
                    (it.nameWithoutExtension == base || it.name.startsWith("$base."))
            }
            .sortedBy { it.name }
    }

    fun resolve(tag: String, base: String, mode: LocalSaveMode = LocalSaveMode.NORMAL): LocalSave? {
        val files = matchingFiles(tag, base, mode)
        if (files.isEmpty()) return null
        val isBundle = mode == LocalSaveMode.NORMAL && files.size > 1
        val hash = when {
            isBundle -> SaveHasher.hashBundle(files.associateBy { it.name })
            mode == LocalSaveMode.PPSSPP_DIRECTORY_ARCHIVE ->
                SaveHasher.hashZipBundle(files.single()) ?: return null
            else -> SaveHasher.hashFile(files.single())
        }
        return LocalSave(
            files = files,
            isBundle = isBundle,
            sizeBytes = files.sumOf { it.length() },
            modifiedMillis = files.maxOf { it.lastModified() },
            contentHash = hash,
            uploadFileName = when {
                mode == LocalSaveMode.PPSSPP_DIRECTORY_ARCHIVE -> "$base.ppsspp.zip"
                isBundle || mode == LocalSaveMode.STANDALONE_ARCHIVE -> "$base.zip"
                else -> "$base.srm"
            },
        )
    }

    fun bundleToZip(
        tag: String,
        base: String,
        dest: File,
        mode: LocalSaveMode = LocalSaveMode.NORMAL,
    ): File {
        val files = matchingFiles(tag, base, mode)
        if (mode != LocalSaveMode.NORMAL) {
            files.single().copyTo(dest, overwrite = true)
            return dest
        }
        ZipOutputStream(dest.outputStream()).use { zos ->
            for (f in files.sortedBy { it.name }) {
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return dest
    }

    fun applyDownload(
        tag: String,
        base: String,
        downloaded: File,
        mode: LocalSaveMode = LocalSaveMode.NORMAL,
    ) {
        val dir = savesDir(tag).apply { mkdirs() }
        archiveFileName(base, mode)?.let { name ->
            val destination = File(dir, name)
            val part = File(dir, ".part_${java.util.UUID.randomUUID().toString().take(8)}_${destination.name}")
            try {
                downloaded.copyTo(part, overwrite = true)
                if (!part.renameTo(destination)) {
                    part.copyTo(destination, overwrite = true)
                    part.delete()
                }
            } finally {
                part.delete()
            }
            return
        }
        val existing = matchingFiles(tag, base, mode)
        // Stage every incoming file to a temp name first. A failure here never touches the live save,
        // and the per-call token keeps a concurrent apply of the same save from staging through our
        // path and publishing each other's half-written bytes.
        val token = java.util.UUID.randomUUID().toString().take(8)
        val staged = ArrayList<Pair<File, File>>() // dest to part
        try {
            if (isZip(downloaded)) {
                ZipFile(downloaded).use { zf ->
                    for (entry in zf.entries()) {
                        if (entry.isDirectory) continue
                        val dest = File(dir, File(entry.name).name)
                        val part = File(dir, ".part_${token}_${dest.name}")
                        zf.getInputStream(entry).use { ins -> part.outputStream().use { ins.copyTo(it) } }
                        staged.add(dest to part)
                    }
                }
            } else {
                val dest = File(dir, "$base.srm")
                val part = File(dir, ".part_${token}_${dest.name}")
                downloaded.copyTo(part, overwrite = true)
                staged.add(dest to part)
            }
        } catch (t: Throwable) {
            staged.forEach { it.second.delete() }
            throw t
        }
        // Everything staged: drop stale files not in the new set, then swap the staged copies in atomically.
        val keepNames = staged.mapTo(HashSet()) { it.first.name }
        existing.filter { it.name !in keepNames }.forEach { it.delete() }
        staged.forEach { (dest, part) ->
            if (!part.renameTo(dest)) { part.copyTo(dest, overwrite = true); part.delete() }
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { ins ->
        val sig = ByteArray(4)
        ins.read(sig) == 4 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
    }

    private fun archiveFileName(base: String, mode: LocalSaveMode): String? = when (mode) {
        LocalSaveMode.NORMAL -> null
        LocalSaveMode.STANDALONE_ARCHIVE -> "$base.cannoli-standalone.zip"
        LocalSaveMode.PPSSPP_DIRECTORY_ARCHIVE -> "$base.cannoli-ppsspp.zip"
    }
}
