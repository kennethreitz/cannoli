package dev.cannoli.scorza.input.screen

import dagger.hilt.android.scopes.ActivityScoped
import dev.cannoli.scorza.boot.BootSequencer
import dev.cannoli.scorza.boot.BootState
import dev.cannoli.scorza.boot.FolderKind
import dev.cannoli.scorza.di.IoScope
import dev.cannoli.scorza.input.ActivityActions
import dev.cannoli.scorza.input.LauncherActions
import dev.cannoli.scorza.input.ScreenInputHandler
import dev.cannoli.scorza.navigation.BrowsePurpose
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.navigation.NavigationController
import dev.cannoli.scorza.onboarding.OnboardingCoordinator
import dev.cannoli.scorza.onboarding.OnboardingStep
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.setup.SetupCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@ActivityScoped
class FolderMissingInputHandler @Inject constructor(
    private val nav: NavigationController,
    private val bootSequencer: BootSequencer,
    private val setupCoordinator: SetupCoordinator,
    private val onboarding: OnboardingCoordinator,
    private val settings: SettingsRepository,
    private val launcherActions: dagger.Lazy<LauncherActions>,
    private val activityActions: ActivityActions,
    @IoScope private val ioScope: CoroutineScope,
) : ScreenInputHandler {

    private fun current(): LauncherScreen.FolderMissing? =
        nav.currentScreen as? LauncherScreen.FolderMissing

    override fun onConfirm() {
        current()?.pendingRomFolder?.let { switchRomFolder(it) }
    }

    override fun onWest() {
        val screen = current() ?: return
        when {
            screen.pendingRomFolder != null -> {}
            screen.kind == FolderKind.CANNOLI -> onboarding.show(OnboardingStep.STORAGE)
            else -> nav.push(
                LauncherScreen.DirectoryBrowser(
                    purpose = BrowsePurpose.BOOT_ROM_DIRECTORY,
                    currentPath = STORAGE_ROOT,
                    entries = setupCoordinator.listDirectories(STORAGE_ROOT),
                )
            )
        }
    }

    override fun onBack() {
        val screen = current() ?: return
        when {
            screen.pendingRomFolder != null -> nav.replaceTop(screen.copy(pendingRomFolder = null))
            screen.canQuit -> activityActions.finishAffinity()
        }
    }

    private fun switchRomFolder(path: String) {
        settings.romDirectory = path
        bootSequencer.advance()
        ioScope.launch {
            bootSequencer.state.first { it is BootState.Ready }
            withContext(Dispatchers.Main) {
                launcherActions.get().rescanWithProgress(reconcileOrphans = true)
            }
        }
    }

    private companion object {
        const val STORAGE_ROOT = "/storage/"
    }
}
