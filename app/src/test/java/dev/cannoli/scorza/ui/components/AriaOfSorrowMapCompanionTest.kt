package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.libretro.AriaMapLayout
import dev.cannoli.scorza.libretro.AriaOfSorrowMapSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriaOfSorrowMapCompanionTest {
    @Test fun `active Aria launch shows the dedicated castle map`() {
        assertTrue(
            shouldShowAriaOfSorrowMap(
                gameActive = true,
                displayName = "Castlevania - Aria of Sorrow",
                fileName = "Castlevania - Aria of Sorrow.gba",
            )
        )
    }

    @Test fun `inactive or unrelated GBA Castlevanias keep the usual treatment`() {
        assertFalse(
            shouldShowAriaOfSorrowMap(
                gameActive = false,
                displayName = "Castlevania - Aria of Sorrow",
                fileName = null,
            )
        )
        assertFalse(
            shouldShowAriaOfSorrowMap(
                gameActive = true,
                displayName = "Castlevania - Harmony of Dissonance",
                fileName = null,
            )
        )
    }

    @Test fun `experience label shows Aria experience remaining`() {
        val level = 12
        val nextLevelThreshold = 13 * 14 * (13 * 3 + 8)
        val snapshot = AriaOfSorrowMapSnapshot(
            layout = AriaMapLayout(
                IntArray(AriaMapLayout.WIDTH * AriaMapLayout.HEIGHT) {
                    AriaMapLayout.EMPTY_CELL
                }
            ),
            mapState = ByteArray(AriaOfSorrowMapSnapshot.MAP_STATE_BYTES),
            playerTileX = 0,
            playerTileY = 0,
            currentArea = 0,
            playerLevel = level,
            experience = nextLevelThreshold - 2_345,
            currentHp = 200,
            maxHp = 300,
            characterName = "SOMA",
            ownedMapItems = 0,
        )

        assertEquals("2,345 XP TO NEXT", ariaExperienceToNextText(snapshot))
    }
}
