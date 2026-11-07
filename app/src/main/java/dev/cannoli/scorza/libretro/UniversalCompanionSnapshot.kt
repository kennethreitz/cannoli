package dev.cannoli.scorza.libretro

/**
 * Game-aware context derived from the live Libretro memory stream.
 *
 * Rich Presence is evaluated by rcheevos using the current game's own memory
 * definition, so the UI can present meaningful state without guessing what
 * arbitrary memory addresses mean.
 */
data class UniversalCompanionSnapshot(
    val richPresence: String?,
    val memoryReady: Boolean,
    val unlockedAchievements: Int,
    val totalAchievements: Int,
    val unlockedPoints: Int = 0,
    val totalPoints: Int = 0,
    val sessionUnlockedAchievements: Int = 0,
    val sessionPoints: Int = 0,
    val featuredAchievement: CompanionAchievement? = null,
    val activeChallenge: CompanionAchievement? = null,
    val activeLeaderboard: CompanionLeaderboard? = null,
    val recentEvent: CompanionEvent? = null,
    val observedAtMillis: Long,
)

data class CompanionAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val unlocked: Boolean,
    val measuredProgress: String?,
    val measuredPercent: Float?,
    val bucket: Int,
    val rarity: Float?,
    val type: Int,
    val badgeUrl: String?,
)

data class CompanionLeaderboard(
    val id: Int,
    val title: String,
    val description: String,
    val trackerValue: String,
)

data class CompanionEvent(
    val type: Int,
    val id: Int,
    val title: String,
    val description: String,
    val value: String?,
    val bestValue: String?,
    val rank: Int,
    val totalEntries: Int,
    val points: Int,
    val badgeUrl: String?,
    val observedAtMillis: Long,
) {
    companion object {
        const val ACHIEVEMENT_UNLOCKED = 1
        const val LEADERBOARD_FAILED = 3
        const val LEADERBOARD_SUBMITTED = 4
        const val LEADERBOARD_SCOREBOARD = 13
        const val GAME_COMPLETED = 15
        const val SUBSET_COMPLETED = 19
    }
}

internal const val COMPANION_BUCKET_ACTIVE_CHALLENGE = 6
internal const val COMPANION_BUCKET_ALMOST_THERE = 7
internal const val COMPANION_ACHIEVEMENT_TYPE_PROGRESSION = 2
