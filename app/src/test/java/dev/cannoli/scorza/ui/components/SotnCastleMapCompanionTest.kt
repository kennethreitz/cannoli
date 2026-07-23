package dev.cannoli.scorza.ui.components

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
