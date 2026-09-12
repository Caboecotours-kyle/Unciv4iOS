package com.unciv.logic.files

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.GameInfo
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(BaseTestRunner::class)
class SaveFileWriterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed serialization leaves previous bytes intact and removes temporary file`() {
        val file = FileHandle(temporaryFolder.newFile("Campaign"))
        file.writeString("previous complete save", false)
        assertThrows(IOException::class.java) {
            SaveFileWriter.write(file) { temporary ->
                temporary.writeString("partial", false)
                throw IOException("disk write failed")
            }
        }
        assertEquals("previous complete save", file.readString())
        assertEquals(listOf("Campaign"), file.parent().list().map { it.name() })
    }

    @Test
    fun `previous version stays visible until complete new version is published`() {
        val file = FileHandle(temporaryFolder.newFile("Campaign"))
        file.writeString("previous", false)
        SaveFileWriter.write(file) { temporary ->
            temporary.writeString("next", false)
            assertEquals("previous", file.readString())
            temporary.writeString(" complete", true)
        }
        assertEquals("next complete", file.readString())
        assertEquals(listOf("Campaign"), file.parent().list().map { it.name() })
    }

    @Test
    fun `failed first save does not publish partial file`() {
        val file = FileHandle(File(temporaryFolder.root, "New game"))
        assertThrows(IOException::class.java) {
            SaveFileWriter.write(file) { temporary ->
                temporary.writeString("partial", false)
                throw IOException("interrupted")
            }
        }
        assertFalse(file.exists())
        assertTrue(temporaryFolder.root.listFiles()!!.isEmpty())
    }

    @Test
    fun `failed publication restores backup on platforms without replacement rename`() {
        val original = temporaryFolder.newFile("Campaign").apply { writeText("previous") }
        val file = renameHandle(original) { _, _ -> false }
        assertThrows(IllegalStateException::class.java) {
            SaveFileWriter.write(file) { it.writeString("next", false) }
        }
        assertEquals("previous", original.readText())
        assertEquals(listOf("Campaign"), original.parentFile.list()!!.toList())
    }

    @Test
    fun `preview reader waits through recoverable publication fallback`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val original = files.getSave("Campaign")
        FileConversions.writeJson(original, GameInfo().apply { turns = 100 }, false)
        val gap = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readerStarted = CountDownLatch(1)
        val readerDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val turn = AtomicReference<Int>()
        val file = renameHandle(original.file()) { source, target ->
            if (target.exists()) false
            else {
                gap.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                File(source.path).renameTo(target)
            }
        }
        val writer = Thread {
            try { SaveFileWriter.write(file) { FileConversions.writeJson(it, GameInfo().apply { turns = 103 }, false) } }
            catch (ex: Throwable) { failure.set(ex) }
        }
        val reader = Thread {
            try {
                readerStarted.countDown()
                turn.set(files.loadGamePreviewFromFile(original).turns)
            } catch (ex: Throwable) { failure.set(ex) }
            finally { readerDone.countDown() }
        }
        writer.start()
        assertTrue(gap.await(5, TimeUnit.SECONDS))
        try {
            reader.start()
            assertTrue(readerStarted.await(5, TimeUnit.SECONDS))
            assertFalse(readerDone.await(100, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        writer.join(5_000)
        reader.join(5_000)
        assertFalse(writer.isAlive)
        assertFalse(reader.isAlive)
        failure.get()?.let { throw it }
        assertEquals(103, turn.get())
    }

    @Test
    fun `save enumeration restores interrupted backup and hides internal files without cloud sync`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val file = files.getSave("Campaign")
        FileConversions.writeJson(file, GameInfo().apply { turns = 100 }, false)
        assertTrue(file.file().renameTo(file.sibling(".Campaign.unciv-backup").file()))
        file.sibling(".Campaign.unciv-tmp").writeString("partial", false)
        assertEquals(listOf("Campaign"), files.getSaves().map { it.name() }.toList())
        assertEquals(100, files.loadGamePreviewFromFile(file).turns)
        assertFalse(file.sibling(".Campaign.unciv-backup").exists())
    }

    @Test
    fun `direct slot lookup recovers interrupted previous version`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val file = files.getSave("Campaign")
        file.writeString("previous", false)
        assertTrue(file.file().renameTo(file.sibling(".Campaign.unciv-backup").file()))
        assertEquals("previous", files.getSave("Campaign").readString())
    }

    @Test
    fun `deleting interrupted save also retires its recoverable backup`() {
        val files = UncivFiles(Gdx.files, temporaryFolder.root.absolutePath)
        val file = files.getSave("Campaign")
        file.writeString("previous", false)
        assertTrue(file.file().renameTo(file.sibling(".Campaign.unciv-backup").file()))
        assertTrue(files.deleteSave(file))
        assertFalse(files.getSave("Campaign").exists())
        assertFalse(file.sibling(".Campaign.unciv-backup").exists())
    }

    private fun renameHandle(file: File, rename: (File, File) -> Boolean): FileHandle = object : FileHandle(file) {
        override fun sibling(name: String): FileHandle {
            if (!name.endsWith(".unciv-tmp")) return super.sibling(name)
            return FileHandle(object : File(file.parentFile, name) {
                override fun renameTo(dest: File): Boolean = rename(this, dest)
            })
        }
    }
}
