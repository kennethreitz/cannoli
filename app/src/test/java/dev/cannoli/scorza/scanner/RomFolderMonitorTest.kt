package dev.cannoli.scorza.scanner

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.ui.components.OsdController
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RomFolderMonitorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var romDir: File
    private val osd = mockk<OsdController>(relaxed = true)
    private val actions = mockk<LauncherActions>(relaxed = true)
    private val onStorageChange = slot<() -> Unit>()
    private lateinit var monitor: RomFolderMonitor

    @Before fun setUp() {
        romDir = tmp.newFolder("usb", "Roms")
        val paths = mockk<CannoliPathsProvider>()
        every { paths.romDir } answers { romDir }
        val setup = mockk<SetupCoordinator>()
        every { setup.watchStorage(capture(onStorageChange)) } returns SetupCoordinator.StorageWatch { }
        monitor = RomFolderMonitor(ApplicationProvider.getApplicationContext(), setup, paths, osd, actions)
    }

    private fun storageEvent() {
        onStorageChange.captured.invoke()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun pulling_the_drive_says_so_and_does_not_rescan() {
        monitor.start()
        tmp.root.resolve("usb").deleteRecursively()
        storageEvent()

        verify(exactly = 1) { osd.show(any(), any(), any()) }
        verify(exactly = 0) { actions.refreshLauncherLists() }
    }

    @Test fun the_drive_coming_back_rescans() {
        monitor.start()
        tmp.root.resolve("usb").deleteRecursively()
        storageEvent()
        romDir.mkdirs()
        storageEvent()

        verify(exactly = 1) { actions.refreshLauncherLists() }
    }

    @Test fun a_drive_pulled_while_away_is_noticed_on_return() {
        monitor.start()
        monitor.stop()
        tmp.root.resolve("usb").deleteRecursively()
        monitor.start()

        verify(exactly = 1) { osd.show(any(), any(), any()) }
    }

    @Test fun storage_events_that_change_nothing_stay_quiet() {
        monitor.start()
        storageEvent()

        verify(exactly = 0) { osd.show(any(), any(), any()) }
        verify(exactly = 0) { actions.refreshLauncherLists() }
    }
}
