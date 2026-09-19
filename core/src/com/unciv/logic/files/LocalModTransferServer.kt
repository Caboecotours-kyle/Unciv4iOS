package com.unciv.logic.files

import com.badlogic.gdx.files.FileHandle
import com.unciv.logic.github.GithubAPI
import com.unciv.utils.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** A temporary one-upload HTTP receiver for Mod archives. */
class LocalModTransferServer(
    private val modsFolder: FileHandle,
    private val onInstalled: (String) -> Unit,
) : AutoCloseable {
    companion object {
        private const val maxUploadBytes = 256L * 1024 * 1024
        private const val maxUncompressedBytes = 512L * 1024 * 1024
        private const val maxFailedCodes = 5
        private const val maxHeaderBytes = 16 * 1024

        private val uploadPage = """<!doctype html>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Unciv Mod Transfer</title>
<h1>Send a Mod to Unciv</h1>
<p>Select a packaged Mod ZIP and enter the access code shown in the app.</p>
<input id="file" type="file" accept=".zip,application/zip">
<input id="code" type="password" inputmode="numeric" autocomplete="one-time-code" placeholder="Access code">
<button id="send">Install Mod</button>
<p id="status"></p>
<script>
document.getElementById('send').onclick = async function () {
  const file = document.getElementById('file').files[0];
  const status = document.getElementById('status');
  if (!file) { status.textContent = 'Choose a ZIP file first.'; return; }
  status.textContent = 'Uploading and installing...';
  try {
    const response = await fetch('/upload', { method: 'POST', headers: {
      'Content-Type': 'application/zip',
      'X-Unciv-Access-Code': document.getElementById('code').value,
      'X-Mod-Name': encodeURIComponent(file.name)
    }, body: file });
    status.textContent = await response.text();
  } catch (error) { status.textContent = 'Transfer failed. Check the Wi-Fi connection and try again.'; }
};
</script>"""
    }

    val accessCode: String
    val uploadUrl: String

    private val stopped = AtomicBoolean(false)
    private val serverSocket = ServerSocket()
    @Volatile private var activeSocket: Socket? = null
    private var failedCodes = 0

    init {
        try {
            serverSocket.reuseAddress = true
            val address = findLocalIpv4Address()
                ?: throw IOException("Connect this device to Wi-Fi and try again")
            serverSocket.bind(InetSocketAddress(address, 0))
            accessCode = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
            uploadUrl = "http://${address.hostAddress}:${serverSocket.localPort}/"
            Thread(::serve, "UncivModTransfer").apply {
                isDaemon = true
                start()
            }
        } catch (ex: Exception) {
            serverSocket.close()
            throw ex
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            serverSocket.close()
        } catch (_: IOException) {
        }
        try {
            activeSocket?.close()
        } catch (_: IOException) {
        }
    }

    private fun serve() {
        while (!stopped.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (ex: SocketException) {
                if (!stopped.get()) Log.error("Mod transfer server stopped unexpectedly", ex)
                return
            } catch (ex: IOException) {
                if (!stopped.get()) Log.error("Mod transfer server stopped unexpectedly", ex)
                return
            }

            if (stopped.get()) {
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                return
            }
            activeSocket = socket
            var installedMod: String? = null
            try {
                socket.soTimeout = 30_000
                installedMod = handleRequest(socket)
            } catch (ex: Exception) {
                if (!stopped.get()) Log.error("Could not receive a Mod archive", ex)
            } finally {
                activeSocket = null
                try {
                    socket.close()
                } catch (_: IOException) {
                }
            }

            if (installedMod != null) {
                close()
                try {
                    onInstalled(installedMod)
                } catch (ex: Exception) {
                    Log.error("Could not refresh the installed Mod", ex)
                }
                return
            }
        }
    }

    private fun handleRequest(socket: Socket): String? {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val request = try {
            readRequest(input)
        } catch (ex: IOException) {
            respond(output, 400, "Bad request")
            return null
        }

        if (request.method == "GET" && request.path == "/") {
            respond(output, 200, uploadPage, "text/html; charset=utf-8")
            return null
        }
        if (request.method != "POST" || request.path != "/upload") {
            respond(output, 404, "Not found")
            return null
        }
        if (request.headers["x-unciv-access-code"] != accessCode) {
            failedCodes++
            respond(output, 403, if (failedCodes >= maxFailedCodes) "Too many incorrect codes" else "Incorrect access code")
            if (failedCodes >= maxFailedCodes) close()
            return null
        }
        if (request.headers["transfer-encoding"] != null) {
            respond(output, 411, "A fixed-size ZIP upload is required")
            return null
        }
        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength <= 0L) {
            respond(output, 411, "Content-Length is required")
            return null
        }
        if (contentLength > maxUploadBytes) {
            respond(output, 413, "ZIP file exceeds the 256 MiB upload limit")
            return null
        }
        if (request.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/zip") {
            respond(output, 415, "Choose a ZIP archive")
            return null
        }
        if (request.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            output.flush()
        }

        val encodedName = request.headers["x-mod-name"].orEmpty().take(300)
        val archiveName = try {
            URLDecoder.decode(encodedName, "UTF-8")
        } catch (_: IllegalArgumentException) {
            "Uploaded Mod.zip"
        }
        val installedMod = try {
            runBlocking {
                GithubAPI.installUploadedModArchive(
                    FixedLengthInputStream(input, contentLength),
                    archiveName,
                    modsFolder,
                    maxUncompressedBytes,
                ).name()
            }
        } catch (ex: Exception) {
            respond(output, 400, "Could not install Mod: ${ex.message ?: "invalid ZIP archive"}")
            return null
        }

        respond(output, 200, "Mod installed. Return to Unciv.")
        return installedMod
    }

    private fun readRequest(input: InputStream): Request {
        val requestLine = readLine(input) ?: throw EOFException("Missing request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3 || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1"))
            throw IOException("Malformed request line")

        val headers = HashMap<String, String>()
        var totalBytes = requestLine.length
        while (true) {
            val line = readLine(input) ?: throw EOFException("Incomplete request headers")
            totalBytes += line.length + 2
            if (totalBytes > maxHeaderBytes) throw IOException("Request headers are too large")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) throw IOException("Malformed request header")
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = line.substring(separator + 1).trim()
            if (headers.put(name, value) != null) throw IOException("Duplicate request header")
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (bytes.size() == 0) return null
                throw EOFException("Incomplete request line")
            }
            if (value == '\n'.code) return String(bytes.toByteArray(), Charsets.US_ASCII)
            if (value != '\r'.code) bytes.write(value)
            if (bytes.size() > 4096) throw IOException("Request line is too large")
        }
    }

    private fun respond(output: java.io.OutputStream, status: Int, body: String, contentType: String = "text/plain; charset=utf-8") {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            415 -> "Unsupported Media Type"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.write(bodyBytes)
        output.flush()
    }

    private fun findLocalIpv4Address(): Inet4Address? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        // On iOS, en0 is Wi-Fi; prefer it over private addresses from VPN or cellular interfaces.
        val orderedInterfaces = Collections.list(interfaces).sortedBy { if (it.name == "en0") 0 else 1 }
        for (networkInterface in orderedInterfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            for (address in Collections.list(networkInterface.inetAddresses)) {
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress)
                    return address
            }
        }
        return null
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>)

    private class FixedLengthInputStream(
        private val input: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = input.read()
            if (value < 0) throw EOFException("Incomplete ZIP upload")
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Incomplete ZIP upload")
            remaining -= count
            return count
        }

        override fun close() = Unit
    }
}
