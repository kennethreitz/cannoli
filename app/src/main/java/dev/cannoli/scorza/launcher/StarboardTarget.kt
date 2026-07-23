package dev.cannoli.scorza.launcher

import android.net.Uri

data class StarboardTarget(
    val packageName: String,
    val zipName: String,
    val launcher: String,
    val title: String,
) {
    fun encode(): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(packageName)
        .appendPath(zipName)
        .appendQueryParameter(LAUNCHER, launcher)
        .appendQueryParameter(TITLE, title)
        .build()
        .toString()

    companion object {
        const val STARBOARD_PACKAGE = "org.force9.starboard"
        private const val SCHEME = "cannoli-starboard"
        private const val LAUNCHER = "launcher"
        private const val TITLE = "title"

        fun decode(value: String): StarboardTarget? {
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
            if (uri.scheme != SCHEME) return null
            val packageName = uri.authority?.takeIf { it.isNotBlank() } ?: return null
            val zipName = uri.pathSegments.singleOrNull()
                ?.takeIf { it.isSafeFileName() && it.endsWith(".zip", ignoreCase = true) }
                ?: return null
            val launcher = uri.getQueryParameter(LAUNCHER)
                ?.takeIf { it.isSafeRelativePath() && it.endsWith(".sh", ignoreCase = true) }
                ?: return null
            val title = uri.getQueryParameter(TITLE)?.takeIf { it.isNotBlank() } ?: return null
            return StarboardTarget(packageName, zipName, launcher, title)
        }

        private fun String.isSafeFileName(): Boolean =
            isNotBlank() && none { it == '/' || it == '\\' || it == '\u0000' }

        private fun String.isSafeRelativePath(): Boolean {
            if (isBlank() || startsWith('/') || contains('\\') || contains('\u0000')) return false
            return split('/').none { it.isBlank() || it == "." || it == ".." }
        }
    }
}
