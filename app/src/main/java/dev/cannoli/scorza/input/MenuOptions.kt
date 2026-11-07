package dev.cannoli.scorza.input

internal const val MENU_RENAME = "Rename"
internal const val MENU_DELETE = "Delete"
internal const val MENU_DELETE_GAME = "Delete Game"
internal const val MENU_DELETE_ART = "Delete Art"
internal const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
internal const val MENU_EMULATOR_OVERRIDE = "Emulator Override"
internal const val MENU_REMOVE_FROM_COLLECTION = "Remove From Collection"
internal const val MENU_CHILD_COLLECTIONS = "Child Collections"
internal const val MENU_RA_GAME_ID = "RA Game ID"
internal const val MENU_PRELOAD_ACHIEVEMENTS = "Preload Achievements"
internal const val MENU_ADD_FAVORITE = "Add To Favorites"
internal const val MENU_REMOVE_FAVORITE = "Remove From Favorites"
internal const val MENU_REMOVE = "Remove Shortcut"
internal const val MENU_REMOVE_FROM_RECENTS = "Remove From Recently Played"
internal const val MENU_DOWNLOAD_ART = "Download Missing Art"
internal const val MENU_SAVE_SLOTS = "Save Slots"
internal const val MENU_RESTORE_BACKUP = "Restore from Backup"
internal const val MENU_ROMM_SAVES = "RomM Saves"
internal const val MENU_DOWNLOAD_LATEST_STATE = "Download Latest Save State"
internal const val MENU_GUIDES = "Guides"
internal const val MENU_UPLOAD_TO_ROMM = "Upload to RomM"
internal const val MENU_UPLOAD_ALREADY_PRESENT = "$MENU_UPLOAD_TO_ROMM\tAlready present"

internal fun MutableList<String>.addRommUploadOption(
    availability: dev.cannoli.scorza.romm.upload.RommRomUploadAvailability,
) {
    val option = when (availability) {
        dev.cannoli.scorza.romm.upload.RommRomUploadAvailability.AVAILABLE -> MENU_UPLOAD_TO_ROMM
        dev.cannoli.scorza.romm.upload.RommRomUploadAvailability.ALREADY_PRESENT ->
            MENU_UPLOAD_ALREADY_PRESENT
        dev.cannoli.scorza.romm.upload.RommRomUploadAvailability.HIDDEN -> return
    }
    if (any { it == MENU_UPLOAD_TO_ROMM || it == MENU_UPLOAD_ALREADY_PRESENT }) return
    val renameIndex = indexOf(MENU_RENAME)
    if (renameIndex >= 0) add(renameIndex, option)
    else add(option)
}

internal fun isDisabledMenuOption(option: String): Boolean =
    option == MENU_UPLOAD_ALREADY_PRESENT
