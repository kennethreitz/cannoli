package dev.cannoli.igm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutActionTest {

    @Test
    fun `rewind shortcut is experimental`() {
        assertFalse(ShortcutAction.HOLD_REWIND in availableShortcutActions(false))
        assertTrue(ShortcutAction.HOLD_REWIND in availableShortcutActions(true))
    }
}
