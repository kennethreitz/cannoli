package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.libretro.SotnMapSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SotnCastleMapCompanionTest {

    @Test fun `explored mask preserves the shared blueprint border`() {
        assertEquals(10f, sotnRevealedMaskTileExtent(tileSize = 8f), 0.001f)
    }

    @Test fun `normal castle origin follows the blueprint room grid`() {
        val (originX, originY) = sotnSourceMapOrigin(invertedCastle = false)

        assertEquals(0, originX % 4)
        assertEquals(0, originY % 4)
    }

    @Test fun `unexplored special rooms remain blue`() {
        val blueprintBlue = 0xFF5070F8.toInt()

        assertEquals(blueprintBlue, sotnUnexploredMapColor(0xFFF80000.toInt()))
        assertEquals(blueprintBlue, sotnUnexploredMapColor(0xFFF88000.toInt()))
    }

    @Test fun `unexplored map preserves blue rooms and pale outlines`() {
        assertEquals(0xFF5070F8.toInt(), sotnUnexploredMapColor(0xFF5070F8.toInt()))
        assertEquals(0xFFC0C0C0.toInt(), sotnUnexploredMapColor(0xFFC0C0C0.toInt()))
    }

    @Test fun `experience label shows remaining points with grouping`() {
        val snapshot = SotnMapSnapshot(
            castleMap = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES),
            playerTileX = 0,
            playerTileY = 0,
            invertedCastle = false,
            playerLevel = 19,
            experience = 9_000,
        )

        assertEquals("1,450 XP TO NEXT", sotnExperienceToNextText(snapshot))
    }

    @Test fun `experience label identifies maximum level`() {
        val snapshot = SotnMapSnapshot(
            castleMap = ByteArray(SotnMapSnapshot.CASTLE_MAP_BYTES),
            playerTileX = 0,
            playerTileY = 0,
            invertedCastle = false,
            playerLevel = SotnMapSnapshot.MAX_PLAYER_LEVEL,
            experience = 999_999,
        )

        assertEquals("MAX LEVEL", sotnExperienceToNextText(snapshot))
    }

    @Test fun `active Symphony of the Night launch shows the castle map`() {
        assertTrue(
            shouldShowSotnCastleMap(
                gameActive = true,
                displayName = "Castlevania - Symphony of the Night",
                fileName = "Castlevania - Symphony of the Night.chd",
            )
        )
    }

    @Test fun `short SOTN filename is recognized`() {
        assertTrue(
            shouldShowSotnCastleMap(
                gameActive = true,
                displayName = "Castlevania SOTN",
                fileName = null,
            )
        )
    }

    @Test fun `inactive or unrelated games keep the usual launcher treatment`() {
        assertFalse(
            shouldShowSotnCastleMap(
                gameActive = false,
                displayName = "Castlevania - Symphony of the Night",
                fileName = null,
            )
        )
        assertFalse(
            shouldShowSotnCastleMap(
                gameActive = true,
                displayName = "Castlevania: Rondo of Blood",
                fileName = null,
            )
        )
    }
}
