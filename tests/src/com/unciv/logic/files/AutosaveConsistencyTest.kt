package com.unciv.logic.files

import com.badlogic.gdx.Files
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.files.cloud.CloudSaveSync
import com.unciv.models.metadata.GameSettings
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.RedirectOutput
import com.unciv.testing.RedirectPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy

@RunWith(BaseTestRunner::class)
class AutosaveConsistencyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun initializeGame() {
        UncivGame.Current = UncivGame()
        UncivGame.Current.settings = GameSettings()
    }

    @Test
    @RedirectOutput(RedirectPolicy.Show)
    fun `history keeps originating turn while another save competes for primary`() {
        lateinit var files: UncivFiles
        val competingStarted = CountDownLatch(1)
        val competingDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val competing = Thread {
            try {
                competingStarted.countDown()
                files.saveGame(GameInfo().apply { turns = 103 }, "Autosave")
            } catch (ex: Throwable) { failure.set(ex) }
            finally { competingDone.countDown() }
        }
        val instrumented = object : Files by Gdx.files {
            override fun absolute(path: String): FileHandle = CopyObservedHandle(File(path)) {
                competing.start()
                assertTrue(competingStarted.await(5, TimeUnit.SECONDS))
                assertFalse(competingDone.await(100, TimeUnit.MILLISECONDS))
            }
        }
        files = UncivFiles(instrumented, temporaryFolder.root.absolutePath)
        try {
            assertTrue(files.autosaves.autoSave(GameInfo().apply { turns = 100 }, nextTurn = true))
        } finally { if (competing.state != Thread.State.NEW) competing.join(5_000) }
        assertFalse(competing.isAlive)
        failure.get()?.let { throw it }
        val history = files.getSaves().single { it.name().startsWith("Autosave-") }
        assertEquals(100, FileConversions.readJson(history, GameInfo::class.java)!!.turns)
        assertEquals(103, FileConversions.readJson(files.getSave("Autosave"), GameInfo::class.java)!!.turns)
        val metrics = files.saveMetrics.snapshot()
        assertEquals(2L, metrics[SavePhase.LocalSave]!!.count)
        assertEquals(1L, metrics[SavePhase.HistoryCopy]!!.count)
        assertEquals(history.length(), metrics[SavePhase.HistoryCopy]!!.bytesRead)
        assertEquals(history.length(), metrics[SavePhase.HistoryCopy]!!.bytesWritten)
        println("SAVE_HISTORY originating_turn=100 primary_turn=103 history_bytes=${history.length()} metrics=$metrics")
    }

    @Test
    fun `non turn save keeps autosave frequency but does not create history copy`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        for (turn in 1..3) assertTrue(files.autosaves.autoSave(GameInfo().apply { turns = turn }))
        assertEquals(listOf("Autosave"), files.getSaves().map { it.name() }.toList())
        assertEquals(3L, files.saveMetrics.snapshot()[SavePhase.LocalSave]!!.count)
        assertNull(files.saveMetrics.snapshot()[SavePhase.HistoryCopy])
    }

    @Test
    @RedirectOutput(RedirectPolicy.Show)
    fun `queued saves preserve submission order and tail waits for all snapshots`() {
        val files = spy(UncivFiles(Gdx.files, temporaryFolder.root.absolutePath))
        val autosaves = Autosaves(files)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val tailDone = CountDownLatch(1)
        val settingsReads = AtomicInteger()
        val savedTurns = arrayListOf<Int>()
        doAnswer {
            if (settingsReads.incrementAndGet() == 1) {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
            }
            GameSettings()
        }.`when`(files).getGeneralSettings()
        files.cloudSaveSync = object : CloudSaveSync by CloudSaveSync.None {
            override fun onLocalSave(file: FileHandle, game: GameInfo) { savedTurns += game.turns }
        }
        val first = autosaves.requestAutoSave(GameInfo().apply { turns = 100 })
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val middleGame = GameInfo().apply { turns = 102 }
        val middle = autosaves.requestAutoSave(middleGame)
        middleGame.turns = 999
        val last = autosaves.requestAutoSave(GameInfo().apply { turns = 103 })
        last.invokeOnCompletion { tailDone.countDown() }
        try {
            assertSame(last, autosaves.autoSaveJob)
            assertFalse("Tail completed before the first save", tailDone.await(250, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
            runBlocking { withTimeout(5_000) { first.join(); middle.join(); last.join() } }
        }
        assertEquals(listOf(100, 102, 103), savedTurns)
        assertEquals(103, FileConversions.readJson(files.getSave("Autosave"), GameInfo::class.java)!!.turns)
        assertEquals(3L, files.saveMetrics.snapshot()[SavePhase.LocalSave]!!.count)
        println("AUTOSAVE_ORDER saved_turns=$savedTurns final_turn=103 snapshots_immutable=true tail_waited=true")
    }

    @Test
    fun `failed autosave does not prevent later queued save`() {
        val failFirst = AtomicBoolean(true)
        val instrumented = object : Files by Gdx.files {
            override fun absolute(path: String): FileHandle = FailingSaveHandle(File(path), failFirst)
        }
        val files = UncivFiles(instrumented, temporaryFolder.root.absolutePath)
        val first = files.autosaves.requestAutoSave(GameInfo().apply { turns = 100 })
        val last = files.autosaves.requestAutoSave(GameInfo().apply { turns = 103 })
        runBlocking { withTimeout(5_000) { last.join() } }
        assertTrue(first.isCompleted)
        assertFalse(failFirst.get())
        assertEquals(103, FileConversions.readJson(files.getSave("Autosave"), GameInfo::class.java)!!.turns)
        assertEquals(1L, files.saveMetrics.snapshot()[SavePhase.LocalSave]!!.count)
    }

    private class FailingSaveHandle(private val backingFile: File, private val failFirst: AtomicBoolean) : FileHandle(backingFile) {
        override fun child(name: String) = FailingSaveHandle(File(backingFile, name), failFirst)
        override fun sibling(name: String) = FailingSaveHandle(File(backingFile.parentFile, name), failFirst)
        override fun write(append: Boolean): java.io.OutputStream {
            if (name() == ".Autosave.unciv-tmp" && failFirst.compareAndSet(true, false))
                throw OutOfMemoryError("Injected save failure")
            return super.write(append)
        }
    }

    private class CopyObservedHandle(private val backingFile: File, private val beforeCopy: () -> Unit) : FileHandle(backingFile) {
        override fun child(name: String) = CopyObservedHandle(File(backingFile, name), beforeCopy)
        override fun copyTo(destination: FileHandle) {
            if (name() == "Autosave") beforeCopy()
            super.copyTo(destination)
        }
    }
}
