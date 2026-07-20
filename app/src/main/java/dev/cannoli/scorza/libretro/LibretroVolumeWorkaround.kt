package dev.cannoli.scorza.libretro

/**
 * The Thor's AAudio output can bypass Android's per-stream attenuation even though the media
 * volume index and AudioFlinger volume curve both update. Mirror that index into Cannoli's native
 * output as a device-specific fallback. Keeping this narrowly gated avoids double-attenuating
 * Libretro audio on devices whose mixer behaves normally.
 */
internal object LibretroVolumeWorkaround {
    fun applies(manufacturer: String, model: String): Boolean =
        manufacturer.equals("AYN", ignoreCase = true) &&
            model.equals("AYN Thor", ignoreCase = true)

    fun gain(volume: Int, maxVolume: Int): Float {
        if (volume <= 0 || maxVolume <= 0) return 0f
        return (volume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
    }
}
