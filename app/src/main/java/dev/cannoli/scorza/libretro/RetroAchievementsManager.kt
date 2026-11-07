package dev.cannoli.scorza.libretro

import android.os.Handler
import android.os.Looper
import dev.cannoli.igm.AchievementInfo
import dev.cannoli.scorza.BuildConfig
import dev.cannoli.scorza.R
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors

class RetroAchievementsManager(
    private val context: android.content.Context,
    private val cacheDir: java.io.File? = null,
    private val offlineDir: java.io.File? = null,
    private val onEvent: (type: Int, title: String, description: String, points: Int) -> Unit = { _, _, _, _ -> },
    private val onLogin: (success: Boolean, displayName: String, token: String?) -> Unit = { _, _, _ -> },
    private val onSyncStatus: (message: String) -> Unit = {},
    private val onDetectionReady: () -> Unit = {},
    private val logger: (String) -> Unit = {}
) {
    private val httpExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val userAgent: String by lazy {
        val clause = try { nativeGetUserAgentClause() } catch (_: Throwable) { "" }
        val base = "Cannoli/${BuildConfig.VERSION_NAME}"
        if (clause.isNotEmpty()) "$base $clause" else base
    }

    fun init() {
        logger("RA init")
        nativeInit()
        registerNetworkCallback()
    }

    fun destroy() {
        logger("RA destroy")
        unregisterNetworkCallback()
        // shutdownNow + no await: never block the caller (this runs on the main thread from
        // onDestroy/cleanup). The native side already tolerates late HTTP responses.
        httpExecutor.shutdownNow()
        nativeDestroy()
    }

    fun loginWithToken(username: String, token: String) {
        nativeLoginWithToken(username, token)
    }

    fun loginWithPassword(username: String, password: String) {
        nativeLoginWithPassword(username, password)
    }

    @Volatile private var loadStartedAtMs: Long = 0L

    val isResolving: Boolean
        get() {
            val started = loadStartedAtMs
            if (started == 0L) return false
            if (gameId > 0 && isMemoryInitialized) return false
            return android.os.SystemClock.elapsedRealtime() - started < 5_000L
        }

    fun loadGame(romPath: String, consoleId: Int) {
        logger("RA loadGame: romPath=$romPath consoleId=$consoleId")
        resetCompanionSession()
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        nativeLoadGame(romPath, consoleId)
    }

    fun loadGameById(gameId: Int, consoleId: Int) {
        logger("RA loadGameById: gameId=$gameId consoleId=$consoleId")
        resetCompanionSession()
        loadStartedAtMs = android.os.SystemClock.elapsedRealtime()
        nativeLoadGameById(gameId, consoleId)
    }

    fun unloadGame() {
        logger("RA unloadGame")
        resetCompanionSession()
        nativeUnloadGame()
    }

    fun doFrame() {
        nativeDoFrame()
    }

    fun idle() {
        nativeIdle()
    }

    fun reset() {
        logger("RA reset")
        nativeReset()
    }

    fun serializeProgress(): ByteArray? {
        val data = nativeSerializeProgress()
        logger("RA serializeProgress: ${data?.size ?: 0} bytes")
        return data
    }

    fun deserializeProgress(data: ByteArray): Boolean {
        val ok = nativeDeserializeProgress(data)
        logger("RA deserializeProgress: ${data.size} bytes, success=$ok")
        return ok
    }

    val isLoggedIn: Boolean get() = nativeIsLoggedIn()
    val username: String get() = nativeGetUsername()
    val gameId: Int get() = nativeGetGameId()
    val gameTitle: String get() = nativeGetGameTitle()
    val gameHash: String get() = nativeGetGameHash()
    val richPresence: String?
        get() = nativeGetRichPresence()
            .trim()
            .takeIf(String::isNotEmpty)
    val isOnline: Boolean get() {
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private var cachedAchievements: List<Achievement>? = null
    @Volatile private var latestCompanionEvent: CompanionEvent? = null
    private var companionBaselineUnlocked: Int? = null
    private var companionBaselinePoints: Int? = null
    val pendingSyncIds: MutableMap<Int, String> = Collections.synchronizedMap(mutableMapOf())
    val syncingIds: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    val localUnlocks: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var syncExpectedCount = 0
    @Volatile private var syncSuccessCount = 0
    @Volatile private var syncFailCount = 0

    private fun syncPending() {
        if (pendingSyncIds.isEmpty() || !nativeIsLoggedIn()) return
        val toSync: Map<Int, String>
        synchronized(pendingSyncIds) {
            toSync = pendingSyncIds.toMap()
        }
        if (toSync.isEmpty()) return
        val count = toSync.size
        logger("RA syncPending: syncing $count achievements: ${toSync.keys}")
        syncExpectedCount = count
        syncSuccessCount = 0
        syncFailCount = 0
        synchronized(syncingIds) { syncingIds.addAll(toSync.keys) }
        cachedAchievements = null
        for ((id, hash) in toSync) nativeQueueUnlock(id, hash)
    }
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        if (context == null || networkCallback != null) return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                syncPending()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        val cm = context?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        networkCallback = null
    }
    private val pendingSyncFile = cacheDir?.let { java.io.File(it, "pending_sync.txt") }

    init {
        pendingSyncFile?.let { file ->
            if (file.exists()) {
                try {
                    file.readLines().forEach { line ->
                        val parts = line.trim().split('|', limit = 2)
                        val id = parts[0].toIntOrNull() ?: return@forEach
                        val hash = if (parts.size > 1) parts[1] else ""
                        if (hash.isEmpty()) return@forEach
                        pendingSyncIds[id] = hash
                        localUnlocks.add(id)
                    }
                } catch (_: IOException) {}
            }
        }
    }

    private fun savePendingSync() {
        pendingSyncFile?.let { file ->
            try {
                file.parentFile?.mkdirs()
                synchronized(pendingSyncIds) {
                    if (pendingSyncIds.isEmpty()) file.delete()
                    else file.writeText(pendingSyncIds.entries.joinToString("\n") { "${it.key}|${it.value}" })
                }
            } catch (_: IOException) {}
        }
    }
    @Volatile var isOffline = false
        private set

    fun setPendingReset() {
        logger("RA setPendingReset")
        nativeSetPendingReset()
    }

    fun getAchievements(): List<Achievement> {
        cachedAchievements?.let { return it }
        val raw = nativeGetAchievementData()
        if (raw.isEmpty()) return emptyList()
        val list = raw.split('\n').mapNotNull { line ->
            val parts = line.split('|', limit = 7)
            if (parts.size < 7) return@mapNotNull null
            Achievement(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title = parts[1],
                description = parts[2],
                points = parts[3].toIntOrNull() ?: 0,
                unlocked = parts[4] == "1",
                state = parts[5].toIntOrNull() ?: 0,
                unlockTime = parts[6].toLongOrNull() ?: 0
            )
        }.filter { it.id > 0 && !it.title.startsWith("Warning:") && !it.title.startsWith("Unsupported") }
            .sortedBy { if (it.points == 0) 1 else 0 }
        cachedAchievements = list
        return list
    }

    fun getUniversalCompanionSnapshot(nowMillis: Long = System.currentTimeMillis()): UniversalCompanionSnapshot {
        val achievements = parseCompanionAchievements(nativeGetCompanionAchievementData())
        val summary = parseCompanionSummary(nativeGetCompanionSummaryData())
        val baselineUnlocked = companionBaselineUnlocked ?: summary.unlockedAchievements.also {
            companionBaselineUnlocked = it
        }
        val baselinePoints = companionBaselinePoints ?: summary.unlockedPoints.also {
            companionBaselinePoints = it
        }
        val activeChallenge = achievements.firstOrNull {
            !it.unlocked && it.bucket == COMPANION_BUCKET_ACTIVE_CHALLENGE
        }
        val featuredAchievement = activeChallenge ?: achievements
            .asSequence()
            .filter { !it.unlocked }
            .filter {
                it.bucket == COMPANION_BUCKET_ALMOST_THERE ||
                    (it.measuredPercent ?: 0f) > 0f ||
                    it.type == COMPANION_ACHIEVEMENT_TYPE_PROGRESSION
            }
            .sortedWith(
                compareByDescending<CompanionAchievement> {
                    it.bucket == COMPANION_BUCKET_ALMOST_THERE
                }.thenByDescending { it.measuredPercent ?: -1f }
            )
            .firstOrNull()

        return UniversalCompanionSnapshot(
            richPresence = richPresence,
            memoryReady = isMemoryInitialized && gameId > 0,
            unlockedAchievements = summary.unlockedAchievements,
            totalAchievements = summary.totalAchievements,
            unlockedPoints = summary.unlockedPoints,
            totalPoints = summary.totalPoints,
            sessionUnlockedAchievements =
                (summary.unlockedAchievements - baselineUnlocked).coerceAtLeast(0),
            sessionPoints = (summary.unlockedPoints - baselinePoints).coerceAtLeast(0),
            featuredAchievement = featuredAchievement,
            activeChallenge = activeChallenge,
            activeLeaderboard = parseActiveLeaderboard(nativeGetActiveLeaderboardData()),
            recentEvent = latestCompanionEvent?.takeIf {
                nowMillis - it.observedAtMillis <= COMPANION_EVENT_VISIBLE_MS
            },
            observedAtMillis = nowMillis,
        )
    }

    private fun resetCompanionSession() {
        latestCompanionEvent = null
        companionBaselineUnlocked = null
        companionBaselinePoints = null
    }

    private data class CompanionSummary(
        val totalAchievements: Int = 0,
        val unlockedAchievements: Int = 0,
        val totalPoints: Int = 0,
        val unlockedPoints: Int = 0,
    )

    private fun parseCompanionSummary(raw: String): CompanionSummary {
        val parts = raw.split('|')
        if (parts.size < 4) return CompanionSummary()
        return CompanionSummary(
            totalAchievements = parts[0].toIntOrNull() ?: 0,
            unlockedAchievements = parts[1].toIntOrNull() ?: 0,
            totalPoints = parts[2].toIntOrNull() ?: 0,
            unlockedPoints = parts[3].toIntOrNull() ?: 0,
        )
    }

    private fun parseCompanionAchievements(raw: String): List<CompanionAchievement> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(COMPANION_RECORD_SEPARATOR).mapNotNull { record ->
            val parts = record.split(COMPANION_FIELD_SEPARATOR)
            if (parts.size < 11) return@mapNotNull null
            CompanionAchievement(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title = parts[1],
                description = parts[2],
                points = parts[3].toIntOrNull() ?: 0,
                unlocked = parts[4] == "1",
                measuredProgress = parts[5].takeIf(String::isNotBlank),
                measuredPercent = parts[6].toFloatOrNull()?.takeIf { it > 0f },
                bucket = parts[7].toIntOrNull() ?: 0,
                rarity = parts[8].toFloatOrNull()?.takeIf { it > 0f },
                type = parts[9].toIntOrNull() ?: 0,
                badgeUrl = parts[10].takeIf(String::isNotBlank),
            )
        }
    }

    private fun parseActiveLeaderboard(raw: String): CompanionLeaderboard? {
        if (raw.isEmpty()) return null
        val parts = raw.split(COMPANION_FIELD_SEPARATOR)
        if (parts.size < 4) return null
        return CompanionLeaderboard(
            id = parts[0].toIntOrNull() ?: return null,
            title = parts[1],
            description = parts[2],
            trackerValue = parts[3],
        )
    }

    fun invalidateCache() {
        cachedAchievements = null
    }

    data class Achievement(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val unlocked: Boolean,
        val state: Int,
        val unlockTime: Long = 0,
        val pendingSync: Boolean = false
    )

    private fun cacheKey(postData: String?): String? {
        if (postData == null) return null
        val cacheable = postData.contains("r=achievementsets") || postData.contains("r=login2") || postData.contains("r=startsession")
        if (!cacheable) return null
        return postData.replace(CACHE_KEY_STRIP_REGEX, "")
            .hashCode().toUInt().toString(16)
    }

    private fun readCache(key: String): String? {
        val file = java.io.File(cacheDir ?: return null, "ra_$key.json")
        return if (file.exists()) try { file.readText() } catch (_: IOException) { null } else null
    }

    private fun writeCache(key: String, body: String) {
        val dir = cacheDir ?: return
        dir.mkdirs()
        try { java.io.File(dir, "ra_$key.json").writeText(body) } catch (_: IOException) {}
    }

    @Suppress("unused")
    private fun onServerCall(url: String, postData: String?, requestPtr: Long) {
        val urlTag = url.substringAfterLast("/").substringBefore("?").take(40)
        logger("RA http request: $urlTag")
        try {
            httpExecutor.execute {
                val key = cacheKey(postData)
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", userAgent)
                    if (postData != null) {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        OutputStreamWriter(conn.outputStream).use { it.write(postData) }
                    }
                    val status = conn.responseCode
                    val body = try {
                        conn.inputStream.bufferedReader().readText()
                    } catch (_: IOException) {
                        conn.errorStream?.bufferedReader()?.readText() ?: ""
                    }
                    logger("RA http response: $urlTag status=$status bodyLen=${body.length}")
                    if (key != null && status == 200 && body.isNotEmpty()) writeCache(key, body)
                    isOffline = false
                    nativeHttpResponse(requestPtr, body, status)
                } catch (e: IOException) {
                    val cached = if (key != null) readCache(key) else null
                    val offline = cached ?: dev.cannoli.scorza.ra.RaOfflineLookup.bodyFor(offlineDir, postData)
                    if (offline != null) {
                        logger("RA http FAILED ($urlTag): ${e.message} -- using ${if (cached != null) "cache" else "offline store"} (len=${offline.length})")
                        isOffline = true
                        nativeHttpResponse(requestPtr, offline, 200)
                    } else {
                        logger("RA http FAILED ($urlTag): ${e.message} -- no cache")
                        isOffline = true
                        nativeHttpResponse(requestPtr, "", RC_SERVER_ERROR)
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            logger("RA http rejected ($urlTag): executor shut down")
            nativeHttpResponse(requestPtr, "", RC_SERVER_ERROR)
        }
    }

    val pendingSyncCount: Int get() = pendingSyncIds.size

    fun getStatus(): String {
        val offline = !isOnline || isOffline
        return when {
            !offline -> context.getString(R.string.ra_status_online)
            pendingSyncIds.isEmpty() -> context.getString(R.string.ra_status_offline)
            else -> context.getString(R.string.ra_status_offline_pending, pendingSyncIds.size)
        }
    }

    val isMemoryInitialized: Boolean get() = nativeIsMemoryInitialized()

    fun getDetectionStatus(): String {
        if (!nativeIsLoggedIn()) return context.getString(R.string.ra_detect_not_logged_in)
        if (gameId <= 0) {
            return if (isResolving) context.getString(R.string.ra_detect_identifying) else context.getString(R.string.ra_detect_not_recognized)
        }
        val achievementCount = getAchievements().size
        if (achievementCount == 0) return context.getString(R.string.ra_detect_no_achievements)
        if (!isMemoryInitialized) {
            return if (isResolving) context.getString(R.string.ra_detect_initializing) else context.getString(R.string.ra_detect_mem_failed)
        }
        return context.resources.getQuantityString(R.plurals.ra_detect_active, achievementCount, achievementCount)
    }

    @Suppress("unused")
    private fun onAchievementEvent(type: Int, achievementId: Int, title: String, description: String, points: Int) {
        logger("RA achievement event: type=$type id=$achievementId title=$title points=$points")

        if (type == EVENT_RECONNECTED) {
            logger("RA reconnected: rcheevos completed all pending awards")
            return
        }

        if (type == EVENT_DISCONNECTED) {
            logger("RA disconnected: rcheevos has pending awards that will retry")
            mainHandler.postDelayed({ onSyncStatus("Offline: Will Sync Later") }, 3500)
            return
        }

        if (type == EVENT_DETECTION_READY) {
            logger("RA detection ready: memory init complete")
            mainHandler.post { onDetectionReady() }
            return
        }

        if (achievementId > 0) {
            localUnlocks.add(achievementId)
            val hash = nativeGetGameHash()
            pendingSyncIds[achievementId] = hash
            savePendingSync()
            logger("RA achievement pending: id=$achievementId hash=$hash pendingCount=${pendingSyncIds.size}")
        }
        cachedAchievements = null
        mainHandler.post { onEvent(type, title, description, points) }
    }

    @Suppress("unused")
    private fun onCompanionEvent(
        type: Int,
        id: Int,
        title: String,
        description: String,
        value: String,
        bestValue: String,
        rank: Int,
        totalEntries: Int,
        points: Int,
        badgeUrl: String,
    ) {
        logger("RA companion event: type=$type id=$id title=$title value=$value rank=$rank")
        if (type !in COMPANION_TRANSIENT_EVENT_TYPES) return
        latestCompanionEvent = CompanionEvent(
            type = type,
            id = id,
            title = title,
            description = description,
            value = value.takeIf(String::isNotBlank),
            bestValue = bestValue.takeIf(String::isNotBlank),
            rank = rank,
            totalEntries = totalEntries,
            points = points,
            badgeUrl = badgeUrl.takeIf(String::isNotBlank),
            observedAtMillis = System.currentTimeMillis(),
        )
    }

    @Suppress("unused")
    private fun onLoginResult(success: Boolean, displayNameOrError: String, token: String?) {
        logger("RA login result: success=$success offline=$isOffline pendingSync=${pendingSyncIds.size}")
        if (success && !isOffline) syncPending()
        mainHandler.post { onLogin(success, displayNameOrError, token) }
    }

    @Suppress("unused")
    private fun onAwardResult(achievementId: Int, success: Boolean) {
        logger("RA award result: id=$achievementId success=$success")
        if (success) {
            pendingSyncIds.remove(achievementId)
            syncingIds.remove(achievementId)
            savePendingSync()
            syncSuccessCount++
        } else {
            syncingIds.remove(achievementId)
            syncFailCount++
            logger("RA award FAILED for id=$achievementId, keeping in pending queue")
        }
        val done = syncSuccessCount + syncFailCount
        if (done >= syncExpectedCount && syncExpectedCount > 0) {
            val msg = if (syncFailCount == 0) {
                context.resources.getQuantityString(R.plurals.ra_sync_done, syncSuccessCount, syncSuccessCount)
            } else {
                context.getString(R.string.ra_sync_partial, syncSuccessCount, syncFailCount)
            }
            logger("RA sync complete: $msg")
            syncExpectedCount = 0
            mainHandler.post { onSyncStatus(msg) }
        }
    }

    @Suppress("unused")
    private fun onNativeLog(message: String) {
        logger("RA [native] $message")
    }

    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeLoginWithToken(username: String, token: String)
    private external fun nativeLoginWithPassword(username: String, password: String)
    private external fun nativeLoadGame(romPath: String, consoleId: Int)
    private external fun nativeLoadGameById(gameId: Int, consoleId: Int)
    private external fun nativeUnloadGame()
    private external fun nativeDoFrame()
    private external fun nativeIdle()
    private external fun nativeReset()
    private external fun nativeIsLoggedIn(): Boolean
    private external fun nativeGetUsername(): String
    private external fun nativeGetGameId(): Int
    private external fun nativeGetGameTitle(): String
    private external fun nativeGetRichPresence(): String
    private external fun nativeGetUserAgentClause(): String
    private external fun nativeHttpResponse(requestPtr: Long, body: String, httpStatus: Int)
    private external fun nativeGetAchievementData(): String
    private external fun nativeGetCompanionAchievementData(): String
    private external fun nativeGetCompanionSummaryData(): String
    private external fun nativeGetActiveLeaderboardData(): String
    private external fun nativeSerializeProgress(): ByteArray?
    private external fun nativeDeserializeProgress(data: ByteArray): Boolean
    private external fun nativeQueueUnlock(achievementId: Int, gameHash: String)
    private external fun nativeGetGameHash(): String
    private external fun nativeIsMemoryInitialized(): Boolean
    private external fun nativeSetPendingReset()

    companion object {
        init {
            System.loadLibrary("retro_bridge")
        }

        private const val RC_SERVER_ERROR = 503
        private const val EVENT_DISCONNECTED = 17
        private const val EVENT_RECONNECTED = 18
        private const val EVENT_DETECTION_READY = 1000
        private const val COMPANION_EVENT_VISIBLE_MS = 7_000L
        private const val COMPANION_RECORD_SEPARATOR = '\u001E'
        private const val COMPANION_FIELD_SEPARATOR = '\u001F'
        private val COMPANION_TRANSIENT_EVENT_TYPES = setOf(
            CompanionEvent.ACHIEVEMENT_UNLOCKED,
            CompanionEvent.LEADERBOARD_FAILED,
            CompanionEvent.LEADERBOARD_SUBMITTED,
            CompanionEvent.LEADERBOARD_SCOREBOARD,
            CompanionEvent.GAME_COMPLETED,
            CompanionEvent.SUBSET_COMPLETED,
        )
        private val CACHE_KEY_STRIP_REGEX = Regex("[&?](t|u)=[^&]+")

    }
}

fun RetroAchievementsManager.Achievement.toAchievementInfo() = AchievementInfo(
    id = id,
    title = title,
    description = description,
    points = points,
    unlocked = unlocked,
    state = state,
    unlockTime = unlockTime,
    pendingSync = pendingSync
)
