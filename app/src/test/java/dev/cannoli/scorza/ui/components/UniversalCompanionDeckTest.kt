package dev.cannoli.scorza.ui.components

import dev.cannoli.scorza.libretro.CompanionAchievement
import dev.cannoli.scorza.libretro.CompanionEvent
import dev.cannoli.scorza.libretro.CompanionLeaderboard
import dev.cannoli.scorza.libretro.UniversalCompanionSnapshot
import dev.cannoli.scorza.libretro.COMPANION_BUCKET_ACTIVE_CHALLENGE
import dev.cannoli.scorza.libretro.COMPANION_BUCKET_ALMOST_THERE
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

    @Test fun `live leaderboard takes priority over every other companion signal`() {
        val challenge = achievement(bucket = COMPANION_BUCKET_ACTIVE_CHALLENGE)
        val snapshot = snapshot(
            richPresence = "Exploring the castle",
            activeChallenge = challenge,
            featuredAchievement = challenge,
            activeLeaderboard = CompanionLeaderboard(4, "Fastest clear", "", "00:41.20"),
            recentEvent = event(CompanionEvent.ACHIEVEMENT_UNLOCKED),
        )

        assertEquals(CompanionMainMode.LEADERBOARD, companionMainMode(snapshot, 10_000L))
    }

    @Test fun `challenge takes priority over unlock and rich presence`() {
        val challenge = achievement(bucket = COMPANION_BUCKET_ACTIVE_CHALLENGE)
        val snapshot = snapshot(
            richPresence = "Exploring the castle",
            activeChallenge = challenge,
            featuredAchievement = challenge,
            recentEvent = event(CompanionEvent.ACHIEVEMENT_UNLOCKED),
        )

        assertEquals(CompanionMainMode.ACTIVE_CHALLENGE, companionMainMode(snapshot, 10_000L))
    }

    @Test fun `recent events expire back to live presence`() {
        val snapshot = snapshot(
            richPresence = "Exploring the castle",
            recentEvent = event(
                CompanionEvent.ACHIEVEMENT_UNLOCKED,
                observedAtMillis = 1_000L,
            ),
        )

        assertEquals(CompanionMainMode.RECENT_EVENT, companionMainMode(snapshot, 7_500L))
        assertEquals(CompanionMainMode.PRESENCE, companionMainMode(snapshot, 8_500L))
    }

    @Test fun `scoreboard result combines score rank and personal best`() {
        assertEquals(
            "SCORE  00:43.27   ·   RANK  14 / 982   ·   BEST  00:42.11",
            companionEventResult(
                event(
                    type = CompanionEvent.LEADERBOARD_SCOREBOARD,
                    value = "00:43.27",
                    bestValue = "00:42.11",
                    rank = 14,
                    totalEntries = 982,
                )
            )
        )
    }

    @Test fun `achievement buckets receive meaningful labels`() {
        assertEquals(
            "ACTIVE CHALLENGE",
            companionAchievementLabel(achievement(bucket = COMPANION_BUCKET_ACTIVE_CHALLENGE)),
        )
        assertEquals(
            "ALMOST THERE",
            companionAchievementLabel(achievement(bucket = COMPANION_BUCKET_ALMOST_THERE)),
        )
    }

    @Test fun `deck requires its own opt in and a separate game display`() {
        assertTrue(shouldShowUniversalCompanionDeck(true, true, true, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, false, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(false, true, true, true, 0, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, true, true, 1, 1))
        assertFalse(shouldShowUniversalCompanionDeck(true, true, true, true, null, 1))
    }

    private fun achievement(bucket: Int = 0) = CompanionAchievement(
        id = 1,
        title = "Test achievement",
        description = "Do the thing",
        points = 10,
        unlocked = false,
        measuredProgress = "7/10",
        measuredPercent = 70f,
        bucket = bucket,
        rarity = 12.5f,
        type = 0,
        badgeUrl = null,
    )

    private fun event(
        type: Int,
        value: String? = null,
        bestValue: String? = null,
        rank: Int = 0,
        totalEntries: Int = 0,
        observedAtMillis: Long = 5_000L,
    ) = CompanionEvent(
        type = type,
        id = 1,
        title = "Test event",
        description = "",
        value = value,
        bestValue = bestValue,
        rank = rank,
        totalEntries = totalEntries,
        points = 10,
        badgeUrl = null,
        observedAtMillis = observedAtMillis,
    )

    private fun snapshot(
        richPresence: String? = null,
        featuredAchievement: CompanionAchievement? = null,
        activeChallenge: CompanionAchievement? = null,
        activeLeaderboard: CompanionLeaderboard? = null,
        recentEvent: CompanionEvent? = null,
    ) = UniversalCompanionSnapshot(
        richPresence = richPresence,
        memoryReady = true,
        unlockedAchievements = 2,
        totalAchievements = 10,
        featuredAchievement = featuredAchievement,
        activeChallenge = activeChallenge,
        activeLeaderboard = activeLeaderboard,
        recentEvent = recentEvent,
        observedAtMillis = 10_000L,
    )
}
