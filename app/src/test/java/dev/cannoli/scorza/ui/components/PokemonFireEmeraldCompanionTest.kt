package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonFireEmeraldCompanionTest {
    @Test
    fun `companion is limited to active FireEmerald sessions`() {
        assertTrue(
            shouldShowPokemonFireEmeraldCompanion(
                gameActive = true,
                displayName = "Pokémon FireEmerald",
                fileName = "Pokemon FireEmerald (v0.5).gba",
            )
        )
        assertFalse(
            shouldShowPokemonFireEmeraldCompanion(
                gameActive = false,
                displayName = "Pokémon FireEmerald",
                fileName = null,
            )
        )
        assertFalse(
            shouldShowPokemonFireEmeraldCompanion(
                gameActive = true,
                displayName = "Pokémon Emerald",
                fileName = "Pokemon Emerald.gba",
            )
        )
    }
}
