package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.libretro.PokemonFireEmeraldSnapshot
import dev.cannoli.scorza.libretro.SotnMapSnapshot
import dev.cannoli.scorza.libretro.SuperMarioWorldSnapshot
import dev.cannoli.scorza.model.Rom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
    @Volatile var lastLaunched: Rom? = null

    private val _gameActive = MutableStateFlow(false)
    val gameActive: StateFlow<Boolean> = _gameActive

    private val _sotnMap = MutableStateFlow<SotnMapSnapshot?>(null)
    val sotnMap: StateFlow<SotnMapSnapshot?> = _sotnMap

    private val _pokemonFireEmerald = MutableStateFlow<PokemonFireEmeraldSnapshot?>(null)
    val pokemonFireEmerald: StateFlow<PokemonFireEmeraldSnapshot?> = _pokemonFireEmerald

    private val _superMarioWorld = MutableStateFlow<SuperMarioWorldSnapshot?>(null)
    val superMarioWorld: StateFlow<SuperMarioWorldSnapshot?> = _superMarioWorld

    fun markGameStarted() {
        _sotnMap.value = null
        _pokemonFireEmerald.value = null
        _superMarioWorld.value = null
        _gameActive.value = true
    }

    fun markGameEnded() {
        _gameActive.value = false
        _sotnMap.value = null
        _pokemonFireEmerald.value = null
        _superMarioWorld.value = null
    }

    fun updateSotnMap(snapshot: SotnMapSnapshot) {
        _sotnMap.value = snapshot
    }

    fun updatePokemonFireEmerald(snapshot: PokemonFireEmeraldSnapshot) {
        _pokemonFireEmerald.value = snapshot
    }

    fun updateSuperMarioWorld(snapshot: SuperMarioWorldSnapshot) {
        _superMarioWorld.value = snapshot
    }
}
