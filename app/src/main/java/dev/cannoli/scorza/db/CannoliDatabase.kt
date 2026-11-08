package dev.cannoli.scorza.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.cannoli.scorza.config.CannoliPaths
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ScanLog
import java.io.File

class CannoliDatabase(private val pathsProvider: CannoliPathsProvider) {
    private val databaseFile: File get() = CannoliPaths(pathsProvider.root).database

    // Open on first access. Hilt eagerly resolves @Singleton injects at MainActivity onCreate,
    // which is before the user clears the permission gate, so deferring the SQLite open keeps
    // construction free of file I/O (and lets the SD-card root resolved by setup win).
    val conn: SQLiteConnection by lazy {
        val dbFile = databaseFile
        dbFile.parentFile?.mkdirs()
        val c = BundledSQLiteDriver().open(dbFile.absolutePath)
        c.execSQL("PRAGMA foreign_keys = ON")
        c.execSQL("PRAGMA journal_mode = WAL")
        runMigrations(c)
        if (Migrations.repairGameOverrideSource(c)) {
            ScanLog.write("repaired missing game_overrides.source column")
        }
        runIntegrityCheck(c)
        c.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        c
    }

    /**
     * Serializes access to the underlying connection. BundledSQLiteDriver
     * connections are not safe for concurrent use across threads, so every
     * code path that touches [conn] must do so inside this lock (directly or
     * via the [CannoliDatabase] extension helpers in SqlExt). The monitor is
     * reentrant, so transactions can call locked helpers without deadlocking.
     */
    inline fun <T> withConn(block: (SQLiteConnection) -> T): T = synchronized(this) { block(conn) }

    fun close() = synchronized(this) { conn.close() }

    private fun runMigrations(conn: SQLiteConnection) {
        val current = readUserVersion(conn)
        if (current >= Migrations.current) return
        ScanLog.startRun("schema migration v$current -> v${Migrations.current}")
        Migrations.applyFrom(conn, current)
        ScanLog.write("schema migration complete")
    }

    private fun runIntegrityCheck(conn: SQLiteConnection) {
        val integrity = conn.query("PRAGMA integrity_check") { stmt ->
            stmt.step()
            stmt.getText(0)
        }
        if (integrity != "ok") {
            ScanLog.write("ERROR integrity_check returned: $integrity")
            throw DatabaseCorrupt("integrity_check returned: $integrity")
        }
        val fkViolations = conn.query("PRAGMA foreign_key_check") { stmt -> stmt.step() }
        if (fkViolations) {
            ScanLog.write("ERROR foreign_key_check reported violations")
            throw DatabaseCorrupt("foreign_key_check reported violations")
        }
    }

    private fun readUserVersion(conn: SQLiteConnection): Int =
        conn.query("PRAGMA user_version") { stmt ->
            stmt.step()
            stmt.getInt(0)
        }
}


