package dev.cannoli.scorza.libretro

import java.util.Locale

data class SotnMapSnapshot(
    val castleMap: ByteArray,
    val playerTileX: Int,
    val playerTileY: Int,
    val invertedCastle: Boolean,
    val roomCount: Int = 0,
    val playerLevel: Int = 0,
    val experience: Int = 0,
) {
    val hasExploredRooms: Boolean
        get() {
            if (roomCount <= 0) return false
            val castleOffset = if (invertedCastle) CASTLE_MAP_BYTES / 2 else 0
            val castleEnd = castleOffset + CASTLE_MAP_BYTES / 2
            return castleMap
                .asSequence()
                .drop(castleOffset)
                .take(castleEnd - castleOffset)
                .any { it.toInt() and VISITED_ROOM_BITS != 0 }
        }

    val explorationPercent: Double
        get() = roomCount * 100.0 / ROOMS_PER_CASTLE

    val experienceToNextLevel: Int?
        get() = when (playerLevel) {
            in 1 until MAX_PLAYER_LEVEL ->
                (EXPERIENCE_THRESHOLDS[playerLevel + 1] - experience).coerceAtLeast(0)
            MAX_PLAYER_LEVEL -> 0
            else -> null
        }

    fun isVisited(tileX: Int, tileY: Int): Boolean {
        return hasMapBit(tileX, tileY, VISITED_ROOM_BIT)
    }

    fun isBlueprintMapped(tileX: Int, tileY: Int): Boolean {
        return isVisited(tileX, tileY) || hasMapBit(tileX, tileY, BLUEPRINT_ROOM_BIT)
    }

    private fun hasMapBit(tileX: Int, tileY: Int, roomBit: Int): Boolean {
        if (tileX !in MAP_TILES || tileY !in MAP_TILES) return false
        val castleOffset = if (invertedCastle) CASTLE_MAP_BYTES / 2 else 0
        val index = castleOffset + (tileX shr 2) + (tileY * MAP_ROW_BYTES)
        val mask = roomBit shl ((3 - (tileX and 3)) * 2)
        return castleMap[index].toInt() and mask != 0
    }

    companion object {
        const val CASTLE_MAP_BYTES = 0x800
        const val MAP_ROW_BYTES = 16
        const val ROOMS_PER_CASTLE = 942
        const val MAX_PLAYER_LEVEL = 99
        private const val VISITED_ROOM_BIT = 0x01
        private const val BLUEPRINT_ROOM_BIT = 0x02
        private const val VISITED_ROOM_BITS = 0x55
        val MAP_TILES = 0 until 64
        private val EXPERIENCE_THRESHOLDS = intArrayOf(
            0, 0, 100, 250, 450, 700, 1_000, 1_350, 1_750, 2_200,
            2_700, 3_250, 3_850, 4_500, 5_200, 5_950, 6_750, 7_600, 8_500,
            9_450, 10_450, 11_700, 13_200, 15_100, 17_500, 20_400, 23_700,
            27_200, 30_900, 35_000, 39_500, 44_500, 50_000, 56_000, 61_500,
            68_500, 76_000, 84_000, 92_500, 101_500, 110_000, 120_000,
            130_000, 140_000, 150_000, 160_000, 170_000, 180_000, 190_000,
            200_000, 210_000, 222_000, 234_000, 246_000, 258_000, 270_000,
            282_000, 294_000, 306_000, 318_000, 330_000, 344_000, 358_000,
            372_000, 386_000, 400_000, 414_000, 428_000, 442_000, 456_000,
            470_000, 486_000, 502_000, 518_000, 534_000, 550_000, 566_000,
            582_000, 598_000, 614_000, 630_000, 648_000, 666_000, 684_000,
            702_000, 720_000, 738_000, 756_000, 774_000, 792_000, 810_000,
            830_000, 850_000, 870_000, 890_000, 910_000, 930_000, 950_000,
            970_000, 999_999,
        )
    }
}

internal object SotnMapReader {
    private const val RETRO_MEMORY_SYSTEM_RAM = 2
    private const val CASTLE_MAP_OFFSET = 0x6BB74
    private const val ROOM_COUNT_OFFSET = 0x3C760
    private const val TILEMAP_POSITION_OFFSET = 0x730B0
    private const val PLAYER_POSITION_OFFSET = 0x973F0
    private const val STAGE_ID_OFFSET = 0x974A0
    private const val PLAYER_LEVEL_OFFSET = 0x97BE8
    private const val PLAYER_PROGRESS_BYTES = 8
    private const val INVERTED_CASTLE_FLAG = 0x20

    fun matches(displayName: String?, fileName: String?): Boolean {
        val identity = listOfNotNull(displayName, fileName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return identity.contains("castlevania") &&
            (identity.contains("symphony of the night") || identity.contains("sotn"))
    }

    fun read(runner: LibretroRunner): SotnMapSnapshot? {
        val roomCount = runner.copyMemory(
            memoryId = RETRO_MEMORY_SYSTEM_RAM,
            offset = ROOM_COUNT_OFFSET,
            length = Int.SIZE_BYTES,
        ) ?: return null
        val castleMap = runner.copyMemory(
            memoryId = RETRO_MEMORY_SYSTEM_RAM,
            offset = CASTLE_MAP_OFFSET,
            length = SotnMapSnapshot.CASTLE_MAP_BYTES,
        ) ?: return null
        val tilemapPosition = runner.copyMemory(
            memoryId = RETRO_MEMORY_SYSTEM_RAM,
            offset = TILEMAP_POSITION_OFFSET,
            length = 8,
        ) ?: return null
        val playerState = runner.copyMemory(
            memoryId = RETRO_MEMORY_SYSTEM_RAM,
            offset = PLAYER_POSITION_OFFSET,
            length = STAGE_ID_OFFSET - PLAYER_POSITION_OFFSET + Int.SIZE_BYTES,
        ) ?: return null
        val playerProgress = runner.copyMemory(
            memoryId = RETRO_MEMORY_SYSTEM_RAM,
            offset = PLAYER_LEVEL_OFFSET,
            length = PLAYER_PROGRESS_BYTES,
        ) ?: return null
        return parse(castleMap, tilemapPosition, playerState, roomCount, playerProgress)
    }

    internal fun parse(
        castleMap: ByteArray,
        tilemapPosition: ByteArray,
        playerState: ByteArray,
        roomCount: ByteArray = ByteArray(Int.SIZE_BYTES),
        playerProgress: ByteArray = ByteArray(PLAYER_PROGRESS_BYTES),
    ): SotnMapSnapshot? {
        if (castleMap.size != SotnMapSnapshot.CASTLE_MAP_BYTES ||
            tilemapPosition.size < 8 ||
            playerState.size < STAGE_ID_OFFSET - PLAYER_POSITION_OFFSET + Int.SIZE_BYTES ||
            roomCount.size < Int.SIZE_BYTES ||
            playerProgress.size < PLAYER_PROGRESS_BYTES
        ) return null

        val tileX = readInt32(tilemapPosition, 0) + (readInt32(playerState, 0) shr 8)
        val tileY = readInt32(tilemapPosition, 4) + (readInt32(playerState, 4) shr 8)
        if (tileX !in SotnMapSnapshot.MAP_TILES || tileY !in SotnMapSnapshot.MAP_TILES) return null
        val stageId = readInt32(playerState, STAGE_ID_OFFSET - PLAYER_POSITION_OFFSET)
        return SotnMapSnapshot(
            castleMap = castleMap,
            playerTileX = tileX,
            playerTileY = tileY,
            invertedCastle = stageId and INVERTED_CASTLE_FLAG != 0,
            roomCount = readInt32(roomCount, 0).coerceAtLeast(0),
            playerLevel = readInt32(playerProgress, 0),
            experience = readInt32(playerProgress, Int.SIZE_BYTES).coerceAtLeast(0),
        )
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
