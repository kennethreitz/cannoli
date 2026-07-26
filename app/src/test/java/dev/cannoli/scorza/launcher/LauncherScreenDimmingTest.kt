package dev.cannoli.scorza.launcher

import android.app.Activity
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherScreenDimmingTest {
    @Test
    fun `dual screen support dims the launcher only while a game uses the other display`() {
        assertTrue(shouldDimLauncherScreen(true, true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldDimLauncherScreen(false, true, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldDimLauncherScreen(true, false, gameDisplayId = 0, launcherDisplayId = 4))
        assertFalse(shouldDimLauncherScreen(true, true, gameDisplayId = null, launcherDisplayId = 4))
        assertFalse(shouldDimLauncherScreen(true, true, gameDisplayId = 4, launcherDisplayId = 4))
    }

    @Test
    fun `dimmed launcher consumes touch without taking focus from the game`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        setLauncherWindowInputBlocked(activity.window, blocked = true)
        assertTrue(activity.window.attributes.flags and notFocusable != 0)

        setLauncherWindowInputBlocked(activity.window, blocked = false)
        assertFalse(activity.window.attributes.flags and notFocusable != 0)
    }

    @Test
    fun `launcher dimming uses an app local overlay`() {
        assertEquals(DIMMED_LAUNCHER_OVERLAY_ALPHA, launcherDimOverlayAlpha(true), 0f)
        assertEquals(0f, launcherDimOverlayAlpha(false), 0f)
    }
}
