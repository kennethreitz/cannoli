package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.config.EmulatorChoice
import dev.cannoli.scorza.config.EmulatorSource
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.model.Rom
import java.io.File

object RomKeys {
    fun relativeKey(rom: File, romRoot: File): String =
        rom.absolutePath.removePrefix(romRoot.absolutePath).removePrefix(File.separator)

    // A per-game override wins over the platform choice. Standalone apps are named by their
    // curated label so Citra MMJ does not collapse into the platform's bundled core.
    // PPSSPP's directory saves sync under a distinct identity from the libretro core.
    // ifEmpty matters: a standalone override stores an empty coreId, which is non-null, so the
    // elvis never fired and the Save Slots header rendered a blank emulator name.
    fun coreDisplayNameFor(
        rom: Rom,
        platformConfig: PlatformConfig,
        override: EmulatorChoice? = null,
    ): String? {
        if (override?.source == EmulatorSource.Standalone) {
            val app = override.appPackage
                ?: platformConfig.getSelectedStandaloneAppPackage(rom.platformTag)
            if (app != null) return standaloneSyncIdentity(app, platformConfig)
        } else if (override == null) {
            platformConfig.getSelectedStandaloneAppPackage(rom.platformTag)?.let { app ->
                return standaloneSyncIdentity(app, platformConfig)
            }
        }
        val coreId = override?.coreId?.ifEmpty { null }
            ?: platformConfig.getCoreName(rom.platformTag)
            ?: return null
        return platformConfig.getCoreDisplayName(coreId)
    }

    private val STANDALONE_SYNC_IDENTITIES = mapOf(
        "org.ppsspp.ppsspp" to "PPSSPP (Standalone)",
        "org.ppsspp.ppssppgold" to "PPSSPP (Standalone)",
    )

    private fun standaloneSyncIdentity(packageName: String, platformConfig: PlatformConfig): String =
        STANDALONE_SYNC_IDENTITIES[packageName] ?: platformConfig.getAppDisplayName(packageName)
}
