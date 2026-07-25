package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.model.Rom
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibretroStateBridge @Inject constructor(
    private val pathsProvider: CannoliPathsProvider,
    private val platformConfig: PlatformConfig,
    private val launchState: LaunchState,
) {
    private val paths get() = CannoliPaths(pathsProvider.root)

    fun isGameActive(): Boolean = launchState.gameActive.value

    fun supports(gameKey: String): Boolean = coreIdentity(gameKey) != null

    fun slot(gameKey: String): String =
        "$STATE_SLOT_PREFIX${digest(coreIdentity(gameKey) ?: "unknown").take(12)}"

    fun remoteFileName(gameKey: String, base: String): String? =
        coreIdentity(gameKey)?.let { remoteFileNameForCore(base, it) }

    fun isStateSlot(slot: String): Boolean = slot.startsWith(STATE_SLOT_PREFIX)

    fun refreshArchive(tag: String, base: String, gameKey: String): LocalSave? {
        val core = coreIdentity(gameKey) ?: return null
        val files = stateFiles(tag, base)
        val archive = mirrorArchive(tag, base, core)
        if (files.none { isStatePayload(base, it.name) }) {
            archive.delete()
            return null
        }
        val newest = files.maxOf { it.lastModified() }
        val parent = requireNotNull(archive.parentFile).apply { mkdirs() }
        val temp = File.createTempFile("libretro-states", ".zip", parent)
        try {
            writeArchive(temp, base, core, files)
            if (!archive.isFile || SaveHasher.hashFile(archive) != SaveHasher.hashFile(temp)) {
                replaceFile(temp, archive)
                archive.setLastModified(newest)
            }
        } finally {
            temp.delete()
        }
        return localSave(archive, base, core)
    }

    fun applyArchive(tag: String, base: String, gameKey: String, archive: File) {
        val core = coreIdentity(gameKey)
            ?: throw IllegalStateException("game is not configured for a Libretro core")
        val stage = createStageDirectory()
        try {
            extractAndValidate(archive, stage, base, core)
            val destination = paths.saveStateDir(tag, base).apply { mkdirs() }
            val incoming = stage.listFiles().orEmpty().filter { it.isFile }
            val token = UUID.randomUUID().toString().take(8)
            val staged = incoming.map { source ->
                val target = File(destination, source.name)
                val part = File(destination, ".part_${token}_${source.name}")
                source.copyTo(part, overwrite = true)
                part.setLastModified(source.lastModified())
                target to part
            }
            try {
                val keep = staged.mapTo(HashSet()) { it.first.name }
                stateFiles(tag, base).filter { it.name !in keep }.forEach { it.delete() }
                staged.forEach { (target, part) ->
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        target.setLastModified(part.lastModified())
                        part.delete()
                    }
                }
            } finally {
                staged.forEach { it.second.delete() }
            }
            val mirror = mirrorArchive(tag, base, core)
            archive.copyTo(mirror, overwrite = true)
            mirror.setLastModified(System.currentTimeMillis())
        } finally {
            stage.deleteRecursively()
        }
    }

    fun validateArchive(base: String, gameKey: String, archive: File) {
        val core = coreIdentity(gameKey)
            ?: throw IllegalStateException("game is not configured for a Libretro core")
        val stage = createStageDirectory()
        try {
            extractAndValidate(archive, stage, base, core)
        } finally {
            stage.deleteRecursively()
        }
    }

    fun applyRawState(tag: String, base: String, gameKey: String, state: File) {
        if (coreIdentity(gameKey) == null) {
            throw IllegalStateException("game is not configured for a Libretro core")
        }
        if (!state.isFile || state.length() <= 0L) {
            throw IllegalStateException("server save state is empty")
        }
        if (state.length() > MAX_BYTES) {
            throw IllegalStateException("server save state is too large")
        }
        val destinationDir = paths.saveStateDir(tag, base).apply { mkdirs() }
        val destination = File(destinationDir, "$base.state")
        val part = File(destinationDir, ".part_${UUID.randomUUID().toString().take(8)}_${destination.name}")
        try {
            state.copyTo(part, overwrite = true)
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            // A raw state from another RomM client has no matching screenshot. Keeping the old one
            // would make the slot preview lie about the state that will actually load.
            File(destinationDir, "$base.state.png").delete()
            mirrorArchive(tag, base, requireNotNull(coreIdentity(gameKey))).delete()
        } finally {
            part.delete()
        }
    }

    fun backupCurrent(tag: String, base: String, gameKey: String, keepCount: Int) {
        if (keepCount <= 0) return
        val current = refreshArchive(tag, base, gameKey)?.files?.singleOrNull() ?: return
        val dir = File(paths.backupDir, "SaveStateSync/$tag/$base").apply { mkdirs() }
        current.copyTo(File(dir, "${System.currentTimeMillis()}.zip"), overwrite = true)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name.removeSuffix(".zip").toLongOrNull() ?: 0L }
            ?.drop(keepCount)
            ?.forEach { it.delete() }
    }

    private fun coreIdentity(gameKey: String): String? {
        val romFile = File(pathsProvider.romDir, gameKey)
        if (!romFile.isFile) return null
        val tag = gameKey.substringBefore('/')
        val override = platformConfig.getGameOverride(romFile.absolutePath)
        if (override?.appPackage != null) return null
        if (platformConfig.getSelectedStandaloneAppPackage(tag) != null) return null
        return override?.coreId?.takeIf { it.isNotBlank() }
            ?: platformConfig.getCoreName(tag)?.takeIf { it.isNotBlank() }
    }

    private fun stateFiles(tag: String, base: String): List<File> {
        val dir = paths.saveStateDir(tag, base)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && recognized(base, it.name) }
            .sortedBy { it.name }
    }

    private fun recognized(base: String, name: String): Boolean =
        stateNameRegex(base).matches(name)

    private fun isStatePayload(base: String, name: String): Boolean =
        recognized(base, name) && !name.endsWith(".png") && !name.endsWith(".ra")

    private fun stateNameRegex(base: String) =
        Regex("""^${Regex.escape(base)}\.state(?:\.auto|[1-9])?(?:\.png|\.ra)?$""")

    private fun mirrorArchive(tag: String, base: String, core: String): File {
        val key = digest("$tag/$base/$core")
        return File(paths.configCache, "SaveStateSync/$tag/$key.zip")
    }

    private fun localSave(archive: File, base: String, core: String) = LocalSave(
        files = listOf(archive),
        isBundle = false,
        sizeBytes = archive.length(),
        modifiedMillis = archive.lastModified(),
        contentHash = SaveHasher.hashFile(archive),
        uploadFileName = remoteFileNameForCore(base, core),
    )

    private fun writeArchive(
        destination: File,
        base: String,
        core: String,
        files: List<File>,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(stableEntry(MANIFEST_NAME))
            zip.write(manifest(base, core, files).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (file in files) {
                zip.putNextEntry(stableEntry("files/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun extractAndValidate(
        archive: File,
        stage: File,
        base: String,
        core: String,
    ) {
        var manifestText: String? = null
        var entries = 0
        var bytes = 0L
        var hasState = false
        ZipFile(archive).use { zip ->
            val all = zip.entries().toList()
            for (entry in all) {
                if (entry.isDirectory) continue
                entries++
                if (entries > MAX_ENTRIES) throw IllegalStateException("save-state archive has too many files")
                if (entry.name == MANIFEST_NAME) {
                    manifestText = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    continue
                }
                if (!entry.name.startsWith("files/")) {
                    throw IllegalStateException("unrecognized save-state archive entry")
                }
                val name = entry.name.removePrefix("files/")
                if (name.contains('/') || !recognized(base, name)) {
                    throw IllegalStateException("unsafe save-state archive path")
                }
                if (isStatePayload(base, name)) hasState = true
                val output = File(stage, name)
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > MAX_BYTES) throw IllegalStateException("save-state archive is too large")
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        val modifiedTimes = validateManifest(
            manifestText ?: throw IllegalStateException("save-state archive has no manifest"),
            base,
            core,
        )
        for ((name, modifiedMillis) in modifiedTimes) {
            File(stage, name).takeIf { it.isFile }?.setLastModified(modifiedMillis)
        }
        if (!hasState) throw IllegalStateException("save-state archive contains no states")
    }

    private fun manifest(base: String, coreIdentity: String, files: List<File>): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val game = encoder.encodeToString(base.toByteArray(Charsets.UTF_8))
        val core = encoder.encodeToString(coreIdentity.toByteArray(Charsets.UTF_8))
        return buildString {
            appendLine("format=$ARCHIVE_FORMAT")
            appendLine("game=$game")
            appendLine("core=$core")
            for (file in files.sortedBy { it.name }) {
                val name = encoder.encodeToString(file.name.toByteArray(Charsets.UTF_8))
                appendLine("mtime.$name=${file.lastModified()}")
            }
        }
    }

    private fun validateManifest(
        text: String,
        base: String,
        coreIdentity: String,
    ): Map<String, Long> {
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toList()
        val values = fields.toMap()
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val expectedGame = encoder.encodeToString(base.toByteArray(Charsets.UTF_8))
        val expectedCore = encoder.encodeToString(coreIdentity.toByteArray(Charsets.UTF_8))
        val format = values["format"]?.toIntOrNull()
        if (format !in setOf(1, ARCHIVE_FORMAT) ||
            values["game"] != expectedGame ||
            values["core"] != expectedCore
        ) {
            throw IllegalStateException("server save states belong to a different game or core")
        }
        if (format == 1) return emptyMap()
        val decoder = Base64.getUrlDecoder()
        return fields.asSequence()
            .filter { it.first.startsWith("mtime.") }
            .associate { (key, value) ->
                val name = runCatching {
                    String(decoder.decode(key.removePrefix("mtime.")), Charsets.UTF_8)
                }.getOrElse {
                    throw IllegalStateException("save-state archive has invalid timestamps")
                }
                if (!recognized(base, name)) {
                    throw IllegalStateException("save-state archive has invalid timestamps")
                }
                val modifiedMillis = value.toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw IllegalStateException("save-state archive has invalid timestamps")
                name to modifiedMillis
            }
    }

    private fun createStageDirectory(): File {
        val parent = File(paths.configCache, "SaveStateSync").apply { mkdirs() }
        val marker = File.createTempFile("state-restore", ".tmp", parent)
        marker.delete()
        check(marker.mkdir()) { "could not create save-state staging directory" }
        return marker
    }

    private fun stableEntry(name: String) = ZipEntry(name).apply { time = 0L }

    private fun replaceFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun remoteFileNameForCore(base: String, core: String): String =
        "$base.cannoli-${digest(core).take(12)}.statebundle"

    companion object {
        const val STATE_SLOT_PREFIX = "cannoli-savestates-"
        private const val MANIFEST_NAME = "cannoli-libretro-states.txt"
        private const val ARCHIVE_FORMAT = 2
        private const val MAX_ENTRIES = 64
        private const val MAX_BYTES = 1024L * 1024L * 1024L
    }
}
