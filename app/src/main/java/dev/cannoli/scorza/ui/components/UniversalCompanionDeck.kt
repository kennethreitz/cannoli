package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.libretro.UniversalCompanionSnapshot
import dev.cannoli.scorza.libretro.CompanionAchievement
import dev.cannoli.scorza.libretro.CompanionEvent
import dev.cannoli.scorza.libretro.CompanionLeaderboard
import dev.cannoli.scorza.libretro.COMPANION_ACHIEVEMENT_TYPE_PROGRESSION
import dev.cannoli.scorza.libretro.COMPANION_BUCKET_ACTIVE_CHALLENGE
import dev.cannoli.scorza.libretro.COMPANION_BUCKET_ALMOST_THERE
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.ui.components.SaveSyncStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

internal fun shouldShowUniversalCompanionDeck(
    experimentalFeatures: Boolean,
    dualScreenLaunching: Boolean,
    enabled: Boolean,
    gameActive: Boolean,
    gameDisplayId: Int?,
    launcherDisplayId: Int,
): Boolean = experimentalFeatures &&
    dualScreenLaunching &&
    enabled &&
    gameActive &&
    gameDisplayId != null &&
    gameDisplayId != launcherDisplayId

internal data class CompanionStat(
    val label: String,
    val value: String,
)

internal data class CompanionPresence(
    val headline: String,
    val stats: List<CompanionStat>,
)

/**
 * Turns common rich-presence formats into a calm headline plus optional stats.
 * Free-form presence remains untouched instead of trying to invent structure.
 */
internal fun parseCompanionPresence(message: String?): CompanionPresence? {
    val clean = message
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val segments = message
        .split(Regex("\\s*(?:\\n|[•|]|\\s·\\s)\\s*"))
        .map { it.trim().replace(Regex("\\s+"), " ") }
        .filter(String::isNotEmpty)
    val parsed = segments.map { segment ->
        val match = COMPANION_STAT_REGEX.matchEntire(segment) ?: return@map null
        CompanionStat(
            label = match.groupValues[1].trim(),
            value = match.groupValues[2].trim(),
        )
    }
    val stats = parsed.filterNotNull()
    val prose = segments.filterIndexed { index, _ -> parsed[index] == null }

    return when {
        stats.isEmpty() -> CompanionPresence(clean, emptyList())
        prose.isNotEmpty() -> CompanionPresence(
            headline = prose.joinToString(" · "),
            stats = stats.take(MAX_COMPANION_STATS),
        )
        stats.size > 1 -> CompanionPresence(
            headline = stats.first().value,
            stats = stats.drop(1).take(MAX_COMPANION_STATS),
        )
        else -> CompanionPresence(clean, emptyList())
    }
}

internal fun companionAchievementProgress(unlocked: Int, total: Int): Float =
    if (total <= 0) 0f else unlocked.coerceIn(0, total).toFloat() / total.toFloat()

internal enum class CompanionMainMode {
    LEADERBOARD,
    ACTIVE_CHALLENGE,
    RECENT_EVENT,
    PRESENCE,
    FEATURED_ACHIEVEMENT,
    FALLBACK,
}

internal fun companionMainMode(
    snapshot: UniversalCompanionSnapshot?,
    nowMillis: Long,
): CompanionMainMode = when {
    snapshot?.activeLeaderboard != null -> CompanionMainMode.LEADERBOARD
    snapshot?.activeChallenge != null -> CompanionMainMode.ACTIVE_CHALLENGE
    snapshot?.recentEvent?.let {
        nowMillis - it.observedAtMillis <= COMPANION_EVENT_VISIBLE_MS
    } == true -> CompanionMainMode.RECENT_EVENT
    !snapshot?.richPresence.isNullOrBlank() -> CompanionMainMode.PRESENCE
    snapshot?.featuredAchievement != null -> CompanionMainMode.FEATURED_ACHIEVEMENT
    else -> CompanionMainMode.FALLBACK
}

@Composable
fun UniversalCompanionDeck(
    rom: Rom?,
    startedAtMillis: Long?,
    saveSyncStatus: SaveSyncStatus,
    snapshot: UniversalCompanionSnapshot?,
    modifier: Modifier = Modifier,
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val title = rom?.displayName?.takeIf(String::isNotBlank) ?: "Game session"
    val platform = rom?.platformTag
        ?.replace('_', ' ')
        ?.uppercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: "GAME"
    val source = rom?.launchTarget?.let(::companionLaunchLabel)
    val presence = parseCompanionPresence(snapshot?.richPresence)
    val liveMemory = snapshot?.memoryReady == true
    val accent = companionAccent(title)
    val mainMode = companionMainMode(snapshot, nowMillis)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 38.dp, vertical = 30.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = platform,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.2.sp,
            )
            source?.let {
                Text(
                    text = "  /  $it",
                    color = CompanionMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatCompanionElapsed(startedAtMillis, nowMillis),
                color = CompanionMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.1.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 29.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.weight(0.34f))
        when (mainMode) {
            CompanionMainMode.LEADERBOARD -> snapshot?.activeLeaderboard?.let {
                LeaderboardPanel(it, accent)
            }
            CompanionMainMode.ACTIVE_CHALLENGE -> snapshot?.activeChallenge?.let {
                AchievementPanel("ACTIVE CHALLENGE", it, accent)
            }
            CompanionMainMode.RECENT_EVENT -> snapshot?.recentEvent?.let {
                CompanionEventPanel(it, accent)
            }
            CompanionMainMode.PRESENCE -> presence?.let {
                PresencePanel(it, accent)
            }
            CompanionMainMode.FEATURED_ACHIEVEMENT -> snapshot?.featuredAchievement?.let {
                AchievementPanel(companionAchievementLabel(it), it, accent)
            }
            CompanionMainMode.FALLBACK -> {
                Text(
                    text = if (liveMemory) "Playing" else "Session in progress",
                    color = CompanionText,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = companionFallbackText(rom, snapshot),
                    color = CompanionMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.weight(0.46f))

        val featured = snapshot?.featuredAchievement
        val mainAlreadyShowsAchievement =
            mainMode == CompanionMainMode.ACTIVE_CHALLENGE ||
                mainMode == CompanionMainMode.FEATURED_ACHIEVEMENT ||
                (mainMode == CompanionMainMode.RECENT_EVENT &&
                    snapshot?.recentEvent?.type == CompanionEvent.ACHIEVEMENT_UNLOCKED)
        if (featured != null && !mainAlreadyShowsAchievement) {
            CompactAchievementProgress(featured, accent)
            Spacer(Modifier.height(20.dp))
        }

        if (snapshot != null && snapshot.totalAchievements > 0) {
            AchievementProgress(
                unlocked = snapshot.unlockedAchievements,
                total = snapshot.totalAchievements,
                unlockedPoints = snapshot.unlockedPoints,
                totalPoints = snapshot.totalPoints,
                sessionUnlocked = snapshot.sessionUnlockedAchievements,
                sessionPoints = snapshot.sessionPoints,
                accent = accent,
            )
            Spacer(Modifier.height(22.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(companionSaveSyncColor(saveSyncStatus))
            Spacer(Modifier.width(8.dp))
            Text(
                text = companionSaveSyncLabel(saveSyncStatus),
                color = CompanionMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.weight(1f))
            if (presence != null) {
                Text(
                    text = "LIVE GAME DATA",
                    color = accent.copy(alpha = 0.82f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
        }
    }
}

@Composable
private fun PresencePanel(
    presence: CompanionPresence,
    accent: Color,
) {
    Text(
        text = "RIGHT NOW",
        color = CompanionMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(9.dp))
    Text(
        text = presence.headline,
        color = CompanionText,
        fontSize = 27.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    if (presence.stats.isNotEmpty()) {
        Spacer(Modifier.height(26.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            presence.stats.forEach { stat ->
                CompanionStatBlock(
                    stat = stat,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
            }
            repeat((MAX_COMPANION_STATS - presence.stats.size).coerceAtLeast(0)) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AchievementPanel(
    label: String,
    achievement: CompanionAchievement,
    accent: Color,
) {
    Text(
        text = label,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CompanionBadge(achievement.badgeUrl)
        if (!achievement.badgeUrl.isNullOrBlank()) Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = achievement.title,
                color = CompanionText,
                fontSize = 24.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (achievement.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = achievement.description,
                    color = CompanionMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    AchievementMeasurement(achievement, accent, compact = false)
}

@Composable
private fun CompactAchievementProgress(
    achievement: CompanionAchievement,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = companionAchievementLabel(achievement),
                color = CompanionMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = achievement.title,
                color = CompanionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        achievement.measuredProgress?.let {
            Spacer(Modifier.width(16.dp))
            Text(
                text = it,
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    AchievementMeasurement(achievement, accent, compact = true)
}

@Composable
private fun AchievementMeasurement(
    achievement: CompanionAchievement,
    accent: Color,
    compact: Boolean,
) {
    val measured = achievement.measuredPercent?.div(100f)?.coerceIn(0f, 1f)
    if (measured != null) {
        Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 3.dp else 5.dp),
        ) {
            drawRect(color = CompanionTrack)
            drawRect(color = accent, size = size.copy(width = size.width * measured))
        }
    }
    if (!compact) {
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            achievement.measuredProgress?.let {
                Text(
                    text = it,
                    color = accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                text = "${achievement.points} PTS",
                color = CompanionMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            companionAchievementTypeLabel(achievement.type)?.let {
                Text(
                    text = "  /  $it",
                    color = CompanionMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            achievement.rarity?.let {
                Text(
                    text = "${formatCompanionPercent(it)} UNLOCKED",
                    color = CompanionMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
            }
        }
    }
}

@Composable
private fun LeaderboardPanel(
    leaderboard: CompanionLeaderboard,
    accent: Color,
) {
    Text(
        text = "LIVE LEADERBOARD",
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text(
        text = leaderboard.title,
        color = CompanionText,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(14.dp))
    Text(
        text = leaderboard.trackerValue.ifBlank { "Attempt in progress" },
        color = Color.White,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Bold,
    )
    if (leaderboard.description.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = leaderboard.description,
            color = CompanionMuted,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompanionEventPanel(
    event: CompanionEvent,
    accent: Color,
) {
    Text(
        text = companionEventLabel(event.type),
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.8.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        CompanionBadge(event.badgeUrl)
        if (!event.badgeUrl.isNullOrBlank()) Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title.ifBlank { companionEventFallbackTitle(event.type) },
                color = CompanionText,
                fontSize = 25.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = event.description,
                    color = CompanionMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val result = companionEventResult(event)
    if (result.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        Text(
            text = result,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
    }
}

@Composable
private fun CompanionBadge(url: String?) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(62.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(CompanionTrack),
    )
}

@Composable
private fun CompanionStatBlock(
    stat: CompanionStat,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accent.copy(alpha = 0.55f), RoundedCornerShape(1.dp))
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stat.label.uppercase(Locale.ROOT),
            color = CompanionMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stat.value,
            color = CompanionText,
            fontSize = 18.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AchievementProgress(
    unlocked: Int,
    total: Int,
    unlockedPoints: Int,
    totalPoints: Int,
    sessionUnlocked: Int,
    sessionPoints: Int,
    accent: Color,
) {
    val progress = companionAchievementProgress(unlocked, total)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ACHIEVEMENTS",
                color = CompanionMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                drawRect(color = CompanionTrack)
                drawRect(
                    color = accent,
                    size = size.copy(width = size.width * progress),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$unlocked / $total",
                color = CompanionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (totalPoints > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$unlockedPoints / $totalPoints PTS",
                    color = CompanionMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            if (sessionUnlocked > 0 || sessionPoints > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "SESSION +$sessionUnlocked  /  +$sessionPoints PTS",
                    color = accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Canvas(modifier = Modifier.width(7.dp).height(7.dp)) {
        drawCircle(color = color)
    }
}

internal fun formatCompanionElapsed(startedAtMillis: Long?, nowMillis: Long): String {
    val elapsedSeconds = ((nowMillis - (startedAtMillis ?: nowMillis)) / 1_000L).coerceAtLeast(0)
    val hours = elapsedSeconds / 3_600
    val minutes = elapsedSeconds % 3_600 / 60
    val seconds = elapsedSeconds % 60
    return "%02d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
}

internal fun companionSaveSyncLabel(status: SaveSyncStatus): String = when (status) {
    SaveSyncStatus.DISABLED -> "SAVE SYNC OFF"
    SaveSyncStatus.OFFLINE -> "SAVES OFFLINE"
    SaveSyncStatus.UP_TO_DATE -> "SAVES SYNCED"
    SaveSyncStatus.CHECKING -> "CHECKING SAVES"
    SaveSyncStatus.UPLOADING -> "UPLOADING SAVE"
    SaveSyncStatus.DOWNLOADING -> "DOWNLOADING SAVE"
    SaveSyncStatus.CONFLICT -> "SAVE NEEDS ATTENTION"
    SaveSyncStatus.ERROR -> "SAVE SYNC ERROR"
}

private fun companionSaveSyncColor(status: SaveSyncStatus): Color = when (status) {
    SaveSyncStatus.UP_TO_DATE -> Color(0xFF79D9A6)
    SaveSyncStatus.CHECKING, SaveSyncStatus.UPLOADING, SaveSyncStatus.DOWNLOADING ->
        Color(0xFF80BFE8)
    SaveSyncStatus.CONFLICT -> Color(0xFFE7B45B)
    SaveSyncStatus.ERROR, SaveSyncStatus.OFFLINE -> Color(0xFFE47777)
    SaveSyncStatus.DISABLED -> CompanionMuted
}

private fun companionLaunchLabel(target: LaunchTarget): String = when (target) {
    LaunchTarget.RetroArch -> "RETROARCH"
    is LaunchTarget.Embedded -> "LIBRETRO"
    is LaunchTarget.EmuLaunch -> "STANDALONE"
    is LaunchTarget.ApkLaunch -> "ANDROID"
}

internal fun companionAchievementLabel(achievement: CompanionAchievement): String = when {
    achievement.bucket == COMPANION_BUCKET_ACTIVE_CHALLENGE -> "ACTIVE CHALLENGE"
    achievement.bucket == COMPANION_BUCKET_ALMOST_THERE -> "ALMOST THERE"
    achievement.type == COMPANION_ACHIEVEMENT_TYPE_PROGRESSION -> "PROGRESSION"
    else -> "ACHIEVEMENT PROGRESS"
}

private fun companionAchievementTypeLabel(type: Int): String? = when (type) {
    1 -> "MISSABLE"
    2 -> "PROGRESSION"
    3 -> "COMPLETION"
    else -> null
}

internal fun companionEventLabel(type: Int): String = when (type) {
    CompanionEvent.ACHIEVEMENT_UNLOCKED -> "ACHIEVEMENT UNLOCKED"
    CompanionEvent.LEADERBOARD_FAILED -> "ATTEMPT ENDED"
    CompanionEvent.LEADERBOARD_SUBMITTED -> "SCORE SUBMITTED"
    CompanionEvent.LEADERBOARD_SCOREBOARD -> "LEADERBOARD RESULT"
    CompanionEvent.GAME_COMPLETED -> "ACHIEVEMENTS COMPLETE"
    CompanionEvent.SUBSET_COMPLETED -> "SUBSET COMPLETE"
    else -> "RETROACHIEVEMENTS"
}

private fun companionEventFallbackTitle(type: Int): String = when (type) {
    CompanionEvent.GAME_COMPLETED -> "Every core achievement unlocked"
    CompanionEvent.SUBSET_COMPLETED -> "Achievement subset completed"
    CompanionEvent.LEADERBOARD_FAILED -> "Leaderboard attempt finished"
    else -> "Game progress updated"
}

internal fun companionEventResult(event: CompanionEvent): String = when (event.type) {
    CompanionEvent.ACHIEVEMENT_UNLOCKED ->
        if (event.points > 0) "+${event.points} POINTS" else ""
    CompanionEvent.LEADERBOARD_FAILED ->
        event.value.orEmpty()
    CompanionEvent.LEADERBOARD_SUBMITTED ->
        event.value?.let { "SUBMITTED  $it" }.orEmpty()
    CompanionEvent.LEADERBOARD_SCOREBOARD -> buildList {
        event.value?.let { add("SCORE  $it") }
        if (event.rank > 0) {
            add(
                if (event.totalEntries > 0) {
                    "RANK  ${event.rank} / ${event.totalEntries}"
                } else {
                    "RANK  ${event.rank}"
                }
            )
        }
        event.bestValue?.takeIf { it != event.value }?.let { add("BEST  $it") }
    }.joinToString("   ·   ")
    else -> ""
}

private fun formatCompanionPercent(value: Float): String =
    if (value >= 10f) {
        "${value.toInt()}%"
    } else {
        "%.1f%%".format(Locale.ROOT, value)
    }

private fun companionFallbackText(
    rom: Rom?,
    snapshot: UniversalCompanionSnapshot?,
): String = when {
    snapshot?.memoryReady == true ->
        "This game is recognized, but it does not publish additional live context."
    rom?.launchTarget is LaunchTarget.Embedded ->
        "Live details will appear when this game's memory profile is available."
    else ->
        "This emulator does not currently expose live game details to Cannoli."
}

private fun companionAccent(title: String): Color {
    val palette = listOf(
        Color(0xFF78D7B0),
        Color(0xFF78BCE8),
        Color(0xFFC1A6EF),
        Color(0xFFE8AA78),
        Color(0xFFE4819D),
    )
    return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
}

private val COMPANION_STAT_REGEX = Regex("^([^:]{1,24}):\\s*(.{1,64})$")
private const val MAX_COMPANION_STATS = 3
private const val COMPANION_EVENT_VISIBLE_MS = 7_000L
private val CompanionText = Color(0xFFE9EDF0)
private val CompanionMuted = Color(0xFF737A82)
private val CompanionTrack = Color(0xFF171A1E)
