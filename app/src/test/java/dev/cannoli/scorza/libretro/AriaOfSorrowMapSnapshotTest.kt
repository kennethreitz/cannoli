package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaOfSorrowMapSnapshotTest {
    @Test fun `matches Aria of Sorrow but not the other GBA Castlevanias`() {
        assertTrue(
            AriaOfSorrowMapReader.matches(
                "Castlevania - Aria of Sorrow",
                "Castlevania - Aria of Sorrow.gba",
            )
        )
        assertFalse(AriaOfSorrowMapReader.matches("Castlevania - Harmony of Dissonance", null))
        assertFalse(AriaOfSorrowMapReader.matches("Castlevania - Circle of the Moon", null))
    }

    @Test fun `visited map bits work on both halves of the castle grid`() {
        val mapState = ByteArray(AriaOfSorrowMapSnapshot.MAP_STATE_BYTES)
        reveal(mapState, 31, 12)
        reveal(mapState, 32, 13)
        val snapshot = snapshot(mapState = mapState)

        assertTrue(snapshot.isVisited(31, 12))
        assertTrue(snapshot.isVisited(32, 13))
        assertFalse(snapshot.isVisited(31, 13))
        assertFalse(snapshot.isVisited(32, 12))
    }

    @Test fun `purchased maps reveal only their blue castle regions`() {
        val layout = layoutOf(
            mapCell(3, 4, area = 0),
            mapCell(5, 6, area = 1),
            mapCell(7, 8, area = 3),
            mapCell(9, 10, area = 5),
            mapCell(11, 12, area = 8),
            mapCell(4, 13, area = 0),
        )
        val snapshot = snapshot(layout = layout, ownedMapItems = 1 or 2 or 4)

        assertTrue(snapshot.isBlueprintMapped(3, 4))
        assertTrue(snapshot.isBlueprintMapped(5, 6))
        assertTrue(snapshot.isBlueprintMapped(7, 8))
        assertTrue(snapshot.isBlueprintMapped(9, 10))
        assertFalse(snapshot.isBlueprintMapped(11, 12))
        assertFalse(snapshot.isBlueprintMapped(4, 13))
    }

    @Test fun `exploration percentage uses Arias 848 room scale`() {
        val mapState = ByteArray(AriaOfSorrowMapSnapshot.MAP_STATE_BYTES)
        repeat(424) { index ->
            reveal(mapState, index % 64, index / 64)
        }

        assertEquals(50.0, snapshot(mapState = mapState).explorationPercent, 0.001)
    }

    @Test fun `experience remaining follows Arias cubic level curve`() {
        val level = 12
        val nextLevelThreshold = 13 * 14 * (13 * 3 + 8)
        val snapshot = snapshot(
            playerLevel = level,
            experience = nextLevelThreshold - 1_234,
        )

        assertEquals(1_234, snapshot.experienceToNextLevel)
    }

    private fun snapshot(
        layout: AriaMapLayout = layoutOf(mapCell(10, 10, area = 0)),
        mapState: ByteArray = ByteArray(AriaOfSorrowMapSnapshot.MAP_STATE_BYTES),
        playerLevel: Int = 12,
        experience: Int = 0,
        ownedMapItems: Int = 0,
    ) = AriaOfSorrowMapSnapshot(
        layout = layout,
        mapState = mapState,
        playerTileX = 10,
        playerTileY = 10,
        currentArea = 0,
        playerLevel = playerLevel,
        experience = experience,
        currentHp = 200,
        maxHp = 300,
        characterName = "SOMA",
        ownedMapItems = ownedMapItems,
    )

    private fun layoutOf(vararg cells: Pair<Pair<Int, Int>, Int>): AriaMapLayout {
        val values = IntArray(AriaMapLayout.WIDTH * AriaMapLayout.HEIGHT) {
            AriaMapLayout.EMPTY_CELL
        }
        for ((position, value) in cells) {
            values[position.second * AriaMapLayout.WIDTH + position.first] = value
        }
        return AriaMapLayout(values)
    }

    private fun mapCell(
        x: Int,
        y: Int,
        area: Int,
        room: Int = 0,
    ): Pair<Pair<Int, Int>, Int> = (x to y) to ((area shl 6) or room)

    private fun reveal(mapState: ByteArray, x: Int, y: Int) {
        val bank = x shr 5
        val offset = ((bank * 40 + y) * 8) + 4
        val value = readInt(mapState, offset) or (1 shl (x and 31))
        writeInt(mapState, offset, value)
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
