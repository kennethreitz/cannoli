package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RommClientRomUploadTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: RommClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val okhttp = OkHttpClient()
        client = RommClient(
            baseUrlProvider = { server.url("/").toString().trimEnd('/') },
            clientProvider = { okhttp },
            uploadClientProvider = { okhttp },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `chunked upload sends RomM 5 headers and exact file slices`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"upload_id":"upload-123"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"received":1,"total":1}"""))
        server.enqueue(MockResponse().setResponseCode(201))

        val file = tmp.newFile("Pokemon Platinum.nds").apply {
            writeText("0123456789ABCDEF")
        }

        val uploadId = client.startRomUpload(
            platformId = 37,
            fileName = file.name,
            totalSize = file.length(),
            totalChunks = 1,
        )
        assertEquals("upload-123", uploadId)

        client.uploadRomChunk(uploadId, chunkIndex = 0, file = file, offset = 3, length = 7)
        client.completeRomUpload(uploadId)

        val start = server.takeRequest()
        assertEquals("/api/roms/upload/start", start.path)
        assertEquals("POST", start.method)
        assertEquals("37", start.getHeader("X-Upload-Platform"))
        assertEquals(file.name, start.getHeader("X-Upload-Filename"))
        assertEquals(file.length().toString(), start.getHeader("X-Upload-Total-Size"))
        assertEquals("1", start.getHeader("X-Upload-Total-Chunks"))

        val chunk = server.takeRequest()
        assertEquals("/api/roms/upload/upload-123", chunk.path)
        assertEquals("PUT", chunk.method)
        assertEquals("0", chunk.getHeader("X-Chunk-Index"))
        assertTrue(chunk.getHeader("Content-Type")!!.startsWith("application/octet-stream"))
        assertEquals("3456789", chunk.body.readUtf8())

        val complete = server.takeRequest()
        assertEquals("/api/roms/upload/upload-123/complete", complete.path)
        assertEquals("POST", complete.method)
    }

    @Test fun `cancel posts to upload session`() {
        server.enqueue(MockResponse().setResponseCode(204))
        client.cancelRomUpload("upload-456")
        val request = server.takeRequest()
        assertEquals("/api/roms/upload/upload-456/cancel", request.path)
        assertEquals("POST", request.method)
    }
}
