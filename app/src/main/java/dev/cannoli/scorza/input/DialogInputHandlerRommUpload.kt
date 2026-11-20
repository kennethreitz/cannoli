package dev.cannoli.scorza.input

import dev.cannoli.scorza.romm.upload.RommRomUploadOutcome
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.model.ListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Fork: RomM ROM upload flow, ported onto v2's split DialogInputHandler.

internal fun DialogInputHandler.confirmRommUpload(ds: DialogState.RommUploadConfirm) {
    val rom = (gameListViewModel.getSelectedItem() as? ListItem.RomItem)?.rom
    if (rom == null || rom.path.name != ds.fileName || rommRomUploader == null) {
        nav.dialogState.value = DialogState.None
        return
    }
    startRommUpload(rom)
}

internal fun DialogInputHandler.cancelRommUpload() {
    romUploadJob?.cancel()
    romUploadJob = null
    restoreContextMenu()
}

internal fun DialogInputHandler.dismissRommUploadResult(result: DialogState.RommUploadResult) {
    if (result.returnToContextMenu) restoreContextMenu()
    else nav.dialogState.value = DialogState.None
}

private fun DialogInputHandler.startRommUpload(rom: dev.cannoli.scorza.model.Rom) {
    val uploader = rommRomUploader ?: return
    romUploadJob?.cancel()
    nav.dialogState.value = DialogState.RommUploadProgress(gameName = rom.displayName, fileName = rom.path.name)
    val job = ioScope.launch(start = CoroutineStart.LAZY) {
        try {
            val outcome = uploader.upload(rom) { uploaded, total ->
                withContext(Dispatchers.Main) {
                    val current = nav.dialogState.value as? DialogState.RommUploadProgress
                    if (current?.fileName == rom.path.name) {
                        nav.dialogState.value = current.copy(
                            progress = if (total > 0L) (uploaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f,
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) {
                pendingContextReturn = null
                val message = when (outcome) {
                    RommRomUploadOutcome.Uploaded ->
                        context.getString(dev.cannoli.ui.R.string.romm_upload_success, rom.path.name)
                    RommRomUploadOutcome.AlreadyPresent ->
                        context.getString(dev.cannoli.ui.R.string.romm_upload_already_present, rom.path.name)
                }
                nav.dialogState.value = DialogState.RommUploadResult(success = true, message = message, returnToContextMenu = false)
            }
        } catch (_: CancellationException) {
            // Back already restored the context menu; the uploader cancels the server session
            // in its NonCancellable cleanup.
        } catch (failure: Throwable) {
            withContext(Dispatchers.Main) {
                nav.dialogState.value = DialogState.RommUploadResult(
                    success = false,
                    message = context.getString(
                        dev.cannoli.ui.R.string.romm_upload_failed,
                        rom.path.name,
                        romUploadErrorMessage(failure),
                    ),
                    returnToContextMenu = true,
                )
            }
        } finally {
            if (romUploadJob === currentCoroutineContext()[Job]) romUploadJob = null
        }
    }
    romUploadJob = job
    job.start()
}

private fun DialogInputHandler.romUploadErrorMessage(failure: Throwable): String {
    val rommFailure = failure as? dev.cannoli.scorza.romm.RommException
    if (rommFailure?.statusCode == 401 || rommFailure?.statusCode == 403) {
        return context.getString(dev.cannoli.ui.R.string.romm_upload_permission_error)
    }
    val raw = failure.message.orEmpty()
    val detail = Regex("""["']detail["']\s*:\s*["']([^"']+)["']""").find(raw)?.groupValues?.getOrNull(1)
    return detail ?: raw.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
}
