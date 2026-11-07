package dev.cannoli.scorza.romm.sync

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.CannoliDatabase
import dev.cannoli.scorza.db.RommLinkRepository
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveSyncPpssppTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var client: RommClient
    private lateinit var bridge: StandaloneSaveBridge
    private lateinit var retroArch: RetroArchSaveBridge
    private lateinit var service: SaveSyncService
    private lateinit var sd: File

    @Before fun setup() {
        sd = tmp.newFolder("SD")
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        settings.sdCardRoot = sd.absolutePath
        settings.rommSaveSyncEnabled = true
        settings.rommDeviceId = "dev-1"
        val paths = CannoliPathsProvider(settings)
        val db = CannoliDatabase(paths)
        val links = RommLinkRepository(db) { File(sd, "Roms") }
        links.upsertLink(42, GAME_KEY, "download")
        val connStore = mockk<RommConnectionStore>(relaxed = true)
        every { connStore.isConfigured } returns true
        every { connStore.serverVersion } returns "5.0.0"
        client = mockk(relaxed = true)
        bridge = mockk(relaxed = true)
        retroArch = mockk(relaxed = true)
        every { bridge.kindFor("PSP", "PPSSPP (Standalone)") } returns StandaloneSaveKind.PPSSPP
        every { bridge.archiveMode(StandaloneSaveKind.PPSSPP) } returns
            LocalSaveMode.PPSSPP_DIRECTORY_ARCHIVE
        every { bridge.isLinked(StandaloneSaveKind.PPSSPP) } returns true
        every { bridge.isGameActive() } returns false
        val registrar = mockk<DeviceRegistrar>()
        every { registrar.deviceId() } returns "dev-1"
        val resolver = LocalSaveResolver(paths.root)
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
            mockk(relaxed = true),
            standalone = bridge,
            retroArch = retroArch,
        )
    }

    @Test fun `negotiates PPSSPP directory as a logical ppsspp zip save`() = runTest {
        val archive = localArchive()
        every { bridge.refreshMirror(StandaloneSaveKind.PPSSPP, GAME_KEY, BASE) } returns
            StandaloneMirrorResult.Ready(archive)
        val payload = slot<SyncNegotiatePayload>()
        every { client.negotiateSync(capture(payload)) } returns
            SyncNegotiateResponse(sessionId = 1, totalNoOp = 1)

        val outcome = service.syncBeforeLaunch("PSP", BASE, GAME_KEY, "PPSSPP (Standalone)")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        val save = payload.captured.saves.single()
        assertEquals("$BASE.ppsspp.zip", save.fileName)
        assertEquals(SaveHasher.hashZipBundle(archive), save.contentHash)
    }

    @Test fun `restores downloaded OpenVault bundle through PPSSPP bridge before launch`() = runTest {
        every { bridge.refreshMirror(StandaloneSaveKind.PPSSPP, GAME_KEY, BASE) } returns
            StandaloneMirrorResult.Missing
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(
                id = 501,
                romId = 42,
                slot = DEFAULT_SLOT,
                fileName = "$BASE.ppsspp [2026-07-27_05-53-18].zip",
                updatedAt = "2026-07-27T00:00:00Z",
            ),
            RommSaveDto(
                id = 502,
                romId = 42,
                slot = DEFAULT_SLOT,
                fileName = "$BASE.srm",
                updatedAt = "2026-07-28T00:00:00Z",
            ),
        )
        every { client.downloadSaveContent(501, "dev-1", any()) } answers {
            writeOpenVaultBundle(thirdArg())
        }
        every { client.confirmSaveDownloaded(501, "dev-1") } returns
            RommSaveDto(id = 501, romId = 42, slot = DEFAULT_SLOT)

        val outcome = service.syncBeforeLaunch("PSP", BASE, GAME_KEY, "PPSSPP (Standalone)")

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        val cached = File(sd, "Saves/PSP/$BASE.cannoli-ppsspp.zip")
        assertTrue(cached.isFile)
        verify(exactly = 1) {
            bridge.applyMirror(
                StandaloneSaveKind.PPSSPP,
                GAME_KEY,
                match { it.canonicalFile == cached.canonicalFile },
            )
        }
        verify(exactly = 0) { client.downloadSaveContent(502, any(), any()) }
    }

    @Test fun `PPSSPP libretro uses the same directory bundle contract`() = runTest {
        val archive = localArchive()
        val emulator = "Sony - PlayStation Portable (PPSSPP)"
        every { bridge.kindFor("PSP", emulator) } returns null
        every { retroArch.refreshPpsspp("PSP", BASE, GAME_KEY) } returns
            RetroArchMirrorResult.Ready
        val payload = slot<SyncNegotiatePayload>()
        every { client.negotiateSync(capture(payload)) } returns
            SyncNegotiateResponse(sessionId = 1, totalNoOp = 1)

        val outcome = service.syncBeforeLaunch("PSP", BASE, GAME_KEY, emulator)

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        assertEquals("$BASE.ppsspp.zip", payload.captured.saves.single().fileName)
        verify(exactly = 1) { retroArch.refreshPpsspp("PSP", BASE, GAME_KEY) }
    }

    @Test fun `restores OpenVault bundle into PPSSPP libretro before launch`() = runTest {
        val emulator = "Sony - PlayStation Portable (PPSSPP)"
        every { bridge.kindFor("PSP", emulator) } returns null
        every { retroArch.refreshPpsspp("PSP", BASE, GAME_KEY) } returns
            RetroArchMirrorResult.Missing
        every { client.getSaves(42, "dev-1") } returns listOf(
            RommSaveDto(
                id = 601,
                romId = 42,
                slot = DEFAULT_SLOT,
                fileName = "$BASE.ppsspp.zip",
                updatedAt = "2026-07-27T00:00:00Z",
            ),
        )
        every { client.downloadSaveContent(601, "dev-1", any()) } answers {
            writeOpenVaultBundle(thirdArg())
        }
        every { client.confirmSaveDownloaded(601, "dev-1") } returns
            RommSaveDto(id = 601, romId = 42, slot = DEFAULT_SLOT)

        val outcome = service.syncBeforeLaunch("PSP", BASE, GAME_KEY, emulator)

        assertTrue(outcome is PreLaunchOutcome.Proceed)
        verify(exactly = 1) { retroArch.applyPpsspp("PSP", BASE, GAME_KEY) }
    }

    @Test fun `background sweep defers PPSSPP libretro while RetroArch is active`() = runTest {
        val emulator = "Sony - PlayStation Portable (PPSSPP)"
        every { bridge.kindFor("PSP", emulator) } returns null
        every { retroArch.isGameActive() } returns true

        val summary = service.sweep {
            Triple("PSP", BASE, emulator)
        }

        assertEquals(0, summary.uploaded)
        assertEquals(0, summary.downloaded)
        assertEquals(0, summary.conflicts)
        verify(exactly = 0) { retroArch.refreshPpsspp(any(), any(), any()) }
        verify(exactly = 0) { client.negotiateSync(any()) }
    }

    private fun localArchive(): File =
        File(sd, "Saves/PSP/$BASE.cannoli-ppsspp.zip").also(::writeOpenVaultBundle)

    private fun writeOpenVaultBundle(destination: File) {
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("PSP/SAVEDATA/UCUS98662_GameData0/DATA.BIN"))
            zip.write("remote-locoroco".toByteArray())
            zip.closeEntry()
        }
    }

    private companion object {
        const val BASE = "LocoRoco (USA) (PSP) (PSN)"
        const val GAME_KEY = "PSP/$BASE.iso"
    }
}
