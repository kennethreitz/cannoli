package dev.cannoli.scorza.input

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.db.RomsRepository
import dev.cannoli.scorza.model.ListItem
import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.romm.sync.SaveSyncService
import dev.cannoli.scorza.romm.sync.SaveSyncStatusHolder
import dev.cannoli.scorza.romm.sync.SyncDirection
import dev.cannoli.scorza.romm.sync.SyncScheduler
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.SyncHistoryRow
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncHistoryActionsTest {

    private val rom = Rom(
        id = 1L,
        path = File("/roms/GBA/Castlevania - Aria of Sorrow.gba"),
        platformTag = "GBA",
        displayName = "Castlevania - Aria of Sorrow",
    )

    @Test
    fun `confirm on old save state history does not launch when state sync is disabled`() {
        val nav = NavigationController()
        val settings = mockk<SettingsRepository>(relaxed = true)
        val roms = mockk<RomsRepository>(relaxed = true)
        val launcherActions = mockk<LauncherActions>(relaxed = true)
        every { settings.romDirectory } returns "/roms"
        every { roms.gameByPath(rom.path.absolutePath) } returns rom
        every { launcherActions.launchSelected(ListItem.RomItem(rom), true) } returns null
        nav.dialogState.value = DialogState.SyncHistory(listOf(saveStateRow()))

        handler(
            nav = nav,
            settings = settings,
            roms = roms,
            launcherActions = launcherActions,
        ).onConfirm()

        verify(exactly = 0) { launcherActions.launchSelected(any(), any()) }
        verify(exactly = 0) { launcherActions.recordRecentlyPlayedByPath(any()) }
    }

    @Test
    fun `confirm on successful ordinary save history launches the game normally`() {
        val nav = NavigationController()
        val settings = mockk<SettingsRepository>(relaxed = true)
        val roms = mockk<RomsRepository>(relaxed = true)
        val launcherActions = mockk<LauncherActions>(relaxed = true)
        every { settings.romDirectory } returns "/roms"
        every { roms.gameByPath(rom.path.absolutePath) } returns rom
        every { launcherActions.launchSelected(ListItem.RomItem(rom), false) } returns null
        nav.dialogState.value = DialogState.SyncHistory(
            listOf(saveStateRow().copy(name = "Castlevania - Aria of Sorrow", isSaveState = false)),
        )

        handler(
            nav = nav,
            settings = settings,
            roms = roms,
            launcherActions = launcherActions,
        ).onConfirm()

        verify(exactly = 1) { launcherActions.launchSelected(ListItem.RomItem(rom), false) }
        verify(exactly = 1) {
            launcherActions.recordRecentlyPlayedByPath(rom.path.absolutePath)
        }
    }

    @Test
    fun `north starts an immediate sync and marks history busy`() {
        val nav = NavigationController()
        val saveSync = mockk<SaveSyncService>(relaxed = true)
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        every { saveSync.syncEnabled() } returns true
        every { saveSync.deviceIdOrNull() } returns "thor"
        nav.dialogState.value = DialogState.SyncHistory(listOf(saveStateRow()))

        handler(nav = nav, saveSync = saveSync, scheduler = scheduler).onNorth()

        assertTrue((nav.dialogState.value as DialogState.SyncHistory).syncing)
        verify(exactly = 1) { scheduler.syncNow(any()) }
    }

    private fun saveStateRow() = SyncHistoryRow(
        gameKey = "GBA/Castlevania - Aria of Sorrow.gba",
        name = "Castlevania - Aria of Sorrow (Save states)",
        direction = SyncDirection.DOWNLOAD,
        relativeTime = "just now",
        isSaveState = true,
    )

    private fun handler(
        nav: NavigationController,
        settings: SettingsRepository = mockk(relaxed = true),
        roms: RomsRepository = mockk(relaxed = true),
        launcherActions: LauncherActions = mockk(relaxed = true),
        saveSync: SaveSyncService = mockk(relaxed = true),
        scheduler: SyncScheduler = mockk(relaxed = true),
    ) = DialogInputHandler(
        nav = nav,
        ioScope = mockk<CoroutineScope>(relaxed = true),
        context = ApplicationProvider.getApplicationContext(),
        settings = settings,
        collectionManager = mockk(relaxed = true),
        recentlyPlayedManager = mockk(relaxed = true),
        platformResolver = mockk(relaxed = true),
        installedCoreService = mockk(relaxed = true),
        launchManager = mockk(relaxed = true),
        updateManager = mockk(relaxed = true),
        atomicRename = mockk(relaxed = true),
        scanner = mockk(relaxed = true),
        settingsViewModel = mockk(relaxed = true),
        gameListViewModel = mockk(relaxed = true),
        systemListViewModel = mockk(relaxed = true),
        romsRepository = roms,
        appsRepository = mockk(relaxed = true),
        launcherActions = launcherActions,
        activityActions = mockk(relaxed = true),
        controllersViewModel = mockk(relaxed = true),
        emulatorMappingBuilder = mockk(relaxed = true),
        rommStore = mockk(relaxed = true),
        rommDownloader = mockk(relaxed = true),
        rommBrowseViewModel = mockk(relaxed = true),
        rommArtFetcher = mockk(relaxed = true),
        raPreloadController = mockk(relaxed = true),
        deviceRegistrar = mockk(relaxed = true),
        saveSyncService = saveSync,
        slotManager = mockk(relaxed = true),
        saveSlotsHandler = mockk(relaxed = true),
        syncHistoryStore = mockk(relaxed = true),
        pendingConflictStore = mockk(relaxed = true),
        saveSyncStatusHolder = SaveSyncStatusHolder(),
        syncScheduler = scheduler,
        standaloneSaveBridge = mockk(relaxed = true),
        osdController = mockk(relaxed = true),
        rommDevicePairing = mockk(relaxed = true),
        rommRomUploader = mockk(relaxed = true),
    )
}
