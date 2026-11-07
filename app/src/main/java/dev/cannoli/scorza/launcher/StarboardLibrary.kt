package dev.cannoli.scorza.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Xml
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.db.AppsRepository
import dev.cannoli.scorza.model.AppType
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StarboardLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Snapshot(
        val available: Boolean,
        val games: List<StarboardTarget>,
    )

    fun installedGames(): List<StarboardTarget> = snapshot().games

    /** Keeps Starboard-owned rows current without touching manually managed Ports. */
    fun syncInto(appsRepository: AppsRepository): Boolean {
        val snapshot = snapshot()
        if (!snapshot.available) return false

        var changed = false
        val discovered = snapshot.games.associateBy { it.encode() }
        val existing = appsRepository.all(AppType.PORT)
            .filter { StarboardTarget.decode(it.packageName) != null }
            .associateBy { it.packageName }

        existing.filterKeys { it !in discovered }.values.forEach { app ->
            appsRepository.delete(app.id)
            changed = true
        }
        discovered.forEach { (encoded, target) ->
            val app = existing[encoded]
            if (app == null) {
                appsRepository.upsert(AppType.PORT, target.title, encoded)
                changed = true
            } else if (app.displayName != target.title) {
                appsRepository.updateDisplayName(app.id, target.title)
                changed = true
            }
        }
        return changed
    }

    private fun snapshot(): Snapshot {
        if (!context.isPackageInstalled(StarboardTarget.STARBOARD_PACKAGE)) {
            return Snapshot(available = true, games = emptyList())
        }
        if (!hasStoragePermission()) return Snapshot(available = false, games = emptyList())

        val roots = candidatePortRoots()
        val existingRoots = roots.filter(File::isDirectory)
        if (existingRoots.isEmpty()) return Snapshot(available = true, games = emptyList())

        val games = mutableListOf<StarboardTarget>()
        var readAtLeastOneRoot = false
        existingRoots.forEach { root ->
            val children = root.listFiles() ?: return@forEach
            readAtLeastOneRoot = true
            games += readInstalledGames(root, children.toList())
        }
        return Snapshot(
            available = readAtLeastOneRoot,
            games = games.distinctBy { it.encode() }
                .sortedBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    @Suppress("DEPRECATION")
    private fun candidatePortRoots(): List<File> {
        val volumeRoots = buildList {
            add(Environment.getExternalStorageDirectory())
            context.getExternalFilesDirs(null).filterNotNull().forEach { externalFiles ->
                generateSequence(externalFiles) { it.parentFile }
                    .firstOrNull { it.name == "Android" }
                    ?.parentFile
                    ?.let(::add)
            }
        }
        return volumeRoots.distinctBy { it.absolutePath }
            .map { File(it, "Starboard/ports") }
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    companion object {
        internal fun readInstalledGames(
            portsRoot: File,
            portDirectories: List<File> = portsRoot.listFiles()?.toList().orEmpty(),
        ): List<StarboardTarget> = portDirectories.asSequence()
            .filter(File::isDirectory)
            .filter { File(it, ".starboard_installed").isFile }
            .flatMap(::readPortGames)
            .distinctBy { it.encode() }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()

        private fun readPortGames(portDirectory: File): Sequence<StarboardTarget> {
            val metadataDirectories = sequenceOf(portDirectory) +
                portDirectory.listFiles().orEmpty().asSequence().filter(File::isDirectory)
            val metadata = metadataDirectories.toList()
            val portJson = metadata.firstNotNullOfOrNull { directory ->
                File(directory, "port.json").takeIf(File::isFile)
            } ?: return emptySequence()
            val zipName = runCatching {
                JSONObject(portJson.readText()).optString("name").takeIf(String::isNotBlank)
            }.getOrNull() ?: return emptySequence()
            if (!isSafeZipName(zipName)) return emptySequence()

            return metadata.asSequence()
                .map { File(it, "gameinfo.xml") }
                .filter(File::isFile)
                .flatMap(::readGameInfo)
                .mapNotNull { (title, launcher) ->
                    val launcherFile = File(portDirectory, launcher)
                    if (!isSafeLauncher(portDirectory, launcherFile, launcher)) return@mapNotNull null
                    StarboardTarget(
                        packageName = StarboardTarget.STARBOARD_PACKAGE,
                        zipName = zipName,
                        launcher = launcher,
                        title = title,
                    )
                }
        }

        private fun readGameInfo(file: File): Sequence<Pair<String, String>> = runCatching {
            val games = mutableListOf<Pair<String, String>>()
            file.inputStream().buffered().use { input ->
                val parser = Xml.newPullParser().apply {
                    runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
                    setInput(input, "UTF-8")
                }
                var inGame = false
                var title: String? = null
                var path: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> when (parser.name) {
                            "game" -> {
                                inGame = true
                                title = null
                                path = null
                            }
                            "name" -> if (inGame) title = parser.nextText().trim()
                            "path" -> if (inGame) path = parser.nextText().trim()
                        }
                        XmlPullParser.END_TAG -> if (parser.name == "game") {
                            val launcher = path?.removePrefix("./")
                            if (!title.isNullOrBlank() && !launcher.isNullOrBlank()) {
                                games += title!! to launcher
                            }
                            inGame = false
                        }
                    }
                    parser.next()
                }
            }
            games.asSequence()
        }.getOrElse { emptySequence() }

        private fun isSafeZipName(zipName: String): Boolean =
            zipName.endsWith(".zip", ignoreCase = true) &&
                zipName.none { it == '/' || it == '\\' || it == '\u0000' }

        private fun isSafeLauncher(root: File, file: File, launcher: String): Boolean = runCatching {
            launcher.endsWith(".sh", ignoreCase = true) &&
                !launcher.startsWith('/') &&
                !launcher.contains('\\') &&
                launcher.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                file.isFile &&
                file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        }.getOrDefault(false)
    }
}
