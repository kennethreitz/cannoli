package dev.cannoli.scorza.romm.sync

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The interoperable PPSSPP bundle used by OpenVault and Cannoli.
 *
 * A bundle is a plain ZIP rooted at PSP/SAVEDATA and contains only the save
 * directories belonging to one PSP DISC_ID. ZIP timestamps and compression
 * details are intentionally not part of the logical save hash.
 */
object PpssppSaveBundle {
    const val SAVEDATA_PATH = "PSP/SAVEDATA"

    /**
     * Writes every save directory belonging to [expectedGameId] from a PPSSPP
     * memory-stick root. Returns the newest source timestamp, or null when this
     * game has no save data.
     */
    fun createFromMemstick(
        memstickRoot: File,
        expectedGameId: String,
        destination: File,
    ): Long? {
        val savedata = File(memstickRoot, SAVEDATA_PATH)
        val files = matchingDirectories(savedata, expectedGameId)
            .flatMap { directory ->
                directory.walkTopDown().filter(File::isFile).map { file ->
                    file to "$SAVEDATA_PATH/${file.relativeTo(savedata).invariantSeparatorsPath}"
                }.toList()
            }
            .sortedBy { it.second }
        if (files.isEmpty()) return null
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for ((file, relativePath) in files) {
                zip.putNextEntry(ZipEntry(relativePath).apply { time = 0L })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.maxOf { it.first.lastModified() }
    }

    /**
     * Validates and atomically replaces this game's directories while leaving
     * all other PPSSPP games untouched.
     */
    fun restoreToMemstick(
        archive: File,
        memstickRoot: File,
        expectedGameId: String,
    ) {
        val stage = createTempDir(memstickRoot)
        try {
            val incomingDirectories = extractAndValidate(archive, stage, expectedGameId)
            val savedata = File(memstickRoot, SAVEDATA_PATH)
            check(savedata.mkdirs() || savedata.isDirectory) {
                "cannot create PPSSPP SAVEDATA directory"
            }
            val existing = matchingDirectories(savedata, expectedGameId)
                .associateBy { it.name.lowercase() }
            val sourceRoot = File(stage, SAVEDATA_PATH)
            for (name in incomingDirectories.sorted()) {
                replaceDirectory(
                    source = File(sourceRoot, name),
                    destination = File(savedata, name),
                )
            }
            val incomingKeys = incomingDirectories.mapTo(HashSet()) { it.lowercase() }
            existing.filterKeys { it !in incomingKeys }.values.forEach { stale ->
                check(stale.deleteRecursively()) {
                    "cannot remove stale PPSSPP save directory ${stale.name}"
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    fun matchesSaveDirectory(
        directoryName: String,
        expectedGameId: String,
        paramSfo: ByteArray? = null,
    ): Boolean {
        val expected = PpssppParamSfo.normalizeGameId(expectedGameId) ?: return false
        val embedded = paramSfo?.let(PpssppParamSfo::discId)
        return if (embedded != null) {
            embedded == expected
        } else {
            directoryName.uppercase().startsWith(expected)
        }
    }

    /**
     * Extracts a client-compatible bundle into [stage] and returns its
     * top-level SAVEDATA directory names.
     */
    fun extractAndValidate(
        archive: File,
        stage: File,
        expectedGameId: String,
    ): Set<String> {
        val expected = PpssppParamSfo.normalizeGameId(expectedGameId)
            ?: throw IllegalStateException("invalid PSP game ID")
        val stageRoot = stage.canonicalFile
        val seenPaths = HashSet<String>()
        var entryCount = 0
        var extractedBytes = 0L
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw IllegalStateException("PPSSPP save bundle has too many files")
                }
                val relative = normalizedEntryPath(entry.name)
                if (!relative.startsWith("$SAVEDATA_PATH/")) {
                    throw IllegalStateException("PPSSPP save bundle must be rooted at $SAVEDATA_PATH")
                }
                val suffix = relative.removePrefix("$SAVEDATA_PATH/")
                if ('/' !in suffix) {
                    throw IllegalStateException("PPSSPP save bundle entry is outside a game save directory")
                }
                if (!seenPaths.add(relative)) {
                    throw IllegalStateException("PPSSPP save bundle contains duplicate entries")
                }
                val output = File(stageRoot, relative).canonicalFile
                if (!output.path.startsWith(stageRoot.path + File.separator)) {
                    throw IllegalStateException("unsafe PPSSPP save bundle path")
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
                                throw IllegalStateException("PPSSPP save bundle is too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        if (entryCount == 0) throw IllegalStateException("PPSSPP save bundle is empty")
        val savedata = File(stageRoot, SAVEDATA_PATH)
        val directories = savedata.listFiles { file -> file.isDirectory }.orEmpty()
        if (directories.isEmpty()) throw IllegalStateException("PPSSPP save bundle has no save directories")
        for (directory in directories) {
            val param = File(directory, "PARAM.SFO")
                .takeIf { it.isFile && it.length() <= MAX_PARAM_SFO_BYTES }
                ?.readBytes()
            if (!matchesSaveDirectory(directory.name, expected, param)) {
                throw IllegalStateException(
                    "PPSSPP save directory ${directory.name} does not belong to $expected",
                )
            }
        }
        return directories.mapTo(LinkedHashSet()) { it.name }
    }

    private fun normalizedEntryPath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) {
            throw IllegalStateException("unsafe PPSSPP save bundle path")
        }
        val components = path.split('/')
        if (components.any { it.isBlank() || it == "." || it == ".." }) {
            throw IllegalStateException("unsafe PPSSPP save bundle path")
        }
        return components.joinToString("/")
    }

    private fun matchingDirectories(savedata: File, expectedGameId: String): List<File> =
        savedata.listFiles { file -> file.isDirectory }.orEmpty()
            .filter { directory ->
                val param = File(directory, "PARAM.SFO")
                    .takeIf { it.isFile && it.length() <= MAX_PARAM_SFO_BYTES }
                    ?.readBytes()
                matchesSaveDirectory(directory.name, expectedGameId, param)
            }
            .sortedBy { it.name }

    private fun createTempDir(memstickRoot: File): File {
        val preferredParent = File(memstickRoot, SAVEDATA_PATH).takeIf { it.isDirectory }
            ?: memstickRoot.takeIf { it.isDirectory }
            ?: File(requireNotNull(System.getProperty("java.io.tmpdir")))
        return File(preferredParent, ".cannoli-ppsspp-${UUID.randomUUID()}").also {
            check(it.mkdirs()) { "cannot create PPSSPP restore staging directory" }
        }
    }

    private fun replaceDirectory(source: File, destination: File) {
        val parent = requireNotNull(destination.parentFile)
        val token = UUID.randomUUID().toString()
        val staged = File(parent, ".cannoli-restore-$token")
        val backup = File(parent, ".cannoli-backup-$token")
        check(source.copyRecursively(staged, overwrite = true)) {
            "cannot stage PPSSPP save directory ${destination.name}"
        }
        var backedUp = false
        try {
            if (destination.exists()) {
                check(destination.renameTo(backup)) {
                    "cannot stage existing PPSSPP save directory ${destination.name}"
                }
                backedUp = true
            }
            if (!staged.renameTo(destination)) {
                if (backedUp) backup.renameTo(destination)
                throw IllegalStateException(
                    "cannot publish PPSSPP save directory ${destination.name}",
                )
            }
            if (backedUp) {
                check(backup.deleteRecursively()) {
                    "cannot remove old PPSSPP save directory ${destination.name}"
                }
            }
        } finally {
            staged.deleteRecursively()
            if (backup.exists() && !destination.exists()) backup.renameTo(destination)
        }
    }

    private const val MAX_ARCHIVE_ENTRIES = 10_000
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_PARAM_SFO_BYTES = 1024L * 1024L
}
