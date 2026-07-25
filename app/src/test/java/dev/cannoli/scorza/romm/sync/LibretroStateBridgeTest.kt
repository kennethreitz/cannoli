package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibretroStateBridgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var sd: File
    private lateinit var platformConfig: PlatformConfig
    private lateinit var bridge: LibretroStateBridge
    private val gameKey = "SNES/Super Mario World.sfc"
    private val base = "Super Mario World"

    @Before fun setup() {
        sd = tmp.newFolder("SD")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context)
        settings.sdCardRoot = sd.absolutePath
        val paths = CannoliPathsProvider(settings)
        platformConfig = PlatformConfig(sd, context.assets)
        bridge = LibretroStateBridge(paths, platformConfig, LaunchState())
        File(paths.romDir, gameKey).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }

    @Test fun `archive includes durable states and thumbnails but excludes undo scratch files`() {
        val stateDir = File(sd, "Save States/SNES/$base").apply { mkdirs() }
        File(stateDir, "$base.state.auto").writeText("AUTO")
        File(stateDir, "$base.state.auto.png").writeText("AUTO-THUMB")
        File(stateDir, "$base.state").writeText("MANUAL")
        File(stateDir, "$base.state9").writeText("MANUAL-10")
        File(stateDir, "$base.state.undo").writeText("UNDO")
        File(stateDir, "$base.state.undo.png").writeText("UNDO-THUMB")

        val local = bridge.refreshArchive("SNES", base, gameKey)
        assertNotNull(local)
        val names = ZipFile(local!!.files.single()).use { zip ->
            zip.entries().toList().map { it.name }.toSet()
        }

        assertTrue("files/$base.state.auto" in names)
        assertTrue("files/$base.state.auto.png" in names)
        assertTrue("files/$base.state" in names)
        assertTrue("files/$base.state9" in names)
        assertFalse(names.any { it.contains(".undo") })
    }

    @Test fun `apply replaces the durable state set and preserves excluded undo scratch files`() {
        val stateDir = File(sd, "Save States/SNES/$base").apply { mkdirs() }
        File(stateDir, "$base.state.auto").writeText("SERVER-AUTO")
        File(stateDir, "$base.state").writeText("SERVER-MANUAL")
        val archive = bridge.refreshArchive("SNES", base, gameKey)!!.files.single()
        val downloaded = tmp.newFile("downloaded-states.zip")
        archive.copyTo(downloaded, overwrite = true)

        File(stateDir, "$base.state.auto").writeText("LOCAL-AUTO")
        File(stateDir, "$base.state1").writeText("STALE-SLOT")
        File(stateDir, "$base.state.undo").writeText("UNDO")

        bridge.applyArchive("SNES", base, gameKey, downloaded)

        assertEquals("SERVER-AUTO", File(stateDir, "$base.state.auto").readText())
        assertEquals("SERVER-MANUAL", File(stateDir, "$base.state").readText())
        assertFalse(File(stateDir, "$base.state1").exists())
        assertEquals("UNDO", File(stateDir, "$base.state.undo").readText())
    }

    @Test fun `downloaded bundle preserves which state Resume should consider newest`() {
        val stateDir = File(sd, "Save States/SNES/$base").apply { mkdirs() }
        val stamp = System.currentTimeMillis() - 60_000L
        val auto = File(stateDir, "$base.state.auto").apply {
            writeText("AUTO")
            setLastModified(stamp + 1_000L)
        }
        val manual = File(stateDir, "$base.state").apply {
            writeText("MANUAL")
            setLastModified(stamp + 2_000L)
        }
        val newest = File(stateDir, "$base.state1").apply {
            writeText("LATEST")
            setLastModified(stamp + 3_000L)
        }
        val downloaded = tmp.newFile("server-bundle.zip")
        bridge.refreshArchive("SNES", base, gameKey)!!.files.single()
            .copyTo(downloaded, overwrite = true)

        auto.setLastModified(stamp + 9_000L)
        manual.setLastModified(stamp + 9_000L)
        newest.setLastModified(stamp + 9_000L)
        bridge.applyArchive("SNES", base, gameKey, downloaded)

        assertEquals(stamp + 1_000L, auto.lastModified())
        assertEquals(stamp + 2_000L, manual.lastModified())
        assertEquals(stamp + 3_000L, newest.lastModified())
        assertEquals(
            "$base.state1",
            listOf(auto, manual, newest).maxBy { it.lastModified() }.name,
        )
    }

    @Test fun `core identity changes the reserved slot and rejects incompatible archives`() {
        val stateDir = File(sd, "Save States/SNES/$base").apply { mkdirs() }
        File(stateDir, "$base.state.auto").writeText("AUTO")
        val oldSlot = bridge.slot(gameKey)
        val archive = bridge.refreshArchive("SNES", base, gameKey)!!.files.single()
        val downloaded = tmp.newFile("old-core.zip")
        archive.copyTo(downloaded, overwrite = true)

        platformConfig.setCoreMapping("SNES", "bsnes_libretro", "RetroArch")

        assertNotEquals(oldSlot, bridge.slot(gameKey))
        val error = runCatching {
            bridge.applyArchive("SNES", base, gameKey, downloaded)
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("different game or core"))
    }
}
