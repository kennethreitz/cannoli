package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps melonDS cartridge saves in Cannoli's normal per-ROM save layout.
 *
 * melonDS names a save after the .nds file inside an archive, while Cannoli and RomM identify the
 * game by the archive's file name. This bridge maintains one canonical mirror for sync and copies a
 * downloaded save back to the file name melonDS expects. Save states are deliberately not touched.
 */
@Singleton
class MelonDsSaveBridge @Inject constructor(
    private val pathsProvider: CannoliPathsProvider,
    private val platformConfig: PlatformConfig,
) {
    fun supports(tag: String, @Suppress("UNUSED_PARAMETER") emulator: String?, gameKey: String): Boolean {
        if (!tag.equals(NDS_TAG, ignoreCase = true)) return false
        val rom = File(pathsProvider.romDir, gameKey)
        if (!rom.isFile) return false
        val packageName = platformConfig.getGameOverride(rom.absolutePath)?.appPackage
            ?: platformConfig.getSelectedStandaloneAppPackage(tag)
        return packageName in MELONDS_PACKAGES
    }

    fun refresh(tag: String, base: String, gameKey: String): RetroArchMirrorResult {
        val rom = File(pathsProvider.romDir, gameKey)
        val emulatorBase = emulatorSaveBase(rom)
            ?: return RetroArchMirrorResult.Unavailable("could not read the melonDS ROM name")
        val saveDir = File(pathsProvider.root, "Saves/$tag").apply { mkdirs() }
        val source = File(saveDir, "$emulatorBase.sav")
        if (!source.isFile) return RetroArchMirrorResult.Missing
        if (emulatorBase == base) return RetroArchMirrorResult.Ready

        val destination = File(saveDir, "$base.sav")
        saveDir.listFiles().orEmpty()
            .filter { it.isFile && matchesBase(base, it.name) && it != destination }
            .forEach(File::delete)
        copyIfChanged(source, destination)
        return RetroArchMirrorResult.Ready
    }

    fun apply(tag: String, base: String, gameKey: String) {
        val rom = File(pathsProvider.romDir, gameKey)
        val emulatorBase = emulatorSaveBase(rom)
            ?: throw IllegalStateException("could not read the melonDS ROM name")
        val saveDir = File(pathsProvider.root, "Saves/$tag").apply { mkdirs() }
        val canonical = saveDir.listFiles().orEmpty()
            .filter { it.isFile && matchesBase(base, it.name) }
            .maxWithOrNull(compareBy<File>({ it.lastModified() }, { it.length() }))
            ?: throw IllegalStateException("downloaded melonDS save is missing")
        val destination = File(saveDir, "$emulatorBase.sav")
        copyIfChanged(canonical, destination)

        // A single RomM save downloads as <archive-base>.srm. Replace that staging name with the
        // canonical mirror so the next sweep sees exactly one cartridge save, not a fake bundle.
        if (emulatorBase != base) {
            saveDir.listFiles().orEmpty()
                .filter { it.isFile && matchesBase(base, it.name) }
                .forEach(File::delete)
            copyIfChanged(destination, File(saveDir, "$base.sav"))
        } else {
            saveDir.listFiles().orEmpty()
                .filter { it.isFile && matchesBase(base, it.name) && it != destination }
                .forEach(File::delete)
        }
    }

    internal fun emulatorSaveBase(rom: File): String? {
        if (!rom.isFile) return null
        if (!rom.extension.equals("zip", ignoreCase = true)) return rom.nameWithoutExtension
        return runCatching {
            ZipFile(rom).use { zip ->
                zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { File(it.name).name }
                    .firstOrNull { it.endsWith(".nds", ignoreCase = true) }
                    ?.let { File(it).nameWithoutExtension }
            }
        }.getOrNull()
    }

    private fun copyIfChanged(source: File, destination: File) {
        if (
            destination.isFile &&
            source.length() == destination.length() &&
            SaveHasher.hashFile(source) == SaveHasher.hashFile(destination)
        ) {
            destination.setLastModified(source.lastModified())
            return
        }
        val part = File(
            requireNotNull(destination.parentFile),
            ".part_${UUID.randomUUID().toString().take(8)}_${destination.name}",
        )
        try {
            source.copyTo(part, overwrite = true)
            part.setLastModified(source.lastModified())
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                destination.setLastModified(source.lastModified())
                part.delete()
            }
        } finally {
            part.delete()
        }
    }

    private fun matchesBase(base: String, name: String): Boolean =
        File(name).nameWithoutExtension == base || name.startsWith("$base.")

    private companion object {
        const val NDS_TAG = "NDS"
        val MELONDS_PACKAGES = setOf(
            "me.magnum.melonds",
            "me.magnum.melonds.nightly",
            "me.magnum.melondualds",
        )
    }
}
