package dev.cannoli.scorza.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.libretro.AriaMapCell
import dev.cannoli.scorza.libretro.AriaOfSorrowMapReader
import dev.cannoli.scorza.libretro.AriaOfSorrowMapSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal fun shouldShowAriaOfSorrowMap(
    gameActive: Boolean,
    displayName: String?,
    fileName: String?,
): Boolean = gameActive && AriaOfSorrowMapReader.matches(displayName, fileName)

@Composable
fun AriaOfSorrowMapCompanion(
    snapshot: AriaOfSorrowMapSnapshot?,
    modifier: Modifier = Modifier,
) {
    val readySnapshot = snapshot?.takeIf(AriaOfSorrowMapSnapshot::hasExploredRooms)
    if (readySnapshot == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(AriaBackground)
        )
        return
    }

    var showFullMap by remember { mutableStateOf(false) }
    val markerTransition = rememberInfiniteTransition(label = "Aria map marker")
    val markerAlpha by markerTransition.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Aria map marker alpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AriaBackground)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 10.dp, start = 18.dp),
        ) {
            Text(
                text = readySnapshot.characterName,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
            )
            Text(
                text = "LEVEL ${readySnapshot.playerLevel}",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = ariaExperienceToNextText(readySnapshot),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
            )
            Text(
                text = "HP ${readySnapshot.currentHp} / ${readySnapshot.maxHp}",
                color = AriaExploredRoom,
                fontSize = 10.sp,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 13.dp),
        ) {
            Text(
                text = "DRACULA'S CASTLE",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = readySnapshot.currentAreaName.uppercase(Locale.ROOT),
                color = AriaExploredRoom.copy(alpha = 0.78f),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 18.dp),
        ) {
            TextButton(onClick = { showFullMap = !showFullMap }) {
                Text(
                    text = if (showFullMap) "Explored" else "Full map",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = String.format(
                    Locale.ROOT,
                    "%.1f%% explored",
                    readySnapshot.explorationPercent,
                ),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 58.dp)
        ) {
            drawAriaCastleMap(
                snapshot = readySnapshot,
                showFullMap = showFullMap,
                markerAlpha = markerAlpha,
            )
        }

        Text(
            text = if (showFullMap) {
                "Full castle blueprint · discovered rooms are brighter"
            } else {
                "Live exploration · marker shows your current room"
            },
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}

internal fun ariaExperienceToNextText(snapshot: AriaOfSorrowMapSnapshot): String =
    when (val remaining = snapshot.experienceToNextLevel) {
        null -> ""
        0 -> if (snapshot.playerLevel == AriaOfSorrowMapSnapshot.MAX_PLAYER_LEVEL) {
            "MAX LEVEL"
        } else {
            "0 XP TO NEXT"
        }
        else -> String.format(Locale.ROOT, "%,d XP TO NEXT", remaining)
    }

private fun DrawScope.drawAriaCastleMap(
    snapshot: AriaOfSorrowMapSnapshot,
    showFullMap: Boolean,
    markerAlpha: Float,
) {
    val displayedCells = if (showFullMap) {
        snapshot.layout.cells
    } else {
        snapshot.layout.cells.filter { snapshot.isBlueprintMapped(it.x, it.y) }
    }.toMutableList()
    snapshot.layout.cellAt(snapshot.playerTileX, snapshot.playerTileY)?.let { playerCell ->
        if (displayedCells.none { it.x == playerCell.x && it.y == playerCell.y }) {
            displayedCells += playerCell
        }
    }
    if (displayedCells.isEmpty()) return

    val minX = max(0, displayedCells.minOf(AriaMapCell::x) - MAP_MARGIN_TILES)
    val maxX = min(63, displayedCells.maxOf(AriaMapCell::x) + MAP_MARGIN_TILES)
    val minY = max(0, displayedCells.minOf(AriaMapCell::y) - MAP_MARGIN_TILES)
    val maxY = min(34, displayedCells.maxOf(AriaMapCell::y) + MAP_MARGIN_TILES)
    val tileSize = min(
        size.width / (maxX - minX + 1),
        size.height / (maxY - minY + 1),
    )
    val mapWidth = (maxX - minX + 1) * tileSize
    val mapHeight = (maxY - minY + 1) * tileSize
    val origin = Offset(
        x = (size.width - mapWidth) / 2f,
        y = (size.height - mapHeight) / 2f,
    )
    val displayedCoordinates = displayedCells
        .asSequence()
        .map { it.x to it.y }
        .toHashSet()

    for (cell in displayedCells) {
        val visited = snapshot.isVisited(cell.x, cell.y)
        val left = origin.x + (cell.x - minX) * tileSize
        val top = origin.y + (cell.y - minY) * tileSize
        val fill = if (visited) AriaExploredRoom else AriaBlueprintRoom
        drawRect(
            color = fill,
            topLeft = Offset(left, top),
            size = Size(tileSize, tileSize),
        )

        val outline = if (visited) AriaExploredOutline else AriaBlueprintOutline
        val strokeWidth = max(1f, tileSize * 0.08f)
        fun sharesRoom(x: Int, y: Int): Boolean {
            if (x to y !in displayedCoordinates) return false
            val neighbor = snapshot.layout.cellAt(x, y) ?: return false
            return neighbor.area == cell.area && neighbor.room == cell.room
        }
        if (!sharesRoom(cell.x, cell.y - 1)) {
            drawLine(outline, Offset(left, top), Offset(left + tileSize, top), strokeWidth)
        }
        if (!sharesRoom(cell.x + 1, cell.y)) {
            drawLine(
                outline,
                Offset(left + tileSize, top),
                Offset(left + tileSize, top + tileSize),
                strokeWidth,
            )
        }
        if (!sharesRoom(cell.x, cell.y + 1)) {
            drawLine(
                outline,
                Offset(left, top + tileSize),
                Offset(left + tileSize, top + tileSize),
                strokeWidth,
            )
        }
        if (!sharesRoom(cell.x - 1, cell.y)) {
            drawLine(outline, Offset(left, top), Offset(left, top + tileSize), strokeWidth)
        }

        if (visited && (cell.saveRoom || cell.warpRoom)) {
            val symbolRadius = max(1.2f, tileSize * 0.18f)
            val center = Offset(left + tileSize / 2f, top + tileSize / 2f)
            if (cell.warpRoom) {
                drawCircle(
                    color = AriaRoomSymbol,
                    radius = symbolRadius,
                    center = center,
                    style = Stroke(width = max(1f, tileSize * 0.08f)),
                )
            } else {
                drawRect(
                    color = AriaRoomSymbol,
                    topLeft = Offset(center.x - symbolRadius, center.y - symbolRadius),
                    size = Size(symbolRadius * 2, symbolRadius * 2),
                )
            }
        }
    }

    val markerCenter = Offset(
        x = origin.x + (snapshot.playerTileX - minX + 0.5f) * tileSize,
        y = origin.y + (snapshot.playerTileY - minY + 0.5f) * tileSize,
    )
    val markerRadius = min(MAX_MARKER_RADIUS, max(5f, tileSize * 0.62f))
    drawCircle(
        color = Color.Black.copy(alpha = markerAlpha * 0.78f),
        radius = markerRadius + max(2f, tileSize * 0.1f),
        center = markerCenter,
    )
    drawCircle(
        color = AriaCurrentRoom.copy(alpha = markerAlpha),
        radius = markerRadius,
        center = markerCenter,
    )
    drawCircle(
        color = Color.White.copy(alpha = markerAlpha),
        radius = markerRadius,
        center = markerCenter,
        style = Stroke(width = max(2f, tileSize * 0.11f)),
    )
}

private val AriaBackground = Color.Black
private val AriaExploredRoom = Color(0xFF3E8ED0)
private val AriaExploredOutline = Color(0xFFB9E4FF)
private val AriaBlueprintRoom = Color(0xFF0D2438)
private val AriaBlueprintOutline = Color(0xFF28567B)
private val AriaRoomSymbol = Color(0xFFDBF1FF)
private val AriaCurrentRoom = Color(0xFF63C7FF)
private const val MAP_MARGIN_TILES = 2
private const val MAX_MARKER_RADIUS = 18f
