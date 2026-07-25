package dev.cannoli.scorza.romm

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RommClientCollectionsTest {
    private fun client(server: MockWebServer): RommClient {
        val ok = OkHttpClient()
        return RommClient(baseUrlProvider = { server.url("/").toString().trimEnd('/') }, clientProvider = { ok })
    }

    @Test
    fun getCollections_parsesUserGroup() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":7,"name":"Favorites","rom_ids":[1,2,3],"rom_count":3,"is_favorite":true}]
        """.trimIndent()))
        server.start()
        val result = client(server).getCollections(RommCollectionGroup.USER)
        assertEquals(1, result.size)
        assertEquals("7", result[0].id)
        assertEquals("Favorites", result[0].name)
        assertEquals(listOf(1, 2, 3), result[0].romIds)
        assertTrue(result[0].isFavorite)
        server.shutdown()
    }

    @Test
    fun favoriteCollection_mutationsUseAtomicEndpoints() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":7,"name":"Favorites","rom_ids":[1],"rom_count":1,"is_favorite":true}]
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"id":7,"name":"Favorites","rom_ids":[1,3,4],"rom_count":3,"is_favorite":true}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"id":7,"name":"Favorites","rom_ids":[3,4],"rom_count":2,"is_favorite":true}
        """.trimIndent()))
        server.start()
        try {
            val client = client(server)
            assertEquals(7, client.getFavoriteCollection()?.id)
            client.addRomsToCollection(7, setOf(4, 3))
            client.removeRomsFromCollection(7, setOf(1))

            assertEquals("GET", server.takeRequest().method)
            val add = server.takeRequest()
            assertEquals("POST", add.method)
            assertEquals("/api/collections/7/roms", add.path)
            assertEquals("""{"rom_ids":[3,4]}""", add.body.readUtf8())
            val remove = server.takeRequest()
            assertEquals("DELETE", remove.method)
            assertEquals("/api/collections/7/roms", remove.path)
            assertEquals("""{"rom_ids":[1]}""", remove.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun createFavoriteCollection_marksSpecialCollection() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            {"id":9,"name":"Favorites","rom_ids":[],"rom_count":0,"is_favorite":true}
        """.trimIndent()))
        server.start()
        try {
            val created = client(server).createFavoriteCollection()
            assertEquals(9, created.id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("true", request.requestUrl?.queryParameter("is_favorite"))
            assertEquals("false", request.requestUrl?.queryParameter("is_public"))
            assertTrue(request.body.readUtf8().contains("Favorites"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun getCollections_virtualSendsTypeAllAndParsesStringId() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":"genre/rpg","name":"RPG","rom_ids":[4,5],"rom_count":2}]
        """.trimIndent()))
        server.start()
        val result = client(server).getCollections(RommCollectionGroup.VIRTUAL)
        val request = server.takeRequest()
        assertEquals("/api/collections/virtual", request.requestUrl?.encodedPath)
        assertEquals("all", request.requestUrl?.queryParameter("type"))
        assertEquals(1, result.size)
        assertEquals("genre/rpg", result[0].id)
        assertEquals(listOf(4, 5), result[0].romIds)
        server.shutdown()
    }

    @Test
    fun getCollections_virtualParsesType() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [{"id":"v1","name":"Zelda","type":"franchise","rom_ids":[1],"rom_count":1}]
        """.trimIndent()))
        server.start()
        val result = client(server).getCollections(RommCollectionGroup.VIRTUAL)
        assertEquals("franchise", result[0].virtualType)
        server.shutdown()
    }
}
