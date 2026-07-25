package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.execute
import dev.cannoli.scorza.db.queryAll
import dev.cannoli.scorza.db.queryOne

data class PendingConflict(
    val gameKey: String,
    val slot: String,
    val romId: Int,
    val displayName: String,
    val serverSaveId: Int?,
    val serverContentHash: String?,
    val serverUpdatedAt: String?,
    val detectedAt: Long,
    val dismissedHash: String?,
)

class PendingConflictStore(private val db: CannoliDatabase) {

    fun upsert(c: PendingConflict) = db.execute(
        "INSERT OR REPLACE INTO pending_conflicts (game_key, slot, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        c.gameKey, c.slot, c.romId, c.displayName, c.serverSaveId, c.serverContentHash, c.serverUpdatedAt, c.detectedAt, c.dismissedHash,
    )

    fun all(): List<PendingConflict> = db.queryAll(
        "SELECT game_key, slot, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash FROM pending_conflicts ORDER BY detected_at DESC",
    ) { it.toConflict() }

    fun get(gameKey: String, slot: String = DEFAULT_SLOT): PendingConflict? = db.queryOne(
        "SELECT game_key, slot, rom_id, display_name, server_save_id, server_content_hash, server_updated_at, detected_at, dismissed_hash FROM pending_conflicts WHERE game_key = ? AND slot = ?",
        gameKey, slot,
    ) { it.toConflict() }

    fun delete(gameKey: String, slot: String = DEFAULT_SLOT) =
        db.execute("DELETE FROM pending_conflicts WHERE game_key = ? AND slot = ?", gameKey, slot)

    fun count(): Int = db.queryOne("SELECT COUNT(*) FROM pending_conflicts") { it.getInt(0) } ?: 0

    fun markDismissed(gameKey: String, slot: String, serverHash: String?) =
        db.execute(
            "UPDATE pending_conflicts SET dismissed_hash = ? WHERE game_key = ? AND slot = ?",
            serverHash, gameKey, slot,
        )

    private fun androidx.sqlite.SQLiteStatement.toConflict() = PendingConflict(
        gameKey = getText(0),
        slot = getText(1),
        romId = getInt(2),
        displayName = getText(3),
        serverSaveId = if (isNull(4)) null else getInt(4),
        serverContentHash = if (isNull(5)) null else getText(5),
        serverUpdatedAt = if (isNull(6)) null else getText(6),
        detectedAt = getLong(7),
        dismissedHash = if (isNull(8)) null else getText(8),
    )
}
