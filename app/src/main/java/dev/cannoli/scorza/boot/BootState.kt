package dev.cannoli.scorza.boot

sealed interface BootState {
    data object Resolving : BootState
    data class NeedsPermission(val storageGranted: Boolean) : BootState
    data class NeedsSetup(val volumes: List<Pair<String, String>>) : BootState
    data class FolderMissing(val kind: FolderKind, val path: String) : BootState
    data class Initializing(val phase: BootPhase, val progress: Float, val label: String) : BootState
    data class Error(val message: String) : BootState
    data object Ready : BootState
}

enum class FolderKind { CANNOLI, ROMS }

enum class BootPhase { IMPORT, INITIAL_SCAN, LIBRARY_REFRESH }

val BootState.takesInput: Boolean
    get() = this is BootState.NeedsSetup || this is BootState.NeedsPermission || this is BootState.FolderMissing
