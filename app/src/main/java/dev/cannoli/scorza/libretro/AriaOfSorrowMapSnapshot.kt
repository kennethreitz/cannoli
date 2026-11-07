package dev.cannoli.scorza.libretro

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

data class AriaMapCell(
    val x: Int,
    val y: Int,
    val area: Int,
    val room: Int,
    val saveRoom: Boolean,
    val warpRoom: Boolean,
)

class AriaMapLayout internal constructor(
    private val values: IntArray,
) {
    fun cellAt(x: Int, y: Int): AriaMapCell? {
        if (x !in X_TILES || y !in Y_TILES) return null
        val value = values[y * WIDTH + x]
        if (value == EMPTY_CELL) return null
        return AriaMapCell(
            x = x,
            y = y,
            area = (value shr 6) and 0xF,
            room = value and 0x3F,
            saveRoom = value and SAVE_ROOM_FLAG != 0,
            warpRoom = value and WARP_ROOM_FLAG != 0,
        )
    }

    val cells: List<AriaMapCell> by lazy {
        buildList {
            for (y in Y_TILES) {
                for (x in X_TILES) {
                    cellAt(x, y)?.let(::add)
                }
            }
        }
    }

    companion object {
        const val WIDTH = 64
        const val HEIGHT = 35
        const val BYTE_SIZE = WIDTH * HEIGHT * Short.SIZE_BYTES
        const val EMPTY_CELL = 0xFFFF
        private const val SAVE_ROOM_FLAG = 0x8000
        private const val WARP_ROOM_FLAG = 0x4000
        val X_TILES = 0 until WIDTH
        val Y_TILES = 0 until HEIGHT

        internal fun decode(bytes: ByteArray): AriaMapLayout? {
            if (bytes.size != BYTE_SIZE) return null
            val values = IntArray(WIDTH * HEIGHT) { index ->
                val offset = index * Short.SIZE_BYTES
                bytes.u16(offset)
            }
            val occupiedCount = values.count { it != EMPTY_CELL }
            return values
                .takeIf { occupiedCount in 400..1_200 }
                ?.let(::AriaMapLayout)
        }
    }
}

data class AriaOfSorrowMapSnapshot(
    val layout: AriaMapLayout,
    private val mapState: ByteArray,
    val playerTileX: Int,
    val playerTileY: Int,
    val currentArea: Int,
    val playerLevel: Int,
    val experience: Int,
    val currentHp: Int,
    val maxHp: Int,
    val characterName: String,
    private val ownedMapItems: Int,
) {
    val hasExploredRooms: Boolean
        get() = exploredRoomCount > 0

    val exploredRoomCount: Int
        get() {
            var count = 0
            for (y in MAP_STATE_Y_TILES) {
                for (x in AriaMapLayout.X_TILES) {
                    if (isVisited(x, y)) count++
                }
            }
            return count
        }

    val explorationPercent: Double
        get() = exploredRoomCount * 100.0 / EXPLORATION_ROOM_DIVISOR

    val experienceToNextLevel: Int?
        get() = when (playerLevel) {
            in 1 until MAX_PLAYER_LEVEL ->
                (experienceThreshold(playerLevel + 1) - experience).coerceAtLeast(0)
            MAX_PLAYER_LEVEL -> 0
            else -> null
        }

    val currentAreaName: String
        get() = AREA_NAMES.getOrElse(currentArea) { "Dracula's Castle" }

    fun isVisited(x: Int, y: Int): Boolean =
        hasMapBit(x, y, VISITED_WORD_OFFSET)

    fun isBlueprintMapped(x: Int, y: Int): Boolean {
        if (isVisited(x, y)) return true
        val cell = layout.cellAt(x, y) ?: return false
        if (x to y in PURCHASED_MAP_EXCLUSIONS) return false
        return when (cell.area) {
            0, 1 -> ownedMapItems and MAP_ONE != 0
            3 -> ownedMapItems and MAP_TWO != 0
            5 -> ownedMapItems and MAP_THREE != 0
            else -> false
        }
    }

    private fun hasMapBit(x: Int, y: Int, wordOffset: Int): Boolean {
        if (x !in AriaMapLayout.X_TILES || y !in MAP_STATE_Y_TILES) return false
        val bank = x shr 5
        val offset = ((bank * MAP_STATE_HEIGHT + y) * MAP_ENTRY_SIZE) + wordOffset
        val mask = 1 shl (x and 31)
        return mapState.readInt32(offset) and mask != 0
    }

    companion object {
        const val MAX_PLAYER_LEVEL = 99
        private const val MAP_STATE_HEIGHT = 40
        const val MAP_STATE_BYTES = 2 * MAP_STATE_HEIGHT * 8
        private const val MAP_ENTRY_SIZE = 8
        private const val VISITED_WORD_OFFSET = 4
        private const val EXPLORATION_ROOM_DIVISOR = 0x350
        private const val MAP_ONE = 1
        private const val MAP_TWO = 2
        private const val MAP_THREE = 4
        private val MAP_STATE_Y_TILES = 0 until MAP_STATE_HEIGHT
        private val PURCHASED_MAP_EXCLUSIONS = setOf(
            4 to 13,
            12 to 13,
            13 to 13,
            15 to 22,
            47 to 20,
        )
        private val AREA_NAMES = listOf(
            "Castle Corridor",
            "Chapel",
            "Study",
            "Dance Hall",
            "Inner Quarters",
            "Floating Garden",
            "Clock Tower",
            "Underground Reservoir",
            "Arena",
            "Top Floor",
            "Forbidden Area",
            "Chaotic Realm",
        )

        private fun experienceThreshold(level: Int): Int =
            level * (level + 1) * (level * 3 + 8)
    }
}

internal object AriaOfSorrowMapReader {
    private const val EWRAM_START = 0x02000000
    private const val EWRAM_END = 0x02040000
    private const val GAME_MODE_ADDRESS = 0x02000010
    private const val IN_GAME_SUBMODE_ADDRESS = 0x02000064
    private const val MAP_POSITION_ADDRESS = 0x0200008C
    private const val CURRENT_AREA_ADDRESS = 0x0200009E
    private const val MAP_STATE_ADDRESS = 0x020000B4
    private const val BG1_ADDRESS = 0x0200A094
    private const val PLAYER_POINTER_ADDRESS = 0x02013110
    private const val PLAYER_PROGRESS_ADDRESS = 0x02013266
    private const val PLAYER_PROGRESS_BYTES = 0x4E
    private const val PLAYER_X_INTEGER_OFFSET = 0x42
    private const val PLAYER_Y_INTEGER_OFFSET = 0x46
    private const val PLAYER_POSITION_BYTES = 8
    private const val MAP_LAYOUT_ROM_OFFSET = 0x116650L

    @Volatile private var cachedRomPath: String? = null
    @Volatile private var cachedLayout: AriaMapLayout? = null

    fun matches(displayName: String?, fileName: String?): Boolean {
        val identity = listOfNotNull(displayName, fileName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return identity.contains("aria of sorrow") &&
            (identity.contains("castlevania") || identity.contains("aria"))
    }

    fun read(runner: LibretroRunner, romPath: String): AriaOfSorrowMapSnapshot? =
        read(romPath, runner::copyMappedMemory)

    internal fun read(
        romPath: String,
        copyMemory: (address: Int, length: Int) -> ByteArray?,
    ): AriaOfSorrowMapSnapshot? {
        val layout = layoutForRom(romPath) ?: return null
        val gameMode = copyMemory(GAME_MODE_ADDRESS, 1)?.u8(0) ?: return null
        val inGameSubmode = copyMemory(IN_GAME_SUBMODE_ADDRESS, 1)?.u8(0) ?: return null
        if (!isStableAriaGameplay(gameMode, inGameSubmode)) return null

        val packedMapPosition = copyMemory(MAP_POSITION_ADDRESS, Short.SIZE_BYTES)
            ?.u16(0)
            ?: return null
        val mapOriginX = packedMapPosition and 0x7F
        val mapOriginY = (packedMapPosition shr 7) and 0x7F
        val currentArea = copyMemory(CURRENT_AREA_ADDRESS, 1)?.u8(0) ?: return null
        val mapState = copyMemory(MAP_STATE_ADDRESS, AriaOfSorrowMapSnapshot.MAP_STATE_BYTES)
            ?: return null
        val bg1 = copyMemory(BG1_ADDRESS, 12) ?: return null
        val playerPointer = copyMemory(PLAYER_POINTER_ADDRESS, Int.SIZE_BYTES)
            ?.readInt32(0)
            ?.takeIf { it in EWRAM_START until EWRAM_END - PLAYER_Y_INTEGER_OFFSET - 2 }
            ?: return null
        val playerPosition = copyMemory(
            playerPointer + PLAYER_X_INTEGER_OFFSET,
            PLAYER_POSITION_BYTES,
        ) ?: return null
        val playerProgress = copyMemory(PLAYER_PROGRESS_ADDRESS, PLAYER_PROGRESS_BYTES)
            ?: return null

        val roomX = bg1.s16(6) + playerPosition.s16(0)
        val roomY = bg1.s16(10) + playerPosition.s16(4)
        val playerTileX = mapOriginX + (roomX shr 8)
        val playerTileY = mapOriginY + (roomY shr 8)
        if (playerTileX !in AriaMapLayout.X_TILES ||
            playerTileY !in AriaMapLayout.Y_TILES ||
            layout.cellAt(playerTileX, playerTileY) == null
        ) return null

        val playerLevel = playerProgress.u8(0x13)
        val currentHp = playerProgress.s16(0x14).coerceAtLeast(0)
        val maxHp = playerProgress.u16(0x18)
        if (playerLevel !in 1..AriaOfSorrowMapSnapshot.MAX_PLAYER_LEVEL ||
            maxHp !in 1..9_999 ||
            currentHp > maxHp
        ) return null
        val ownedMapItems =
            (if (playerProgress.u8(0x4B) != 0) 1 else 0) or
                (if (playerProgress.u8(0x4C) != 0) 2 else 0) or
                (if (playerProgress.u8(0x4D) != 0) 4 else 0)

        return AriaOfSorrowMapSnapshot(
            layout = layout,
            mapState = mapState,
            playerTileX = playerTileX,
            playerTileY = playerTileY,
            currentArea = currentArea,
            playerLevel = playerLevel,
            experience = playerProgress.readInt32(0x26).coerceAtLeast(0),
            currentHp = currentHp,
            maxHp = maxHp,
            characterName = if (playerProgress.u8(0) == 0) "SOMA" else "JULIUS",
            ownedMapItems = ownedMapItems,
        )
    }

    @Synchronized
    private fun layoutForRom(romPath: String): AriaMapLayout? {
        if (cachedRomPath == romPath) return cachedLayout
        val layout = runCatching {
            RandomAccessFile(File(romPath), "r").use { rom ->
                if (rom.length() < MAP_LAYOUT_ROM_OFFSET + AriaMapLayout.BYTE_SIZE) {
                    return@use null
                }
                val bytes = ByteArray(AriaMapLayout.BYTE_SIZE)
                rom.seek(MAP_LAYOUT_ROM_OFFSET)
                rom.readFully(bytes)
                AriaMapLayout.decode(bytes)
            }
        }.getOrNull()
        cachedRomPath = romPath
        cachedLayout = layout
        return layout
    }
}

private const val ARIA_GAME_MODE_IN_GAME = 4
private const val ARIA_IN_GAME_SUBMODE_PLAYING = 1

internal fun isStableAriaGameplay(gameMode: Int, inGameSubmode: Int): Boolean =
    gameMode == ARIA_GAME_MODE_IN_GAME &&
        inGameSubmode == ARIA_IN_GAME_SUBMODE_PLAYING

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

private fun ByteArray.u16(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8)

private fun ByteArray.s16(offset: Int): Int = u16(offset).toShort().toInt()

private fun ByteArray.readInt32(offset: Int): Int =
    u8(offset) or
        (u8(offset + 1) shl 8) or
        (u8(offset + 2) shl 16) or
        (u8(offset + 3) shl 24)
