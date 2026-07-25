package dev.cannoli.scorza.ui.components

import dev.cannoli.ui.components.SaveSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCompanionDeckTest {
    @Test fun `elapsed session time is stable and zero padded`() {
        assertEquals("00:00:00", formatCompanionElapsed(1_000L, 1_000L))
        assertEquals("01:02:03", formatCompanionElapsed(1_000L, 3_724_000L))
    }

    @Test fun `elapsed session time never goes negative`() {
        assertEquals("00:00:00", formatCompanionElapsed(2_000L, 1_000L))
    }

    @Test fun `save sync labels remain concise`() {
        assertEquals("SAVES SYNCED", companionSaveSyncLabel(SaveSyncStatus.UP_TO_DATE))
        assertEquals("SAVE NEEDS ATTENTION", companionSaveSyncLabel(SaveSyncStatus.CONFLICT))
        assertEquals("SAVE SYNC OFF", companionSaveSyncLabel(SaveSyncStatus.DISABLED))
    }

    @Test fun `free form game presence remains a single natural headline`() {
        assertEquals(
            CompanionPresence("Exploring Brinstar at 82 energy", emptyList()),
            parseCompanionPresence("Exploring Brinstar at 82 energy"),
        )
    }

    @Test fun `labeled live memory values become readable stats`() {
        assertEquals(
            CompanionPresence(
                headline = "Brinstar",
                stats = listOf(
                    CompanionStat("Energy", "82"),
                    CompanionStat("Missiles", "12"),
                ),
            ),
            parseCompanionPresence("Location: Brinstar • Energy: 82 • Missiles: 12"),
        )
    }

    @Test fun `prose and labeled values share the companion cleanly`() {
        assertEquals(
            CompanionPresence(
                headline = "Exploring Cerulean Cave",
                stats = listOf(CompanionStat("Badges", "6")),
            ),
            parseCompanionPresence("Exploring Cerulean Cave | Badges: 6"),
        )
    }

    @Test fun `achievement progress is clamped and safe`() {
        assertEquals(0.5f, companionAchievementProgress(5, 10))
        assertEquals(1f, companionAchievementProgress(12, 10))
        assertEquals(0f, companionAchievementProgress(1, 0))
    }

    @Test fun `deck requires its own opt in and a separate game display`() {
        assertTrue(shouldShowUniversalCompanionDeck(true, true, true, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, false, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(false, true, true, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, true, true, 1, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, true, true, null, 1))
    }
}
