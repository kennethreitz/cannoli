package dev.cannoli.scorza.util

import android.os.Environment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageReachTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun a_missing_folder_is_unreachable() {
        assertFalse(StorageReach.isReachable(File(tmp.root, "gone")))
    }

    @Test fun a_folder_on_a_mounted_volume_is_reachable() {
        val dir = tmp.newFolder("card")
        ShadowEnvironment.setExternalStorageState(dir, Environment.MEDIA_MOUNTED)
        assertTrue(StorageReach.isReachable(dir))
    }

    @Test fun a_folder_on_a_volume_being_ejected_is_unreachable() {
        val dir = tmp.newFolder("card")
        ShadowEnvironment.setExternalStorageState(dir, Environment.MEDIA_EJECTING)
        assertFalse(StorageReach.isReachable(dir))
    }

    @Test fun a_folder_on_an_unmounted_volume_is_unreachable() {
        val dir = tmp.newFolder("card")
        ShadowEnvironment.setExternalStorageState(dir, Environment.MEDIA_UNMOUNTED)
        assertFalse(StorageReach.isReachable(dir))
    }
}
