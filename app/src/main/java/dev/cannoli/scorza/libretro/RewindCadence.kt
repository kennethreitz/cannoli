package dev.cannoli.scorza.libretro

import kotlin.math.floor

internal const val REWIND_CAPTURE_INTERVAL_FRAMES = 6

/**
 * Keeps rewind storage density independent from playback speed.
 *
 * We retain ten points per second for a typical 60 fps core instead of copying
 * a complete serialized state every frame. During rewind, the accumulator
 * restores those points at the requested multiple of real time.
 */
internal class RewindCadence(
    private val captureIntervalFrames: Int = REWIND_CAPTURE_INTERVAL_FRAMES,
) {
    private var forwardFrame = 0
    private var rewindAccumulator = 0.0
    private var rewinding = false

    init {
        require(captureIntervalFrames > 0)
    }

    fun shouldCaptureForwardFrame(): Boolean {
        if (rewinding) {
            // The old future has been discarded. Anchor the new timeline at
            // the first forward frame after rewind is released.
            forwardFrame = 0
            rewindAccumulator = 0.0
            rewinding = false
        }
        val capture = forwardFrame == 0
        forwardFrame = (forwardFrame + 1) % captureIntervalFrames
        return capture
    }

    fun statesForRewindTick(speed: Int): Int {
        if (!rewinding) {
            // Move immediately when the shortcut is pressed; subsequent ticks
            // use the cadence accumulator to preserve the requested speed.
            rewinding = true
            rewindAccumulator = 1.0
        } else {
            rewindAccumulator += speed.coerceAtLeast(1).toDouble() / captureIntervalFrames
        }
        val states = floor(rewindAccumulator).toInt()
        rewindAccumulator -= states
        return states
    }

    fun reset() {
        forwardFrame = 0
        rewindAccumulator = 0.0
        rewinding = false
    }
}
