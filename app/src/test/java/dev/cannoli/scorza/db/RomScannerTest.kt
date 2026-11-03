package dev.cannoli.scorza.db

import androidx.test.core.app.ApplicationProvider
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.util.ArcadeTitleLookup
import dev.cannoli.scorza.util.ArtworkLookup
import dev.cannoli.scorza.util.RomDirectoryWalker
import io.mockk.every
import io.mockk.mockk
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
class RomScannerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var romsDir: File
    private lateinit var db: CannoliDatabase
    private lateinit var scanner: RomScanner
    private lateinit var collections: CollectionsRepository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = tmp.newFolder("cannoli")
        File(root, "Config").mkdirs()
        romsDir = tmp.newFolder("usb", "Roms")

        val paths = mockk<CannoliPathsProvider>()
        every { paths.root } returns root
        every { paths.romDir } answers { romsDir }

        db = CannoliDatabase(paths)
        scanner = RomScanner(db, RomDirectoryWalker(paths, ctx.assets, ArcadeTitleLookup(paths)), ArtworkLookup(paths))
        collections = CollectionsRepository(db)
    }

    private fun romId(): Long = db.queryOne("SELECT id FROM roms WHERE platform_tag = 'NES'") { it.getLong(0) }!!

    private fun nesGameInACollection(): Pair<Long, Long> {
        File(romsDir, "NES").mkdirs()
        File(romsDir, "NES/Mega Game.nes").writeText("rom")
        scanner.scanPlatform("NES")
        val rom = romId()
        val rpgs = collections.create("RPGs")
        collections.addMember(rpgs, LibraryRef.Rom(rom))
        return rom to rpgs
    }

    @Test fun `an unplugged rom drive keeps games and their collections`() {
        val (rom, rpgs) = nesGameInACollection()

        tmp.root.resolve("usb").deleteRecursively()
        val counts = scanner.scanPlatform("NES")

        assertEquals(RomScanner.SyncCounts(0, 0, 0), counts)
        assertEquals(rom, romId())
        assertEquals(listOf(rom), collections.romIdsIn(rpgs))
    }

    @Test fun `a deleted platform folder still clears its games`() {
        val (_, rpgs) = nesGameInACollection()

        File(romsDir, "NES").deleteRecursively()
        val counts = scanner.scanPlatform("NES")

        assertEquals(1, counts.removed)
        assertTrue(collections.romIdsIn(rpgs).isEmpty())
    }
}
