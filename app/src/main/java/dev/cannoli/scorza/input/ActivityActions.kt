package dev.cannoli.scorza.input

interface ActivityActions {
    fun finishAffinity()
    fun restartApp()
    fun applyLauncherDisplayPreference()
    fun startRaLogin(username: String, password: String)
    fun startRommPairing(host: String)
    fun startRommCodePairing(host: String, pairCode: String)
    fun requestStandaloneSaveAccess(kind: dev.cannoli.scorza.romm.sync.StandaloneSaveKind)
}
