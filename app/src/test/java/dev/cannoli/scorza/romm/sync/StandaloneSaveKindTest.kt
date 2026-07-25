package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneSaveKindTest {
    @Test fun `matches Citra MMJ and Cemu identities`() {
        assertEquals(
            StandaloneSaveKind.CITRA_MMJ,
            StandaloneSaveKind.forGame("3DS", "Citra MMJ"),
        )
        assertEquals(
            StandaloneSaveKind.CEMU,
            StandaloneSaveKind.forGame("wiiu", "cemu"),
        )
    }

    @Test fun `does not claim other Citra builds`() {
        assertNull(StandaloneSaveKind.forGame("3DS", "Citra"))
        assertNull(StandaloneSaveKind.forGame("3DS", "Azahar"))
    }

    @Test fun `only Citra receives its double slash document id workaround`() {
        assertEquals(
            "root//citra-emu",
            safeStandaloneDocumentId(
                StandaloneSaveKind.CITRA_MMJ.documentAuthority,
                "root/",
                "root/citra-emu",
            ),
        )
        assertEquals(
            "root/mlc01",
            safeStandaloneDocumentId(
                StandaloneSaveKind.CEMU.documentAuthority,
                "root/",
                "root/mlc01",
            ),
        )
    }
}
