package dev.cannoli.scorza.util

import android.os.Environment
import java.io.File

object StorageReach {
    private val UNREACHABLE = setOf(
        Environment.MEDIA_UNMOUNTED,
        Environment.MEDIA_CHECKING,
        Environment.MEDIA_EJECTING,
        Environment.MEDIA_REMOVED,
        Environment.MEDIA_BAD_REMOVAL,
        Environment.MEDIA_UNMOUNTABLE,
        Environment.MEDIA_NOFS,
        Environment.MEDIA_SHARED,
    )

    fun isReachable(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val state = try {
            Environment.getExternalStorageState(dir)
        } catch (_: Exception) {
            null
        }
        return state !in UNREACHABLE
    }
}
