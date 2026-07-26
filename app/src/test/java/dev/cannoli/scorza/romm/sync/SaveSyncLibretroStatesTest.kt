package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.launcher.LaunchState
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
class SaveSyncLibretroStatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var service: SaveSyncService
    private lateinit var client: RommClient
    private lateinit var stateSlot: String
    private lateinit var stateFile: File

    @Before fun setup() {
        val sd = tmp.newFolder("SD")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsRepository(context).apply {
            sdCardRoot = sd.absolutePath
            rommSaveSyncEnabled = true
            rommDeviceId = "dev-1"
        }
        val paths = CannoliPathsProvider(settings)
        val gameKey = "SNES/Zelda.sfc"
        File(paths.romDir, gameKey).apply {
            parentFile?.mkdirs()
            writeText("ROM")
        }
        File(sd, "Save States/SNES/Zelda").mkdirs()
        stateFile = File(sd, "Save States/SNES/Zelda/Zelda.state.auto").apply {
            writeText("LOCAL-STATE")
        }

        val bridge = LibretroStateBridge(
            paths,
            PlatformConfig(sd, context.assets),
            LaunchState(),
        )
        stateSlot = bridge.slot(gameKey)

        val db = CannoliDatabase(paths)
        val links = RommLinkRepository(db) { paths.romDir }
        links.upsertLink(42, gameKey, "download")
        val connStore = mockk<RommConnectionStore>(relaxed = true)
        every { connStore.isConfigured } returns true
        every { connStore.serverVersion } returns "5.0.0"
        val registrar = mockk<DeviceRegistrar>()
        every { registrar.deviceId() } returns "dev-1"
        client = mockk(relaxed = true)
        val resolver = LocalSaveResolver(paths.root)
        val roms = mockk<dev.cannoli.scorza.db.RomsRepository>()
        every { roms.allRelativePaths() } returns listOf(gameKey)
        service = SaveSyncService(
            client,
            connStore,
            settings,
            registrar,
            SaveSyncStore(db),
            resolver,
            links,
            paths,
            SaveBackupManager(paths.root, resolver),
            SyncHistoryStore(db),
            PendingConflictStore(db),
            RestorePromotionStore(db),
            SaveSyncStatusHolder(),
            mockk(relaxed = true),
            roms,
            null,
            bridge,
        )
    }

    @Test fun `save states are not offered for download`() = runBlocking {
        assertTrue(stateSlot.startsWith(LibretroStateBridge.STATE_SLOT_PREFIX))
        assertFalse(service.canDownloadSaveStates("SNES/Zelda.sfc"))

        val result = service.downloadLatestSaveState(
            tag = "SNES",
            base = "Zelda",
            gameKey = "SNES/Zelda.sfc",
            emulator = "Snes9x",
        )

        assertFalse(result.success)
        assertEquals("Save-state sync is disabled", result.message)
        verify(exactly = 0) { client.getStates(any()) }
        verify(exactly = 0) { client.downloadStateContent(any(), any()) }
    }

    @Test fun `prelaunch sync ignores server save states`() = runBlocking {
        every { client.getSaves(42, "dev-1") } returns emptyList()

        val outcome = service.syncBeforeLaunch("SNES", "Zelda", "SNES/Zelda.sfc", "Snes9x")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        assertEquals("LOCAL-STATE", stateFile.readText())
        verify(exactly = 0) { client.getStates(any()) }
        verify(exactly = 0) { client.downloadStateContent(any(), any()) }
        verify(exactly = 0) { client.uploadState(any(), any(), any()) }
    }

    @Test fun `sweep never uploads libretro save states`() = runBlocking {
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1)

        val summary = service.sweep { Triple("SNES", "Zelda", "Snes9x") }

        assertEquals(0, summary.uploaded)
        assertEquals("LOCAL-STATE", stateFile.readText())
        verify(exactly = 0) { client.getStates(any()) }
        verify(exactly = 0) { client.uploadState(any(), any(), any()) }
    }
}
