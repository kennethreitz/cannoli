package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroidGbaMapSnapshotTest {
    @Test fun `reader recognizes both GBA Metroid games without claiming other entries`() {
        assertEquals(
            MetroidGbaGame.FUSION,
            MetroidGbaMapReader.gameFor("Metroid Fusion", "Metroid - Fusion.gba"),
        )
        assertEquals(
            MetroidGbaGame.ZERO_MISSION,
            MetroidGbaMapReader.gameFor(
                "Metroid: Zero Mission",
                "Metroid - Zero Mission.gba",
            ),
        )
        assertNull(MetroidGbaMapReader.gameFor("Super Metroid", "Super Metroid.sfc"))
        assertNull(MetroidGbaMapReader.gameFor("Metroid Prime", "Metroid Prime.iso"))
    }

    @Test fun `native map tiles identify backgrounds and save rooms per game`() {
        val fusion = layout(
            game = MetroidGbaGame.FUSION,
            background = 0xA0,
            cells = mapOf((2 to 3) to 0x17B, (3 to 3) to 0x61),
        )
        assertTrue(fusion.cellAt(2, 3)?.saveRoom == true)
        assertFalse(fusion.cellAt(3, 3)?.saveRoom == true)
        assertNull(fusion.cellAt(0, 0))

        val zeroMission = layout(
            game = MetroidGbaGame.ZERO_MISSION,
            background = 0x140,
            cells = mapOf((7 to 8) to 0xC9, (8 to 8) to 0x45),
        )
        assertTrue(zeroMission.cellAt(7, 8)?.saveRoom == true)
        assertFalse(zeroMission.cellAt(8, 8)?.saveRoom == true)
        assertNull(zeroMission.cellAt(0, 0))
    }

    @Test fun `exploration follows the games row bitfield`() {
        val layout = layout(
            game = MetroidGbaGame.FUSION,
            background = 0xA0,
            cells = mapOf((2 to 3) to 0x61, (3 to 3) to 0x62),
        )
        val visited = ByteArray(MetroidGbaMapSnapshot.VISITED_AREA_BYTES)
        visited.writeInt32(3 * Int.SIZE_BYTES, 1 shl 2)
        val snapshot = MetroidGbaMapSnapshot(
            game = MetroidGbaGame.FUSION,
            layout = layout,
            visitedRows = visited,
            currentArea = 0,
            currentRoom = 4,
            playerTileX = 2,
            playerTileY = 3,
        )

        assertTrue(snapshot.isVisited(2, 3))
        assertFalse(snapshot.isVisited(3, 3))
        assertEquals(1, snapshot.exploredCellCount)
    }

    @Test fun `reader publishes only stable gameplay with a visited current tile`() {
        val game = MetroidGbaGame.FUSION
        val gameState = ByteArray(4).apply {
            writeU16(0, game.mainGameMode)
            writeU16(2, 2)
        }
        val position = ByteArray(7).apply {
            this[0] = 0
            this[1] = 4
            this[5] = 2
            this[6] = 3
        }
        val mapData = mapBytes(
            background = game.backgroundTile,
            cells = buildMap {
                for (x in 0 until 8) {
                    for (y in 0 until 5) put(x to y, 0x61)
                }
            },
        )
        val visited = ByteArray(MetroidGbaMapSnapshot.VISITED_AREA_BYTES).apply {
            writeInt32(3 * Int.SIZE_BYTES, 1 shl 2)
        }
        val memory = mapOf(
            game.gameModeAddress to gameState,
            game.positionAddress to position,
            game.mapDataAddress to mapData,
            game.visitedMapAddress to visited,
        )
        val snapshot = MetroidGbaMapReader.read(game) { address, length ->
            memory[address]?.takeIf { it.size == length }
        }

        assertEquals(2, snapshot?.playerTileX)
        assertEquals(3, snapshot?.playerTileY)

        gameState.writeU16(2, 3)
        assertNull(
            MetroidGbaMapReader.read(game) { address, length ->
                memory[address]?.takeIf { it.size == length }
            }
        )
    }

    @Test fun `door and room loading modes hold the previous marker`() {
        assertTrue(isStableMetroidGbaGameplay(MetroidGbaGame.FUSION, 1, 2))
        assertTrue(isStableMetroidGbaGameplay(MetroidGbaGame.ZERO_MISSION, 4, 2))
        assertFalse(isStableMetroidGbaGameplay(MetroidGbaGame.FUSION, 1, 1))
        assertFalse(isStableMetroidGbaGameplay(MetroidGbaGame.ZERO_MISSION, 4, 3))
    }

    private fun layout(
        game: MetroidGbaGame,
        background: Int,
        cells: Map<Pair<Int, Int>, Int>,
    ): MetroidGbaMapLayout {
        val values = IntArray(MetroidGbaMapLayout.SIZE * MetroidGbaMapLayout.SIZE) {
            background
        }
        cells.forEach { (position, tile) ->
            values[position.second * MetroidGbaMapLayout.SIZE + position.first] = tile
        }
        return MetroidGbaMapLayout(values, game)
    }

    private fun mapBytes(
        background: Int,
        cells: Map<Pair<Int, Int>, Int>,
    ): ByteArray = ByteArray(MetroidGbaMapLayout.BYTE_SIZE).apply {
        repeat(MetroidGbaMapLayout.SIZE * MetroidGbaMapLayout.SIZE) { index ->
            writeU16(index * Short.SIZE_BYTES, background)
        }
        cells.forEach { (position, tile) ->
            val index = position.second * MetroidGbaMapLayout.SIZE + position.first
            writeU16(index * Short.SIZE_BYTES, tile)
        }
    }

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
    }

    private fun ByteArray.writeInt32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }
}
