package dev.cannoli.scorza.romm.sync

import android.content.Context
import dev.cannoli.scorza.config.PlatformConfig
import dev.cannoli.scorza.romm.RommHttp
import dev.cannoli.scorza.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncSchedulerTest {
    @Test fun `poll interval uses minutes and clamps unsafe values`() {
        assertTrue(SyncScheduler.pollIntervalMs(3) == 180_000L)
        assertTrue(SyncScheduler.pollIntervalMs(0) == 60_000L)
        assertTrue(SyncScheduler.pollIntervalMs(Int.MAX_VALUE) == 86_400_000L)
    }

    @Test fun `debounce blocks within interval and allows after`() {
        assertFalse(SyncScheduler.shouldSweep(now = 1_000L, lastSweepAt = 900L, intervalMs = 1_800_000L))
        assertTrue(SyncScheduler.shouldSweep(now = 2_000_000L, lastSweepAt = 100L, intervalMs = 1_800_000L))
    }

    // Screen lock/unlock re-registers the network callbacks, which fire immediately: without a
    // cooldown every unlock forces a full sweep, several times over.
    @Test fun `forced sweeps are held off until the cooldown passes`() {
        assertFalse(SyncScheduler.pastForceCooldown(now = 130_000L, lastSweepAt = 100_000L, cooldownMs = 60_000L))
        assertTrue(SyncScheduler.pastForceCooldown(now = 160_000L, lastSweepAt = 100_000L, cooldownMs = 60_000L))
    }

    @Test fun `the first forced sweep is never held off`() {
        assertTrue(SyncScheduler.pastForceCooldown(now = 1_000L, lastSweepAt = 0L, cooldownMs = 60_000L))
    }

    @Test fun `manual sync joins an active sweep instead of queueing a duplicate`() {
        val service = mockk<SaveSyncService>()
        val sweepStarted = CountDownLatch(1)
        val releaseSweep = CountDownLatch(1)
        val completionCalled = CountDownLatch(1)
        every { service.syncEnabled() } returns true
        every { service.deviceIdOrNull() } returns "thor"
        coEvery { service.sweep(any()) } coAnswers {
            sweepStarted.countDown()
            releaseSweep.await(2, TimeUnit.SECONDS)
            SaveSyncService.SyncSummary(uploaded = 0, downloaded = 0, conflicts = 0)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scheduler = SyncScheduler(
            context = mockk<Context>(relaxed = true),
            service = service,
            statusHolder = SaveSyncStatusHolder(),
            platformResolver = mockk<PlatformConfig>(relaxed = true),
            settings = mockk<SettingsRepository>(relaxed = true),
            romDir = { File("/roms") },
            scope = scope,
            http = mockk<RommHttp>(relaxed = true),
        )

        try {
            scheduler.syncNow()
            assertTrue(sweepStarted.await(2, TimeUnit.SECONDS))

            scheduler.syncNow { completionCalled.countDown() }
            releaseSweep.countDown()

            assertTrue(completionCalled.await(2, TimeUnit.SECONDS))
            coVerify(exactly = 1) { service.sweep(any()) }
        } finally {
            releaseSweep.countDown()
            scope.cancel()
        }
    }
}
