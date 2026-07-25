package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetroArchSaveBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var sd: File
    private lateinit var external: File
    private lateinit var bridge: RetroArchSaveBridge
    private val gameKey = "GBA/Castlevania - Aria of Sorrow.gba"
    private val base = "Castlevania - Aria of Sorrow"

    @Before fun setup() {
        sd = tmp.newFolder("SD")
        external = tmp.newFolder("RetroArch")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context).apply {
            sdCardRoot = sd.absolutePath
            retroArchPackage = RetroArchSaveBridge.PACKAGE_NAME
        }
        val paths = CannoliPathsProvider(settings)
        val config = PlatformConfig(sd, context.assets).apply {
            setCoreMapping("GBA", "mgba_libretro", "RetroArch")
        }
        File(paths.romDir, gameKey).apply {
            parentFile?.mkdirs()
            writeText("ROM")
        }
        bridge = RetroArchSaveBridge(context, settings, paths, config, LaunchState()).apply {
            externalRootOverride = external
            storageAccessOverride = true
            installedOverride = true
        }
    }

    @Test fun `save mirror is scoped to the current game in both directions`() {
        val retroSaves = File(external, "saves").apply { mkdirs() }
        File(retroSaves, "$base.srm").writeText("RETRO-SAVE")
        File(retroSaves, "Another Game.srm").writeText("OTHER")
        val cannoliSaves = File(sd, "Saves/GBA").apply { mkdirs() }
        File(cannoliSaves, "$base.rtc").writeText("STALE")

        assertEquals(
            RetroArchMirrorResult.Ready,
            bridge.refreshSaves("GBA", base, gameKey),
        )
        assertEquals("RETRO-SAVE", File(cannoliSaves, "$base.srm").readText())
        assertFalse(File(cannoliSaves, "$base.rtc").exists())

        File(cannoliSaves, "$base.srm").writeText("ROMM-SAVE")
        bridge.applySaves("GBA", base, gameKey)

        assertEquals("ROMM-SAVE", File(retroSaves, "$base.srm").readText())
        assertEquals("OTHER", File(retroSaves, "Another Game.srm").readText())
    }

    @Test fun `state mirror preserves all current game slots and ignores scratch states`() {
        val retroStates = File(external, "states").apply { mkdirs() }
        File(retroStates, "$base.state").writeText("MANUAL")
        File(retroStates, "$base.state.auto").writeText("AUTO")
        File(retroStates, "$base.state1").writeText("SLOT-1")
        File(retroStates, "$base.state.undo").writeText("UNDO")
        val cannoliStates = File(sd, "Save States/GBA/$base")

        assertEquals(
            RetroArchMirrorResult.Ready,
            bridge.refreshStates("GBA", base, gameKey),
        )
        assertEquals("MANUAL", File(cannoliStates, "$base.state").readText())
        assertEquals("AUTO", File(cannoliStates, "$base.state.auto").readText())
        assertEquals("SLOT-1", File(cannoliStates, "$base.state1").readText())
        assertFalse(File(cannoliStates, "$base.state.undo").exists())

        File(cannoliStates, "$base.state").writeText("ROMM-STATE")
        bridge.applyStates("GBA", base, gameKey)

        assertEquals("ROMM-STATE", File(retroStates, "$base.state").readText())
        assertEquals("UNDO", File(retroStates, "$base.state.undo").readText())
        assertTrue(File(retroStates, "$base.state.auto").isFile)
    }
}
