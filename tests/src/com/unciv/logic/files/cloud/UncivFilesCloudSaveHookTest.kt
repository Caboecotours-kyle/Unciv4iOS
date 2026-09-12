package com.unciv.logic.files.cloud

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.files.FileConversions
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class UncivFilesCloudSaveHookTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `successful local save invokes injected hook`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val sync = RecordingSync()
        files.cloudSaveSync = sync
        val game = GameInfo().apply { gameId = "game-id" }
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveGame(game, "My save") { saveError = it }

        assertNull(saveError)
        assertEquals("My save", sync.saved.single().name())
    }

    @Test
    fun `cloud hook exception cannot fail completed local save`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        files.cloudSaveSync = RecordingSync(throwOnSave = true)
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveGame(GameInfo(), "My save") { saveError = it }

        assertNull(saveError)
        assertEquals(true, files.getSave("My save").exists())
    }

    @Test
    fun `cloud hook exception cannot fail completed local deletion`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val save = files.getSave("My save")
        save.writeString("save", false)
        val sync = RecordingSync(throwOnDelete = true)
        files.cloudSaveSync = sync

        val deleted = files.deleteSave(save)

        assertEquals(true, deleted)
        assertEquals(false, save.exists())
    }

    @Test
    fun `multiplayer preview saves do not enter personal cloud sync`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val sync = RecordingSync()
        files.cloudSaveSync = sync
        var saveError: Exception? = IllegalStateException("callback not invoked")

        files.saveMultiplayerGamePreview(GameInfoPreview(), "multiplayer") { saveError = it }

        assertNull(saveError)
        assertEquals(0, sync.saved.size)
    }

    @Test
    fun `save snapshot hook holds slot lock through concurrent save and delete`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val copiedTurns = arrayListOf<Int>()
        files.cloudSaveSync = object : CloudSaveSync by RecordingSync() {
            override fun onLocalSave(file: FileHandle, game: GameInfo) {
                if (game.turns == 100) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                copiedTurns += FileConversions.readJson(file, GameInfo::class.java)!!.turns
            }
        }
        val first = Thread { files.saveGame(GameInfo().apply { turns = 100 }, "Autosave") }
        val second = Thread {
            secondStarted.countDown()
            files.saveGame(GameInfo().apply { turns = 103 }, "Autosave")
            files.deleteSave("Autosave")
            secondFinished.countDown()
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            second.start()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        first.join(5_000)
        second.join(5_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(listOf(100, 103), copiedTurns)
        assertFalse(files.getSave("Autosave").exists())
    }

    @Test
    fun `partial local write preserves previous game and does not enqueue cloud work`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val sync = RecordingSync()
        files.cloudSaveSync = sync
        files.saveGame(GameInfo().apply { turns = 100 }, "Campaign")
        val original = files.getSave("Campaign")
        val failing = object : FileHandle(original.file()) {
            override fun sibling(name: String): FileHandle {
                if (!name.endsWith(".unciv-tmp")) return super.sibling(name)
                return object : FileHandle(super.sibling(name).file()) {
                    override fun writer(append: Boolean, charset: String?): Writer =
                        OutputStreamWriter(write(append), charset ?: Charsets.UTF_8.name())
                    override fun write(append: Boolean): OutputStream {
                        val output = super.write(append)
                        return object : OutputStream() {
                            override fun write(value: Int) {
                                output.write(value)
                                throw IOException("storage failed")
                            }
                            override fun close() = output.close()
                        }
                    }
                }
            }
        }
        var failure: Exception? = null
        files.saveGame(GameInfo().apply { turns = 103 }, failing) { failure = it }
        assertTrue(failure != null)
        assertEquals(100, FileConversions.readJson(original, GameInfo::class.java)!!.turns)
        assertEquals(1, sync.saved.size)
        assertEquals(listOf("Campaign"), original.parent().list().map { it.name() })
    }

    @Test
    fun `atomic save preserves plain and compressed formats and committed hook bytes`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val observed = arrayListOf<Int>()
        files.cloudSaveSync = object : CloudSaveSync by RecordingSync() {
            override fun onLocalSave(file: FileHandle, game: GameInfo) {
                observed += FileConversions.readJson(file, GameInfo::class.java)!!.turns
            }
        }
        val previous = UncivFiles.saveZipped
        try {
            for ((turn, zipped) in listOf(100 to false, 103 to true)) {
                UncivFiles.saveZipped = zipped
                files.saveGame(GameInfo().apply { turns = turn }, "Campaign")
                assertEquals(turn, files.loadGamePreviewFromFile(files.getSave("Campaign")).turns)
            }
        } finally { UncivFiles.saveZipped = previous }
        assertEquals(listOf(100, 103), observed)
    }

    private class RecordingSync(
        private val throwOnSave: Boolean = false,
        private val throwOnDelete: Boolean = false,
    ) : CloudSaveSync {
        val saved = arrayListOf<FileHandle>()
        override val isSupported = true
        override val status = CloudSaveStatus(CloudSaveState.Available)
        override fun onLocalSave(file: FileHandle, game: GameInfo) {
            if (throwOnSave) throw IllegalStateException("offline")
            saved += file
        }
        override fun onLocalDelete(file: FileHandle) {
            if (throwOnDelete) throw IllegalStateException("offline")
        }
        override fun requestSync(trigger: CloudSyncTrigger) = Unit
        override fun addStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun removeStatusListener(listener: (CloudSaveStatus) -> Unit) = Unit
        override fun close() = Unit
    }
}
