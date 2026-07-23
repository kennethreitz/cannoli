package dev.cannoli.scorza.libretro

import java.util.Locale

data class SotnMapSnapshot(
    val castleMap: ByteArray,
    val playerTileX: Int,
    val playerTileY: Int,
    val invertedCastle: Boolean,
    val roomCount: Int = 0,
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

    fun isVisited(tileX: Int, tileY: Int): Boolean {
        if (tileX !in MAP_TILES || tileY !in MAP_TILES) return false
        val castleOffset = if (invertedCastle) CASTLE_MAP_BYTES / 2 else 0
        val index = castleOffset + (tileX shr 2) + (tileY * MAP_ROW_BYTES)
        val mask = 1 shl ((3 - (tileX and 3)) * 2)
        return castleMap[index].toInt() and mask != 0
    }

    companion object {
        const val CASTLE_MAP_BYTES = 0x800
        const val MAP_ROW_BYTES = 16
        const val ROOMS_PER_CASTLE = 942
        private const val VISITED_ROOM_BITS = 0x55
        val MAP_TILES = 0 until 64
    }
}

internal object SotnMapReader {
    private const val RETRO_MEMORY_SYSTEM_RAM = 2
    private const val CASTLE_MAP_OFFSET = 0x6BB74
    private const val ROOM_COUNT_OFFSET = 0x3C760
    private const val TILEMAP_POSITION_OFFSET = 0x730B0
    private const val PLAYER_POSITION_OFFSET = 0x973F0
    private const val STAGE_ID_OFFSET = 0x974A0
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
        return parse(castleMap, tilemapPosition, playerState, roomCount)
    }

    internal fun parse(
        castleMap: ByteArray,
        tilemapPosition: ByteArray,
        playerState: ByteArray,
        roomCount: ByteArray = ByteArray(Int.SIZE_BYTES),
    ): SotnMapSnapshot? {
        if (castleMap.size != SotnMapSnapshot.CASTLE_MAP_BYTES ||
            tilemapPosition.size < 8 ||
            playerState.size < STAGE_ID_OFFSET - PLAYER_POSITION_OFFSET + Int.SIZE_BYTES ||
            roomCount.size < Int.SIZE_BYTES
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
        )
    }

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
