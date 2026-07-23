package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperMarioWorldCompanionTest {
    @Test fun `companion is limited to active original Super Mario World sessions`() {
        assertTrue(
            shouldShowSuperMarioWorldCompanion(
                gameActive = true,
                displayName = "Super Mario World",
                fileName = "Super Mario World (U) [!].sfc",
            )
        )
        assertFalse(
            shouldShowSuperMarioWorldCompanion(
                gameActive = false,
                displayName = "Super Mario World",
                fileName = null,
            )
        )
        assertFalse(
            shouldShowSuperMarioWorldCompanion(
                gameActive = true,
                displayName = "Super Mario World 2: Yoshi's Island",
                fileName = null,
            )
        )
    }
}
