package dev.cannoli.scorza.romm.sync

enum class StandaloneSaveKind(
    val platformTag: String,
    val emulatorName: String,
    val packageName: String,
    val documentAuthority: String,
) {
    CITRA_MMJ(
        platformTag = "3DS",
        emulatorName = "Citra MMJ",
        packageName = "org.citra.emu",
        documentAuthority = "org.citra.emu.userpathprovider",
    ),
    CEMU(
        platformTag = "WIIU",
        emulatorName = "Cemu",
        packageName = "info.cemu.cemu",
        documentAuthority = "info.cemu.cemu.provider",
    );

    companion object {
        fun forGame(platformTag: String, emulator: String?): StandaloneSaveKind? =
            entries.firstOrNull {
                it.platformTag.equals(platformTag, ignoreCase = true) &&
                    it.emulatorName.equals(emulator, ignoreCase = true)
            }

        fun fromAuthority(authority: String?): StandaloneSaveKind? =
            entries.firstOrNull { it.documentAuthority == authority }
    }
}
