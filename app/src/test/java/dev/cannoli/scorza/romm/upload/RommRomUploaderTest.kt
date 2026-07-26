package dev.cannoli.scorza.romm.upload

import dev.cannoli.scorza.model.Rom
import dev.cannoli.scorza.romm.PlatformDto
import dev.cannoli.scorza.romm.PlatformMap
import dev.cannoli.scorza.romm.RommClient
import dev.cannoli.scorza.romm.RommConnectionStore
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.RomsPageDto
import dev.cannoli.scorza.romm.SimpleRomDto
import dev.cannoli.scorza.romm.cache.RommSyncCoordinator
import dev.cannoli.scorza.romm.sync.RommCacheMatcher
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommRomUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val client = mockk<RommClient>()
    private val connection = mockk<RommConnectionStore>()
    private val matcher = mockk<RommCacheMatcher>()
    private val platformMap = mockk<PlatformMap>()
    private val syncCoordinator = mockk<RommSyncCoordinator>()

    @Test fun `upload splits file into chunks reports progress and refreshes cache`() = runBlocking {
        val file = tmp.newFile("Pokemon Platinum.nds").apply {
            writeBytes(ByteArray(25) { it.toByte() })
        }
        val rom = Rom(1, file, "NDS", "Pokemon Platinum")
        stubConnectedPlatform()
        every { client.getRoms(37, 100, 0, "Pokemon Platinum") } returns RomsPageDto()
        every { client.startRomUpload(37, file.name, 25, 3) } returns "upload-id"
        every { client.uploadRomChunk(any(), any(), any(), any(), any()) } just Runs
        every { client.completeRomUpload("upload-id") } just Runs
        every { matcher.refresh() } just Runs
        coEvery { syncCoordinator.syncDelta() } just Runs

        val progress = mutableListOf<Pair<Long, Long>>()
        val uploader = uploader(chunkSize = 10)
        val result = uploader.upload(rom) { sent, total -> progress += sent to total }

        assertEquals(RommRomUploadOutcome.Uploaded, result)
        assertEquals(listOf(0L to 25L, 10L to 25L, 20L to 25L, 25L to 25L), progress)
        verify { client.uploadRomChunk("upload-id", 0, file, 0, 10) }
        verify { client.uploadRomChunk("upload-id", 1, file, 10, 10) }
        verify { client.uploadRomChunk("upload-id", 2, file, 20, 5) }
        verify { client.completeRomUpload("upload-id") }
        coVerify { syncCoordinator.syncDelta() }
        verify { matcher.refresh() }
        assertFalse(uploader.shouldOffer(rom))
    }

    @Test fun `live exact filename match avoids an upload`() = runBlocking {
        val file = tmp.newFile("Pokemon Platinum.nds").apply { writeText("ROM") }
        val rom = Rom(1, file, "NDS", "Pokemon Platinum")
        stubConnectedPlatform()
        every { client.getRoms(37, 100, 0, "Pokemon Platinum") } returns RomsPageDto(
            items = listOf(
                SimpleRomDto(
                    id = 42000,
                    platformId = 37,
                    fsName = file.name,
                    name = "Pokemon Platinum",
                ),
            ),
        )
        every { matcher.refresh() } just Runs
        coEvery { syncCoordinator.syncDelta() } just Runs

        val uploader = uploader()
        val result = uploader.upload(rom) { _, _ -> }

        assertEquals(RommRomUploadOutcome.AlreadyPresent, result)
        verify(exactly = 0) { client.startRomUpload(any(), any(), any(), any()) }
        assertFalse(uploader.shouldOffer(rom))
    }

    @Test fun `menu eligibility requires a paired supported server and a missing single file`() {
        val file = tmp.newFile("game.nds").apply { writeText("ROM") }
        assertEquals(RommRomUploadAvailability.AVAILABLE, rommUploadAvailability(true, file, false, null, false))
        assertEquals(RommRomUploadAvailability.HIDDEN, rommUploadAvailability(false, file, false, null, false))
        assertEquals(RommRomUploadAvailability.HIDDEN, rommUploadAvailability(true, file, true, null, false))
        assertEquals(RommRomUploadAvailability.ALREADY_PRESENT, rommUploadAvailability(true, file, false, 42, false))
        assertEquals(RommRomUploadAvailability.ALREADY_PRESENT, rommUploadAvailability(true, file, false, null, true))
        assertEquals(
            RommRomUploadAvailability.HIDDEN,
            rommUploadAvailability(true, File(file.parentFile, "missing.nds"), false, null, false),
        )
    }

    private fun stubConnectedPlatform() {
        every { connection.isConfigured } returns true
        every { connection.serverVersion } returns "5.0.0"
        every { matcher.rommIdFor("NDS", "Pokemon Platinum.nds") } returns null
        every { client.getPlatforms() } returns listOf(
            PlatformDto(id = 37, slug = "nds", fsSlug = "nds", name = "Nintendo DS"),
        )
        every { platformMap.toDomain(any()) } returns listOf(
            RommPlatform(37, "nds", "NDS", "Nintendo DS", 100),
        )
    }

    private fun uploader(chunkSize: Long = 10L) = RommRomUploader(
        client = client,
        connection = connection,
        matcher = matcher,
        platformMap = platformMap,
        syncCoordinator = syncCoordinator,
        chunkSizeBytes = chunkSize,
        retryDelay = {},
    )
}
