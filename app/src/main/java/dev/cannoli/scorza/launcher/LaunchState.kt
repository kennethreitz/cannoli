package dev.cannoli.scorza.launcher

import dev.cannoli.scorza.model.Rom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LaunchState @Inject constructor() {
    @Volatile var launching: Boolean = false
    @Volatile var lastLaunched: Rom? = null
    @Volatile var gameStartedAtMillis: Long? = null
        private set

    // Fork (dual-screen): tracks whether a game session owns the game display so the launcher
    // can dim itself and block input on the other screen.
    private val _gameActive = MutableStateFlow(false)
    val gameActive: StateFlow<Boolean> = _gameActive

    fun markGameStarted() {
        gameStartedAtMillis = System.currentTimeMillis()
        _gameActive.value = true
    }

    fun markGameEnded() {
        _gameActive.value = false
        gameStartedAtMillis = null
    }
}
