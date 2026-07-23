package dev.cannoli.scorza.libretro

import java.util.Locale

data class SuperMarioWorldExit(
    val event: Int,
    val completed: Boolean,
)

data class SuperMarioWorldLevel(
    val name: String,
    val translevel: Int,
    val normalExit: SuperMarioWorldExit,
    val secretExit: SuperMarioWorldExit? = null,
)

data class SuperMarioWorldWorld(
    val name: String,
    val levels: List<SuperMarioWorldLevel>,
)

data class SuperMarioWorldSnapshot(
    val worlds: List<SuperMarioWorldWorld>,
    val currentTranslevel: Int,
    val completedExitCount: Int,
) {
    val totalExitCount: Int
        get() = worlds.sumOf { world ->
            world.levels.sumOf { level -> 1 + if (level.secretExit != null) 1 else 0 }
        }
}

internal object SuperMarioWorldReader {
    private const val RETRO_MEMORY_SYSTEM_RAM = 2
    private const val EVENT_FLAGS_OFFSET = 0x1F02
    private const val EVENT_FLAGS_SIZE = 14
    private const val GAME_MODE_OFFSET = 0x0100
    private const val CURRENT_LEVEL_OFFSET = 0x13BF
    private const val CURRENT_CHARACTER_X4_OFFSET = 0x0DD6
    private const val OVERWORLD_STATE_OFFSET = 0x1F11
    private const val OVERWORLD_STATE_SIZE = 22
    private const val GRID_POSITION_OFFSET = 14
    private const val LEVEL_NUMBER_TABLE_OFFSET = 0xD000

    fun matches(displayName: String?, fileName: String?): Boolean {
        val identities = listOfNotNull(displayName, fileName).map { value ->
            value
                .substringBeforeLast('.')
                .lowercase(Locale.ROOT)
                .filter(Char::isLetterOrDigit)
        }
        return identities.any { identity ->
            identity == "supermarioworld" ||
                identity.startsWith("supermarioworldu") ||
                identity.startsWith("supermarioworldusa")
        }
    }

    fun read(runner: LibretroRunner, previousTranslevel: Int = -1): SuperMarioWorldSnapshot? {
        val eventFlags = runner.copyMemory(
            RETRO_MEMORY_SYSTEM_RAM,
            EVENT_FLAGS_OFFSET,
            EVENT_FLAGS_SIZE,
        ) ?: return null
        val currentTranslevel = readCurrentTranslevel(runner, previousTranslevel)
        return parse(eventFlags, currentTranslevel)
    }

    private fun readCurrentTranslevel(runner: LibretroRunner, previousTranslevel: Int): Int {
        val gameMode = runner.copyMemory(
            RETRO_MEMORY_SYSTEM_RAM,
            GAME_MODE_OFFSET,
            1,
        )?.firstOrNull()?.toInt()?.and(0xFF) ?: return -1
        if (gameMode in 0x14..0x15) {
            return runner.copyMemory(
                RETRO_MEMORY_SYSTEM_RAM,
                CURRENT_LEVEL_OFFSET,
                1,
            )?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        }
        // Load/fade/prepare modes expose transient map and level indexes. Retain the last
        // trustworthy row until mode 0E (overworld) or mode 14 (live gameplay) is stable.
        if (holdsPreviousTranslevel(gameMode)) return previousTranslevel

        val currentCharacterX4 = runner.copyMemory(
            RETRO_MEMORY_SYSTEM_RAM,
            CURRENT_CHARACTER_X4_OFFSET,
            2,
        )?.u16(0)?.takeIf { it == 0 || it == 4 } ?: return -1
        val currentCharacter = currentCharacterX4 / 4
        val state = runner.copyMemory(
            RETRO_MEMORY_SYSTEM_RAM,
            OVERWORLD_STATE_OFFSET,
            OVERWORLD_STATE_SIZE,
        ) ?: return -1
        val gridOffset = GRID_POSITION_OFFSET + currentCharacter * 4
        val mapId = state.u8(currentCharacter)
        val gridX = state.u16(gridOffset)
        val gridY = state.u16(gridOffset + 2)
        val tileIndex = mapTileIndex(mapId, gridX, gridY).takeIf { it >= 0 } ?: return -1
        return runner.copyMemory(
            RETRO_MEMORY_SYSTEM_RAM,
            LEVEL_NUMBER_TABLE_OFFSET + tileIndex,
            1,
        )?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
    }

    internal fun mapTileIndex(mapId: Int, gridX: Int, gridY: Int): Int {
        if (mapId !in 0..6 || gridX !in 0..31 || gridY !in 0..31) return -1
        var index = (gridX and 0x0F) + 16 * (gridX and 0x10) + ((16 * gridY) and 0xFF)
        if (gridY and 0x10 != 0) index += 512
        if (mapId != 0) index += 0x400
        return index
    }

    internal fun holdsPreviousTranslevel(gameMode: Int): Boolean =
        gameMode != 0x0E && gameMode !in 0x14..0x15

    internal fun parse(eventFlags: ByteArray, currentTranslevel: Int = -1): SuperMarioWorldSnapshot? {
        if (eventFlags.size < EVENT_FLAGS_SIZE) return null
        val worlds = WORLD_DEFINITIONS.map { world ->
            SuperMarioWorldWorld(
                name = world.name,
                levels = world.levels.map { level ->
                    SuperMarioWorldLevel(
                        name = level.name,
                        translevel = level.translevel,
                        normalExit = SuperMarioWorldExit(
                            event = level.normalEvent,
                            completed = eventFlags.hasEvent(level.normalEvent),
                        ),
                        secretExit = level.secretEvent?.let { event ->
                            SuperMarioWorldExit(event, eventFlags.hasEvent(event))
                        },
                    )
                },
            )
        }
        val completed = worlds.sumOf { world ->
            world.levels.sumOf { level ->
                (if (level.normalExit.completed) 1 else 0) +
                    (if (level.secretExit?.completed == true) 1 else 0)
            }
        }
        return SuperMarioWorldSnapshot(worlds, currentTranslevel, completed)
    }

    private fun ByteArray.hasEvent(event: Int): Boolean {
        if (event !in 0..0x6D) return false
        val mask = 0x80 ushr (event and 7)
        return (this[event ushr 3].toInt() and mask) != 0
    }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private data class LevelDefinition(
        val name: String,
        val translevel: Int,
        val normalEvent: Int,
        val secretEvent: Int? = null,
    )

    private data class WorldDefinition(
        val name: String,
        val levels: List<LevelDefinition>,
    )

    private fun level(
        name: String,
        translevel: Int,
        normalEvent: Int,
        secret: Boolean = false,
    ) = LevelDefinition(
        name = name,
        translevel = translevel,
        normalEvent = normalEvent,
        secretEvent = if (secret) normalEvent + 1 else null,
    )

    private val WORLD_DEFINITIONS = listOf(
        WorldDefinition("YOSHI'S ISLAND", listOf(
            level("Yoshi's Island 1", 0x29, 0x01),
            level("Yellow Switch Palace", 0x14, 0x02),
            level("Yoshi's Island 2", 0x2A, 0x03),
            level("Yoshi's Island 3", 0x27, 0x04),
            level("Yoshi's Island 4", 0x26, 0x05),
            level("#1 Iggy's Castle", 0x25, 0x06),
        )),
        WorldDefinition("DONUT PLAINS", listOf(
            level("Donut Plains 1", 0x15, 0x07, secret = true),
            level("Donut Plains 2", 0x09, 0x09, secret = true),
            level("Donut Ghost House", 0x04, 0x0B, secret = true),
            level("Donut Plains 3", 0x05, 0x0D),
            level("Donut Plains 4", 0x06, 0x0E),
            level("#2 Morton's Castle", 0x07, 0x0F),
            level("Donut Secret 1", 0x0A, 0x10, secret = true),
            level("Donut Secret 2", 0x2F, 0x14),
            level("Donut Secret House", 0x13, 0x12, secret = true),
            level("Green Switch Palace", 0x08, 0x28),
        )),
        WorldDefinition("VANILLA DOME", listOf(
            level("Vanilla Dome 1", 0x3E, 0x15, secret = true),
            level("Vanilla Dome 2", 0x3C, 0x17, secret = true),
            level("Vanilla Ghost House", 0x2B, 0x19),
            level("Vanilla Dome 3", 0x2E, 0x1A),
            level("Vanilla Dome 4", 0x3D, 0x1B),
            level("#3 Lemmy's Castle", 0x40, 0x1C),
            level("Vanilla Secret 1", 0x2D, 0x1D, secret = true),
            level("Vanilla Secret 2", 0x01, 0x1F),
            level("Vanilla Secret 3", 0x02, 0x20),
            level("Vanilla Fortress", 0x0B, 0x21),
            level("Red Switch Palace", 0x3F, 0x29),
        )),
        WorldDefinition("TWIN BRIDGES", listOf(
            level("Cheese Bridge Area", 0x0F, 0x25, secret = true),
            level("Soda Lake", 0x11, 0x60),
            level("Cookie Mountain", 0x10, 0x27),
            level("Butter Bridge 1", 0x0C, 0x22),
            level("Butter Bridge 2", 0x0D, 0x23),
            level("#4 Ludwig's Castle", 0x0E, 0x24),
        )),
        WorldDefinition("FOREST OF ILLUSION", listOf(
            level("Forest of Illusion 1", 0x42, 0x2A, secret = true),
            level("Forest of Illusion 2", 0x44, 0x2C, secret = true),
            level("Blue Switch Palace", 0x45, 0x37),
            level("Forest of Illusion 3", 0x47, 0x2E, secret = true),
            level("Forest Ghost House", 0x41, 0x30, secret = true),
            level("Forest of Illusion 4", 0x43, 0x32, secret = true),
            level("Forest Secret Area", 0x46, 0x34),
            level("Forest Fortress", 0x1F, 0x35),
            level("#5 Roy's Castle", 0x20, 0x61),
        )),
        WorldDefinition("CHOCOLATE ISLAND", listOf(
            level("Chocolate Island 1", 0x22, 0x62),
            level("Choco-Ghost House", 0x21, 0x63),
            level("Chocolate Island 2", 0x24, 0x46, secret = true),
            level("Chocolate Secret", 0x3B, 0x4F),
            level("Chocolate Island 3", 0x23, 0x48, secret = true),
            level("Chocolate Fortress", 0x1B, 0x4A),
            level("Chocolate Island 4", 0x1D, 0x4B),
            level("Chocolate Island 5", 0x1C, 0x4C),
            level("#6 Wendy's Castle", 0x1A, 0x4D),
        )),
        WorldDefinition("VALLEY OF BOWSER", listOf(
            level("Sunken Ghost Ship", 0x18, 0x4E),
            level("Valley of Bowser 1", 0x3A, 0x38),
            level("Valley of Bowser 2", 0x39, 0x39, secret = true),
            level("Valley Ghost House", 0x38, 0x3B, secret = true),
            level("Valley of Bowser 3", 0x37, 0x3D),
            level("Valley of Bowser 4", 0x33, 0x3E, secret = true),
            level("Valley Fortress", 0x35, 0x41),
            level("#7 Larry's Castle", 0x34, 0x40),
        )),
        WorldDefinition("STAR WORLD", listOf(
            level("Star World 1", 0x58, 0x51, secret = true),
            level("Star World 2", 0x54, 0x54, secret = true),
            level("Star World 3", 0x56, 0x57, secret = true),
            level("Star World 4", 0x59, 0x5A, secret = true),
            level("Star World 5", 0x5A, 0x5D, secret = true),
        )),
        WorldDefinition("SPECIAL", listOf(
            level("Gnarly", 0x4E, 0x65),
            level("Tubular", 0x4F, 0x66),
            level("Way Cool", 0x50, 0x67),
            level("Awesome", 0x51, 0x68),
            level("Groovy", 0x4C, 0x69),
            level("Mondo", 0x4B, 0x6A),
            level("Outrageous", 0x4A, 0x6B),
            level("Funky", 0x49, 0x6C),
        )),
    )
}
