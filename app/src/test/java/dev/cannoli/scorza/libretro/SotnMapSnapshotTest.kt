package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SotnMapSnapshotTest {

    @Test fun `parser combines room origin and player position`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        val tilemap = ByteArray(8).apply {
            writeInt32(0, 10)
            writeInt32(4, 20)
        }
        val player = ByteArray(0xB4).apply {
            writeInt32(0, 3 shl 8)
            writeInt32(4, 4 shl 8)
        }

        val snapshot = SotnMapReader.parse(map, tilemap, player)

        assertNotNull(snapshot)
        assertEquals(13, snapshot!!.playerTileX)
        assertEquals(24, snapshot.playerTileY)
        assertFalse(snapshot.invertedCastle)
    }

    @Test fun `parser reads Alucard level and experience`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        val tilemap = ByteArray(8)
        val player = ByteArray(0xB4)
        val progress = ByteArray(8).apply {
            writeInt32(0, 12)
            writeInt32(4, 4_200)
        }

        val snapshot = SotnMapReader.parse(
            castleMap = map,
            tilemapPosition = tilemap,
            playerState = player,
            playerProgress = progress,
        )

        assertEquals(12, snapshot?.playerLevel)
        assertEquals(4_200, snapshot?.experience)
        assertEquals(300, snapshot?.experienceToNextLevel)
    }

    @Test fun `maximum level has no remaining experience`() {
        val snapshot = SotnMapSnapshot(
            castleMap = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES),
            playerTileX = 0,
            playerTileY = 0,
            invertedCastle = false,
            playerLevel = SotnMapSnapshot.MAX_PLAYER_LEVEL,
            experience = 999_999,
        )

        assertEquals(0, snapshot.experienceToNextLevel)
    }

    @Test fun `visited bits are read from the active castle half`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        reveal(map, tileX = 13, tileY = 24, inverted = false)
        reveal(map, tileX = 31, tileY = 17, inverted = true)

        val normal = SotnMapSnapshot(map, 13, 24, invertedCastle = false)
        val inverted = SotnMapSnapshot(map, 31, 17, invertedCastle = true)

        assertTrue(normal.isVisited(13, 24))
        assertFalse(normal.isVisited(31, 17))
        assertTrue(inverted.isVisited(31, 17))
        assertFalse(inverted.isVisited(13, 24))
    }

    @Test fun `purchased blueprint bits are distinct from visited rooms`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        reveal(map, tileX = 13, tileY = 24, inverted = false)
        revealBlueprint(map, tileX = 14, tileY = 24, inverted = false)

        val snapshot = SotnMapSnapshot(map, 13, 24, invertedCastle = false)

        assertTrue(snapshot.isVisited(13, 24))
        assertTrue(snapshot.isBlueprintMapped(13, 24))
        assertFalse(snapshot.isVisited(14, 24))
        assertTrue(snapshot.isBlueprintMapped(14, 24))
        assertFalse(snapshot.isBlueprintMapped(15, 24))
    }

    @Test fun `stage flag selects the inverted castle`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        val tilemap = ByteArray(8)
        val player = ByteArray(0xB4).apply { writeInt32(0xB0, 0x20) }

        val snapshot = SotnMapReader.parse(map, tilemap, player)

        assertNotNull(snapshot)
        assertTrue(snapshot!!.invertedCastle)
    }

    @Test fun `exploration percentage uses the games room-count scale`() {
        val roomCount = ByteArray(4).apply { writeInt32(0, 471) }

        val snapshot = SotnMapReader.parse(
            castleMap = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES),
            tilemapPosition = ByteArray(8),
            playerState = ByteArray(0xB4),
            roomCount = roomCount,
        )

        assertNotNull(snapshot)
        assertEquals(50.0, snapshot!!.explorationPercent, 0.001)
    }

    @Test fun `empty startup memory is not treated as a loaded castle map`() {
        val snapshot = SotnMapSnapshot(
            castleMap = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES),
            playerTileX = 0,
            playerTileY = 0,
            invertedCastle = false,
            roomCount = 0,
        )

        assertFalse(snapshot.hasExploredRooms)
    }

    @Test fun `map becomes ready after the active castle has explored room data`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        reveal(map, tileX = 13, tileY = 24, inverted = false)

        val snapshot = SotnMapSnapshot(
            castleMap = map,
            playerTileX = 13,
            playerTileY = 24,
            invertedCastle = false,
            roomCount = 1,
        )

        assertTrue(snapshot.hasExploredRooms)
    }

    @Test fun `room data from the other castle does not make startup ready`() {
        val map = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES)
        reveal(map, tileX = 31, tileY = 17, inverted = true)

        val snapshot = SotnMapSnapshot(
            castleMap = map,
            playerTileX = 13,
            playerTileY = 24,
            invertedCastle = false,
            roomCount = 1,
        )

        assertFalse(snapshot.hasExploredRooms)
    }

    private fun reveal(map: ByteArray, tileX: Int, tileY: Int, inverted: Boolean) {
        val castleOffset = if (inverted) SotnMapSnapshot.CASTLE_MAP_BYTES / 2 else 0
        val index = castleOffset + (tileX shr 2) + tileY * SotnMapSnapshot.MAP_ROW_BYTES
        val mask = 1 shl ((3 - (tileX and 3)) * 2)
        map[index] = (map[index].toInt() or mask).toByte()
    }

    private fun revealBlueprint(
        map: ByteArray,
        tileX: Int,
        tileY: Int,
        inverted: Boolean,
    ) {
        val castleOffset = if (inverted) SotnMapSnapshot.CASTLE_MAP_BYTES / 2 else 0
        val index = castleOffset + (tileX shr 2) + tileY * SotnMapSnapshot.MAP_ROW_BYTES
        val mask = 2 shl ((3 - (tileX and 3)) * 2)
        map[index] = (map[index].toInt() or mask).toByte()
    }

    private fun ByteArray.writeInt32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value shr 8).toByte()
        this[offset + 2] = (value shr 16).toByte()
        this[offset + 3] = (value shr 24).toByte()
    }
}
