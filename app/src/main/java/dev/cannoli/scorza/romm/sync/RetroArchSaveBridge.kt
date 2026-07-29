package dev.cannoli.scorza.romm.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.settings.SettingsRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RetroArchMirrorResult {
    data object Ready : RetroArchMirrorResult
    data object Missing : RetroArchMirrorResult
    data class Unavailable(val reason: String) : RetroArchMirrorResult
}

/**
 * Mirrors the public save folders used by the official AArch64 RetroArch build into Cannoli's
 * normal save layout. RomM can then use the same hashing, conflict, backup, and transfer pipeline
 * as embedded Libretro without ever scanning or modifying another game's files.
 */
@Singleton
class RetroArchSaveBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val pathsProvider: CannoliPathsProvider,
    private val platformConfig: PlatformConfig,
    private val launchState: LaunchState,
) {
    private val paths get() = CannoliPaths(pathsProvider.root)

    internal var externalRootOverride: File? = null
    internal var storageAccessOverride: Boolean? = null
    internal var installedOverride: Boolean? = null

    fun isGameActive(): Boolean = launchState.gameActive.value

    fun isInstalled(): Boolean = installedOverride ?: try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                PACKAGE_NAME,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasStorageAccess(): Boolean =
        storageAccessOverride ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun supports(gameKey: String): Boolean {
        if (settings.retroArchPackage != PACKAGE_NAME) return false
        val romFile = File(pathsProvider.romDir, gameKey)
        if (!romFile.isFile) return false
        val tag = gameKey.substringBefore('/')
        val override = platformConfig.getGameOverride(romFile.absolutePath)
        if (override?.appPackage != null) return false
        if (platformConfig.getSelectedStandaloneAppPackage(tag) != null) return false
        val runner = override?.runner ?: platformConfig.getRunnerPreference(tag)
        return when (EmulatorSource.fromRunnerLabel(runner)) {
            EmulatorSource.RetroArch -> true
            EmulatorSource.Internal, EmulatorSource.Standalone -> false
            null -> {
                val core = override?.coreId ?: platformConfig.getCoreName(tag) ?: return false
                !File(context.filesDir, "cores/${core}_android.so").isFile
            }
        }
    }

    fun refreshSaves(tag: String, base: String, gameKey: String): RetroArchMirrorResult =
        refresh(
            gameKey = gameKey,
            sourceDir = File(externalRoot(), SAVES_DIR),
            destinationDir = paths.savesFor(tag),
            matches = { matchesSave(base, it) },
        )

    fun applySaves(tag: String, base: String, gameKey: String) {
        apply(
            gameKey = gameKey,
            sourceDir = paths.savesFor(tag),
            destinationDir = File(externalRoot(), SAVES_DIR),
            matches = { matchesSave(base, it) },
        )
    }

    /**
     * PPSSPP does not expose a cartridge-style SRAM file. Its normal in-game
     * save is a per-title directory below PSP/SAVEDATA, for both embedded
     * Libretro and RetroArch AArch64.
     */
    fun refreshPpsspp(tag: String, base: String, gameKey: String): RetroArchMirrorResult {
        ppssppUnavailableReason(gameKey)?.let { return RetroArchMirrorResult.Unavailable(it) }
        val rom = File(pathsProvider.romDir, gameKey)
        val gameId = PpssppGameIdParser.gameId(rom)
            ?: return RetroArchMirrorResult.Unavailable("could not read PSP game ID from ${rom.name}")
        val archive = File(paths.savesFor(tag), "$base$PPSSPP_ARCHIVE_SUFFIX")
        val temp = File.createTempFile("ppsspp-save", ".zip", paths.savesFor(tag).apply { mkdirs() })
        return try {
            val newest = PpssppSaveBundle.createFromMemstick(
                ppssppMemstickRoot(tag, gameKey),
                gameId,
                temp,
            )
            if (newest == null) {
                archive.delete()
                RetroArchMirrorResult.Missing
            } else {
                val currentHash = archive.takeIf(File::isFile)?.let(SaveHasher::hashZipBundle)
                val nextHash = SaveHasher.hashZipBundle(temp)
                    ?: return RetroArchMirrorResult.Unavailable("PPSSPP save bundle is empty")
                if (currentHash != nextHash) {
                    replaceFile(temp, archive)
                    archive.setLastModified(if (newest > 0L) newest else System.currentTimeMillis())
                }
                RetroArchMirrorResult.Ready
            }
        } catch (t: Throwable) {
            RetroArchMirrorResult.Unavailable(t.message ?: t.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    fun applyPpsspp(tag: String, base: String, gameKey: String) {
        ppssppUnavailableReason(gameKey)?.let { throw IllegalStateException(it) }
        val rom = File(pathsProvider.romDir, gameKey)
        val gameId = PpssppGameIdParser.gameId(rom)
            ?: throw IllegalStateException("could not read PSP game ID from ${rom.name}")
        val archive = File(paths.savesFor(tag), "$base$PPSSPP_ARCHIVE_SUFFIX")
        check(archive.isFile) { "PPSSPP save archive was not staged" }
        PpssppSaveBundle.restoreToMemstick(
            archive,
            ppssppMemstickRoot(tag, gameKey),
            gameId,
        )
    }

    fun refreshStates(tag: String, base: String, gameKey: String): RetroArchMirrorResult =
        refresh(
            gameKey = gameKey,
            sourceDir = File(externalRoot(), STATES_DIR),
            destinationDir = paths.saveStateDir(tag, base),
            matches = { matchesState(base, it) },
        )

    fun applyStates(tag: String, base: String, gameKey: String) {
        apply(
            gameKey = gameKey,
            sourceDir = paths.saveStateDir(tag, base),
            destinationDir = File(externalRoot(), STATES_DIR),
            matches = { matchesState(base, it) },
        )
    }

    private fun refresh(
        gameKey: String,
        sourceDir: File,
        destinationDir: File,
        matches: (String) -> Boolean,
    ): RetroArchMirrorResult {
        unavailableReason(gameKey)?.let { return RetroArchMirrorResult.Unavailable(it) }
        val incoming = sourceDir.listFiles().orEmpty()
            .filter { it.isFile && matches(it.name) }
            .sortedBy { it.name }
        reconcile(incoming, destinationDir, matches)
        return if (incoming.isEmpty()) RetroArchMirrorResult.Missing else RetroArchMirrorResult.Ready
    }

    private fun apply(
        gameKey: String,
        sourceDir: File,
        destinationDir: File,
        matches: (String) -> Boolean,
    ) {
        unavailableReason(gameKey)?.let { throw IllegalStateException(it) }
        val incoming = sourceDir.listFiles().orEmpty()
            .filter { it.isFile && matches(it.name) }
            .sortedBy { it.name }
        reconcile(incoming, destinationDir, matches)
    }

    private fun unavailableReason(gameKey: String): String? = when {
        !supports(gameKey) -> "game is not configured for RetroArch AArch64"
        !isInstalled() -> "RetroArch AArch64 is not installed"
        !hasStorageAccess() -> "all-files access is not available"
        isGameActive() -> "game is currently running"
        else -> null
    }

    private fun ppssppUnavailableReason(gameKey: String): String? =
        if (supports(gameKey)) {
            unavailableReason(gameKey)
        } else {
            "game is currently running".takeIf { isGameActive() }
        }

    private fun ppssppMemstickRoot(tag: String, gameKey: String): File =
        if (supports(gameKey)) File(externalRoot(), SAVES_DIR) else paths.savesFor(tag)

    private fun reconcile(
        incoming: List<File>,
        destinationDir: File,
        matches: (String) -> Boolean,
    ) {
        destinationDir.mkdirs()
        val keep = incoming.mapTo(HashSet()) { it.name }
        destinationDir.listFiles().orEmpty()
            .filter { it.isFile && matches(it.name) && it.name !in keep }
            .forEach { it.delete() }

        val token = UUID.randomUUID().toString().take(8)
        for (source in incoming) {
            val destination = File(destinationDir, source.name)
            if (sameContents(source, destination)) {
                destination.setLastModified(source.lastModified())
                continue
            }
            val part = File(destinationDir, ".part_${token}_${source.name}")
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
    }

    private fun sameContents(a: File, b: File): Boolean =
        b.isFile && a.length() == b.length() && SaveHasher.hashFile(a) == SaveHasher.hashFile(b)

    private fun replaceFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun externalRoot(): File =
        externalRootOverride ?: File(Environment.getExternalStorageDirectory(), RETROARCH_DIR)

    private fun matchesSave(base: String, name: String): Boolean {
        val file = File(name)
        return file.nameWithoutExtension == base || name.startsWith("$base.")
    }

    private fun matchesState(base: String, name: String): Boolean =
        Regex("""^${Regex.escape(base)}\.state(?:\.auto|[1-9])?(?:\.png|\.ra)?$""").matches(name)

    companion object {
        const val PACKAGE_NAME = "com.retroarch.aarch64"
        private const val RETROARCH_DIR = "RetroArch"
        private const val SAVES_DIR = "saves"
        private const val STATES_DIR = "states"
        private const val PPSSPP_ARCHIVE_SUFFIX = ".cannoli-ppsspp.zip"
    }
}
