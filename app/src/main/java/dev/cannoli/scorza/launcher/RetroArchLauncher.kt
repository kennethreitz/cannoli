package dev.cannoli.scorza.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cannoli.igm.IgmColors
import dev.cannoli.igm.IgmDisplaySettings
import dev.cannoli.igm.IgmInputMapping
import dev.cannoli.igm.RicottaLaunchParams
import dev.cannoli.ricotta.RicottaLaunchTranslator
import dev.cannoli.scorza.i18n.LocaleOverride
import dev.cannoli.scorza.util.ErrorLog
import dev.cannoli.scorza.util.LaunchLog
import java.io.File

data class RicottaIgm(
    val gameTitle: String,
    val stateBasePath: String,
    val cannoliRoot: String,
    val platformTag: String,
    val platformName: String,
    val igmTriggerKeycodes: List<Int>,
    val quitOnFocusLoss: Boolean = true,
    val preferredRefreshRate: Int? = null,
    val colors: IgmColors? = null,
    val displaySettings: IgmDisplaySettings,
    val inputMapping: IgmInputMapping? = null,
    val romBaseName: String = "",
    val hardcoreInEffect: Boolean = false,
    val curatedSettings: Boolean = true,
    val shortcuts: Map<dev.cannoli.igm.ShortcutAction, Set<Int>> = emptyMap(),
)

class RetroArchLauncher(
    private val context: Context,
    private val activityDisplayRouter: ActivityDisplayRouter,
) {
    fun launchRicotta(
        romFile: File,
        coreId: String,
        configPath: String? = null,
        igm: RicottaIgm,
    ): LaunchResult {
        val params = RicottaLaunchParams(
            coreId = coreId,
            romPath = romFile.absolutePath,
            configFilePath = configPath,
            gameTitle = igm.gameTitle,
            stateBasePath = igm.stateBasePath,
            cannoliRoot = igm.cannoliRoot,
            platformTag = igm.platformTag,
            platformName = igm.platformName,
            igmTriggerKeycodes = igm.igmTriggerKeycodes,
            quitOnFocusLoss = igm.quitOnFocusLoss,
            preferredRefreshRate = igm.preferredRefreshRate,
            colors = igm.colors,
            displaySettings = igm.displaySettings,
            inputMapping = igm.inputMapping,
            localeTag = LocaleOverride.currentTag(context),
            romBaseName = igm.romBaseName,
            hardcoreInEffect = igm.hardcoreInEffect,
            curatedSettings = igm.curatedSettings,
            shortcuts = igm.shortcuts,
        )

        val intent = RicottaLaunchTranslator.toRetroIntent(context, params).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Fork: dual-screen routing sends the game to the game display when it is enabled.
        val launchDisplayId = activityDisplayRouter.gameLaunchDisplayId()
        LaunchLog.write(
            "ricotta request core=$coreId rom=${romFile.absolutePath} exists=${romFile.exists()} " +
                "displayPolicy=${launchDisplayId ?: "system-default"} " +
                activityDisplayRouter.diagnosticSummary()
        )
        return context.startActivityNoAnim(
            intent,
            "Failed to launch RicottaArch",
            launchDisplayId = launchDisplayId,
            logLabel = "ricotta",
        )
    }

}

fun Context.isPackageInstalled(packageName: String): Boolean =
    packageManager.isPackageInstalled(packageName)

fun Context.startActivityNoAnim(
    intent: Intent,
    fallbackMsg: String,
    launchDisplayId: Int? = null,
    logLabel: String = "activity",
): LaunchResult {
    val component = intent.component?.flattenToShortString()
        ?: intent.`package`
        ?: intent.action
        ?: "<implicit>"
    LaunchLog.write(
        "$logLabel dispatch component=$component display=${launchDisplayId ?: "system-default"} " +
            "flags=0x${intent.flags.toString(16)} extras=${intent.extras?.keySet()?.sorted() ?: emptyList<String>()}"
    )
    if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
        LaunchLog.write("$logLabel rejected unresolved component=$component")
        return LaunchResult.Error(fallbackMsg)
    }
    return try {
        val dispatchIntent = ExternalGameSessionActivity.wrap(this, intent, launchDisplayId)
        startActivity(dispatchIntent, noAnimationActivityOptions(launchDisplayId))
        LaunchLog.write("$logLabel accepted component=$component")
        LaunchResult.Success
    } catch (e: Exception) {
        LaunchLog.error("$logLabel failed component=$component", e)
        ErrorLog.error("$logLabel activity launch failed component=$component", e)
        LaunchResult.Error(e.message ?: fallbackMsg)
    }
}

fun Context.romFileProviderUri(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

fun PackageManager.isPackageInstalled(packageName: String): Boolean = try {
    getPackageInfo(packageName, 0)
    true
} catch (_: PackageManager.NameNotFoundException) {
    false
}
