package dev.cannoli.scorza.boot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class BootSequencerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePerms(var storage: Boolean = true, var overlay: Boolean = true) : PermissionStatus {
        override fun hasStorage() = storage
        override fun hasOverlay() = overlay
    }

    private fun sequencer(
        perms: PermissionStatus,
        setupResolved: Boolean,
        runs: AtomicInteger,
        scope: TestScope,
    ): BootSequencer {
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        return BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { setupResolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = scope,
        )
    }

    @Test fun advance_runs_initialization_once_then_ready() = runTest {
        val runs = AtomicInteger(0)
        val s = sequencer(FakePerms(), setupResolved = true, runs, scope = this)
        s.advance()
        advanceUntilIdle()
        assertEquals(1, runs.get())
        assertEquals(BootState.Ready, s.state.value)
    }

    @Test fun repeated_advance_does_not_re_run_initialization() = runTest {
        val runs = AtomicInteger(0)
        val s = sequencer(FakePerms(), setupResolved = true, runs, scope = this)
        s.advance(); s.advance(); s.advance()
        advanceUntilIdle()
        assertEquals(1, runs.get())
    }

    @Test fun missing_storage_stays_in_needs_permission() = runTest {
        val s = sequencer(FakePerms(storage = false), setupResolved = true, AtomicInteger(0), scope = this)
        s.advance()
        assertTrue(s.state.value is BootState.NeedsPermission)
    }

    private class FakeMountWatcher : MountWatcher {
        var onChange: (() -> Unit)? = null
        var started = false
        var stopped = false
        override fun start(onChange: () -> Unit) { this.onChange = onChange; started = true }
        override fun stop() { stopped = true; onChange = null }
    }

    @Test fun unresolved_holds_on_splash_then_mount_event_initializes() = runTest {
        val runs = AtomicInteger(0)
        var resolved = false
        val watcher = FakeMountWatcher()
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        val s = BootSequencer(
            permissionStatus = FakePerms(),
            isSetupResolved = { resolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = this,
            now = { 0L },
            mountWatcher = watcher,
        )
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        assertTrue(watcher.started)
        resolved = true
        watcher.onChange?.invoke()
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
        assertTrue(watcher.stopped)
    }

    @Test fun unresolved_falls_through_to_needs_setup_after_timeout() = runTest {
        var nowMs = 0L
        val s = BootSequencer(
            permissionStatus = FakePerms(),
            isSetupResolved = { false },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = BootSequencer.InitRunner { BootResult.Success },
            scope = this,
            now = { nowMs },
            mountWatcher = FakeMountWatcher(),
        )
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
    }

    @Test fun granting_permission_from_wizard_shows_setup_without_black_splash() = runTest {
        val perms = FakePerms(storage = false)
        var scheduledCount = 0
        val watcher = FakeMountWatcher()
        val s = BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { false },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> },
            startStorageDependent = { },
            initRunner = BootSequencer.InitRunner { BootResult.Success },
            scope = this,
            now = { 0L },
            mountWatcher = watcher,
            scheduleTimeout = { _, _ -> scheduledCount++ },
        )
        s.advance()
        assertTrue(s.state.value is BootState.NeedsPermission)
        perms.storage = true
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
        assertEquals(0, scheduledCount)
        assertTrue(watcher.started)
    }

    @Test fun setup_unresolved_goes_to_needs_setup_then_folder_choice_initializes() = runTest {
        val runs = AtomicInteger(0)
        var resolved = false
        var nowMs = 0L
        val perms = FakePerms()
        val initRunner = BootSequencer.InitRunner { onPhase ->
            runs.incrementAndGet()
            onPhase(BootPhase.IMPORT, 1f, "done")
            BootResult.Success
        }
        val s = BootSequencer(
            permissionStatus = perms,
            isSetupResolved = { resolved },
            detectVolumes = { listOf("Internal Storage" to "/storage/emulated/0/") },
            onSetupResolved = { _ -> resolved = true },
            startStorageDependent = { },
            initRunner = initRunner,
            scope = this,
            now = { nowMs },
            mountWatcher = FakeMountWatcher(),
        )
        s.advance()
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertTrue(s.state.value is BootState.NeedsSetup)
        s.onFolderChosen("/storage/emulated/0/Cannoli/")
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
    }

    private val usbRoms = BootState.FolderMissing(FolderKind.ROMS, "/storage/USB/Roms")

    private fun folderSequencer(
        scope: TestScope,
        missing: () -> BootState.FolderMissing?,
        nowMs: () -> Long,
        watcher: MountWatcher,
        runs: AtomicInteger,
        storageDependent: AtomicInteger = AtomicInteger(0),
    ) = BootSequencer(
        permissionStatus = FakePerms(),
        isSetupResolved = { true },
        detectVolumes = { emptyList() },
        onSetupResolved = { _ -> },
        startStorageDependent = { storageDependent.incrementAndGet() },
        initRunner = BootSequencer.InitRunner { runs.incrementAndGet(); BootResult.Success },
        scope = scope,
        now = nowMs,
        mountWatcher = watcher,
        missingFolder = missing,
    )

    @Test fun missing_rom_folder_waits_on_the_splash_before_warning() = runTest {
        var nowMs = 0L
        val runs = AtomicInteger(0)
        val storageDependent = AtomicInteger(0)
        val s = folderSequencer(this, { usbRoms }, { nowMs }, FakeMountWatcher(), runs, storageDependent)
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertEquals(usbRoms, s.state.value)
        assertEquals(1, storageDependent.get())
        assertEquals(0, runs.get())
    }

    @Test fun missing_cannoli_folder_warns_without_touching_storage() = runTest {
        var nowMs = 0L
        val runs = AtomicInteger(0)
        val storageDependent = AtomicInteger(0)
        val cannoli = BootState.FolderMissing(FolderKind.CANNOLI, "/storage/USB/Cannoli/")
        val s = folderSequencer(this, { cannoli }, { nowMs }, FakeMountWatcher(), runs, storageDependent)
        s.advance()
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertEquals(cannoli, s.state.value)
        assertEquals(0, storageDependent.get())
        assertEquals(0, runs.get())
    }

    @Test fun a_folder_that_mounts_during_the_wait_never_shows_the_warning() = runTest {
        var missing: BootState.FolderMissing? = usbRoms
        val watcher = FakeMountWatcher()
        val runs = AtomicInteger(0)
        val s = folderSequencer(this, { missing }, { 0L }, watcher, runs)
        s.advance()
        assertEquals(BootState.Resolving, s.state.value)
        missing = null
        watcher.onChange?.invoke()
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
    }

    @Test fun a_late_mount_clears_the_warning() = runTest {
        var missing: BootState.FolderMissing? = usbRoms
        var nowMs = 0L
        val watcher = FakeMountWatcher()
        val runs = AtomicInteger(0)
        val s = folderSequencer(this, { missing }, { nowMs }, watcher, runs)
        s.advance()
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        assertTrue(s.state.value is BootState.FolderMissing)
        missing = null
        watcher.onChange?.invoke()
        advanceUntilIdle()
        assertEquals(BootState.Ready, s.state.value)
        assertEquals(1, runs.get())
        assertTrue(watcher.stopped)
    }

    @Test fun nothing_but_the_folder_returning_gets_past_the_warning() = runTest {
        var nowMs = 0L
        val watcher = FakeMountWatcher()
        val runs = AtomicInteger(0)
        val s = folderSequencer(this, { usbRoms }, { nowMs }, watcher, runs)
        s.advance()
        nowMs = BootSequencer.MOUNT_WAIT_TIMEOUT_MS + 1
        s.advance()
        watcher.onChange?.invoke()
        s.advance()
        advanceUntilIdle()
        assertTrue(s.state.value is BootState.FolderMissing)
        assertEquals(0, runs.get())
    }
}
