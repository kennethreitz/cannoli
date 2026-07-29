package dev.cannoli.scorza.romm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StandaloneSaveKindTest {
    @Test fun `matches supported standalone emulator identities`() {
        assertEquals(
            StandaloneSaveKind.CITRA_MMJ,
            StandaloneSaveKind.forGame("3DS", "Citra MMJ"),
        )
        assertEquals(
            StandaloneSaveKind.CEMU,
            StandaloneSaveKind.forGame("wiiu", "cemu"),
        )
        assertEquals(
            StandaloneSaveKind.VITA3K,
            StandaloneSaveKind.forGame("psvita", "Vita3K"),
        )
        assertEquals(
            StandaloneSaveKind.DOLPHIN,
            StandaloneSaveKind.forGame("gc", "Dolphin"),
        )
        assertEquals(
            StandaloneSaveKind.DOLPHIN,
            StandaloneSaveKind.forGame("WII", "dolphin"),
        )
        assertEquals(
            StandaloneSaveKind.PPSSPP,
            StandaloneSaveKind.forGame("PSP", "PPSSPP (Standalone)"),
        )
        assertEquals(
            StandaloneSaveKind.PPSSPP,
            StandaloneSaveKind.forGame("psp", "PPSSPP Gold"),
        )
        assertEquals(
            setOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold"),
            StandaloneSaveKind.PPSSPP.packageNames,
        )
    }

    @Test fun `does not claim other Citra builds`() {
        assertNull(StandaloneSaveKind.forGame("3DS", "Citra"))
        assertNull(StandaloneSaveKind.forGame("3DS", "Azahar"))
        assertNull(StandaloneSaveKind.forGame("GC", "Dolphin MMJR"))
        assertNull(StandaloneSaveKind.forGame("PSP", "PPSSPP"))
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
        assertEquals(
            "primary:Vita3K/vita/ux0",
            safeStandaloneDocumentId(
                StandaloneSaveKind.VITA3K.documentAuthority,
                "primary:Vita3K/vita",
                "primary:Vita3K/vita/ux0",
            ),
        )
    }
}
