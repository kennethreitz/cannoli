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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.libretro.UniversalCompanionSnapshot
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.model.Rom
import dev.cannoli.ui.components.SaveSyncStatus
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

        Spacer(Modifier.weight(0.42f))
        if (presence != null) {
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
        } else {
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
        Spacer(Modifier.weight(0.58f))

        if (snapshot != null && snapshot.totalAchievements > 0) {
            AchievementProgress(
                unlocked = snapshot.unlockedAchievements,
                total = snapshot.totalAchievements,
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
        Text(
            text = "$unlocked / $total",
            color = CompanionText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
private val CompanionText = Color(0xFFE9EDF0)
private val CompanionMuted = Color(0xFF737A82)
private val CompanionTrack = Color(0xFF171A1E)
