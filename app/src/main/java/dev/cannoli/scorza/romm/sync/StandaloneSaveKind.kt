package dev.cannoli.scorza.romm.sync

enum class StandaloneSaveKind(
    val platformTags: Set<String>,
    val emulatorName: String,
    val packageName: String,
    val documentAuthority: String,
    val providerRootId: String? = "root",
    val initialDocumentId: String? = null,
    private val emulatorAliases: Set<String> = emptySet(),
    private val packageAliases: Set<String> = emptySet(),
) {
    CITRA_MMJ(
        platformTags = setOf("3DS"),
        emulatorName = "Citra MMJ",
        packageName = "org.citra.emu",
        documentAuthority = "org.citra.emu.userpathprovider",
    ),
    CEMU(
        platformTags = setOf("WIIU"),
        emulatorName = "Cemu",
        packageName = "info.cemu.cemu",
        documentAuthority = "info.cemu.cemu.provider",
    ),
    VITA3K(
        platformTags = setOf("PSVITA"),
        emulatorName = "Vita3K",
        packageName = "org.vita3k.emulator",
        documentAuthority = "com.android.externalstorage.documents",
        providerRootId = null,
        initialDocumentId = "primary:Vita3K/vita",
    ),
    DOLPHIN(
        platformTags = setOf("GC", "WII"),
        emulatorName = "Dolphin",
        packageName = "org.dolphinemu.dolphinemu",
        documentAuthority = "org.dolphinemu.dolphinemu.user",
    ),
    PPSSPP(
        platformTags = setOf("PSP"),
        emulatorName = "PPSSPP (Standalone)",
        packageName = "org.ppsspp.ppsspp",
        documentAuthority = "com.android.externalstorage.documents",
        providerRootId = null,
        initialDocumentId = "primary:",
        emulatorAliases = setOf("PPSSPP Gold"),
        packageAliases = setOf("org.ppsspp.ppssppgold"),
    );

    val platformTag: String get() = platformTags.first()
    val emulatorNames: Set<String> get() = setOf(emulatorName) + emulatorAliases
    val packageNames: Set<String> get() = setOf(packageName) + packageAliases

    companion object {
        fun forGame(platformTag: String, emulator: String?): StandaloneSaveKind? =
            entries.firstOrNull {
                it.platformTags.any { tag -> tag.equals(platformTag, ignoreCase = true) } &&
                    it.emulatorNames.any { name -> name.equals(emulator, ignoreCase = true) }
            }

        fun fromAuthority(authority: String?): StandaloneSaveKind? =
            entries.firstOrNull { it.documentAuthority == authority }
    }
}
