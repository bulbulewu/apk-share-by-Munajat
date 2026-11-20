package com.example.transfer

import android.util.Log
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.ExtractedApkBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale

data class HttpServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val activeClients: Int = 0,
    val bytesServed: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val lastEvent: String = "Idle",
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesServed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

class LocalHttpApkServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentBundle: ExtractedApkBundle? = null
    private var currentMetadataJson: ByteArray = ByteArray(0)

    private val _status = MutableStateFlow(HttpServerStatus())
    val status: StateFlow<HttpServerStatus> = _status.asStateFlow()

    @Volatile
    var powerSaverEnabled: Boolean = false

    fun start(bundle: ExtractedApkBundle, preferredPort: Int = 8080): Int {
        stop()
        currentBundle = bundle
        val metadata = ApkBundleMetadata(
            transferId = bundle.transferId,
            appName = bundle.appName,
            packageName = bundle.packageName,
            versionName = bundle.versionName,
            versionCode = bundle.versionCode,
            totalSizeBytes = bundle.totalSizeBytes,
            isSplitApk = bundle.isSplitApk,
            files = bundle.files.map { ApkFileEntry(it.name, it.length()) },
            iconBase64 = bundle.iconBase64
        )
        currentMetadataJson = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

        val socket = try {
            ServerSocket(preferredPort, 16, InetAddress.getByName("0.0.0.0"))
        } catch (t: Throwable) {
            Log.w(TAG, "Port $preferredPort busy, binding random port: ${t.message}")
            try {
                ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
            } catch (err: Throwable) {
                _status.value = HttpServerStatus(
                    isRunning = false,
                    errorMessage = "Could not open local HTTP server: ${err.message}"
                )
                return -1
            }
        }

        serverSocket = socket
        val boundPort = socket.localPort
        _status.value = HttpServerStatus(
            isRunning = true,
            port = boundPort,
            totalBytes = bundle.totalSizeBytes,
            lastEvent = "Serving ${bundle.appName} on port $boundPort"
        )
        Log.i(TAG, "HTTP APK server started on 0.0.0.0:$boundPort")

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client, bundle) }
                } catch (e: Throwable) {
                    if (isActive && !socket.isClosed) {
                        Log.w(TAG, "Socket accept error: ${e.message}")
                    }
                    break
                }
            }
        }
        return boundPort
    }

    private fun handleClient(client: Socket, bundle: ExtractedApkBundle) {
        client.use { socket ->
            try {
                socket.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = parts[1].substringBefore("?")

                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                val out = BufferedOutputStream(socket.getOutputStream())
                if (method != "GET" && method != "HEAD") {
                    writeSimpleResponse(out, "405 Method Not Allowed", "text/plain", "Method Not Allowed".toByteArray())
                    return
                }

                when {
                    rawPath == "/manifest.json" -> {
                        writeSimpleResponse(out, "200 OK", "application/json; charset=utf-8", currentMetadataJson)
                    }
                    rawPath == "/app.apk" -> {
                        val file = bundle.files.firstOrNull()
                        if (file != null && file.exists()) {
                            serveFile(out, file, rangeHeader, headOnly = method == "HEAD")
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "APK not found".toByteArray())
                        }
                    }
                    rawPath.startsWith("/split/") -> {
                        val idx = rawPath.removePrefix("/split/").substringBefore("/").toIntOrNull()
                        val file = idx?.let { bundle.files.getOrNull(it) }
                        if (file != null && file.exists()) {
                            serveFile(out, file, rangeHeader, headOnly = method == "HEAD")
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "Split APK not found".toByteArray())
                        }
                    }
                    rawPath == "/" -> {
                        val linksHtml = bundle.files.mapIndexed { idx, file ->
                            val href = if (idx == 0 && !bundle.isSplitApk) "/app.apk" else "/split/$idx/${file.name}"
                            "<li><a href=\"$href\">${file.name}</a> (${file.length() / (1024 * 1024)} MB)</li>"
                        }.joinToString("\n")
                        val html = """
                            <!DOCTYPE html>
                            <html><head><meta name="viewport" content="width=device-width,initial-scale=1"/>
                            <title>APK Share - ${bundle.appName}</title>
                            <style>body{font-family:sans-serif;padding:24px;max-width:520px;margin:auto;}a.btn{display:inline-block;padding:12px 20px;background:#2563EB;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;}</style>
                            </head><body>
                            <h2>${bundle.appName} (v${bundle.versionName})</h2>
                            <p>${if (bundle.isSplitApk) "Split APK Bundle (${bundle.files.size} files)" else "Single APK"}</p>
                            <p><a class="btn" href="/app.apk">Download APK</a></p>
                            <ul>$linksHtml</ul>
                            </body></html>
                        """.trimIndent().toByteArray(Charsets.UTF_8)
                        writeSimpleResponse(out, "200 OK", "text/html; charset=utf-8", html)
                    }
                    else -> {
                        writeSimpleResponse(out, "404 Not Found", "text/plain", "Not Found".toByteArray())
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Client handler error: ${t.message}")
            }
        }
    }

    private fun writeSimpleResponse(
        out: BufferedOutputStream,
        status: String,
        contentType: String,
        body: ByteArray
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun serveFile(
        out: BufferedOutputStream,
        file: File,
        rangeHeader: String?,
        headOnly: Boolean
    ) {
        val totalLen = file.length()
        var startByte = 0L
        var endByte = totalLen - 1L
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dashParts = spec.split("-")
            val parsedStart = dashParts.getOrNull(0)?.toLongOrNull()
            val parsedEnd = dashParts.getOrNull(1)?.toLongOrNull()
            if (parsedStart != null && parsedStart in 0 until totalLen) {
                startByte = parsedStart
                if (parsedEnd != null && parsedEnd in startByte until totalLen) {
                    endByte = parsedEnd
                }
                isPartial = true
            }
        }

        val contentLen = (endByte - startByte + 1L).coerceAtLeast(0L)
        val headers = buildString {
            append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/vnd.android.package-archive\r\n")
            append("Content-Disposition: attachment; filename=\"${file.name}\"\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isPartial) {
                append("Content-Range: bytes $startByte-$endByte/$totalLen\r\n")
            }
            append("Content-Length: $contentLen\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.flush()
        if (headOnly) return

        _status.value = _status.value.copy(
            activeClients = _status.value.activeClients + 1,
            lastEvent = "Sending ${file.name}..."
        )

        val transferStartMs = System.currentTimeMillis()
        var totalSentInRequest = 0L
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(65536)
                var remaining = contentLen
                var lastUiUpdate = transferStartMs
                var servedSinceUpdate = 0L
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                    servedSinceUpdate += read
                    totalSentInRequest += read
                    val now = System.currentTimeMillis()
                    val updateInterval = if (powerSaverEnabled) 1500L else 200L
                    if (now - lastUiUpdate >= updateInterval) {
                        val elapsedMs = (now - transferStartMs).coerceAtLeast(1L)
                        val speed = (totalSentInRequest * 1000L) / elapsedMs
                        _status.value = _status.value.copy(
                            bytesServed = _status.value.bytesServed + servedSinceUpdate,
                            speedBytesPerSec = speed
                        )
                        servedSinceUpdate = 0L
                        lastUiUpdate = now
                    }
                }
                if (servedSinceUpdate > 0L) {
                    val elapsedMs = (System.currentTimeMillis() - transferStartMs).coerceAtLeast(1L)
                    val speed = (totalSentInRequest * 1000L) / elapsedMs
                    _status.value = _status.value.copy(
                        bytesServed = _status.value.bytesServed + servedSinceUpdate,
                        speedBytesPerSec = speed
                    )
                }
                out.flush()
            }
            _status.value = _status.value.copy(lastEvent = "Delivered ${file.name}")
        } finally {
            _status.value = _status.value.copy(
                activeClients = (_status.value.activeClients - 1).coerceAtLeast(0)
            )
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        currentBundle = null
        _status.value = HttpServerStatus()
    }

    companion object {
        private const val TAG = "LocalHttpApkServer"
    }
}
