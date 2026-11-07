package dev.cannoli.scorza.romm.sync

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Restores Vita3K saves produced by Cannoli, RetroVault, or a native Vita3K
 * savedata export into one title-scoped staging directory.
 *
 * Cannoli's portable representation contains a title marker plus `save/...`.
 * RetroVault also accepts an older representation whose entries are rooted
 * directly at the title's save directory, and some tools retain the complete
 * `ux0/user/00/savedata/<title-id>/...` prefix. Supporting all three layouts
 * lets the same in-game save move between Android and macOS without requiring
 * a one-off migration.
 */
object Vita3KSaveBundle {
    const val MANIFEST_NAME = "cannoli-standalone-save.txt"

    fun extractAndValidate(
        archive: File,
        stage: File,
        expectedTitleId: String,
    ) {
        val titleId = normalizeTitleId(expectedTitleId)
        val expectedManifest = "format=1\nemulator=VITA3K\ntitle_id=$titleId\n"
        val stageRoot = stage.canonicalFile
        var extractedBytes = 0L
        val extractedPaths = HashSet<String>()

        ZipFile(archive).use { zip ->
            val fileEntries = zip.entries().toList().filterNot(ZipEntry::isDirectory)
            if (fileEntries.isEmpty()) throw IllegalStateException("Vita3K save bundle is empty")
            if (fileEntries.size > MAX_ARCHIVE_ENTRIES) {
                throw IllegalStateException("Vita3K save bundle has too many files")
            }

            // Normalize every path before deciding which layout is present so an unsafe entry
            // cannot hide in a native bundle outside the title directory we intend to restore.
            val entries = fileEntries.map { it to normalizedEntryPath(it.name) }
            val manifests = entries.filter { it.second == MANIFEST_NAME }
            if (manifests.size > 1) {
                throw IllegalStateException("Vita3K save bundle contains duplicate entries")
            }
            val portable = manifests.singleOrNull()?.let { (entry, _) ->
                val manifest = zip.getInputStream(entry).use {
                    it.readAtMost(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                }
                if (manifest != expectedManifest) {
                    throw IllegalStateException("server save belongs to a different emulator or game")
                }
                true
            } ?: false

            val nativePrefixes = listOf(
                "ux0/user/00/savedata/$titleId/",
                "vita/ux0/user/00/savedata/$titleId/",
            )
            val hasNativeRoot = entries.any { (_, path) ->
                path.startsWith("ux0/user/00/savedata/") ||
                    path.startsWith("vita/ux0/user/00/savedata/")
            }

            for ((entry, path) in entries) {
                if (path == MANIFEST_NAME) continue
                val relative = when {
                    portable -> {
                        if (!path.startsWith(PORTABLE_PREFIX)) {
                            throw IllegalStateException("unrecognized Vita3K save bundle entry")
                        }
                        path.removePrefix(PORTABLE_PREFIX)
                    }
                    hasNativeRoot -> {
                        val prefix = nativePrefixes.firstOrNull(path::startsWith)
                            ?: continue // Ignore other titles in a full savedata export.
                        path.removePrefix(prefix)
                    }
                    else -> path // RetroVault's older title-directory bundle.
                }
                if (relative.isBlank()) continue
                if (!extractedPaths.add(relative)) {
                    throw IllegalStateException("Vita3K save bundle contains duplicate entries")
                }

                val output = File(stageRoot, relative).canonicalFile
                if (!output.path.startsWith(stageRoot.path + File.separator)) {
                    throw IllegalStateException("unsafe Vita3K save bundle path")
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
                                throw IllegalStateException("Vita3K save bundle is too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }

        if (extractedPaths.isEmpty()) {
            throw IllegalStateException("Vita3K save bundle has no data for title $titleId")
        }
    }

    private fun normalizeTitleId(titleId: String): String {
        val normalized = titleId.trim().uppercase()
        if (normalized.length != 9 || normalized.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
            throw IllegalStateException("invalid Vita3K title ID")
        }
        return normalized
    }

    private fun normalizedEntryPath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) {
            throw IllegalStateException("unsafe Vita3K save bundle path")
        }
        val components = path.split('/')
        if (components.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalStateException("unsafe Vita3K save bundle path")
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
            check(total <= maxBytes) { "Vita3K save bundle manifest is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private const val PORTABLE_PREFIX = "save/"
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
}
