package dev.cannoli.scorza.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.libretro.PokemonFireEmeraldReader
import dev.cannoli.scorza.libretro.PokemonFireEmeraldSnapshot
import dev.cannoli.scorza.libretro.PokemonPartyMember
import dev.cannoli.scorza.libretro.PokemonStatus
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

internal fun shouldShowPokemonFireEmeraldCompanion(
    gameActive: Boolean,
    displayName: String?,
    fileName: String?,
): Boolean = gameActive && PokemonFireEmeraldReader.matches(displayName, fileName)

@Composable
fun PokemonFireEmeraldCompanion(
    snapshot: PokemonFireEmeraldSnapshot?,
    modifier: Modifier = Modifier,
) {
    val readySnapshot = snapshot ?: run {
        Box(modifier.fillMaxSize().background(PokeBlack))
        return
    }
    val section = FireEmeraldMap.section(readySnapshot.mapSectionId)
    val region = FireEmeraldMap.regionFor(readySnapshot.mapSectionId)
    val transition = rememberInfiniteTransition(label = "PokéNav location")
    val markerAlpha by transition.animateFloat(
        initialValue = 0.38f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "PokéNav location marker",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PokeBlack)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "POKéNAV // FIREEMERALD",
                    color = PokeGreen,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = section?.name ?: "Unknown area",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = region.label,
                color = PokeGreen.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FireEmeraldRegionMap(
                region = region,
                currentSection = section,
                markerAlpha = markerAlpha,
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight(),
            )
            PartyPanel(
                snapshot = readySnapshot,
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = readySnapshot.trainerName.uppercase(Locale.ROOT),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Text(
                text = "  ${readySnapshot.playTimeHours}:${readySnapshot.playTimeMinutes.toString().padStart(2, '0')}",
                color = PokeMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "₽${NumberFormat.getIntegerInstance(Locale.US).format(readySnapshot.money)}",
                color = PokeGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun FireEmeraldRegionMap(
    region: FireEmeraldRegion,
    currentSection: FireEmeraldMapSection?,
    markerAlpha: Float,
    modifier: Modifier,
) {
    Box(
        modifier = modifier.background(PokePanel, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            val sections = FireEmeraldMap.surface(region)
            val cellSize = min(size.width / region.gridWidth, size.height / region.gridHeight)
            val mapWidth = region.gridWidth * cellSize
            val mapHeight = region.gridHeight * cellSize
            val origin = Offset((size.width - mapWidth) / 2f, (size.height - mapHeight) / 2f)

            sections.filterNot(FireEmeraldMapSection::city).forEach { mapSection ->
                drawRoundRect(
                    color = RouteColor,
                    topLeft = Offset(
                        origin.x + mapSection.x * cellSize,
                        origin.y + mapSection.y * cellSize,
                    ),
                    size = Size(mapSection.width * cellSize, mapSection.height * cellSize),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellSize * 0.2f),
                )
            }
            sections.filter(FireEmeraldMapSection::city).forEach { mapSection ->
                val center = Offset(
                    origin.x + (mapSection.x + mapSection.width / 2f) * cellSize,
                    origin.y + (mapSection.y + mapSection.height / 2f) * cellSize,
                )
                drawCircle(CityShadow, cellSize * 0.52f, center)
                drawCircle(CityColor, cellSize * 0.34f, center)
            }

            currentSection?.takeIf { it.region == region && it.hasCoordinates }?.let { location ->
                val center = Offset(
                    origin.x + (location.x + location.width / 2f) * cellSize,
                    origin.y + (location.y + location.height / 2f) * cellSize,
                )
                drawCircle(Color.Black.copy(alpha = 0.9f), cellSize * 0.78f, center)
                drawCircle(CurrentLocation.copy(alpha = markerAlpha), cellSize * 0.60f, center)
                drawCircle(
                    Color.White.copy(alpha = markerAlpha),
                    cellSize * 0.60f,
                    center,
                    style = Stroke(width = min(4f, cellSize * 0.18f)),
                )
            }
        }
        Text(
            text = "LIVE REGION MAP",
            color = PokeMuted.copy(alpha = 0.7f),
            fontSize = 9.sp,
            letterSpacing = 1.2.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }
}

@Composable
private fun PartyPanel(
    snapshot: PokemonFireEmeraldSnapshot,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = "PARTY  ${snapshot.party.size}/6",
            color = PokeGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        snapshot.party.forEach { member ->
            PartyMember(member, Modifier.weight(1f))
        }
        repeat(6 - snapshot.party.size) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(PokePanel.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            )
        }
    }
}

@Composable
private fun PartyMember(member: PokemonPartyMember, modifier: Modifier) {
    val hpColor = when {
        member.hp == 0 -> FaintedColor
        member.hpFraction <= 0.2f -> LowHpColor
        member.hpFraction <= 0.5f -> MidHpColor
        else -> HealthyColor
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PokePanel, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = member.nickname,
                color = if (member.hp == 0) PokeMuted else Color.White,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Lv${member.level}", color = PokeMuted, fontSize = 8.sp, lineHeight = 9.sp)
            if (member.status != PokemonStatus.OK) {
                Spacer(Modifier.width(7.dp))
                Text(
                    member.status.label,
                    color = StatusColor,
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        PartyProgressLine(
            label = "HP",
            fraction = member.hpFraction,
            value = "${member.hp}/${member.maxHp}",
            color = hpColor,
        )
        PartyProgressLine(
            label = "EXP",
            fraction = member.experienceFraction,
            value = member.experienceToNextLevel?.let {
                "${NumberFormat.getIntegerInstance(Locale.US).format(it)} left"
            } ?: "MAX",
            color = ExperienceColor,
        )
    }
}

@Composable
private fun PartyProgressLine(
    label: String,
    fraction: Float,
    value: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = color,
            fontSize = 6.5.sp,
            lineHeight = 7.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.width(19.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(3.dp)
                .background(Color(0xFF26352F), RoundedCornerShape(99.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(color, RoundedCornerShape(99.dp)),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = value,
            color = PokeMuted,
            fontSize = 6.5.sp,
            lineHeight = 7.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(45.dp),
        )
    }
}

private enum class FireEmeraldRegion(
    val label: String,
    val gridWidth: Int,
    val gridHeight: Int,
) {
    HOENN("HOENN", 28, 15),
    KANTO("KANTO", 20, 15),
    SEVII("SEVII ISLANDS", 20, 15),
}

private data class FireEmeraldMapSection(
    val id: Int,
    val name: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val region: FireEmeraldRegion,
    val city: Boolean,
) {
    val hasCoordinates: Boolean get() = x >= 0 && y >= 0
}

private object FireEmeraldMap {
    private val sections = listOf(
        // Hoenn cities and towns.
        s(0, "Littleroot Town", 4, 11, city = true), s(1, "Oldale Town", 4, 9, city = true),
        s(2, "Dewford Town", 2, 14, city = true), s(3, "Lavaridge Town", 5, 3, city = true),
        s(4, "Fallarbor Town", 3, 0, city = true), s(5, "Verdanturf Town", 4, 6, city = true),
        s(6, "Pacifidlog Town", 17, 10, city = true), s(7, "Petalburg City", 1, 9, city = true),
        s(8, "Slateport City", 8, 10, 1, 2, city = true), s(9, "Mauville City", 8, 6, 2, 1, city = true),
        s(10, "Rustboro City", 0, 5, 1, 2, city = true), s(11, "Fortree City", 12, 0, city = true),
        s(12, "Lilycove City", 18, 3, 2, 1, city = true), s(13, "Mossdeep City", 24, 5, 2, 1, city = true),
        s(14, "Sootopolis City", 21, 7, city = true), s(15, "Ever Grande City", 27, 8, 1, 2, city = true),
        s(16, "Route 101", 4, 10), s(17, "Route 102", 2, 9, 2), s(18, "Route 103", 4, 8, 4),
        s(19, "Route 104", 0, 7, 1, 3), s(20, "Route 105", 0, 10, 1, 3), s(21, "Route 106", 0, 13, 2),
        s(22, "Route 107", 3, 14, 3), s(23, "Route 108", 6, 14, 2), s(24, "Route 109", 8, 12, 1, 3),
        s(25, "Route 110", 8, 7, 1, 3), s(26, "Route 111", 8, 0, 1, 6), s(27, "Route 112", 6, 3, 2),
        s(28, "Route 113", 4, 0, 4), s(29, "Route 114", 1, 0, 2, 3), s(30, "Route 115", 0, 2, 1, 3),
        s(31, "Route 116", 1, 5, 4), s(32, "Route 117", 5, 6, 3), s(33, "Route 118", 10, 6, 2),
        s(34, "Route 119", 11, 0, 1, 6), s(35, "Route 120", 13, 0, 1, 4), s(36, "Route 121", 14, 3, 4),
        s(37, "Route 122", 16, 4, 1, 2), s(38, "Route 123", 12, 6, 5), s(39, "Route 124", 20, 3, 4, 3),
        s(40, "Route 125", 24, 3, 2, 2), s(41, "Route 126", 20, 6, 3, 3), s(42, "Route 127", 23, 6, 3, 3),
        s(43, "Route 128", 23, 9, 4), s(44, "Route 129", 24, 10, 2), s(45, "Route 130", 21, 10, 3),
        s(46, "Route 131", 18, 10, 3), s(47, "Route 132", 15, 10, 2), s(48, "Route 133", 12, 10, 3),
        s(49, "Route 134", 9, 10, 3),
        s(55, "Granite Cave", 1, 13), s(56, "Mt. Chimney", 6, 2), s(59, "Petalburg Woods", 0, 8),
        s(60, "Rusturf Tunnel", 2, 5), s(62, "New Mauville", 8, 7), s(63, "Meteor Falls", 0, 3),
        s(65, "Mt. Pyre", 16, 4), s(67, "Shoal Cave", 24, 4), s(68, "Seafloor Cavern", 24, 9),
        s(70, "Victory Road", 27, 9), s(72, "Cave of Origin", 21, 7), s(74, "Fiery Path", 6, 3),
        s(80, "Scorched Slab", 13, 0), s(85, "Sky Pillar", 19, 10),

        // Kanto cities, towns, routes, and landmarks.
        s(88, "Pallet Town", 4, 11, region = FireEmeraldRegion.KANTO, city = true),
        s(89, "Viridian City", 4, 8, region = FireEmeraldRegion.KANTO, city = true),
        s(90, "Pewter City", 4, 4, region = FireEmeraldRegion.KANTO, city = true),
        s(91, "Cerulean City", 14, 3, region = FireEmeraldRegion.KANTO, city = true),
        s(92, "Lavender Town", 18, 6, region = FireEmeraldRegion.KANTO, city = true),
        s(93, "Vermilion City", 14, 9, region = FireEmeraldRegion.KANTO, city = true),
        s(94, "Celadon City", 11, 6, region = FireEmeraldRegion.KANTO, city = true),
        s(95, "Fuchsia City", 12, 12, region = FireEmeraldRegion.KANTO, city = true),
        s(96, "Cinnabar Island", 4, 14, region = FireEmeraldRegion.KANTO, city = true),
        s(97, "Indigo Plateau", 2, 3, region = FireEmeraldRegion.KANTO, city = true),
        s(98, "Saffron City", 14, 6, region = FireEmeraldRegion.KANTO, city = true),
        k(101, "Route 1", 4, 9, 1, 2), k(102, "Route 2", 4, 5, 1, 3), k(103, "Route 3", 5, 4, 4),
        k(104, "Route 4", 8, 3, 6), k(105, "Route 5", 14, 4, 1, 2), k(106, "Route 6", 14, 7, 1, 2),
        k(107, "Route 7", 12, 6, 2), k(108, "Route 8", 15, 6, 3), k(109, "Route 9", 15, 3, 3),
        k(110, "Route 10", 18, 3, 1, 3), k(111, "Route 11", 15, 9, 3), k(112, "Route 12", 18, 7, 1, 5),
        k(113, "Route 13", 16, 11, 2), k(114, "Route 14", 15, 11, 1, 2), k(115, "Route 15", 13, 12, 2),
        k(116, "Route 16", 7, 6, 4), k(117, "Route 17", 7, 7, 1, 5), k(118, "Route 18", 7, 12, 5),
        k(119, "Route 19", 12, 13, 1, 2), k(120, "Route 20", 5, 14, 7), k(121, "Route 21", 4, 12, 1, 2),
        k(122, "Route 22", 2, 8, 2), k(123, "Route 23", 2, 4, 1, 4), k(124, "Route 24", 14, 1, 1, 2),
        k(125, "Route 25", 15, 1, 2), k(126, "Viridian Forest", 4, 6), k(127, "Mt. Moon", 9, 3),
        k(128, "S.S. Anne", 14, 9), k(129, "Underground Path", 14, 7), k(131, "Diglett's Cave", 15, 9),
        k(132, "Victory Road", 2, 4), k(133, "Rocket Hideout", 11, 6), k(134, "Silph Co.", 14, 6),
        k(135, "Pokémon Mansion", 4, 14), k(136, "Safari Zone", 12, 12), k(137, "Pokémon League", 2, 3),
        k(138, "Rock Tunnel", 18, 5), k(139, "Seafoam Islands", 8, 14), k(140, "Pokémon Tower", 18, 6),
        k(141, "Cerulean Cave", 13, 3), k(142, "Power Plant", 18, 4),

        // Sevii Islands and connecting paths.
        v(143, "One Island", 1, 8, city = true), v(144, "Two Island", 9, 9, city = true),
        v(145, "Three Island", 18, 12, city = true), v(146, "Four Island", 3, 4, city = true),
        v(147, "Five Island", 16, 11, city = true), v(148, "Seven Island", 5, 8, city = true),
        v(149, "Six Island", 17, 5, city = true), v(150, "Kindle Road", 2, 3, 1, 6),
        v(151, "Treasure Beach", 1, 9, 1, 2), v(152, "Cape Brink", 9, 7, 1, 2),
        v(153, "Bond Bridge", 13, 12, 4), v(154, "Three Isle Port", 18, 13, 2),
        v(155, "Resort Gorgeous", 16, 9, 3), v(156, "Water Labyrinth", 14, 10, 3),
        v(157, "Five Isle Meadow", 17, 10, 1, 3), v(158, "Memorial Pillar", 18, 12, 1, 3),
        v(159, "Outcast Island", 15, 0, 1, 3), v(160, "Green Path", 15, 3, 3),
        v(161, "Water Path", 18, 3, 1, 5), v(162, "Ruin Valley", 16, 7, 2, 2),
        v(163, "Trainer Tower", 5, 6, 1, 2), v(164, "Canyon Entrance", 5, 9),
        v(165, "Sevault Canyon", 6, 9, 1, 3), v(166, "Tanoby Ruins", 3, 12, 7),
        v(171, "Mt. Ember", 2, 3), v(172, "Berry Forest", 14, 12), v(173, "Icefall Cave", 3, 4),
        v(174, "Rocket Warehouse", 17, 11), v(176, "Dotted Hole", 16, 8), v(177, "Lost Cave", 18, 9),
        v(178, "Pattern Bush", 17, 3), v(179, "Altering Cave", 15, 0), v(180, "Tanoby Chambers", 9, 12),
    )
    private val byId = sections.associateBy(FireEmeraldMapSection::id)

    fun section(id: Int): FireEmeraldMapSection? = byId[id]
        ?: FireEmeraldMapSection(id, "Area $id", -1, -1, 1, 1, regionFor(id), false)

    fun surface(region: FireEmeraldRegion): List<FireEmeraldMapSection> = sections.filter {
        it.region == region && when (region) {
            FireEmeraldRegion.HOENN -> it.id in 0..49
            FireEmeraldRegion.KANTO -> it.id in 88..125
            FireEmeraldRegion.SEVII -> it.id in 143..166
        }
    }

    fun regionFor(id: Int): FireEmeraldRegion = when (id) {
        in 88..142 -> FireEmeraldRegion.KANTO
        in 143..192 -> FireEmeraldRegion.SEVII
        else -> FireEmeraldRegion.HOENN
    }

    private fun s(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        width: Int = 1,
        height: Int = 1,
        region: FireEmeraldRegion = FireEmeraldRegion.HOENN,
        city: Boolean = false,
    ) = FireEmeraldMapSection(id, name, x, y, width, height, region, city)

    private fun k(id: Int, name: String, x: Int, y: Int, width: Int = 1, height: Int = 1) =
        s(id, name, x, y, width, height, FireEmeraldRegion.KANTO)

    private fun v(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        width: Int = 1,
        height: Int = 1,
        city: Boolean = false,
    ) = s(id, name, x, y, width, height, FireEmeraldRegion.SEVII, city)
}

private val PokeBlack = Color.Black
private val PokePanel = Color(0xFF101916)
private val PokeGreen = Color(0xFF65F2A7)
private val PokeMuted = Color(0xFF8FA69B)
private val RouteColor = Color(0xFF284F43)
private val CityShadow = Color(0xFF0B241C)
private val CityColor = Color(0xFF65F2A7)
private val CurrentLocation = Color(0xFFFFD45A)
private val HealthyColor = Color(0xFF59D87B)
private val ExperienceColor = Color(0xFF5AA7FF)
private val MidHpColor = Color(0xFFE3C94E)
private val LowHpColor = Color(0xFFE56B55)
private val FaintedColor = Color(0xFF69453F)
private val StatusColor = Color(0xFFFFD45A)
