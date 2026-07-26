package dev.cannoli.scorza.config

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformsJsonAssetTest {
    @Test fun `bundled platforms_json parses without throwing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "fake-root"), ctx.assets)
        check(pc.getAllTags().isNotEmpty())
    }

    @Test fun `NES defaults to the memory-map aware FCEUmm core`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "nes-core-root"), ctx.assets)

        assertEquals("fceumm_libretro", pc.getCoreMapping("NES"))
    }

    @Test fun `bundled PC platform launches GameNative from the file contents`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = PlatformConfig(File(ctx.cacheDir, "fake-root-pc"), ctx.assets)

        assertTrue("PC" in config.getAllTags())
        assertNull(config.getCoreName("PC"))

        val app = config.getAppOptions("PC").single { it.packageName == "app.gamenative" }
        assertEquals("app.gamenative", app.packageName)
        assertEquals("app.gamenative.MainActivity", app.activity)
        assertEquals("app.gamenative.LAUNCH_GAME", app.action)
        assertTrue(app.data is DataBinding.None)

        val appId = app.extras.single { it.key == "app_id" }
        assertEquals(ExtraValueKind.INT, appId.kind)
        assertEquals("{rom_id}", appId.value)

        val source = app.extras.single { it.key == "game_source" }
        assertEquals(ExtraValueKind.STRING, source.kind)
        assertEquals("{rom_extension}", source.value)
        assertEquals(
            mapOf(
                "steam" to "STEAM",
                "steamappid" to "STEAM",
                "epic" to "EPIC",
                "gog" to "GOG",
                "amazon" to "AMAZON",
                "pcgame" to "CUSTOM_GAME",
            ),
            source.map,
        )
    }

    @Test fun `bundled PC platform offers GameHub Lite as a second launcher`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = PlatformConfig(File(ctx.cacheDir, "fake-root-pc-hub"), ctx.assets)

        val apps = config.getAppOptions("PC")
        assertEquals(2, apps.size)

        val hub = apps.single { it.packageName == "gamehub.lite" }
        assertEquals("com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity", hub.activity)
        assertEquals("gamehub.lite.LAUNCH_GAME", hub.action)
        assertTrue(hub.data is DataBinding.None)

        val steamAppId = hub.extras.single { it.key == "steamAppId" }
        assertEquals(ExtraValueKind.STRING, steamAppId.kind)
        assertEquals("{rom_id}", steamAppId.value)
        assertEquals(setOf("steam", "steamappid"), steamAppId.whenExtension)

        val localGameId = hub.extras.single { it.key == "localGameId" }
        assertEquals(ExtraValueKind.STRING, localGameId.kind)
        assertEquals("{rom_id}", localGameId.value)
        assertEquals(setOf("localgameid", "txt"), localGameId.whenExtension)

        // GameHub opens the game's detail page without this and waits for a tap on Play Now.
        val autoStart = hub.extras.single { it.key == "autoStartGame" }
        assertEquals(ExtraValueKind.BOOL, autoStart.kind)
        assertEquals("true", autoStart.value)

        // NO_HISTORY drops the detail activity once it hands off to the emulator, so quitting the
        // game empties GameHub's task and falls back to the launcher rather than its detail page.
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY,
            hub.intentFlags,
        )
    }

    @Test fun `Citra MMJ uses its exported game path launch contract`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pc = PlatformConfig(File(ctx.cacheDir, "citra-mmj-root"), ctx.assets)
        val config = pc.getAppOptions("3DS").first { it.packageName == "org.citra.emu" }

        assertEquals("org.citra.emu.ui.EmulationActivity", config.activity)
        assertEquals(1, config.extras.size)
        assertEquals("GamePath", config.extras.single().key)
        assertEquals(ExtraValueKind.FILE_PATH, config.extras.single().kind)
    }
}
