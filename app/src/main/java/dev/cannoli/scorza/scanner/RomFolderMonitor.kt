package dev.cannoli.scorza.scanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.di.CannoliPathsProvider
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.setup.SetupCoordinator
import dev.cannoli.scorza.util.ScanLog
import dev.cannoli.scorza.util.StorageReach
import dev.cannoli.ui.components.OsdController
import javax.inject.Inject

@ActivityScoped
class RomFolderMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupCoordinator: SetupCoordinator,
    private val pathsProvider: CannoliPathsProvider,
    private val osdController: OsdController,
    private val launcherActions: LauncherActions,
) {
    private val main = Handler(Looper.getMainLooper())
    private var watch: SetupCoordinator.StorageWatch? = null
    private var available: Boolean? = null

    fun start() {
        check()
        if (watch == null) watch = setupCoordinator.watchStorage { main.post { check() } }
    }

    fun stop() {
        watch?.stop()
        watch = null
    }

    private fun check() {
        val now = StorageReach.isReachable(pathsProvider.romDir)
        val was = available
        available = now
        if (was == null || was == now) return
        ScanLog.write("rom folder ${if (now) "available" else "unavailable"}: ${pathsProvider.romDir.absolutePath}")
        if (now) {
            launcherActions.refreshLauncherLists()
        } else {
            osdController.show(context.getString(dev.cannoli.ui.R.string.osd_rom_folder_unavailable))
        }
    }
}
