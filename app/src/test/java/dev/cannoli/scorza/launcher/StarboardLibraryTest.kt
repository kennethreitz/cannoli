package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarboardLibraryTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `installed marker and game info produce launchable games`() {
        val ports = temporaryFolder.newFolder("ports")
        val neverball = File(ports, "neverball").apply { mkdirs() }
        File(neverball, ".starboard_installed").writeText("")
        File(neverball, "Neverball.sh").writeText("#!/bin/sh")
        File(neverball, "Neverputt.sh").writeText("#!/bin/sh")
        val metadata = File(neverball, "neverball").apply { mkdirs() }
        File(metadata, "port.json").writeText("""{"name":"neverball.zip"}""")
        File(metadata, "gameinfo.xml").writeText(
            """
            <gameList>
              <game><path>./Neverball.sh</path><name>Neverball</name></game>
              <game><path>./Neverputt.sh</path><name>Neverputt</name></game>
            </gameList>
            """.trimIndent()
        )

        val games = StarboardLibrary.readInstalledGames(ports)

        assertEquals(listOf("Neverball", "Neverputt"), games.map { it.title })
        assertEquals(listOf("Neverball.sh", "Neverputt.sh"), games.map { it.launcher })
        assertEquals(setOf("neverball.zip"), games.map { it.zipName }.toSet())
    }

    @Test fun `uninstalled and unsafe launchers are ignored`() {
        val ports = temporaryFolder.newFolder("ports")
        createPort(ports, "not-installed", marker = false, launcher = "Missing.sh")
        createPort(ports, "unsafe", marker = true, launcher = "../Outside.sh")

        assertEquals(emptyList<StarboardTarget>(), StarboardLibrary.readInstalledGames(ports))
    }

    private fun createPort(ports: File, id: String, marker: Boolean, launcher: String) {
        val port = File(ports, id).apply { mkdirs() }
        if (marker) File(port, ".starboard_installed").writeText("")
        val metadata = File(port, id).apply { mkdirs() }
        File(metadata, "port.json").writeText("""{"name":"$id.zip"}""")
        File(metadata, "gameinfo.xml").writeText(
            """<gameList><game><path>./$launcher</path><name>$id</name></game></gameList>"""
        )
    }
}
