package dev.cannoli.scorza.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformConfigAppLabelTest {

    @Test fun `Citra MMJ stays distinguishable when both apps call themselves Citra`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        // Upstream keeps Azahar as the maintained 3DS app. Citra MMJ still ships the same
        // "Citra" application label, so the curated name has to win in the picker.
        installPackage(pm, "org.azahar_emu.azahar", "Citra")
        installPackage(pm, "org.citra.emu", "Citra")
        val config = PlatformConfig(File(context.cacheDir, "citra-label-root"), context.assets)

        val options = config.emulatorOptionsForSource("3DS", EmulatorSource.Standalone, includeAll = false, pm = pm)
        val azahar = options.first { it.appPackage == "org.azahar_emu.azahar" }
        val mmj = options.first { it.appPackage == "org.citra.emu" }

        assertEquals("Azahar", azahar.displayName)
        assertEquals("Citra MMJ", mmj.displayName)
        assertNotEquals(azahar.displayName, mmj.displayName)
    }

    private fun installPackage(pm: android.content.pm.PackageManager, packageName: String, label: String) {
        shadowOf(pm).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = label
            }
        })
    }
}
