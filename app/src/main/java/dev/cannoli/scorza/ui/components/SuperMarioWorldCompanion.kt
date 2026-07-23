package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.libretro.SuperMarioWorldExit
import dev.cannoli.scorza.libretro.SuperMarioWorldLevel
import dev.cannoli.scorza.libretro.SuperMarioWorldReader
import dev.cannoli.scorza.libretro.SuperMarioWorldSnapshot
import dev.cannoli.scorza.libretro.SuperMarioWorldWorld

internal fun shouldShowSuperMarioWorldCompanion(
    gameActive: Boolean,
    displayName: String?,
    fileName: String?,
): Boolean = gameActive && SuperMarioWorldReader.matches(displayName, fileName)

@Composable
fun SuperMarioWorldCompanion(
    snapshot: SuperMarioWorldSnapshot?,
    modifier: Modifier = Modifier,
) {
    val readySnapshot = snapshot ?: run {
        Box(modifier.fillMaxSize().background(SmwBlack))
        return
    }
    val worldColumns = listOf(
        readySnapshot.worlds.take(3),
        readySnapshot.worlds.drop(3).take(3),
        readySnapshot.worlds.drop(6),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SmwBlack)
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SUPER MARIO WORLD",
                    color = Color.White,
                    fontSize = 20.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.2.sp,
                )
                Text(
                    text = "LIVE EXIT CHECKLIST",
                    color = SmwMuted,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }
            ExitLegend("N", "NORMAL")
            Spacer(Modifier.width(12.dp))
            ExitLegend("S", "SECRET")
            Spacer(Modifier.width(22.dp))
            Text(
                text = "${readySnapshot.completedExitCount}",
                color = SmwYellow,
                fontSize = 30.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.End,
            )
            Text(
                text = " / ${readySnapshot.totalExitCount}\nEXITS",
                color = SmwMuted,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            worldColumns.forEach { worlds ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    worlds.forEach { world ->
                        WorldChecklist(
                            world = world,
                            currentTranslevel = readySnapshot.currentTranslevel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitLegend(letter: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = letter,
            color = SmwBlack,
            fontSize = 8.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .size(13.dp)
                .background(SmwYellow, RoundedCornerShape(50))
                .padding(top = 2.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = SmwMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WorldChecklist(
    world: SuperMarioWorldWorld,
    currentTranslevel: Int,
) {
    Column {
        Text(
            text = world.name,
            color = SmwYellow,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 5.dp, bottom = 1.dp),
        )
        world.levels.forEach { level ->
            LevelChecklistRow(level, currentTranslevel == level.translevel)
        }
    }
}

@Composable
private fun LevelChecklistRow(level: SuperMarioWorldLevel, current: Boolean) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .background(if (current) SmwYellow.copy(alpha = 0.16f) else Color.Transparent, shape)
            .then(
                if (current) Modifier.border(0.8.dp, SmwYellow.copy(alpha = 0.9f), shape)
                else Modifier
            )
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = level.name,
            color = if (current) SmwYellow else SmwText,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        ExitPip(level.normalExit, "N")
        Spacer(Modifier.width(4.dp))
        level.secretExit?.let { ExitPip(it, "S") } ?: Spacer(Modifier.width(11.dp))
    }
}

@Composable
private fun ExitPip(exit: SuperMarioWorldExit, letter: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(9.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (exit.completed) {
                drawCircle(SmwYellow)
            } else {
                drawCircle(SmwIncomplete, style = Stroke(width = 1.1.dp.toPx()))
            }
        }
        if (exit.completed) {
            Text(
                text = letter,
                color = SmwBlack,
                fontSize = 5.5.sp,
                lineHeight = 6.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val SmwBlack = Color(0xFF000000)
private val SmwYellow = Color(0xFFFFD740)
private val SmwText = Color(0xFFE5E7EB)
private val SmwMuted = Color(0xFF8E96A3)
private val SmwIncomplete = Color(0xFF4A515C)
