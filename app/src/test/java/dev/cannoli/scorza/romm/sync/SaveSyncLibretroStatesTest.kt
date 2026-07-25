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
    private lateinit var store: SaveSyncStore
    private lateinit var stateSlot: String
    private lateinit var sd: File
    private lateinit var bridge: LibretroStateBridge
    private lateinit var stateFile: File

    @Before fun setup() {
        sd = tmp.newFolder("SD")
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
        File(sd, "Save States/SNES/Zelda").apply { mkdirs() }
        stateFile = File(sd, "Save States/SNES/Zelda/Zelda.state.auto").apply { writeText("STATE") }
        File(sd, "Save States/SNES/Zelda/Zelda.state.auto.png").writeText("THUMB")

        val platformConfig = PlatformConfig(sd, context.assets)
        bridge = LibretroStateBridge(paths, platformConfig, LaunchState())
        stateSlot = bridge.slot(gameKey)

        val db = CannoliDatabase(paths)
        store = SaveSyncStore(db)
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
            store,
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

    @Test fun `sweep uploads save states through the native RomM states API`() = runBlocking {
        every { client.negotiateSync(any()) } returns SyncNegotiateResponse(sessionId = 1)
        every { client.getStates(42) } returns emptyList()
        every {
            client.uploadState(42, "Snes9x", any())
        } returns RommStateDto(
            id = 99,
            romId = 42,
            fileName = "Zelda.cannoli-8d94d9927fb3.statebundle",
            updatedAt = "2026-07-25T12:00:00Z",
        )

        val summary = service.sweep { Triple("SNES", "Zelda", "Snes9x") }

        assertTrue(stateSlot.startsWith(LibretroStateBridge.STATE_SLOT_PREFIX))
        assertEquals(1, summary.uploaded)
        assertEquals(99, store.get("SNES/Zelda.sfc", stateSlot)?.rommSaveId)
        verify(exactly = 1) {
            client.uploadState(42, "Snes9x", match { it.name.endsWith(".statebundle") })
        }
        verify(exactly = 0) { client.uploadSave(42, "Snes9x", stateSlot, any(), any(), any()) }
    }

    @Test fun `launch automatically adopts a newer compatible raw RomM state`() = runBlocking {
        every { client.getStates(42) } returns listOf(
            RommStateDto(
                id = 6,
                romId = 42,
                fileName = "Zelda [2099-01-01 00-00-00].state",
                updatedAt = "2099-01-01T00:00:00Z",
                emulator = "snes9x",
            ),
        )
        every { client.downloadStateContent(6, any()) } answers {
            secondArg<File>().writeText("SERVER-STATE")
        }
        every { client.uploadState(42, "Snes9x", any()) } returns RommStateDto(
            id = 100,
            romId = 42,
            fileName = "Zelda.cannoli-8d94d9927fb3.statebundle",
            updatedAt = "2099-01-01T00:00:01Z",
        )
        every { client.getSaves(42, "dev-1") } returns emptyList()

        val outcome = service.syncBeforeLaunch("SNES", "Zelda", "SNES/Zelda.sfc", "Snes9x")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        assertEquals(
            "SERVER-STATE",
            File(sd, "Save States/SNES/Zelda/Zelda.state").readText(),
        )
        verify(exactly = 1) { client.downloadStateContent(6, any()) }
        verify(exactly = 1) {
            client.uploadState(42, "Snes9x", match { it.name.endsWith(".statebundle") })
        }
    }

    @Test fun `legacy migration skips a corrupted newer row and adopts the newest valid archive`() = runBlocking {
        stateFile.writeText("SERVER-LEGACY")
        val legacyArchive = tmp.newFile("legacy-state.zip")
        bridge.refreshArchive("SNES", "Zelda", "SNES/Zelda.sfc")!!.files.single()
            .copyTo(legacyArchive, overwrite = true)
        stateFile.writeText("LOCAL-OLDER")
        stateFile.setLastModified(1_000L)

        every { client.getStates(42) } returns emptyList()
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(
                id = 197,
                slot = stateSlot,
                contentHash = SaveHasher.md5Hex("NOT-A-ZIP".toByteArray()),
                updatedAt = "2099-01-01T00:00:01Z",
            ),
            RommSaveDto(
                id = 165,
                slot = stateSlot,
                contentHash = SaveHasher.hashFile(legacyArchive),
                updatedAt = "2099-01-01T00:00:00Z",
            ),
        )
        every { client.downloadSaveContent(any(), "dev-1", any()) } answers {
            val saveId = firstArg<Int>()
            val destination = thirdArg<File>()
            if (saveId == 197) destination.writeText("NOT-A-ZIP")
            else legacyArchive.copyTo(destination, overwrite = true)
        }
        every { client.uploadState(42, "Snes9x", any()) } returns RommStateDto(
            id = 101,
            romId = 42,
            fileName = "Zelda.cannoli-8d94d9927fb3.statebundle",
            updatedAt = "2099-01-01T00:00:02Z",
        )

        val outcome = service.syncBeforeLaunch("SNES", "Zelda", "SNES/Zelda.sfc", "Snes9x")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        assertEquals("SERVER-LEGACY", stateFile.readText())
        verify(exactly = 1) { client.downloadSaveContent(197, "dev-1", any()) }
        verify(exactly = 1) { client.downloadSaveContent(165, "dev-1", any()) }
    }
}
