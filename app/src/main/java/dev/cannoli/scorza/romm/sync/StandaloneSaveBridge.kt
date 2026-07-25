package dev.cannoli.scorza.romm.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StandaloneMirrorResult {
    data class Ready(val file: File) : StandaloneMirrorResult
    data object Missing : StandaloneMirrorResult
    data class Unavailable(val reason: String) : StandaloneMirrorResult
}

@Singleton
class StandaloneSaveBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pathsProvider: CannoliPathsProvider,
    private val launchState: LaunchState,
) {
    private val resolver get() = context.contentResolver
    private val romDir get() = pathsProvider.romDir
    private val root get() = pathsProvider.root

    fun kindFor(platformTag: String, emulator: String?): StandaloneSaveKind? =
        StandaloneSaveKind.forGame(platformTag, emulator)

    fun isGameActive(): Boolean = launchState.gameActive.value

    fun isInstalled(kind: StandaloneSaveKind): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(kind.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(kind.packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isLinked(kind: StandaloneSaveKind): Boolean =
        directFileRoot(kind) != null || treeUri(kind) != null

    fun accessIntent(kind: StandaloneSaveKind): Intent {
        val initialUri = kind.initialDocumentId?.let {
            DocumentsContract.buildDocumentUri(kind.documentAuthority, it)
        } ?: DocumentsContract.buildRootUri(
            kind.documentAuthority,
            requireNotNull(kind.providerRootId),
        )
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
    }

    fun persistAccess(kind: StandaloneSaveKind, uri: Uri, flags: Int): Boolean {
        if (uri.authority != kind.documentAuthority) return false
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false
        if (kind.providerRootId != null && documentId.trimEnd('/') != kind.providerRootId) return false
        val takeFlags = flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        if (takeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            takeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0
        ) return false
        return runCatching {
            resolver.takePersistableUriPermission(uri, takeFlags)
            if (!isValidTree(kind, uri)) {
                resolver.releasePersistableUriPermission(uri, takeFlags)
                return@runCatching false
            }
            treePreferences.edit().putString(treePreferenceKey(kind), uri.toString()).apply()
            true
        }.getOrDefault(false)
    }

    fun refreshMirror(
        kind: StandaloneSaveKind,
        gameKey: String,
        base: String,
    ): StandaloneMirrorResult {
        directFileRoot(kind)?.let { fileRoot ->
            return refreshFileMirror(kind, gameKey, base, fileRoot)
        }
        val tree = treeUri(kind)
            ?: return StandaloneMirrorResult.Unavailable("${kind.emulatorName} save folder is not connected")
        val rom = File(romDir, gameKey)
        val titleId = StandaloneTitleIdParser.titleId(kind, rom)
            ?: return StandaloneMirrorResult.Unavailable("could not read title ID from ${rom.name}")
        return try {
            val docs = DocumentTree(resolver, tree)
            val saveRoot = findSaveRoot(docs, kind, titleId) ?: run {
                dev.cannoli.scorza.util.RommLog.write(
                    "standalone ${kind.emulatorName} save missing [$base]: title=$titleId " +
                        "root=${docs.root.displayName} children=${docs.children(docs.root).joinToString { it.displayName }}",
                )
                standaloneArchive(kind.platformTag, base).delete()
                return StandaloneMirrorResult.Missing
            }
            val files = docs.walkFiles(saveRoot)
            if (files.isEmpty()) {
                dev.cannoli.scorza.util.RommLog.write(
                    "standalone ${kind.emulatorName} save empty [$base]: title=$titleId root=${saveRoot.displayName}",
                )
                standaloneArchive(kind.platformTag, base).delete()
                return StandaloneMirrorResult.Missing
            }
            val archive = standaloneArchive(kind.platformTag, base)
            val archiveDir = requireNotNull(archive.parentFile).apply { mkdirs() }
            val temp = File.createTempFile("standalone-save", ".zip", archiveDir)
            try {
                writeArchive(docs, kind, titleId, files, temp)
                if (!archive.isFile || SaveHasher.hashFile(archive) != SaveHasher.hashFile(temp)) {
                    replaceFile(temp, archive)
                    val newest = files.maxOfOrNull { it.node.lastModified } ?: 0L
                    archive.setLastModified(if (newest > 0L) newest else System.currentTimeMillis())
                }
            } finally {
                temp.delete()
            }
            StandaloneMirrorResult.Ready(archive)
        } catch (t: Throwable) {
            StandaloneMirrorResult.Unavailable(t.message ?: t.javaClass.simpleName)
        }
    }

    fun applyMirror(
        kind: StandaloneSaveKind,
        gameKey: String,
        archive: File,
    ) {
        directFileRoot(kind)?.let { fileRoot ->
            applyFileMirror(kind, gameKey, archive, fileRoot)
            return
        }
        val tree = treeUri(kind)
            ?: throw IllegalStateException("${kind.emulatorName} save folder is not connected")
        val titleId = StandaloneTitleIdParser.titleId(kind, File(romDir, gameKey))
            ?: throw IllegalStateException("could not read title ID from ${File(gameKey).name}")
        val stage = createStageDirectory()
        try {
            extractAndValidate(archive, stage, kind, titleId)
            val docs = DocumentTree(resolver, tree)
            val saveRoot = findSaveRoot(docs, kind, titleId)
                ?: createSaveRoot(docs, kind, titleId)
            reconcile(
                docs,
                saveRoot,
                stage,
                allowCreate = kind != StandaloneSaveKind.CITRA_MMJ,
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    fun archiveMode(kind: StandaloneSaveKind?): LocalSaveMode =
        if (kind != null) LocalSaveMode.STANDALONE_ARCHIVE else LocalSaveMode.NORMAL

    private fun directFileRoot(kind: StandaloneSaveKind): File? {
        if (kind != StandaloneSaveKind.VITA3K) return null
        val root = File(VITA3K_PUBLIC_ROOT)
        val savedata = File(root, VITA3K_SAVEDATA_PATH)
        return root.takeIf {
            it.isDirectory &&
                savedata.isDirectory &&
                savedata.canRead() &&
                savedata.canWrite()
        }
    }

    private fun refreshFileMirror(
        kind: StandaloneSaveKind,
        gameKey: String,
        base: String,
        fileRoot: File,
    ): StandaloneMirrorResult {
        val rom = File(romDir, gameKey)
        val titleId = StandaloneTitleIdParser.titleId(kind, rom)
            ?: return StandaloneMirrorResult.Unavailable("could not read title ID from ${rom.name}")
        return try {
            val saveRoot = File(fileRoot, "$VITA3K_SAVEDATA_PATH/${titleId.uppercase()}")
            val files = saveRoot.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) {
                dev.cannoli.scorza.util.RommLog.write(
                    "standalone ${kind.emulatorName} save missing [$base]: title=$titleId root=${fileRoot.path}",
                )
                standaloneArchive(kind.platformTag, base).delete()
                StandaloneMirrorResult.Missing
            } else {
                val archive = standaloneArchive(kind.platformTag, base)
                val archiveDir = requireNotNull(archive.parentFile).apply { mkdirs() }
                val temp = File.createTempFile("standalone-save", ".zip", archiveDir)
                try {
                    writeFileArchive(kind, titleId, saveRoot, files, temp)
                    if (!archive.isFile || SaveHasher.hashFile(archive) != SaveHasher.hashFile(temp)) {
                        replaceFile(temp, archive)
                        val newest = files.maxOfOrNull(File::lastModified) ?: 0L
                        archive.setLastModified(if (newest > 0L) newest else System.currentTimeMillis())
                    }
                } finally {
                    temp.delete()
                }
                StandaloneMirrorResult.Ready(archive)
            }
        } catch (t: Throwable) {
            StandaloneMirrorResult.Unavailable(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun applyFileMirror(
        kind: StandaloneSaveKind,
        gameKey: String,
        archive: File,
        fileRoot: File,
    ) {
        val titleId = StandaloneTitleIdParser.titleId(kind, File(romDir, gameKey))
            ?: throw IllegalStateException("could not read title ID from ${File(gameKey).name}")
        val stage = createStageDirectory()
        try {
            extractAndValidate(archive, stage, kind, titleId)
            val saveRoot = File(fileRoot, "$VITA3K_SAVEDATA_PATH/${titleId.uppercase()}")
            reconcileFileTree(saveRoot, stage)
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun treeUri(kind: StandaloneSaveKind): Uri? =
        treePreferences.getString(treePreferenceKey(kind), null)
            ?.let(Uri::parse)
            ?.takeIf { stored ->
                resolver.persistedUriPermissions.any {
                    it.uri == stored && it.isReadPermission && it.isWritePermission
                } && isValidTree(kind, stored)
            }
            ?: resolver.persistedUriPermissions
                .lastOrNull {
                    it.uri.authority == kind.documentAuthority &&
                        it.isReadPermission &&
                        it.isWritePermission &&
                        kind.providerRootId != null &&
                        runCatching {
                            DocumentsContract.getTreeDocumentId(it.uri).trimEnd('/') == kind.providerRootId
                        }.getOrDefault(false)
                }
                ?.uri

    private val treePreferences by lazy {
        context.getSharedPreferences(TREE_PREFERENCES, Context.MODE_PRIVATE)
    }

    private fun treePreferenceKey(kind: StandaloneSaveKind): String =
        "tree_${kind.name.lowercase()}"

    private fun isValidTree(kind: StandaloneSaveKind, uri: Uri): Boolean = runCatching {
        val docs = DocumentTree(resolver, uri)
        when (kind) {
            StandaloneSaveKind.VITA3K ->
                docs.findPath(docs.root, listOf("ux0", "user", "00", "savedata")) != null
            else -> true
        }
    }.getOrDefault(false)

    private fun standaloneArchive(tag: String, base: String): File =
        File(File(root, "Saves/$tag"), "$base$STANDALONE_SUFFIX")

    private fun findSaveRoot(
        docs: DocumentTree,
        kind: StandaloneSaveKind,
        titleId: String,
    ): DocumentNode? = when (kind) {
        StandaloneSaveKind.CITRA_MMJ -> {
            val nintendo = docs.findPath(docs.root, listOf("citra-emu", "sdmc", "Nintendo 3DS"))
                ?: return null
            for (system in docs.children(nintendo).filter { it.isDirectory }) {
                for (card in docs.children(system).filter { it.isDirectory }) {
                    val path = listOf(
                        "title",
                        titleId.substring(0, 8).lowercase(),
                        titleId.substring(8).lowercase(),
                        "data",
                        "00000001",
                    )
                    docs.findPath(card, path)?.let { return it }
                }
            }
            null
        }
        StandaloneSaveKind.CEMU -> docs.findPath(
            docs.root,
            listOf(
                "mlc01",
                "usr",
                "save",
                titleId.substring(0, 8).lowercase(),
                titleId.substring(8).lowercase(),
            ),
        )
        StandaloneSaveKind.VITA3K -> docs.findPath(
            docs.root,
            listOf("ux0", "user", "00", "savedata", titleId.uppercase()),
        )
    }

    private fun createSaveRoot(
        docs: DocumentTree,
        kind: StandaloneSaveKind,
        titleId: String,
    ): DocumentNode = when (kind) {
        StandaloneSaveKind.CITRA_MMJ -> throw IllegalStateException(
            "Citra MMJ must create this game's save once before Cannoli can restore it",
        )
        StandaloneSaveKind.CEMU -> docs.ensurePath(
            docs.root,
            listOf(
                "mlc01",
                "usr",
                "save",
                titleId.substring(0, 8).lowercase(),
                titleId.substring(8).lowercase(),
            ),
        )
        StandaloneSaveKind.VITA3K -> docs.ensurePath(
            docs.root,
            listOf("ux0", "user", "00", "savedata", titleId.uppercase()),
        )
    }

    private fun writeArchive(
        docs: DocumentTree,
        kind: StandaloneSaveKind,
        titleId: String,
        files: List<DocumentFileEntry>,
        destination: File,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(stableEntry(MANIFEST_NAME))
            zip.write(manifest(kind, titleId).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (entry in files.sortedBy { it.relativePath }) {
                zip.putNextEntry(stableEntry("save/${entry.relativePath}"))
                docs.openInput(entry.node).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun writeFileArchive(
        kind: StandaloneSaveKind,
        titleId: String,
        saveRoot: File,
        files: List<File>,
        destination: File,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(stableEntry(MANIFEST_NAME))
            zip.write(manifest(kind, titleId).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (file in files.sortedBy { it.relativeTo(saveRoot).invariantSeparatorsPath }) {
                val relative = file.relativeTo(saveRoot).invariantSeparatorsPath
                zip.putNextEntry(stableEntry("save/$relative"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun extractAndValidate(
        archive: File,
        stage: File,
        expectedKind: StandaloneSaveKind,
        expectedTitleId: String,
    ) {
        var entryCount = 0
        var extractedBytes = 0L
        var manifestText: String? = null
        val stageRoot = stage.canonicalFile
        ZipFile(archive).use { zip ->
            val entries = zip.entries().toList()
            for (entry in entries) {
                if (entry.isDirectory) continue
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) throw IllegalStateException("save archive has too many files")
                if (entry.name == MANIFEST_NAME) {
                    manifestText = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                    continue
                }
                if (!entry.name.startsWith("save/")) throw IllegalStateException("unrecognized save archive entry")
                val relative = entry.name.removePrefix("save/")
                if (relative.isBlank()) continue
                val output = File(stageRoot, relative).canonicalFile
                if (!output.path.startsWith(stageRoot.path + File.separator)) {
                    throw IllegalStateException("unsafe save archive path")
                }
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { out ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            extractedBytes += count
                            if (extractedBytes > MAX_ARCHIVE_BYTES) {
                                throw IllegalStateException("save archive is too large")
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
        val expected = manifest(expectedKind, expectedTitleId)
        if (manifestText != expected) {
            throw IllegalStateException("server save belongs to a different emulator or game")
        }
        if (stage.walkTopDown().none { it.isFile }) throw IllegalStateException("save archive is empty")
    }

    private fun reconcile(
        docs: DocumentTree,
        saveRoot: DocumentNode,
        stage: File,
        allowCreate: Boolean,
    ) {
        val incomingFiles = stage.walkTopDown()
            .filter { it.isFile }
            .associateBy { it.relativeTo(stage).invariantSeparatorsPath }
        val incomingDirs = incomingFiles.keys
            .flatMap { path ->
                generateSequence(File(path).parentFile) { it.parentFile }
                    .takeWhile { it.path != "." && it.path.isNotEmpty() }
                    .map { it.invariantSeparatorsPath }
            }
            .distinct()
            .sortedBy { it.count { ch -> ch == '/' } }

        if (!allowCreate) {
            val existing = docs.walkFiles(saveRoot).mapTo(HashSet()) { it.relativePath }
            val missing = incomingFiles.keys - existing
            if (missing.isNotEmpty()) {
                throw IllegalStateException(
                    "Citra MMJ must create this save layout before Cannoli can restore it",
                )
            }
        }

        val directories = HashMap<String, DocumentNode>()
        directories[""] = saveRoot
        for (relative in incomingDirs) {
            val parentPath = File(relative).parent?.replace(File.separatorChar, '/')?.takeUnless { it == "." } ?: ""
            val parent = directories[parentPath]
                ?: throw IllegalStateException("save archive directory order is invalid")
            directories[relative] = docs.child(parent, File(relative).name)
                ?: if (allowCreate) docs.createDirectory(parent, File(relative).name)
                else throw IllegalStateException("Citra MMJ save directory is incomplete")
        }
        for ((relative, source) in incomingFiles.toSortedMap()) {
            val parentPath = File(relative).parent?.replace(File.separatorChar, '/')?.takeUnless { it == "." } ?: ""
            val parent = directories[parentPath] ?: saveRoot
            val target = docs.child(parent, File(relative).name)
                ?: if (allowCreate) docs.createFile(parent, File(relative).name)
                else throw IllegalStateException("Citra MMJ save file is missing")
            docs.openOutput(target).use { out -> source.inputStream().use { it.copyTo(out) } }
        }

        // Publish all incoming bytes before removing files no longer present in the server copy.
        val stale = docs.walkFiles(saveRoot).filter { it.relativePath !in incomingFiles }
        stale.sortedByDescending { it.relativePath.length }.forEach { docs.delete(it.node) }
    }

    private fun reconcileFileTree(saveRoot: File, stage: File) {
        check(saveRoot.mkdirs() || saveRoot.isDirectory) {
            "cannot create Vita3K save directory"
        }
        val canonicalRoot = saveRoot.canonicalFile
        val incoming = stage.walkTopDown()
            .filter { it.isFile }
            .associateBy { it.relativeTo(stage).invariantSeparatorsPath }

        for ((relative, source) in incoming.toSortedMap()) {
            val target = File(canonicalRoot, relative).canonicalFile
            check(target.path.startsWith(canonicalRoot.path + File.separator)) {
                "unsafe Vita3K save path"
            }
            target.parentFile?.mkdirs()
            val temp = File.createTempFile(".cannoli-save-", ".tmp", target.parentFile)
            try {
                source.copyTo(temp, overwrite = true)
                replaceFile(temp, target)
            } finally {
                temp.delete()
            }
        }

        // Publish all incoming bytes before removing files no longer present in the server copy.
        saveRoot.walkTopDown()
            .filter { it.isFile && it.relativeTo(saveRoot).invariantSeparatorsPath !in incoming }
            .toList()
            .forEach { check(it.delete()) { "cannot remove stale Vita3K save file ${it.name}" } }
        saveRoot.walkBottomUp()
            .filter { it.isDirectory && it != saveRoot && it.list()?.isEmpty() == true }
            .forEach(File::delete)
    }

    private fun manifest(kind: StandaloneSaveKind, titleId: String): String =
        "format=$ARCHIVE_FORMAT\nemulator=${kind.name}\ntitle_id=${titleId.uppercase()}\n"

    private fun stableEntry(name: String) = ZipEntry(name).apply { time = 0L }

    private fun replaceFile(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun createStageDirectory(): File {
        val parent = File(root, "Config/Cache").apply { mkdirs() }
        val file = File.createTempFile("standalone-restore", ".tmp", parent)
        file.delete()
        check(file.mkdir()) { "could not create save restore staging directory" }
        return file
    }

    private companion object {
        const val DOCUMENT_ROOT_ID = "root"
        const val TREE_PREFERENCES = "standalone_save_trees"
        const val STANDALONE_SUFFIX = ".cannoli-standalone.zip"
        const val MANIFEST_NAME = "cannoli-standalone-save.txt"
        const val ARCHIVE_FORMAT = 1
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
        const val VITA3K_PUBLIC_ROOT = "/storage/emulated/0/Vita3K/vita"
        const val VITA3K_SAVEDATA_PATH = "ux0/user/00/savedata"
    }
}

private data class DocumentNode(
    val uri: Uri,
    val displayName: String,
    val isDirectory: Boolean,
    val lastModified: Long,
)

private data class DocumentFileEntry(
    val node: DocumentNode,
    val relativePath: String,
)

private class DocumentTree(
    private val resolver: android.content.ContentResolver,
    private val treeUri: Uri,
) {
    private val treeRootId = DocumentsContract.getTreeDocumentId(treeUri)

    val root: DocumentNode by lazy {
        queryDocument(documentUri(treeRootId))
    }

    fun children(parent: DocumentNode): List<DocumentNode> {
        val parentId = DocumentsContract.getDocumentId(parent.uri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    add(
                        DocumentNode(
                            uri = documentUri(id),
                            displayName = cursor.getString(nameIndex),
                            isDirectory = cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR,
                            lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    fun child(parent: DocumentNode, name: String): DocumentNode? =
        children(parent).firstOrNull { it.displayName.equals(name, ignoreCase = true) }

    fun findPath(start: DocumentNode, names: List<String>): DocumentNode? {
        var current = start
        for (name in names) {
            current = child(current, name) ?: return null
        }
        return current
    }

    fun ensurePath(start: DocumentNode, names: List<String>): DocumentNode {
        var current = start
        for (name in names) {
            current = child(current, name) ?: createDirectory(current, name)
        }
        return current
    }

    fun walkFiles(start: DocumentNode): List<DocumentFileEntry> {
        val out = ArrayList<DocumentFileEntry>()
        fun walk(directory: DocumentNode, prefix: String) {
            for (node in children(directory)) {
                val relative = if (prefix.isEmpty()) node.displayName else "$prefix/${node.displayName}"
                if (node.isDirectory) walk(node, relative) else out.add(DocumentFileEntry(node, relative))
            }
        }
        walk(start, "")
        return out
    }

    fun openInput(node: DocumentNode) =
        resolver.openInputStream(node.uri) ?: throw IllegalStateException("cannot read ${node.displayName}")

    fun openOutput(node: DocumentNode) =
        resolver.openOutputStream(node.uri, "wt") ?: throw IllegalStateException("cannot write ${node.displayName}")

    fun createDirectory(parent: DocumentNode, name: String): DocumentNode =
        create(parent, DocumentsContract.Document.MIME_TYPE_DIR, name)

    fun createFile(parent: DocumentNode, name: String): DocumentNode =
        create(parent, "application/octet-stream", name)

    fun delete(node: DocumentNode) {
        if (!DocumentsContract.deleteDocument(resolver, node.uri)) {
            throw IllegalStateException("cannot delete ${node.displayName}")
        }
    }

    private fun create(parent: DocumentNode, mimeType: String, name: String): DocumentNode {
        val uri = DocumentsContract.createDocument(resolver, parent.uri, mimeType, name)
            ?: throw IllegalStateException("cannot create $name")
        return queryDocument(uri)
    }

    private fun documentUri(documentId: String): Uri {
        // Citra MMJ exposes its root document as "root/" but child IDs as "root/path".
        // Android's tree enforcement expects descendants of "root/" to begin "root//";
        // the provider itself accepts that spelling and resolves it to the same file.
        val safeId = safeStandaloneDocumentId(treeUri.authority, treeRootId, documentId)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, safeId)
    }

    private fun queryDocument(uri: Uri): DocumentNode {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "document not found" }
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            DocumentNode(
                uri = uri,
                displayName = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)),
                isDirectory = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
                    DocumentsContract.Document.MIME_TYPE_DIR,
                lastModified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
            )
        } ?: throw IllegalStateException("document provider unavailable")
    }
}

internal fun safeStandaloneDocumentId(
    authority: String?,
    treeRootId: String,
    documentId: String,
): String = if (
    authority == StandaloneSaveKind.CITRA_MMJ.documentAuthority &&
    treeRootId.endsWith("/") &&
    documentId != treeRootId &&
    documentId.startsWith(treeRootId) &&
    !documentId.startsWith("$treeRootId/")
) {
    "$treeRootId/${documentId.removePrefix(treeRootId)}"
} else {
    documentId
}
