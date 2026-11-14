package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.boot.FolderKind
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.ui.ButtonStyle
import dev.cannoli.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.ConfirmOverlay
import dev.cannoli.ui.components.OverlayScrim
import dev.cannoli.ui.theme.GrayText

@Composable
fun FolderMissingScreen(
    screen: LauncherScreen.FolderMissing,
    buttonStyle: ButtonStyle = ButtonStyle(),
) {
    if (screen.pendingRomFolder != null) {
        ConfirmOverlay(
            message = stringResource(R.string.library_switch_confirm),
            buttonStyle = buttonStyle,
            confirmLabel = stringResource(R.string.label_proceed),
            title = stringResource(R.string.library_switch_confirm_title),
        )
        return
    }
    val cannoli = screen.kind == FolderKind.CANNOLI
    OverlayScrim(
        bottomBar = {
            BottomBar(
                leftItems = if (screen.canQuit) {
                    listOf(buttonStyle.back to stringResource(R.string.label_quit))
                } else {
                    emptyList()
                },
                rightItems = listOf(buttonStyle.west to stringResource(R.string.label_change_folder)),
            )
        }
    ) {
        Column(modifier = Modifier.widthIn(max = 720.dp)) {
            Text(
                text = stringResource(if (cannoli) R.string.cannoli_folder_missing_title else R.string.rom_folder_missing_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, lineHeight = 48.sp),
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.folder_missing_body),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = screen.path,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = GrayText,
            )
        }
    }
}
