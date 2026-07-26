package dev.cannoli.scorza.input

import dev.cannoli.scorza.romm.upload.RommRomUploadAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class MenuOptionsTest {
    @Test fun `RomM upload is inserted before rename only when eligible`() {
        val offered = mutableListOf(MENU_MANAGE_COLLECTIONS, MENU_RENAME, MENU_DELETE_GAME)
        offered.addRommUploadOption(RommRomUploadAvailability.AVAILABLE)
        assertEquals(
            listOf(MENU_MANAGE_COLLECTIONS, MENU_UPLOAD_TO_ROMM, MENU_RENAME, MENU_DELETE_GAME),
            offered,
        )

        val hidden = mutableListOf(MENU_MANAGE_COLLECTIONS, MENU_RENAME, MENU_DELETE_GAME)
        hidden.addRommUploadOption(RommRomUploadAvailability.HIDDEN)
        assertEquals(listOf(MENU_MANAGE_COLLECTIONS, MENU_RENAME, MENU_DELETE_GAME), hidden)
    }

    @Test fun `existing RomM game stays visible as a disabled status row`() {
        val options = mutableListOf(MENU_MANAGE_COLLECTIONS, MENU_RENAME, MENU_DELETE_GAME)
        options.addRommUploadOption(RommRomUploadAvailability.ALREADY_PRESENT)
        assertEquals(
            listOf(MENU_MANAGE_COLLECTIONS, MENU_UPLOAD_ALREADY_PRESENT, MENU_RENAME, MENU_DELETE_GAME),
            options,
        )
        assertEquals(true, isDisabledMenuOption(MENU_UPLOAD_ALREADY_PRESENT))
    }
}
