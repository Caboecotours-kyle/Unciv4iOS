package com.unciv.logic.files

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.badlogic.gdx.utils.SerializationException
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class AutosaveRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var files: UncivFiles
    private lateinit var game: GameInfo

    @Before
    fun setup() {
        val testGame = TestGame()
        val player = testGame.addCiv(testGame.ruleset.nations.getValue("Rome"), isPlayer = true)
        game = testGame.gameInfo
        game.currentPlayer = player.civID
        files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
    }

    @Test
    fun `primary is preferred even when history timestamp is newer`() {
        save("Autosave", 100)
        save("Autosave-Rome-103", 103)
        assertEquals(100, files.autosaves.loadLatestAutosave { fail("Primary must not report recovery") }.turns)
    }

    @Test
    fun `recovery skips broken histories and keeps all original bytes`() {
        val primary = files.getSave("Autosave").apply { writeString("", false) }
        save("Autosave-Rome-90", 90)
        save("Autosave-Rome-100", 100)
        files.getSave("Autosave-Rome-102").apply {
            writeString("{broken", false)
            assertTrue(file().setLastModified(102_000L))
        }
        files.getSave("Autosave-Rome-103").apply {
            writeString("", false)
            assertTrue(file().setLastModified(103_000L))
        }
        val before = files.getSaves().associate { it.name() to it.readBytes().toList() }
        val reportedTurns = arrayListOf<Int>()
        assertEquals(100, files.autosaves.loadLatestAutosave { reportedTurns += it.turns }.turns)
        assertEquals(listOf(100), reportedTurns)
        assertEquals(before, files.getSaves().associate { it.name() to it.readBytes().toList() })
        assertEquals(0L, primary.length())
    }

    @Test
    fun `history alone permits resume and can be loaded`() {
        save("Autosave-Rome-100", 100)
        assertTrue(files.autosaves.autosaveExists())
        assertEquals(100, files.autosaves.loadLatestAutosave().turns)
        assertFalse(files.getSave("Autosave").exists())
    }

    @Test
    fun `empty history retains primary load error instead of selection error`() {
        files.getSave("Autosave").writeString("", false)
        val error = assertThrows(SerializationException::class.java) { files.autosaves.loadLatestAutosave() }
        assertEquals("The file for the game Autosave is empty", error.message)
    }

    @Test
    fun `no autosave or history does not offer resume`() {
        assertFalse(files.autosaves.autosaveExists())
    }

    @Test
    fun `all broken histories retain primary error and report no recovery`() {
        files.getSave("Autosave").writeString("", false)
        files.getSave("Autosave-Rome-100").writeString("", false)
        files.getSave("Autosave-Rome-102").writeString("", false)
        val error = assertThrows(SerializationException::class.java) {
            files.autosaves.loadLatestAutosave { fail("No history is usable") }
        }
        assertEquals("The file for the game Autosave is empty", error.message)
        assertEquals(2, error.suppressed.size)
    }

    private fun save(name: String, turn: Int): FileHandle {
        game.turns = turn
        return files.saveGame(game, name).also { assertTrue(it.file().setLastModified(turn * 1_000L)) }
    }
}
