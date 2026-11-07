package dev.cannoli.scorza.libretro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonFireEmeraldSnapshotTest {
    @Test
    fun `matches FireEmerald but not ordinary Emerald`() {
        assertTrue(PokemonFireEmeraldReader.matches("Pokémon FireEmerald", null))
        assertTrue(PokemonFireEmeraldReader.matches(null, "Pokemon FireEmerald (v0.5).gba"))
        assertFalse(PokemonFireEmeraldReader.matches("Pokémon Emerald", "Pokemon Emerald.gba"))
    }

    @Test
    fun `reads trainer location money and live party`() {
        val saveBlock2Address = 0x0202926C
        val saveBlock1Address = 0x020330E8
        val memory = mutableMapOf<Int, ByteArray>()
        memory[0x030025C0] = littleEndian(saveBlock2Address)
        memory[0x030025C8] = littleEndian(saveBlock1Address)

        val saveBlock2 = ByteArray(0xB0)
        encodePokemonText("Kenneth", saveBlock2, 0, 8)
        writeU16(saveBlock2, 0x0E, 4)
        saveBlock2[0x10] = 23
        val key = 0x12345678
        writeInt(saveBlock2, 0xAC, key)
        memory[saveBlock2Address] = saveBlock2

        val saveBlock1 = ByteArray(0x494)
        writeU16(saveBlock1, 0, 8)
        writeU16(saveBlock1, 2, 7)
        saveBlock1[0x234] = 2
        writeInt(saveBlock1, 0x490, 4_567 xor key)
        memory[saveBlock1Address] = saveBlock1

        val mapHeader = ByteArray(0x18)
        mapHeader[0x14] = 91
        memory[0x020000DC] = mapHeader

        val party = ByteArray(600)
        writePokemon(party, 0, "Charmeleon", 5, 25, 11_929, 0, 69, 0)
        writePokemon(
            party,
            100,
            "Paras",
            46,
            10,
            1_000,
            28,
            28,
            0x08,
            personality = 0xED9084E8.toInt(),
        )
        memory[0x02000560] = party

        val result = PokemonFireEmeraldReader.read { address, length ->
            memory[address]?.copyOf(length)
        }

        requireNotNull(result)
        assertEquals("Kenneth", result.trainerName)
        assertEquals(4, result.playTimeHours)
        assertEquals(23, result.playTimeMinutes)
        assertEquals(4_567, result.money)
        assertEquals(91, result.mapSectionId)
        assertEquals(8, result.mapX)
        assertEquals(7, result.mapY)
        assertEquals(2, result.party.size)
        assertEquals("Charmeleon", result.party[0].nickname)
        assertEquals(0, result.party[0].hp)
        assertEquals(1_482, result.party[0].experienceToNextLevel)
        assertEquals(331, result.party[1].experienceToNextLevel)
        assertTrue(result.party[0].experienceFraction in 0.11f..0.12f)
        assertEquals(0f, result.party[1].experienceFraction)
        assertEquals(PokemonStatus.POISON, result.party[1].status)
    }

    @Test
    fun `ignores memory before save blocks are ready`() {
        val result = PokemonFireEmeraldReader.read { address, length ->
            if (address == 0x030025C0 || address == 0x030025C8) ByteArray(length) else null
        }
        assertNull(result)
    }

    private fun writePokemon(
        bytes: ByteArray,
        offset: Int,
        name: String,
        species: Int,
        level: Int,
        experience: Int,
        hp: Int,
        maxHp: Int,
        status: Int,
        personality: Int = 0,
    ) {
        encodePokemonText(name, bytes, offset + 8, 10)
        writeInt(bytes, offset, personality)
        val order = (personality.toUInt() % 24u).toInt()
        val growthOffsets = intArrayOf(
            0, 0, 0, 0, 0, 0, 1, 1, 2, 3, 2, 3,
            1, 1, 2, 3, 2, 3, 1, 1, 2, 3, 2, 3,
        )
        val growthOffset = offset + 32 + growthOffsets[order] * 12
        writeU16(bytes, growthOffset, species)
        writeInt(bytes, growthOffset + 4, experience)
        for (secureOffset in offset + 32 until offset + 80 step 4) {
            writeInt(bytes, secureOffset, readInt(bytes, secureOffset) xor personality)
        }
        writeInt(bytes, offset + 80, status)
        bytes[offset + 84] = level.toByte()
        writeU16(bytes, offset + 86, hp)
        writeU16(bytes, offset + 88, maxHp)
    }

    private fun encodePokemonText(text: String, bytes: ByteArray, offset: Int, length: Int) {
        repeat(length) { bytes[offset + it] = 0xFF.toByte() }
        text.take(length).forEachIndexed { index, character ->
            bytes[offset + index] = when (character) {
                in 'A'..'Z' -> (0xBB + character.code - 'A'.code).toByte()
                in 'a'..'z' -> (0xD5 + character.code - 'a'.code).toByte()
                else -> 0x00
            }.toByte()
        }
    }

    private fun littleEndian(value: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, value) }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
