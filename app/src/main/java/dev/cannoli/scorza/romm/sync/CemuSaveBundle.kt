package dev.cannoli.scorza.romm.sync

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Restores either Cannoli's portable Cemu save layout or a native desktop Cemu
 * MLC bundle into a per-title staging directory.
 *
 * Cannoli archives a marker plus `save/…`. RetroVault's native representation
 * is rooted at `usr/save/<title-high>/<title-low>/…` (and older tools may keep
 * the same tree below `mlc01/`). Only the requested title directory is ever
 * extracted from a native MLC archive.
 */
object CemuSaveBundle {
    const val MANIFEST_NAME = "cannoli-standalone-save.txt"

    fun extractAndValidate(
        archive: File,
        stage: File,
        expectedTitleId: String,
    ) {
        val titleId = normalizeTitleId(expectedTitleId)
        val stageRoot = stage.canonicalFile
        val expectedManifest = "format=1\nemulator=CEMU\ntitle_id=${titleId.uppercase()}\n"
        var extractedBytes = 0L
        val extractedPaths = HashSet<String>()

        ZipFile(archive).use { zip ->
            val fileEntries = zip.entries().toList().filterNot(ZipEntry::isDirectory)
            if (fileEntries.isEmpty()) throw IllegalStateException("Cemu save bundle is empty")
            if (fileEntries.size > MAX_ARCHIVE_ENTRIES) {
                throw IllegalStateException("Cemu save bundle has too many files")
            }

            val manifestEntries = fileEntries.filter { normalizedEntryPath(it.name) == MANIFEST_NAME }
            if (manifestEntries.size > 1) {
                throw IllegalStateException("Cemu save bundle contains duplicate entries")
            }
            val portable = manifestEntries.singleOrNull()?.let { entry ->
                val manifest = zip.getInputStream(entry).use {
                    it.readAtMost(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                }
                if (manifest != expectedManifest) {
                    throw IllegalStateException("server save belongs to a different emulator or game")
                }
                true
            } ?: false

            val nativePrefixes = listOf(
                "usr/save/${titleId.take(8)}/${titleId.takeLast(8)}/",
                "mlc01/usr/save/${titleId.take(8)}/${titleId.takeLast(8)}/",
            )

            for (entry in fileEntries) {
                val path = normalizedEntryPath(entry.name)
                if (path == MANIFEST_NAME) continue

                val relative = if (portable) {
                    if (!path.startsWith(PORTABLE_PREFIX)) {
                        throw IllegalStateException("unrecognized Cemu save bundle entry")
                    }
                    path.removePrefix(PORTABLE_PREFIX)
                } else {
                    val prefix = nativePrefixes.firstOrNull { path.startsWith(it) }
                        ?: continue // Ignore unrelated files in a native MLC bundle.
                    path.removePrefix(prefix)
                }
                if (relative.isBlank()) continue
                if (!extractedPaths.add(relative)) {
                    throw IllegalStateException("Cemu save bundle contains duplicate entries")
                }

                val output = File(stageRoot, relative).canonicalFile
                if (!output.path.startsWith(stageRoot.path + File.separator)) {
                    throw IllegalStateException("unsafe Cemu save bundle path")
                }
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            extractedBytes += count
                            if (extractedBytes > MAX_ARCHIVE_BYTES) {
                                throw IllegalStateException("Cemu save bundle is too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }

        if (extractedPaths.isEmpty()) {
            throw IllegalStateException("Cemu save bundle has no data for title ${titleId.uppercase()}")
        }
    }

    private fun normalizeTitleId(titleId: String): String {
        val normalized = titleId.replace("-", "").lowercase()
        if (normalized.length != 16 || normalized.any { !it.isDigit() && it !in 'a'..'f' }) {
            throw IllegalStateException("invalid Cemu title ID")
        }
        return normalized
    }

    private fun normalizedEntryPath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) {
            throw IllegalStateException("unsafe Cemu save bundle path")
        }
        val components = path.split('/')
        if (components.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalStateException("unsafe Cemu save bundle path")
        }
        return components.joinToString("/")
    }

    private fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            check(total <= maxBytes) { "Cemu save bundle manifest is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val PORTABLE_PREFIX = "save/"
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
}
