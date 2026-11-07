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
import dev.cannoli.scorza.libretro.MetroidGbaMapCell
import dev.cannoli.scorza.libretro.MetroidGbaMapReader
import dev.cannoli.scorza.libretro.MetroidGbaMapSnapshot
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal fun shouldShowMetroidGbaMap(
    gameActive: Boolean,
    displayName: String?,
    fileName: String?,
): Boolean = gameActive && MetroidGbaMapReader.matches(displayName, fileName)

@Composable
fun MetroidGbaMapCompanion(
    snapshot: MetroidGbaMapSnapshot?,
    modifier: Modifier = Modifier,
) {
    val readySnapshot = snapshot?.takeIf(MetroidGbaMapSnapshot::hasExploredRooms)
    if (readySnapshot == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        return
    }

    var showFullMap by remember(readySnapshot.game) { mutableStateOf(false) }
    val markerTransition = rememberInfiniteTransition(label = "Metroid map marker")
    val markerAlpha by markerTransition.animateFloat(
        initialValue = 0.42f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Metroid map marker alpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 18.dp),
        ) {
            Text(
                text = readySnapshot.game.displayTitle,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
            )
            Text(
                text = "LIVE AREA MAP",
                color = MetroidExploredRoom.copy(alpha = 0.8f),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp),
        ) {
            Text(
                text = readySnapshot.areaName.uppercase(Locale.ROOT),
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Text(
                text = "ROOM ${readySnapshot.currentRoom + 1}",
                color = Color.White.copy(alpha = 0.56f),
                fontSize = 9.sp,
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
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 58.dp)
        ) {
            drawMetroidAreaMap(
                snapshot = readySnapshot,
                showFullMap = showFullMap,
                markerAlpha = markerAlpha,
            )
        }

        Text(
            text = if (showFullMap) {
                "Full area layout · explored rooms are brighter"
            } else {
                "Live exploration · save rooms are red"
            },
            color = Color.White.copy(alpha = 0.66f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}

private fun DrawScope.drawMetroidAreaMap(
    snapshot: MetroidGbaMapSnapshot,
    showFullMap: Boolean,
    markerAlpha: Float,
) {
    val displayedCells = if (showFullMap) {
        snapshot.layout.cells
    } else {
        snapshot.layout.cells.filter { snapshot.isVisited(it.x, it.y) }
    }.toMutableList()
    snapshot.layout.cellAt(snapshot.playerTileX, snapshot.playerTileY)?.let { playerCell ->
        if (displayedCells.none { it.x == playerCell.x && it.y == playerCell.y }) {
            displayedCells += playerCell
        }
    }
    if (displayedCells.isEmpty()) return

    val minX = max(0, displayedCells.minOf(MetroidGbaMapCell::x) - MAP_MARGIN_TILES)
    val maxX = min(31, displayedCells.maxOf(MetroidGbaMapCell::x) + MAP_MARGIN_TILES)
    val minY = max(0, displayedCells.minOf(MetroidGbaMapCell::y) - MAP_MARGIN_TILES)
    val maxY = min(31, displayedCells.maxOf(MetroidGbaMapCell::y) + MAP_MARGIN_TILES)
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
    val gap = min(1.8f, max(0.55f, tileSize * 0.07f))
    val strokeWidth = min(2.2f, max(0.8f, tileSize * 0.07f))

    for (cell in displayedCells) {
        val visited = snapshot.isVisited(cell.x, cell.y)
        val left = origin.x + (cell.x - minX) * tileSize + gap
        val top = origin.y + (cell.y - minY) * tileSize + gap
        val roomSize = Size(
            width = (tileSize - gap * 2f).coerceAtLeast(1f),
            height = (tileSize - gap * 2f).coerceAtLeast(1f),
        )
        val fill = when {
            visited && cell.saveRoom -> MetroidSaveRoom
            visited -> MetroidExploredRoom
            else -> MetroidBlueprintRoom
        }
        val outline = when {
            visited && cell.saveRoom -> MetroidSaveRoomOutline
            visited -> MetroidExploredOutline
            else -> MetroidBlueprintOutline
        }
        drawRect(
            color = fill,
            topLeft = Offset(left, top),
            size = roomSize,
        )
        drawRect(
            color = outline,
            topLeft = Offset(left, top),
            size = roomSize,
            style = Stroke(width = strokeWidth),
        )
        if (visited && cell.saveRoom && tileSize >= 8f) {
            val symbolSize = max(1.5f, tileSize * 0.2f)
            drawRect(
                color = Color.White.copy(alpha = 0.88f),
                topLeft = Offset(
                    left + roomSize.width / 2f - symbolSize / 2f,
                    top + roomSize.height / 2f - symbolSize / 2f,
                ),
                size = Size(symbolSize, symbolSize),
            )
        }
    }

    val markerCenter = Offset(
        x = origin.x + (snapshot.playerTileX - minX + 0.5f) * tileSize,
        y = origin.y + (snapshot.playerTileY - minY + 0.5f) * tileSize,
    )
    val markerRadius = min(MAX_MARKER_RADIUS, max(5f, tileSize * 0.56f))
    drawCircle(
        color = Color.Black.copy(alpha = markerAlpha * 0.82f),
        radius = markerRadius + max(2f, tileSize * 0.12f),
        center = markerCenter,
    )
    drawCircle(
        color = MetroidCurrentRoom.copy(alpha = markerAlpha),
        radius = markerRadius,
        center = markerCenter,
    )
    drawCircle(
        color = Color.White.copy(alpha = markerAlpha),
        radius = markerRadius,
        center = markerCenter,
        style = Stroke(width = max(2f, tileSize * 0.1f)),
    )
}

private val MetroidExploredRoom = Color(0xFF2F83D3)
private val MetroidExploredOutline = Color(0xFFB7E3FF)
private val MetroidSaveRoom = Color(0xFFB83242)
private val MetroidSaveRoomOutline = Color(0xFFFFB3BC)
private val MetroidBlueprintRoom = Color(0xFF0A2034)
private val MetroidBlueprintOutline = Color(0xFF28567B)
private val MetroidCurrentRoom = Color(0xFFFFB547)
private const val MAP_MARGIN_TILES = 2
private const val MAX_MARKER_RADIUS = 17f
