package dev.cannoli.scorza.libretro

import java.util.Locale

enum class MetroidGbaGame(
    val displayTitle: String,
    val areaNames: List<String>,
    internal val mainGameMode: Int,
    internal val gameModeAddress: Int,
    internal val positionAddress: Int,
    internal val mapDataAddress: Int,
    internal val visitedMapAddress: Int,
    internal val backgroundTile: Int,
    internal val saveRoomTiles: Set<Int>,
) {
    FUSION(
        displayTitle = "METROID FUSION",
        areaNames = listOf(
            "Main Deck",
            "Sector 1 · SRX",
            "Sector 2 · TRO",
            "Sector 3 · PYR",
            "Sector 4 · AQA",
            "Sector 5 · ARC",
            "Sector 6 · NOC",
        ),
        mainGameMode = 1,
        gameModeAddress = 0x03000BDE,
        positionAddress = 0x0300002C,
        mapDataAddress = 0x02034000,
        visitedMapAddress = 0x02037C00,
        backgroundTile = 0xA0,
        saveRoomTiles = (0x17B..0x17F).toSet(),
    ),
    ZERO_MISSION(
        displayTitle = "METROID: ZERO MISSION",
        areaNames = listOf(
            "Brinstar",
            "Kraid",
            "Norfair",
            "Ridley",
            "Tourian",
            "Crateria",
            "Chozodia",
        ),
        mainGameMode = 4,
        gameModeAddress = 0x03000C70,
        positionAddress = 0x03000054,
        mapDataAddress = 0x02034800,
        visitedMapAddress = 0x02037400,
        backgroundTile = 0x140,
        saveRoomTiles = setOf(0xBA, 0xBB, 0xBF, 0xC7, 0xC8, 0xC9),
    ),
}

data class MetroidGbaMapCell(
    val x: Int,
    val y: Int,
    val tile: Int,
    val saveRoom: Boolean,
)

class MetroidGbaMapLayout internal constructor(
    private val values: IntArray,
    private val game: MetroidGbaGame,
) {
    fun cellAt(x: Int, y: Int): MetroidGbaMapCell? {
        if (x !in TILES || y !in TILES) return null
        val tile = values[y * SIZE + x] and TILE_INDEX_MASK
        if (tile == game.backgroundTile) return null
        return MetroidGbaMapCell(
            x = x,
            y = y,
            tile = tile,
            saveRoom = tile in game.saveRoomTiles,
        )
    }

    val cells: List<MetroidGbaMapCell> by lazy {
        buildList {
            for (y in TILES) {
                for (x in TILES) {
                    cellAt(x, y)?.let(::add)
                }
            }
        }
    }

    companion object {
        const val SIZE = 32
        const val BYTE_SIZE = SIZE * SIZE * Short.SIZE_BYTES
        private const val TILE_INDEX_MASK = 0x3FF
        val TILES = 0 until SIZE

        internal fun decode(game: MetroidGbaGame, bytes: ByteArray): MetroidGbaMapLayout? {
            if (bytes.size != BYTE_SIZE) return null
            val values = IntArray(SIZE * SIZE) { index ->
                bytes.u16(index * Short.SIZE_BYTES)
            }
            val layout = MetroidGbaMapLayout(values, game)
            return layout.takeIf { it.cells.size in 40..400 }
        }
    }
}

data class MetroidGbaMapSnapshot(
    val game: MetroidGbaGame,
    val layout: MetroidGbaMapLayout,
    private val visitedRows: ByteArray,
    val currentArea: Int,
    val currentRoom: Int,
    val playerTileX: Int,
    val playerTileY: Int,
) {
    val areaName: String
        get() = game.areaNames.getOrElse(currentArea) { "Unknown area" }

    val exploredCellCount: Int
        get() = layout.cells.count { isVisited(it.x, it.y) }

    val hasExploredRooms: Boolean
        get() = exploredCellCount > 0

    val explorationPercent: Double
        get() = if (layout.cells.isEmpty()) {
            0.0
        } else {
            exploredCellCount * 100.0 / layout.cells.size
        }

    fun isVisited(x: Int, y: Int): Boolean {
        if (x !in MetroidGbaMapLayout.TILES || y !in MetroidGbaMapLayout.TILES) return false
        val row = visitedRows.readInt32(y * Int.SIZE_BYTES)
        return row and (1 shl x) != 0
    }

    companion object {
        const val VISITED_AREA_BYTES = MetroidGbaMapLayout.SIZE * Int.SIZE_BYTES
    }
}

internal object MetroidGbaMapReader {
    private const val POSITION_BYTES = 7
    private const val POSITION_AREA_OFFSET = 0
    private const val POSITION_ROOM_OFFSET = 1
    private const val POSITION_X_OFFSET = 5
    private const val POSITION_Y_OFFSET = 6
    private const val GAME_STATE_BYTES = 4
    private const val SUB_GAME_MODE_OFFSET = 2

    fun gameFor(displayName: String?, fileName: String?): MetroidGbaGame? {
        val identity = listOfNotNull(displayName, fileName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val isMetroid = identity.contains("metroid") || identity.contains("metriod")
        return when {
            isMetroid && identity.contains("zero mission") -> MetroidGbaGame.ZERO_MISSION
            isMetroid && identity.contains("fusion") -> MetroidGbaGame.FUSION
            else -> null
        }
    }

    fun matches(displayName: String?, fileName: String?): Boolean =
        gameFor(displayName, fileName) != null

    fun read(
        runner: LibretroRunner,
        displayName: String?,
        fileName: String?,
    ): MetroidGbaMapSnapshot? {
        val game = gameFor(displayName, fileName) ?: return null
        return read(game, runner::copyMappedMemory)
    }

    internal fun read(
        game: MetroidGbaGame,
        copyMemory: (address: Int, length: Int) -> ByteArray?,
    ): MetroidGbaMapSnapshot? {
        val gameState = copyMemory(game.gameModeAddress, GAME_STATE_BYTES) ?: return null
        val mainGameMode = gameState.u16(0)
        val subGameMode = gameState.u16(SUB_GAME_MODE_OFFSET)
        if (!isStableMetroidGbaGameplay(game, mainGameMode, subGameMode)) return null

        val position = copyMemory(game.positionAddress, POSITION_BYTES) ?: return null
        val currentArea = position.u8(POSITION_AREA_OFFSET)
        val currentRoom = position.u8(POSITION_ROOM_OFFSET)
        val playerTileX = position.u8(POSITION_X_OFFSET)
        val playerTileY = position.u8(POSITION_Y_OFFSET)
        if (currentArea !in game.areaNames.indices ||
            playerTileX !in MetroidGbaMapLayout.TILES ||
            playerTileY !in MetroidGbaMapLayout.TILES
        ) return null

        val layout = copyMemory(game.mapDataAddress, MetroidGbaMapLayout.BYTE_SIZE)
            ?.let { MetroidGbaMapLayout.decode(game, it) }
            ?: return null
        val visitedRows = copyMemory(
            game.visitedMapAddress +
                currentArea * MetroidGbaMapSnapshot.VISITED_AREA_BYTES,
            MetroidGbaMapSnapshot.VISITED_AREA_BYTES,
        ) ?: return null
        if (layout.cellAt(playerTileX, playerTileY) == null) return null

        val snapshot = MetroidGbaMapSnapshot(
            game = game,
            layout = layout,
            visitedRows = visitedRows,
            currentArea = currentArea,
            currentRoom = currentRoom,
            playerTileX = playerTileX,
            playerTileY = playerTileY,
        )
        return snapshot.takeIf {
            it.hasExploredRooms && it.isVisited(playerTileX, playerTileY)
        }
    }
}

internal fun isStableMetroidGbaGameplay(
    game: MetroidGbaGame,
    mainGameMode: Int,
    subGameMode: Int,
): Boolean = mainGameMode == game.mainGameMode &&
    subGameMode == 2

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u16(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.readInt32(offset: Int): Int =
    u8(offset) or
        (u8(offset + 1) shl 8) or
        (u8(offset + 2) shl 16) or
        (u8(offset + 3) shl 24)
