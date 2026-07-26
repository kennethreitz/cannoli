package dev.cannoli.scorza.launcher

import android.content.Intent
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlackGameScreenTest {
    @Test
    fun `dual screen support always provides a black idle game display`() {
        assertTrue(shouldBlankGameScreen(true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(false, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, gameDisplayId = null, launcherDisplayId = 4))
        assertFalse(shouldBlankGameScreen(true, gameDisplayId = 4, launcherDisplayId = 4))
    }

    @Test
    fun `black screen accounts for a pending launcher display move`() {
        val launcherDisplayId = intendedLauncherDisplayId(
            currentDisplayId = 0,
            preferredDisplayId = 4,
        )

        assertTrue(
            shouldBlankGameScreen(
                dualScreenActive = true,
                gameDisplayId = 0,
                launcherDisplayId = launcherDisplayId,
            )
        )
    }

    @Test
    fun `volume and media keys remain system owned`() {
        assertTrue(isSystemMediaKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(isSystemMediaKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(isSystemMediaKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertTrue(isSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(isSystemMediaKey(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `focus handoff waits for a completed tap`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_MOVE, 105f, 204f))
        assertTrue(detector.onTouch(MotionEvent.ACTION_UP, 105f, 204f))
    }

    @Test
    fun `swipes and canceled touches do not hand off focus`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_MOVE, 100f, 230f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 230f))

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_CANCEL, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 200f))
    }

    @Test
    fun `multi touch does not hand off focus`() {
        val detector = BlackScreenTapGestureDetector(touchSlop = 10f)

        assertFalse(detector.onTouch(MotionEvent.ACTION_DOWN, 100f, 200f))
        assertFalse(detector.onTouch(MotionEvent.ACTION_POINTER_DOWN, 100f, 200f, pointerCount = 2))
        assertFalse(detector.onTouch(MotionEvent.ACTION_UP, 100f, 200f))
    }

    @Test
    fun `focus handoff reorders the existing launcher without Home classification`() {
        val intent = launcherFocusIntent(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        assertNull(intent.action)
        assertFalse(intent.categories?.contains(Intent.CATEGORY_HOME) == true)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `home anchor creates a distinct standard launcher task when none is running`() {
        val intent = launcherFromHomeAnchorIntent(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        assertNull(intent.action)
        assertFalse(intent.categories?.contains(Intent.CATEGORY_HOME) == true)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }

    @Test
    fun `automatic home callbacks do not reorder an existing launcher`() {
        assertFalse(
            shouldRequestLauncherFromHomeAnchor(
                runningLauncherAvailable = true,
                userInitiated = false,
                restorePending = false,
            )
        )
        assertTrue(
            shouldRequestLauncherFromHomeAnchor(
                runningLauncherAvailable = true,
                userInitiated = true,
                restorePending = false,
            )
        )
    }

    @Test
    fun `home anchor restores one missing launcher at a time`() {
        assertTrue(
            shouldRequestLauncherFromHomeAnchor(
                runningLauncherAvailable = false,
                userInitiated = false,
                restorePending = false,
            )
        )
        assertFalse(
            shouldRequestLauncherFromHomeAnchor(
                runningLauncherAvailable = false,
                userInitiated = true,
                restorePending = true,
            )
        )
    }

    @Test
    fun `display relocation replaces the Home task without reparenting it`() {
        val intent = launcherRelocationIntent(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_HOME) == true)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    }
}
