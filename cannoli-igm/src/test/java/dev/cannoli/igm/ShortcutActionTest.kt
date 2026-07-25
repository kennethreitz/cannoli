package dev.cannoli.igm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutActionTest {

    @Test
    fun `rewind shortcut is only available when rewind is enabled`() {
        assertFalse(ShortcutAction.HOLD_REWIND in availableShortcutActions(false))
        assertTrue(ShortcutAction.HOLD_REWIND in availableShortcutActions(true))
    }
}
