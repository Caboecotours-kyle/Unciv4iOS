package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalModTransferServerTest {
    @Test
    fun servesUploadPageRequiresCodeRejectsUnsafeArchiveAndInstallsOneMod() {
        val tempDirectory = Files.createTempDirectory("unciv-mod-transfer-test").toFile()
        val modsFolder = FileHandle(tempDirectory)
        val installed = CountDownLatch(1)
        var installedName: String? = null
        val server = LocalModTransferServer(modsFolder) {
            installedName = it
            installed.countDown()
        }

        try {
            assertTrue(server.accessCode.matches(Regex("[0-9]{6}")))
            val page = URI(server.uploadUrl).toURL().openConnection() as HttpURLConnection
            assertEquals(200, page.responseCode)
            assertTrue(page.inputStream.bufferedReader().use { it.readText() }.contains("Send a Mod to Unciv"))
            page.disconnect()

            val archive = createArchive("QuickTest/jsons/ModOptions.json" to "{}")
            assertEquals(403, upload(server, ByteArray(0), "wrong-code").first)

            val unsafeArchive = createArchive("../escaped.txt" to "outside")
            assertEquals(400, upload(server, unsafeArchive, server.accessCode).first)
            assertFalse(modsFolder.child("escaped.txt").exists())

            val response = upload(server, archive, server.accessCode)
            assertEquals(200, response.first)
            assertTrue(response.second.contains("Mod installed"))
            assertTrue(installed.await(5, TimeUnit.SECONDS))
            assertEquals("QuickTest", installedName)
            assertTrue(modsFolder.child("QuickTest/jsons/ModOptions.json").exists())
            assertFalse(respondsAfterStop(server.uploadUrl))
        } finally {
            server.close()
            modsFolder.deleteDirectory()
        }
    }

    private fun upload(server: LocalModTransferServer, archive: ByteArray, code: String): Pair<Int, String> {
        val connection = URI(server.uploadUrl + "upload").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/zip")
        connection.setRequestProperty("X-Unciv-Access-Code", code)
        connection.setRequestProperty("X-Mod-Name", "QuickTest.zip")
        connection.setFixedLengthStreamingMode(archive.size)
        connection.outputStream.use { it.write(archive) }
        val status = connection.responseCode
        val response = (if (status < 400) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }

    private fun respondsAfterStop(url: String): Boolean {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 500
        connection.readTimeout = 500
        return try {
            connection.responseCode >= 0
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun createArchive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
