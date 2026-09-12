package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.json.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.FilterOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Test

class FileConversionsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `base64 output stays compatible with java encoder`() {
        assertEquals("VW5jaXYgaU9TIFBPQw==", FileConversions.encode("Unciv iOS POC".toByteArray()))
    }

    @Test
    fun `gzip base64 output stays compatible with existing saves`() {
        val encoded = "H4sIAAAAAAAA/wvNS84sU8j0D1YI8HcGAIa8kI8NAAAA"

        assertEquals(encoded, FileConversions.zip("Unciv iOS POC"))
        assertEquals("Unciv iOS POC", FileConversions.unzip(encoded))
    }
    @Test
    fun `streamed JSON preserves existing plain and compressed bytes`() {
        val value = linkedMapOf("key" to "value", "second" to "存档")
        val expected = json().toJson(value)
        for (zipped in listOf(false, true)) {
            val file = FileHandle(temporaryFolder.newFile("save-$zipped"))
            FileConversions.writeJson(file, value, zipped)
            assertEquals(if (zipped) FileConversions.zip(expected) else expected, file.readString("UTF-8"))
        }
    }

    @Test
    fun `output close errors propagate for plain and compressed JSON`() {
        for (zipped in listOf(false, true)) {
            val file = object : FileHandle(temporaryFolder.newFile("failed-$zipped")) {
                override fun write(append: Boolean): OutputStream = object : FilterOutputStream(super.write(append)) {
                    override fun close() {
                        super.close()
                        throw IOException("close failed")
                    }
                }
            }
            assertThrows(IOException::class.java) { FileConversions.writeJson(file, "value", zipped) }
        }
    }

    @Test
    fun `every input stream closes when reading plain or compressed JSON`() {
        for (zipped in listOf(false, true)) {
            val file = TrackingReadHandle(temporaryFolder.newFile("read-$zipped"))
            file.writeString(if (zipped) FileConversions.zip("\"value\"") else "\"value\"", false)
            repeat(5) { assertEquals("value", FileConversions.readJson(file, String::class.java)) }
            assertTrue(file.closed.isNotEmpty())
            assertTrue("Unclosed streams for zip=$zipped: ${file.closed}", file.closed.all { it })
        }
    }

    @Test
    fun `every input stream closes after header or JSON parsing failure`() {
        for ((index, contents) in listOf("{broken", FileConversions.zip("{broken"),
            FileConversions.encode("not gzip".toByteArray())).withIndex()) {
            val file = TrackingReadHandle(temporaryFolder.newFile("corrupt-$index"))
            file.writeString(contents, false)
            assertThrows(Exception::class.java) { FileConversions.readJson(file, HashMap::class.java) }
            assertTrue(file.closed.isNotEmpty())
            assertTrue("Unclosed streams for case $index: ${file.closed}", file.closed.all { it })
        }
    }

    private class TrackingReadHandle(file: java.io.File) : FileHandle(file) {
        val closed = arrayListOf<Boolean>()
        override fun read(): java.io.InputStream {
            val index = closed.size
            closed.add(false)
            return object : FilterInputStream(super.read()) {
                override fun close() {
                    closed[index] = true
                    super.close()
                }
            }
        }
    }

}
