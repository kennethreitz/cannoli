package dev.cannoli.scorza.romm.sync

enum class StandaloneSaveKind(
    val platformTag: String,
    val emulatorName: String,
    val packageName: String,
    val documentAuthority: String,
    val providerRootId: String? = "root",
    val initialDocumentId: String? = null,
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
    ),
    VITA3K(
        platformTag = "PSVITA",
        emulatorName = "Vita3K",
        packageName = "org.vita3k.emulator",
        documentAuthority = "com.android.externalstorage.documents",
        providerRootId = null,
        initialDocumentId = "primary:Vita3K/vita",
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
