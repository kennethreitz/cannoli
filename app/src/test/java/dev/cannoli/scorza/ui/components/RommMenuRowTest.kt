package dev.cannoli.scorza.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RommMenuRowTest {
    @Test fun `downloads row hidden when no downloads`() {
        assertEquals(emptyList<RommActionRow>(), RommActionRow.visibleRows(false))
    }

    @Test fun `downloads row shown when downloads active`() {
        assertEquals(listOf(RommActionRow.DOWNLOADS), RommActionRow.visibleRows(true))
    }

    @Test fun `save sync errors row only when enabled and errors present`() {
        val withErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 1)
        val noErrors = RommSaveSyncRow.visibleRows(supported = true, enabled = true, syncErrors = 0)
        val disabled = RommSaveSyncRow.visibleRows(supported = true, enabled = false, syncErrors = 1)
        assertEquals(true, withErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, noErrors.contains(RommSaveSyncRow.ERRORS))
        assertEquals(false, disabled.contains(RommSaveSyncRow.ERRORS))
    }

    @Test fun `restore row appears whenever backups exist even if sync is off`() {
        val withBackups = RommSaveSyncRow.visibleRows(supported = true, enabled = false, hasBackups = true)
        val noBackups = RommSaveSyncRow.visibleRows(supported = true, enabled = true, hasBackups = false)
        assertEquals(true, withBackups.contains(RommSaveSyncRow.RESTORE))
        assertEquals(false, noBackups.contains(RommSaveSyncRow.RESTORE))
    }

    @Test fun `poll interval appears only when save sync is enabled`() {
        val enabled = RommSaveSyncRow.visibleRows(supported = true, enabled = true)
        val disabled = RommSaveSyncRow.visibleRows(supported = true, enabled = false)

        assertEquals(true, enabled.contains(RommSaveSyncRow.INTERVAL))
        assertEquals(false, disabled.contains(RommSaveSyncRow.INTERVAL))
    }
}
