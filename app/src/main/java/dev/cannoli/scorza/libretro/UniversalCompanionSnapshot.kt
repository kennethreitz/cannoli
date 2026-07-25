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
    val observedAtMillis: Long,
)
