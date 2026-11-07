package dev.cannoli.scorza.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.libretro.SotnMapReader
import dev.cannoli.scorza.libretro.SotnMapSnapshot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

internal fun shouldShowSotnCastleMap(
    gameActive: Boolean,
    displayName: String?,
    fileName: String?,
): Boolean = gameActive && SotnMapReader.matches(displayName, fileName)

@Composable
fun SotnCastleMapCompanion(
    snapshot: SotnMapSnapshot?,
    modifier: Modifier = Modifier,
) {
    val readySnapshot = snapshot?.takeIf(SotnMapSnapshot::hasExploredRooms)
    if (readySnapshot == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(CastleMapBackground)
        )
        return
    }

    val resources = LocalContext.current.resources
    val maps = remember(resources) { loadCastleMaps(resources) }
    var showFullMap by remember { mutableStateOf(false) }
    val markerTransition = rememberInfiniteTransition(label = "SotN map marker")
    val markerAlpha by markerTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "SotN map marker alpha",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CastleMapBackground)
    ) {
        Text(
            text = if (readySnapshot.invertedCastle) "INVERTED CASTLE" else "DRACULA'S CASTLE",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp),
        )
        SotnPlayerProgress(
            snapshot = readySnapshot,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 10.dp, start = 18.dp),
        )
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
                text = String.format(Locale.ROOT, "%.1f%% explored", readySnapshot.explorationPercent),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 54.dp)
        ) {
            if (showFullMap) {
                drawFullCastleMap(maps.original, readySnapshot, markerAlpha)
            } else {
                drawDiscoveredCastleMap(
                    map = maps.original,
                    unexploredMap = maps.unexplored,
                    snapshot = readySnapshot,
                    markerAlpha = markerAlpha,
                )
            }
        }
        Text(
            text = if (showFullMap) {
                "Full castle blueprint"
            } else {
                "Live exploration · marker shows your current room"
            },
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun SotnPlayerProgress(
    snapshot: SotnMapSnapshot,
    modifier: Modifier = Modifier,
) {
    if (snapshot.playerLevel !in 1..SotnMapSnapshot.MAX_PLAYER_LEVEL) return

    Column(modifier = modifier) {
        Text(
            text = "ALUCARD",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
        )
        Text(
            text = "LEVEL ${snapshot.playerLevel}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = sotnExperienceToNextText(snapshot),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp,
        )
    }
}

internal fun sotnExperienceToNextText(snapshot: SotnMapSnapshot): String =
    when (val remaining = snapshot.experienceToNextLevel) {
        null -> ""
        0 -> if (snapshot.playerLevel == SotnMapSnapshot.MAX_PLAYER_LEVEL) {
            "MAX LEVEL"
        } else {
            "0 XP TO NEXT"
        }
        else -> String.format(Locale.ROOT, "%,d XP TO NEXT", remaining)
    }

private data class CastleMapImages(
    val original: ImageBitmap,
    val unexplored: ImageBitmap,
)

private fun loadCastleMaps(
    resources: android.content.res.Resources,
): CastleMapImages {
    val source = BitmapFactory.decodeResource(resources, R.drawable.sotn_castle_map)
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val unexploredPixels = pixels.copyOf()
    for (index in pixels.indices) {
        if (pixels[index] == SourceBackgroundArgb) {
            pixels[index] = android.graphics.Color.TRANSPARENT
            unexploredPixels[index] = android.graphics.Color.TRANSPARENT
        } else {
            unexploredPixels[index] = sotnUnexploredMapColor(pixels[index])
        }
    }
    return CastleMapImages(
        original = pixels.toCastleMapBitmap(source.width, source.height),
        unexplored = unexploredPixels.toCastleMapBitmap(source.width, source.height),
    )
}

private fun IntArray.toCastleMapBitmap(width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(this@toCastleMapBitmap, 0, width, 0, 0, width, height)
    }.asImageBitmap()

internal fun sotnUnexploredMapColor(argb: Int): Int = when (argb) {
    MapDoorArgb, MapWarpArgb -> BlueprintRoomArgb
    else -> argb
}

private fun DrawScope.drawFullCastleMap(
    map: ImageBitmap,
    snapshot: SotnMapSnapshot,
    markerAlpha: Float,
) {
    val invertedCastle = snapshot.invertedCastle
    val sourceTop = if (invertedCastle) InvertedCastleTopPx else 0
    val sourceHeight = if (invertedCastle) InvertedCastleHeightPx else NormalCastleHeightPx
    val sourceSize = IntSize(map.width, min(sourceHeight, map.height - sourceTop))
    val scale = min(size.width / sourceSize.width, size.height / sourceSize.height)
    val destinationSize = IntSize(
        width = (sourceSize.width * scale).roundToInt(),
        height = (sourceSize.height * scale).roundToInt(),
    )
    val destinationOffset = IntOffset(
        x = ((size.width - destinationSize.width) / 2f).roundToInt(),
        y = ((size.height - destinationSize.height) / 2f).roundToInt(),
    )
    drawImage(
        image = map,
        srcOffset = IntOffset(0, sourceTop),
        srcSize = sourceSize,
        dstOffset = destinationOffset,
        dstSize = destinationSize,
        filterQuality = FilterQuality.None,
    )
    val displayTile = snapshot.playerDisplayTile()
    val sourceOrigin = snapshot.sourceMapOrigin()
    drawLocationMarker(
        center = Offset(
            x = destinationOffset.x +
                (displayTile.first * SOURCE_TILE_STEP_PX - sourceOrigin.first + 2f) * scale,
            y = destinationOffset.y +
                (displayTile.second * SOURCE_TILE_STEP_PX - sourceOrigin.second + 2f) * scale,
        ),
        radius = min(MAX_LOCATION_MARKER_RADIUS, max(5f, SOURCE_TILE_STEP_PX * scale * 0.58f)),
        alpha = markerAlpha,
        outlineWidth = max(2f, scale * 0.5f),
    )
}

private fun DrawScope.drawDiscoveredCastleMap(
    map: ImageBitmap,
    unexploredMap: ImageBitmap,
    snapshot: SotnMapSnapshot,
    markerAlpha: Float,
) {
    val visited = mutableListOf<Pair<Int, Int>>()
    val mapped = buildList {
        for (tileY in SotnMapSnapshot.MAP_TILES) {
            for (tileX in SotnMapSnapshot.MAP_TILES) {
                if (snapshot.isVisited(tileX, tileY)) {
                    val displayTile = snapshot.toDisplayTile(tileX, tileY)
                    visited.add(displayTile)
                    add(displayTile)
                } else if (snapshot.isBlueprintMapped(tileX, tileY)) {
                    add(snapshot.toDisplayTile(tileX, tileY))
                }
            }
        }
        val playerDisplayTile = snapshot.playerDisplayTile()
        if (playerDisplayTile !in this) {
            add(playerDisplayTile)
        }
        if (playerDisplayTile !in visited) {
            visited.add(playerDisplayTile)
        }
    }
    if (mapped.isEmpty()) return

    val minTileX = max(0, mapped.minOf { it.first } - MAP_MARGIN_TILES)
    val maxTileX = min(63, mapped.maxOf { it.first } + MAP_MARGIN_TILES)
    val minTileY = max(0, mapped.minOf { it.second } - MAP_MARGIN_TILES)
    val maxTileY = min(63, mapped.maxOf { it.second } + MAP_MARGIN_TILES)
    val tileSize = min(
        size.width / (maxTileX - minTileX + 1),
        size.height / (maxTileY - minTileY + 1),
    )
    val mapWidth = (maxTileX - minTileX + 1) * tileSize
    val mapHeight = (maxTileY - minTileY + 1) * tileSize
    val origin = Offset((size.width - mapWidth) / 2f, (size.height - mapHeight) / 2f)
    val sourceTop = if (snapshot.invertedCastle) InvertedCastleTopPx else 0
    val sourceHeight = if (snapshot.invertedCastle) InvertedCastleHeightPx else NormalCastleHeightPx
    val sourceOrigin = snapshot.sourceMapOrigin()
    val blueprintPath = Path()
    val exploredPath = Path()
    val revealTileExtent = sotnRevealedMaskTileExtent(tileSize)
    for ((tileX, tileY) in mapped.distinct()) {
        val left = origin.x + (tileX - minTileX) * tileSize
        val top = origin.y + (tileY - minTileY) * tileSize
        val sourceX = tileX * SOURCE_TILE_STEP_PX - sourceOrigin.first
        val sourceY = tileY * SOURCE_TILE_STEP_PX - sourceOrigin.second
        if (sourceX in 0 until map.width && sourceY in 0 until sourceHeight) {
            blueprintPath.addRect(
                Rect(left, top, left + revealTileExtent, top + revealTileExtent)
            )
        } else {
            drawRect(
                RoomFallback.copy(alpha = BLUEPRINT_ALPHA),
                topLeft = Offset(left, top),
                size = Size(tileSize, tileSize),
            )
        }
    }
    for ((tileX, tileY) in visited.distinct()) {
        val left = origin.x + (tileX - minTileX) * tileSize
        val top = origin.y + (tileY - minTileY) * tileSize
        val sourceX = tileX * SOURCE_TILE_STEP_PX - sourceOrigin.first
        val sourceY = tileY * SOURCE_TILE_STEP_PX - sourceOrigin.second
        if (sourceX in 0 until map.width && sourceY in 0 until sourceHeight) {
            // SotN reveals a 5x5-pixel patch for each room on its 4-pixel map grid.
            // Adjacent rooms share the fifth row/column, preserving the blueprint outline.
            exploredPath.addRect(
                Rect(left, top, left + revealTileExtent, top + revealTileExtent)
            )
        } else {
            drawRect(RoomFallback, topLeft = Offset(left, top), size = Size(tileSize, tileSize))
            drawRect(
                color = Color.White.copy(alpha = 0.75f),
                topLeft = Offset(left, top),
                size = Size(tileSize, tileSize),
                style = Stroke(width = max(1f, tileSize * 0.08f)),
            )
        }
    }

    // Render the calibrated castle artwork once, then reveal the union of explored cells.
    // The old renderer scaled overlapping 5x5 fragments onto a 4px room grid, which doubled
    // some borders and made outlines drift as the partial-map crop changed.
    val sourceScale = tileSize / SOURCE_TILE_STEP_PX
    val destinationOffset = IntOffset(
        x = (origin.x + (sourceOrigin.first - minTileX * SOURCE_TILE_STEP_PX) * sourceScale)
            .roundToInt(),
        y = (origin.y + (sourceOrigin.second - minTileY * SOURCE_TILE_STEP_PX) * sourceScale)
            .roundToInt(),
    )
    val sourceSize = IntSize(map.width, min(sourceHeight, map.height - sourceTop))
    clipPath(blueprintPath) {
        drawImage(
            image = unexploredMap,
            srcOffset = IntOffset(0, sourceTop),
            srcSize = sourceSize,
            dstOffset = destinationOffset,
            dstSize = IntSize(
                width = (sourceSize.width * sourceScale).roundToInt(),
                height = (sourceSize.height * sourceScale).roundToInt(),
            ),
            alpha = BLUEPRINT_ALPHA,
            filterQuality = FilterQuality.None,
        )
    }
    clipPath(exploredPath) {
        drawImage(
            image = map,
            srcOffset = IntOffset(0, sourceTop),
            srcSize = sourceSize,
            dstOffset = destinationOffset,
            dstSize = IntSize(
                width = (sourceSize.width * sourceScale).roundToInt(),
                height = (sourceSize.height * sourceScale).roundToInt(),
            ),
            filterQuality = FilterQuality.None,
        )
    }

    val playerDisplayTile = snapshot.playerDisplayTile()
    val markerCenter = Offset(
        x = origin.x + (playerDisplayTile.first - minTileX + 0.5f) * tileSize,
        y = origin.y + (playerDisplayTile.second - minTileY + 0.5f) * tileSize,
    )
    drawLocationMarker(
        center = markerCenter,
        radius = min(MAX_LOCATION_MARKER_RADIUS, max(5f, tileSize * 0.58f)),
        alpha = markerAlpha,
        outlineWidth = max(2f, tileSize * 0.12f),
    )
}

private fun SotnMapSnapshot.toDisplayTile(tileX: Int, tileY: Int): Pair<Int, Int> =
    if (invertedCastle) 63 - tileX to 63 - tileY else tileX to tileY

private fun SotnMapSnapshot.playerDisplayTile(): Pair<Int, Int> =
    toDisplayTile(playerTileX, playerTileY)

private fun SotnMapSnapshot.sourceMapOrigin(): Pair<Int, Int> =
    sotnSourceMapOrigin(invertedCastle)

internal fun sotnSourceMapOrigin(invertedCastle: Boolean): Pair<Int, Int> =
    if (invertedCastle) {
        InvertedCastleOriginX to InvertedCastleOriginY
    } else {
        NormalCastleOriginX to NormalCastleOriginY
    }

private fun DrawScope.drawLocationMarker(
    center: Offset,
    radius: Float,
    alpha: Float,
    outlineWidth: Float,
) {
    drawCircle(
        color = Color.Black.copy(alpha = alpha * 0.72f),
        radius = radius + outlineWidth,
        center = center,
    )
    drawCircle(
        color = CurrentRoomMarker.copy(alpha = alpha),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = Color.White.copy(alpha = alpha),
        radius = radius,
        center = center,
        style = Stroke(width = outlineWidth),
    )
}

private val CastleMapBackground = Color.Black
private val RoomFallback = Color(0xFF5271F4)
private val CurrentRoomMarker = Color(0xFFFFD45A)
private val SourceBackgroundArgb = 0xFF546D8E.toInt()
private val BlueprintRoomArgb = 0xFF5070F8.toInt()
private val MapDoorArgb = 0xFFF80000.toInt()
private val MapWarpArgb = 0xFFF88000.toInt()
private const val SOURCE_TILE_STEP_PX = 4
private const val REVEALED_TILE_SIZE_PX = 5
private const val MAP_MARGIN_TILES = 2
private const val MAX_LOCATION_MARKER_RADIUS = 18f
private const val BLUEPRINT_ALPHA = 0.38f
private const val NormalCastleHeightPx = 184
// The normal-castle blueprint borders sit on the source image's four-pixel grid.
// Keeping the origin on that same phase prevents partial-map clips from cutting
// through the one-pixel room outlines.
private const val NormalCastleOriginX = 4
private const val NormalCastleOriginY = 16
private const val InvertedCastleTopPx = 184
private const val InvertedCastleHeightPx = 200
private const val InvertedCastleOriginX = 0
private const val InvertedCastleOriginY = 34

internal fun sotnRevealedMaskTileExtent(tileSize: Float): Float =
    tileSize * REVEALED_TILE_SIZE_PX / SOURCE_TILE_STEP_PX
