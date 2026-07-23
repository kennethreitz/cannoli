package dev.cannoli.scorza.libretro

import java.util.Locale

data class PokemonPartyMember(
    val nickname: String,
    val level: Int,
    val hp: Int,
    val maxHp: Int,
    val status: PokemonStatus,
    val experienceToNextLevel: Int?,
    val experienceFraction: Float,
) {
    val hpFraction: Float
        get() = if (maxHp <= 0) 0f else (hp.toFloat() / maxHp).coerceIn(0f, 1f)
}

enum class PokemonStatus(val label: String) {
    OK("OK"),
    SLEEP("SLP"),
    POISON("PSN"),
    BURN("BRN"),
    FREEZE("FRZ"),
    PARALYSIS("PAR"),
}

data class PokemonFireEmeraldSnapshot(
    val trainerName: String,
    val playTimeHours: Int,
    val playTimeMinutes: Int,
    val money: Int,
    val mapSectionId: Int,
    val mapX: Int,
    val mapY: Int,
    val party: List<PokemonPartyMember>,
)

internal object PokemonFireEmeraldReader {
    private const val SAVE_BLOCK_2_POINTER = 0x030025C0
    private const val SAVE_BLOCK_1_POINTER = 0x030025C8
    private const val MAP_HEADER = 0x020000DC
    private const val PLAYER_PARTY = 0x02000560
    private const val EWRAM_START = 0x02000000
    private const val EWRAM_END = 0x02040000

    private const val MAP_HEADER_SIZE = 0x18
    private const val MAP_SECTION_OFFSET = 0x14
    private const val SAVE_BLOCK_2_SIZE = 0xB0
    private const val SAVE_BLOCK_1_SIZE = 0x494
    private const val PARTY_COUNT_OFFSET = 0x234
    private const val MONEY_OFFSET = 0x490
    private const val ENCRYPTION_KEY_OFFSET = 0xAC
    private const val POKEMON_SIZE = 100
    private const val PARTY_CAPACITY = 6
    private const val SPECIES_MASK = 0x7FF
    private const val EXPERIENCE_MASK = 0x1FFFFF

    fun matches(displayName: String?, fileName: String?): Boolean {
        val identity = listOfNotNull(displayName, fileName)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
            .replace('é', 'e')
            .filter(Char::isLetterOrDigit)
        return identity.contains("pokemonfireemerald")
    }

    fun read(runner: LibretroRunner): PokemonFireEmeraldSnapshot? =
        read(runner::copyMappedMemory)

    internal fun read(
        copyMemory: (address: Int, length: Int) -> ByteArray?,
    ): PokemonFireEmeraldSnapshot? {
        val saveBlock2Pointer = copyMemory(SAVE_BLOCK_2_POINTER, Int.SIZE_BYTES)
            ?.readInt32(0)
            ?.takeIf { it.isEwramPointer(SAVE_BLOCK_2_SIZE) }
            ?: return null
        val saveBlock1Pointer = copyMemory(SAVE_BLOCK_1_POINTER, Int.SIZE_BYTES)
            ?.readInt32(0)
            ?.takeIf { it.isEwramPointer(SAVE_BLOCK_1_SIZE) }
            ?: return null
        val saveBlock2 = copyMemory(saveBlock2Pointer, SAVE_BLOCK_2_SIZE) ?: return null
        val saveBlock1 = copyMemory(saveBlock1Pointer, SAVE_BLOCK_1_SIZE) ?: return null
        val mapHeader = copyMemory(MAP_HEADER, MAP_HEADER_SIZE) ?: return null

        val trainerName = decodePokemonText(saveBlock2, 0, 8)
        val mapSectionId = mapHeader.u8(MAP_SECTION_OFFSET)
        val partyCount = saveBlock1.u8(PARTY_COUNT_OFFSET)
        if (trainerName.isBlank() || mapSectionId !in 0..208 || partyCount !in 0..PARTY_CAPACITY) {
            return null
        }

        val partyBytes = copyMemory(PLAYER_PARTY, POKEMON_SIZE * PARTY_CAPACITY) ?: return null
        val party = (0 until partyCount).mapNotNull { index ->
            parsePartyMember(partyBytes, index * POKEMON_SIZE, index)
        }
        if (party.size != partyCount) return null

        val encryptionKey = saveBlock2.readInt32(ENCRYPTION_KEY_OFFSET)
        val money = (saveBlock1.readInt32(MONEY_OFFSET) xor encryptionKey)
            .takeIf { it in 0..9_999_999 }
            ?: 0
        val hours = saveBlock2.u16(0x0E)
        val minutes = saveBlock2.u8(0x10)
        if (minutes !in 0..59) return null

        return PokemonFireEmeraldSnapshot(
            trainerName = trainerName,
            playTimeHours = hours,
            playTimeMinutes = minutes,
            money = money,
            mapSectionId = mapSectionId,
            mapX = saveBlock1.u16(0),
            mapY = saveBlock1.u16(2),
            party = party,
        )
    }

    private fun parsePartyMember(bytes: ByteArray, offset: Int, index: Int): PokemonPartyMember? {
        if (offset < 0 || offset + POKEMON_SIZE > bytes.size) return null
        val level = bytes.u8(offset + 84)
        val hp = bytes.u16(offset + 86)
        val maxHp = bytes.u16(offset + 88)
        if (level !in 1..100 || maxHp !in 1..999 || hp > maxHp) return null
        val nickname = decodePokemonText(bytes, offset + 8, 10)
            .ifBlank { "Pokémon ${index + 1}" }
        val personality = bytes.readInt32(offset)
        val encryptionKey = personality xor bytes.readInt32(offset + 4)
        val substructOrder = (personality.toUInt() % 24u).toInt()
        val growthOffset = offset + 32 + GROWTH_SUBSTRUCT_OFFSETS[substructOrder] * 12
        val species = (bytes.u16(growthOffset) xor (encryptionKey and 0xFFFF)) and SPECIES_MASK
        val experience = (bytes.readInt32(growthOffset + 4) xor encryptionKey) and EXPERIENCE_MASK
        val experienceProgress = PokemonExperience.progress(species, level, experience)
        return PokemonPartyMember(
            nickname = nickname,
            level = level,
            hp = hp,
            maxHp = maxHp,
            status = decodeStatus(bytes.readInt32(offset + 80)),
            experienceToNextLevel = experienceProgress.toNextLevel,
            experienceFraction = experienceProgress.fraction,
        )
    }

    private fun decodeStatus(value: Int): PokemonStatus = when {
        value and 0x07 != 0 -> PokemonStatus.SLEEP
        value and 0x08 != 0 -> PokemonStatus.POISON
        value and 0x10 != 0 -> PokemonStatus.BURN
        value and 0x20 != 0 -> PokemonStatus.FREEZE
        value and 0x40 != 0 -> PokemonStatus.PARALYSIS
        else -> PokemonStatus.OK
    }

    private fun decodePokemonText(bytes: ByteArray, offset: Int, length: Int): String = buildString {
        for (index in offset until minOf(offset + length, bytes.size)) {
            val value = bytes.u8(index)
            if (value == 0xFF) break
            append(
                when (value) {
                    0x00 -> ' '
                    in 0xA1..0xAA -> '0' + (value - 0xA1)
                    0xAB -> '!'
                    0xAC -> '?'
                    0xAD -> '.'
                    0xB4 -> '\''
                    in 0xBB..0xD4 -> 'A' + (value - 0xBB)
                    in 0xD5..0xEE -> 'a' + (value - 0xD5)
                    else -> ' '
                }
            )
        }
    }.trim()

    private fun Int.isEwramPointer(length: Int): Boolean =
        this in EWRAM_START until EWRAM_END && length <= EWRAM_END - this

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

    private fun ByteArray.u16(offset: Int): Int =
        u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.readInt32(offset: Int): Int =
        u8(offset) or
            (u8(offset + 1) shl 8) or
            (u8(offset + 2) shl 16) or
            (u8(offset + 3) shl 24)

    private val GROWTH_SUBSTRUCT_OFFSETS = intArrayOf(
        0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 2, 3,
        1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3,
    )
}

private object PokemonExperience {
    private enum class GrowthRate { FAST, MEDIUM_FAST, MEDIUM_SLOW, SLOW, ERRATIC, FLUCTUATING }
    data class Progress(val toNextLevel: Int?, val fraction: Float)

    fun progress(species: Int, level: Int, experience: Int): Progress {
        if (level >= 100) return Progress(toNextLevel = null, fraction = 1f)
        val rate = growthRate(species, level, experience)
        val currentLevelExperience = totalAtLevel(rate, level)
        val nextLevelExperience = totalAtLevel(rate, level + 1)
        val levelExperience = (nextLevelExperience - currentLevelExperience).coerceAtLeast(1)
        return Progress(
            toNextLevel = (nextLevelExperience - experience).coerceAtLeast(0),
            fraction = ((experience - currentLevelExperience).toFloat() / levelExperience)
                .coerceIn(0f, 1f),
        )
    }

    private fun growthRate(species: Int, level: Int, experience: Int): GrowthRate {
        val known = when {
            FAST.binarySearch(species) >= 0 -> GrowthRate.FAST
            MEDIUM_SLOW.binarySearch(species) >= 0 -> GrowthRate.MEDIUM_SLOW
            SLOW.binarySearch(species) >= 0 -> GrowthRate.SLOW
            ERRATIC.binarySearch(species) >= 0 -> GrowthRate.ERRATIC
            FLUCTUATING.binarySearch(species) >= 0 -> GrowthRate.FLUCTUATING
            species in 1..386 -> GrowthRate.MEDIUM_FAST
            else -> null
        }
        if (known != null) return known
        return GrowthRate.entries.firstOrNull { rate ->
            experience >= totalAtLevel(rate, level) && experience < totalAtLevel(rate, level + 1)
        } ?: GrowthRate.MEDIUM_FAST
    }

    private fun totalAtLevel(rate: GrowthRate, level: Int): Int {
        if (level <= 1) return 0
        val cube = level * level * level
        return when (rate) {
            GrowthRate.FAST -> 4 * cube / 5
            GrowthRate.MEDIUM_FAST -> cube
            GrowthRate.MEDIUM_SLOW ->
                (6 * cube / 5 - 15 * level * level + 100 * level - 140).coerceAtLeast(0)
            GrowthRate.SLOW -> 5 * cube / 4
            GrowthRate.ERRATIC -> when {
                level <= 50 -> (100 - level) * cube / 50
                level <= 68 -> (150 - level) * cube / 100
                level <= 98 -> ((1911 - 10 * level) / 3) * cube / 500
                else -> (160 - level) * cube / 100
            }
            GrowthRate.FLUCTUATING -> when {
                level <= 15 -> ((level + 1) / 3 + 24) * cube / 50
                level <= 36 -> (level + 14) * cube / 50
                else -> (level / 2 + 32) * cube / 50
            }
        }
    }

    // FireEmerald v0.5 uses the standard National Pokédex IDs for generations 1–3.
    // Medium Fast is the default; the other five curves are listed compactly here.
    private val FAST = intArrayOf(
        35, 36, 39, 40, 113, 165, 166, 167, 168, 173, 174, 175, 176, 183, 184,
        190, 200, 209, 210, 222, 225, 235, 242, 298, 300, 301, 303, 325, 326, 327,
        337, 338, 353, 354, 355, 356, 358, 370,
    )
    private val MEDIUM_SLOW = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 16, 17, 18, 29, 30, 31, 32, 33, 34, 43, 44,
        45, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 74, 75, 76, 92, 93,
        94, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 179, 180, 181, 182,
        186, 187, 188, 189, 191, 192, 198, 207, 213, 215, 251, 252, 253, 254, 255,
        256, 257, 258, 259, 260, 270, 271, 272, 273, 274, 275, 276, 277, 293, 294,
        295, 302, 315, 328, 329, 330, 331, 332, 352, 359, 363, 364, 365,
    )
    private val SLOW = intArrayOf(
        58, 59, 72, 73, 90, 91, 102, 103, 111, 112, 120, 121, 127, 128, 129, 130,
        131, 142, 143, 144, 145, 146, 147, 148, 149, 150, 170, 171, 214, 220, 221,
        226, 227, 228, 229, 234, 241, 243, 244, 245, 246, 247, 248, 249, 250, 280,
        281, 282, 287, 288, 289, 304, 305, 306, 309, 310, 318, 319, 357, 369, 371,
        372, 373, 374, 375, 376, 377, 378, 379, 380, 381, 382, 383, 384, 385, 386,
    )
    private val ERRATIC = intArrayOf(
        290, 291, 292, 313, 333, 334, 335, 345, 346, 347, 348, 349, 350, 366, 367, 368,
    )
    private val FLUCTUATING = intArrayOf(285, 286, 296, 297, 314, 316, 317, 320, 321, 336, 341, 342)
}
