package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CollectionsRepository
import dev.cannoli.scorza.db.LibraryRef
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommException
import dev.cannoli.scorza.util.RommLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal data class FavoriteReconcilePlan(
    val addToServer: Set<Int> = emptySet(),
    val removeFromServer: Set<Int> = emptySet(),
    val addToLocal: Set<Int> = emptySet(),
    val removeFromLocal: Set<Int> = emptySet(),
)

/**
 * Three-way favorite reconciliation.
 *
 * The first run merges both sides. Later runs compare each side with the last server snapshot:
 * local edits are sent to RomM, and RomM edits are applied only to local games that can be mapped
 * unambiguously to a RomM id.
 */
internal fun reconcileFavorites(
    baseline: Set<Int>?,
    local: Set<Int>,
    server: Set<Int>,
    mappable: Set<Int>,
): FavoriteReconcilePlan {
    if (baseline == null) {
        return FavoriteReconcilePlan(
            addToServer = local - server,
            addToLocal = (server - local) intersect mappable,
        )
    }

    val mappedBaseline = baseline intersect mappable
    val localAdded = local - mappedBaseline
    val localRemoved = mappedBaseline - local
    val serverAdded = server - baseline
    val serverRemoved = baseline - server
    return FavoriteReconcilePlan(
        addToServer = localAdded - server,
        removeFromServer = localRemoved intersect server,
        addToLocal = (serverAdded - local) intersect mappable,
        removeFromLocal = (serverRemoved intersect local) intersect mappable,
    )
}

sealed interface FavoriteSyncResult {
    data object NotConfigured : FavoriteSyncResult
    data class Synced(val pushed: Int, val pulled: Int) : FavoriteSyncResult
    data object ReconnectRequired : FavoriteSyncResult
    data class Failed(val message: String) : FavoriteSyncResult
}

@Singleton
class RommFavoritesSync @Inject constructor(
    private val client: RommClient,
    private val connection: RommConnectionStore,
    private val collections: CollectionsRepository,
    private val roms: RomsRepository,
    private val links: RommLinkRepository,
    private val matcher: RommCacheMatcher,
    private val paths: CannoliPathsProvider,
    @IoScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val applyingRemote = AtomicBoolean(false)
    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val localChanges: SharedFlow<Unit> = _localChanges

    init {
        collections.favoriteMutationListener = {
            if (!applyingRemote.get()) requestSync()
        }
    }

    fun syncEnabled(): Boolean = connection.isConfigured

    fun requestSync() {
        if (!syncEnabled()) return
        scope.launch {
            when (val result = sync()) {
                FavoriteSyncResult.ReconnectRequired ->
                    RommLog.write("favorites: RomM connection must be paired again for collections.write")
                is FavoriteSyncResult.Failed ->
                    RommLog.write("favorites: sync failed: ${result.message}")
                else -> Unit
            }
        }
    }

    suspend fun sync(): FavoriteSyncResult = withContext(Dispatchers.IO) {
        if (!syncEnabled()) return@withContext FavoriteSyncResult.NotConfigured
        mutex.withLock {
            runCatching { syncLocked() }.getOrElse { error ->
                if (error is RommException && error.statusCode == 403) {
                    FavoriteSyncResult.ReconnectRequired
                } else {
                    FavoriteSyncResult.Failed(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    private fun syncLocked(): FavoriteSyncResult {
        matcher.refresh()
        val remoteToLocal = buildRemoteToLocalMap()
        val mappable = remoteToLocal.keys
        val localFavoriteRows = collections.favoriteRomIds()
        val local = remoteToLocal.asSequence()
            .filter { (_, localId) -> localId in localFavoriteRows }
            .mapTo(mutableSetOf()) { it.key }

        var favoriteCollection = client.getFavoriteCollection()
        val server = favoriteCollection?.romIds?.toSet() ?: emptySet()
        val plan = reconcileFavorites(
            baseline = connection.favoriteSyncBaseline,
            local = local,
            server = server,
            mappable = mappable,
        )

        val localChanged = applyServerChangesLocally(plan, remoteToLocal)
        // Record what was successfully read even if the token is too old to write. Local edits
        // remain distinguishable from this snapshot and will retry after the user pairs again.
        connection.favoriteSyncBaseline = server

        if ((plan.addToServer.isNotEmpty() || plan.removeFromServer.isNotEmpty()) &&
            favoriteCollection == null
        ) {
            favoriteCollection = client.createFavoriteCollection()
        }

        var finalServer = favoriteCollection?.romIds?.toSet() ?: server
        if (plan.addToServer.isNotEmpty()) {
            finalServer = client.addRomsToCollection(
                requireNotNull(favoriteCollection).id,
                plan.addToServer,
            ).romIds.toSet()
        }
        if (plan.removeFromServer.isNotEmpty()) {
            finalServer = client.removeRomsFromCollection(
                requireNotNull(favoriteCollection).id,
                plan.removeFromServer,
            ).romIds.toSet()
        }
        connection.favoriteSyncBaseline = finalServer

        val pushed = plan.addToServer.size + plan.removeFromServer.size
        val pulled = plan.addToLocal.size + plan.removeFromLocal.size
        if (pushed > 0 || pulled > 0) {
            RommLog.write("favorites: synced pushed=$pushed pulled=$pulled server=${finalServer.size}")
        }
        if (localChanged) _localChanges.tryEmit(Unit)
        return FavoriteSyncResult.Synced(pushed = pushed, pulled = pulled)
    }

    private fun applyServerChangesLocally(
        plan: FavoriteReconcilePlan,
        remoteToLocal: Map<Int, Long>,
    ): Boolean {
        if (plan.addToLocal.isEmpty() && plan.removeFromLocal.isEmpty()) return false
        val favoritesId = collections.favoritesId() ?: return false
        applyingRemote.set(true)
        try {
            plan.addToLocal.forEach { remoteId ->
                remoteToLocal[remoteId]?.let { collections.addMember(favoritesId, LibraryRef.Rom(it)) }
            }
            plan.removeFromLocal.forEach { remoteId ->
                remoteToLocal[remoteId]?.let { collections.removeMember(favoritesId, LibraryRef.Rom(it)) }
            }
        } finally {
            applyingRemote.set(false)
        }
        return true
    }

    private fun buildRemoteToLocalMap(): Map<Int, Long> {
        val root = paths.romDir.absolutePath.trimEnd(File.separatorChar) + File.separator
        return buildMap {
            for (rom in roms.allRoms()) {
                val relative = rom.path.absolutePath
                    .takeIf { it.startsWith(root) }
                    ?.removePrefix(root)
                    ?.replace(File.separatorChar, '/')
                val remoteId = relative?.let(links::rommIdForPath)
                    ?: matcher.rommIdFor(rom.platformTag, rom.path.name)
                    ?: continue
                putIfAbsent(remoteId, rom.id)
            }
        }
    }
}
