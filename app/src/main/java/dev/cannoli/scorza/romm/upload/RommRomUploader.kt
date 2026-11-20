package dev.cannoli.scorza.romm.upload

import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommCapabilities
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommException
import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.romm.sync.RommCacheMatcher
import dev.cannoli.scorza.util.RommLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

sealed interface RommRomUploadOutcome {
    data object Uploaded : RommRomUploadOutcome
    data object AlreadyPresent : RommRomUploadOutcome
}

enum class RommRomUploadAvailability {
    AVAILABLE,
    ALREADY_PRESENT,
    HIDDEN,
}

class RommRomUploader(
    private val client: RommClient,
    private val connection: RommConnectionStore,
    private val matcher: RommCacheMatcher,
    private val platformMap: PlatformMap,
    private val syncCoordinator: RommSyncCoordinator,
    private val chunkSizeBytes: Long = DEFAULT_CHUNK_SIZE_BYTES,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) {
    private val completedUploads = ConcurrentHashMap.newKeySet<String>()

    fun availability(rom: Rom): RommRomUploadAvailability = rommUploadAvailability(
        paired = connection.isConfigured && RommCapabilities.isSupported(connection.serverVersion),
        localFile = rom.path,
        isMultiDisc = rom.isMultiDisc,
        cachedRommId = matcher.rommIdFor(rom.platformTag, rom.path.name),
        uploadedThisSession = uploadKey(rom) in completedUploads,
    )

    fun shouldOffer(rom: Rom): Boolean =
        availability(rom) == RommRomUploadAvailability.AVAILABLE

    suspend fun upload(
        rom: Rom,
        onProgress: suspend (uploadedBytes: Long, totalBytes: Long) -> Unit,
    ): RommRomUploadOutcome = withContext(Dispatchers.IO) {
        val file = rom.path
        require(file.isFile && file.length() > 0L) { "The selected ROM file is unavailable." }
        require(!rom.isMultiDisc) { "RomM does not support uploading multi-file playlist games." }

        val platform = platformMap.toDomain(client.getPlatforms())
            .firstOrNull { it.cannoliTag.equals(rom.platformTag, ignoreCase = true) }
            ?: throw RommException(
                null,
                "RomM does not expose a platform matching ${rom.platformTag}.",
            )

        if (serverAlreadyContains(platform.id, file)) {
            completedUploads += uploadKey(rom)
            refreshCache()
            return@withContext RommRomUploadOutcome.AlreadyPresent
        }

        val totalBytes = file.length()
        val totalChunks = ceil(totalBytes.toDouble() / chunkSizeBytes).toInt().coerceAtLeast(1)
        var uploadId: String? = null
        var complete = false
        try {
            uploadId = try {
                client.startRomUpload(
                    platformId = platform.id,
                    fileName = file.name,
                    totalSize = totalBytes,
                    totalChunks = totalChunks,
                )
            } catch (failure: RommException) {
                if (failure.statusCode == 400 &&
                    failure.message.orEmpty().contains("already exists", ignoreCase = true)
                ) {
                    completedUploads += uploadKey(rom)
                    refreshCache()
                    return@withContext RommRomUploadOutcome.AlreadyPresent
                }
                throw failure
            }
            onProgress(0L, totalBytes)

            for (index in 0 until totalChunks) {
                currentCoroutineContext().ensureActive()
                val offset = index * chunkSizeBytes
                val length = minOf(chunkSizeBytes, totalBytes - offset)
                uploadChunkWithRetry(uploadId, index, file, offset, length)
                onProgress(offset + length, totalBytes)
            }

            currentCoroutineContext().ensureActive()
            client.completeRomUpload(uploadId)
            complete = true
            completedUploads += uploadKey(rom)
            RommLog.write("rom upload complete [${rom.platformTag}] ${file.name} ($totalBytes bytes)")
            refreshCache()
            RommRomUploadOutcome.Uploaded
        } catch (cancelled: CancellationException) {
            RommLog.write("rom upload cancelled [${rom.platformTag}] ${file.name}")
            throw cancelled
        } catch (failure: Throwable) {
            RommLog.write(
                "ERROR rom upload failed [${rom.platformTag}] ${file.name}: " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
            throw failure
        } finally {
            if (!complete && uploadId != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { client.cancelRomUpload(uploadId) }
                        .onFailure { RommLog.write("rom upload cleanup failed: ${it.message}") }
                }
            }
        }
    }

    private fun serverAlreadyContains(platformId: Int, file: File): Boolean {
        val page = client.getRoms(
            platformId = platformId,
            limit = SERVER_DUPLICATE_SEARCH_LIMIT,
            offset = 0,
            search = file.nameWithoutExtension,
        )
        return page.items.any { game ->
            game.fsName.equals(file.name, ignoreCase = true) ||
                game.files.any { it.fileName.equals(file.name, ignoreCase = true) }
        }
    }

    private suspend fun uploadChunkWithRetry(
        uploadId: String,
        index: Int,
        file: File,
        offset: Long,
        length: Long,
    ) {
        var lastFailure: Throwable? = null
        repeat(MAX_CHUNK_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                client.uploadRomChunk(uploadId, index, file, offset, length)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                if (attempt + 1 < MAX_CHUNK_ATTEMPTS) {
                    retryDelay(RETRY_BASE_DELAY_MILLIS shl attempt)
                }
            }
        }
        throw lastFailure ?: RommException(null, "ROM chunk upload failed.")
    }

    private suspend fun refreshCache() {
        runCatching {
            syncCoordinator.syncDelta()
            matcher.refresh()
        }.onFailure {
            RommLog.write("rom upload cache refresh deferred: ${it.message}")
        }
    }

    private fun uploadKey(rom: Rom): String =
        "${rom.platformTag.uppercase(Locale.ROOT)}\u0000${rom.path.name.lowercase(Locale.ROOT)}"

    companion object {
        const val DEFAULT_CHUNK_SIZE_BYTES = 10L * 1024L * 1024L
        private const val MAX_CHUNK_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MILLIS = 1_000L
        private const val SERVER_DUPLICATE_SEARCH_LIMIT = 100
    }
}

internal fun rommUploadAvailability(
    paired: Boolean,
    localFile: File,
    isMultiDisc: Boolean,
    cachedRommId: Int?,
    uploadedThisSession: Boolean,
): RommRomUploadAvailability = when {
    !paired || !localFile.isFile || localFile.length() <= 0L || isMultiDisc ->
        RommRomUploadAvailability.HIDDEN
    cachedRommId != null || uploadedThisSession ->
        RommRomUploadAvailability.ALREADY_PRESENT
    else ->
        RommRomUploadAvailability.AVAILABLE
}
