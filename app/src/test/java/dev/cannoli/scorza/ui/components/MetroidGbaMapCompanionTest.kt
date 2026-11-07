package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroidGbaMapCompanionTest {
    @Test fun `active Fusion and Zero Mission launches use the dedicated map`() {
        assertTrue(
            shouldShowMetroidGbaMap(
                gameActive = true,
                displayName = "Metroid Fusion",
                fileName = "Metroid - Fusion.gba",
            )
        )
        assertTrue(
            shouldShowMetroidGbaMap(
                gameActive = true,
                displayName = "Metroid: Zero Mission",
                fileName = "Metroid - Zero Mission.gba",
            )
        )
    }

    @Test fun `inactive and unrelated Metroid games keep the usual treatment`() {
        assertFalse(
            shouldShowMetroidGbaMap(
                gameActive = false,
                displayName = "Metroid Fusion",
                fileName = null,
            )
        )
        assertFalse(
            shouldShowMetroidGbaMap(
                gameActive = true,
                displayName = "Super Metroid",
                fileName = "Super Metroid.sfc",
            )
        )
        assertFalse(
            shouldShowMetroidGbaMap(
                gameActive = true,
                displayName = "Metroid Prime",
                fileName = "Metroid Prime.iso",
            )
        )
    }
}
