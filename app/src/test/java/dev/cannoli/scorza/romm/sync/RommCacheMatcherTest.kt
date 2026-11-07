package dev.cannoli.scorza.romm.sync

import dev.cannoli.scorza.romm.RommGame
import dev.cannoli.scorza.romm.RommPlatform
import dev.cannoli.scorza.romm.cache.GameRecord
import dev.cannoli.scorza.romm.cache.RommDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RommCacheMatcherTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var database: RommDatabase
    private lateinit var matcher: RommCacheMatcher

    @Before fun setUp() {
        database = RommDatabase { File(tmp.newFolder("Config"), "romm.db") }
        database.replacePlatforms(
            listOf(
                RommPlatform(
                    id = 37,
                    slug = "psvita",
                    cannoliTag = "PSVITA",
                    displayName = "PlayStation Vita",
                    romCount = 2,
                ) to null,
            ),
        )
        database.upsertGames(
            listOf(
                GameRecord(game(23127, "Fez", "FEZ [PCSE00404] [USA] [NoNpDRM].zip"), null),
                GameRecord(game(23128, "Other", "Other [PCSB00001].zip"), null),
            ),
        )
        matcher = RommCacheMatcher(database)
    }

    @After fun tearDown() = database.close()

    @Test fun `vita launcher resolves romm game by title id`() {
        assertEquals(23127, matcher.rommIdForTitleId("PSVITA", "pcse00404"))
    }

    @Test fun `vita title id matching requires an exact token`() {
        assertNull(matcher.rommIdForTitleId("PSVITA", "PCSE0040"))
        assertNull(matcher.rommIdForTitleId("PSVITA", "PCSE004040"))
    }

    private fun game(id: Int, name: String, fsName: String) = RommGame(
        id = id,
        platformId = 37,
        name = name,
        fsName = fsName,
        sizeBytes = 0,
        summary = null,
        revision = null,
        regions = emptyList(),
        languages = emptyList(),
        coverPath = null,
        files = emptyList(),
    )
}
