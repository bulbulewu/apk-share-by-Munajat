package com.example.transfer

import android.util.Log
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.ExtractedApkBundle
import com.example.util.FormatUtils
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
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale

data class PcShareItem(
    val displayName: String,
    val subtitle: String,
    val file: File,
    val sizeBytes: Long = file.length(),
    val mimeType: String = "application/octet-stream",
    val isGenericFile: Boolean = false,
    val isSplitBundle: Boolean = false
)

data class HttpServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val activeClients: Int = 0,
    val bytesServed: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val lastEvent: String = "Idle",
    val errorMessage: String? = null,
    val currentFileName: String? = null,
    val currentFileBytesServed: Long = 0L,
    val currentFileTotalBytes: Long = 0L
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesServed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

class LocalHttpApkServer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val statusLock = Any()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var currentBundle: ExtractedApkBundle? = null
    private var currentPcItems: List<PcShareItem> = emptyList()
    private var currentPcPageSubtitle: String = DEFAULT_PC_SUBTITLE
    private var currentPcDownloadLabel: String = DEFAULT_PC_DOWNLOAD_LABEL
    private var currentPcSafetyNote: String = DEFAULT_SAFETY_NOTE
    private var currentMetadataJson: ByteArray = ByteArray(0)

    private val _status = MutableStateFlow(HttpServerStatus())
    val status: StateFlow<HttpServerStatus> = _status.asStateFlow()

    @Volatile
    var powerSaverEnabled: Boolean = false

    fun start(bundle: ExtractedApkBundle, preferredPort: Int = 8080): Int {
        stop()
        currentBundle = bundle
        currentPcItems = emptyList()
        val metadata = ApkBundleMetadata(
            transferId = bundle.transferId,
            appName = bundle.appName,
            packageName = bundle.packageName,
            versionName = bundle.versionName,
            versionCode = bundle.versionCode,
            totalSizeBytes = bundle.totalSizeBytes,
            isSplitApk = bundle.isSplitApk,
            files = bundle.files.map { ApkFileEntry(it.name, it.length()) },
            iconBase64 = bundle.iconBase64,
            isGenericFile = bundle.isGenericFile,
            mimeType = bundle.mimeType
        )
        currentMetadataJson = json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

        val socket = bindServerSocket(preferredPort) ?: return -1
        serverSocket = socket
        val boundPort = socket.localPort
        synchronized(statusLock) {
            _status.value = HttpServerStatus(
                isRunning = true,
                port = boundPort,
                totalBytes = bundle.totalSizeBytes,
                lastEvent = "Serving ${bundle.appName} on port $boundPort"
            )
        }
        Log.i(TAG, "HTTP APK server started on 0.0.0.0:$boundPort")

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleBundleClient(client, bundle) }
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

    fun startPcShare(
        items: List<PcShareItem>,
        pageSubtitle: String = DEFAULT_PC_SUBTITLE,
        downloadLabel: String = DEFAULT_PC_DOWNLOAD_LABEL,
        safetyNote: String = DEFAULT_SAFETY_NOTE,
        preferredPort: Int = 8080
    ): Int {
        stop()
        currentBundle = null
        currentPcItems = items.toList()
        currentPcPageSubtitle = pageSubtitle.ifBlank { DEFAULT_PC_SUBTITLE }
        currentPcDownloadLabel = downloadLabel.ifBlank { DEFAULT_PC_DOWNLOAD_LABEL }
        currentPcSafetyNote = safetyNote.ifBlank { DEFAULT_SAFETY_NOTE }
        currentMetadataJson = ByteArray(0)

        val socket = bindServerSocket(preferredPort) ?: return -1
        serverSocket = socket
        val boundPort = socket.localPort
        val combinedTotal = currentPcItems.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        synchronized(statusLock) {
            _status.value = HttpServerStatus(
                isRunning = true,
                port = boundPort,
                totalBytes = combinedTotal,
                lastEvent = "Serving ${currentPcItems.size} item(s) on port $boundPort"
            )
        }
        Log.i(TAG, "HTTP PC Share server started on 0.0.0.0:$boundPort (${currentPcItems.size} items)")

        val snapshotItems = currentPcItems
        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handlePcClient(client, snapshotItems) }
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

    private fun bindServerSocket(preferredPort: Int): ServerSocket? {
        return try {
            ServerSocket(preferredPort, 16, InetAddress.getByName("0.0.0.0"))
        } catch (t: Throwable) {
            Log.w(TAG, "Port $preferredPort busy, binding random port: ${t.message}")
            try {
                ServerSocket(0, 16, InetAddress.getByName("0.0.0.0"))
            } catch (err: Throwable) {
                synchronized(statusLock) {
                    _status.value = HttpServerStatus(
                        isRunning = false,
                        errorMessage = "Could not open local HTTP server: ${err.message}"
                    )
                }
                null
            }
        }
    }

    private fun handleBundleClient(client: Socket, bundle: ExtractedApkBundle) {
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

                val contentTypeForBundle = if (bundle.isGenericFile) {
                    bundle.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                } else {
                    "application/vnd.android.package-archive"
                }

                when {
                    rawPath == "/manifest.json" -> {
                        writeSimpleResponse(out, "200 OK", "application/json; charset=utf-8", currentMetadataJson)
                    }
                    rawPath == "/app.apk" || rawPath == "/file" || rawPath.startsWith("/file/") -> {
                        val file = bundle.files.firstOrNull()
                        if (file != null && file.exists()) {
                            serveFile(
                                out = out,
                                file = file,
                                displayName = file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = contentTypeForBundle
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "File not found".toByteArray())
                        }
                    }
                    rawPath.startsWith("/split/") -> {
                        val idx = rawPath.removePrefix("/split/").substringBefore("/").toIntOrNull()
                        val file = idx?.let { bundle.files.getOrNull(it) }
                        if (file != null && file.exists()) {
                            serveFile(
                                out = out,
                                file = file,
                                displayName = file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = contentTypeForBundle
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "Split APK not found".toByteArray())
                        }
                    }
                    rawPath == "/" -> {
                        val primaryHref = if (bundle.isGenericFile) "/file" else "/app.apk"
                        val linksHtml = bundle.files.mapIndexed { idx, file ->
                            val href = when {
                                bundle.isGenericFile -> "/file"
                                idx == 0 && !bundle.isSplitApk -> "/app.apk"
                                else -> "/split/$idx/${urlEncodeSegment(file.name)}"
                            }
                            "<li><a href=\"$href\">${escapeHtml(file.name)}</a> (${file.length()} bytes)</li>"
                        }.joinToString("\n")
                        val subtitleHtml = when {
                            bundle.isGenericFile -> escapeHtml(bundle.mimeType ?: "File")
                            bundle.isSplitApk -> "Split APK Bundle (${bundle.files.size} files)"
                            else -> "Single APK (v${escapeHtml(bundle.versionName)})"
                        }
                        val buttonText = if (bundle.isGenericFile) "Download File" else "Download APK"
                        val html = """
                            <!DOCTYPE html>
                            <html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
                            <title>APK Share - ${escapeHtml(bundle.appName)}</title>
                            <style>body{font-family:sans-serif;padding:24px;max-width:520px;margin:auto;}a.btn{display:inline-block;padding:12px 20px;background:#2563EB;color:#fff;text-decoration:none;border-radius:8px;font-weight:bold;}</style>
                            </head><body>
                            <h2>${escapeHtml(bundle.appName)}</h2>
                            <p>$subtitleHtml</p>
                            <p><a class="btn" href="$primaryHref">$buttonText</a></p>
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

    private fun handlePcClient(client: Socket, items: List<PcShareItem>) {
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
                    rawPath == "/" -> {
                        val html = buildPcIndexHtml(items).toByteArray(Charsets.UTF_8)
                        writeSimpleResponse(out, "200 OK", "text/html; charset=utf-8", html, headOnly = method == "HEAD")
                    }
                    rawPath.startsWith("/item/") -> {
                        val idx = rawPath.removePrefix("/item/").substringBefore("/").toIntOrNull()
                        val item = idx?.let { items.getOrNull(it) }
                        if (item != null && item.file.exists()) {
                            serveFile(
                                out = out,
                                file = item.file,
                                displayName = item.file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = item.mimeType.ifBlank { "application/octet-stream" }
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "Item not found".toByteArray())
                        }
                    }
                    rawPath == "/app.apk" -> {
                        val item = items.firstOrNull { !it.isGenericFile } ?: items.firstOrNull()
                        if (item != null && item.file.exists()) {
                            serveFile(
                                out = out,
                                file = item.file,
                                displayName = item.file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = item.mimeType.ifBlank { "application/vnd.android.package-archive" }
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "APK not found".toByteArray())
                        }
                    }
                    rawPath == "/file" || rawPath.startsWith("/file/") -> {
                        val item = items.firstOrNull { it.isGenericFile } ?: items.firstOrNull()
                        if (item != null && item.file.exists()) {
                            serveFile(
                                out = out,
                                file = item.file,
                                displayName = item.file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = item.mimeType.ifBlank { "application/octet-stream" }
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "File not found".toByteArray())
                        }
                    }
                    rawPath.startsWith("/split/") -> {
                        val idx = rawPath.removePrefix("/split/").substringBefore("/").toIntOrNull()
                        val item = idx?.let { items.getOrNull(it) }
                        if (item != null && item.file.exists()) {
                            serveFile(
                                out = out,
                                file = item.file,
                                displayName = item.file.name,
                                rangeHeader = rangeHeader,
                                headOnly = method == "HEAD",
                                contentType = item.mimeType.ifBlank { "application/octet-stream" }
                            )
                        } else {
                            writeSimpleResponse(out, "404 Not Found", "text/plain", "Item not found".toByteArray())
                        }
                    }
                    else -> {
                        writeSimpleResponse(out, "404 Not Found", "text/plain", "Not Found".toByteArray())
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "PC client handler error: ${t.message}")
            }
        }
    }

    private fun buildPcIndexHtml(items: List<PcShareItem>): String {
        val totalBytes = items.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        val rowsHtml = items.mapIndexed { idx, item ->
            val href = resolvePcItemHref(items.size, idx, item)
            val sizeText = FormatUtils.formatBytes(item.sizeBytes)
            val fileName = item.file.name
            val subtitleLine = if (item.subtitle.isNotBlank() && item.subtitle != fileName) {
                "${escapeHtml(item.subtitle)} &middot; ${escapeHtml(fileName)}"
            } else {
                escapeHtml(fileName)
            }
            """
            <li class="row">
              <div class="info">
                <div class="name">${escapeHtml(item.displayName)}</div>
                <div class="meta">$subtitleLine</div>
              </div>
              <div class="actions">
                <span class="size">${escapeHtml(sizeText)}</span>
                <a class="btn" href="$href" download="${escapeHtml(fileName)}">${escapeHtml(currentPcDownloadLabel)}</a>
              </div>
            </li>
            """.trimIndent()
        }.joinToString("\n")

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>APK Share</title>
              <style>
                body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f4f7fb;color:#111827;margin:0;padding:24px;}
                .card{max-width:760px;margin:0 auto;background:#ffffff;border-radius:16px;padding:24px;box-shadow:0 6px 24px rgba(15,23,42,0.08);}
                h1{margin:0 0 6px;font-size:24px;color:#1d4ed8;}
                .subtitle{margin:0 0 18px;color:#4b5563;font-size:14px;}
                ul.list{list-style:none;padding:0;margin:0;display:flex;flex-direction:column;gap:12px;}
                li.row{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;padding:14px 16px;border:1px solid #e5e7eb;border-radius:12px;background:#f9fafb;}
                .info{min-width:200px;flex:1 1 240px;}
                .name{font-weight:700;font-size:16px;color:#111827;word-break:break-word;}
                .meta{font-size:13px;color:#6b7280;margin-top:4px;word-break:break-word;}
                .actions{display:flex;align-items:center;gap:12px;}
                .size{font-size:13px;font-weight:600;color:#374151;white-space:nowrap;}
                a.btn{display:inline-block;padding:10px 16px;background:#2563eb;color:#ffffff;text-decoration:none;border-radius:10px;font-weight:600;font-size:14px;white-space:nowrap;}
                a.btn:hover{background:#1d4ed8;}
                .safety{margin-top:20px;padding:12px 14px;border-radius:10px;background:#fef3c7;color:#92400e;font-size:13px;line-height:1.45;}
              </style>
            </head>
            <body>
              <div class="card">
                <h1>APK Share</h1>
                <p class="subtitle">${escapeHtml(currentPcPageSubtitle)} (${items.size} &middot; ${escapeHtml(FormatUtils.formatBytes(totalBytes))})</p>
                <ul class="list">
                  $rowsHtml
                </ul>
                <div class="safety">${escapeHtml(currentPcSafetyNote)}</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun resolvePcItemHref(totalItems: Int, index: Int, item: PcShareItem): String {
        val encodedName = urlEncodeSegment(item.file.name)
        return when {
            totalItems == 1 && item.isGenericFile -> "/file"
            totalItems == 1 && !item.isSplitBundle -> "/app.apk"
            else -> "/item/$index/$encodedName"
        }
    }

    private fun writeSimpleResponse(
        out: BufferedOutputStream,
        status: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean = false
    ) {
        val headers = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    private fun serveFile(
        out: BufferedOutputStream,
        file: File,
        displayName: String,
        rangeHeader: String?,
        headOnly: Boolean,
        contentType: String = "application/vnd.android.package-archive"
    ) {
        val totalLen = file.length()
        var startByte = 0L
        var endByte = (totalLen - 1L).coerceAtLeast(0L)
        var isPartial = false
        var rangeNotSatisfiable = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val dashParts = spec.split("-")
            val parsedStart = dashParts.getOrNull(0)?.toLongOrNull()
            val parsedEnd = dashParts.getOrNull(1)?.toLongOrNull()
            if (parsedStart != null) {
                if (parsedStart in 0 until totalLen) {
                    startByte = parsedStart
                    if (parsedEnd != null && parsedEnd in startByte until totalLen) {
                        endByte = parsedEnd
                    }
                    isPartial = true
                } else {
                    rangeNotSatisfiable = true
                }
            }
        }

        if (rangeNotSatisfiable) {
            val headers = buildString {
                append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                append("Content-Range: bytes */$totalLen\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headers.toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }

        val safeDispositionName = file.name.replace("\"", "_").replace("\r", "").replace("\n", "")
        val contentLen = if (totalLen == 0L) 0L else (endByte - startByte + 1L).coerceAtLeast(0L)
        val headers = buildString {
            append(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Disposition: attachment; filename=\"$safeDispositionName\"\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (isPartial) {
                append("Content-Range: bytes $startByte-$endByte/$totalLen\r\n")
            }
            append("Content-Length: $contentLen\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.flush()
        if (headOnly || contentLen == 0L) return

        synchronized(statusLock) {
            _status.value = _status.value.copy(
                activeClients = _status.value.activeClients + 1,
                currentFileName = displayName,
                currentFileBytesServed = startByte,
                currentFileTotalBytes = totalLen,
                lastEvent = "Sending $displayName..."
            )
        }

        val transferStartMs = System.currentTimeMillis()
        val rollingTracker = FormatUtils.RollingTransferSpeedTracker()
        rollingTracker.reset(nowMillis = transferStartMs, initialBytes = 0L)
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
                        val speed = rollingTracker.record(totalSentInRequest, now)
                        synchronized(statusLock) {
                            _status.value = _status.value.copy(
                                bytesServed = _status.value.bytesServed + servedSinceUpdate,
                                currentFileName = displayName,
                                currentFileBytesServed = startByte + totalSentInRequest,
                                currentFileTotalBytes = totalLen,
                                speedBytesPerSec = speed
                            )
                        }
                        servedSinceUpdate = 0L
                        lastUiUpdate = now
                    }
                }
                if (servedSinceUpdate > 0L) {
                    val now = System.currentTimeMillis()
                    val rolling = rollingTracker.record(totalSentInRequest, now)
                    val speed = if (rolling > 0L) {
                        rolling
                    } else {
                        val elapsedMs = (now - transferStartMs).coerceAtLeast(1L)
                        (totalSentInRequest * 1000L) / elapsedMs
                    }
                    synchronized(statusLock) {
                        _status.value = _status.value.copy(
                            bytesServed = _status.value.bytesServed + servedSinceUpdate,
                            currentFileName = displayName,
                            currentFileBytesServed = startByte + totalSentInRequest,
                            currentFileTotalBytes = totalLen,
                            speedBytesPerSec = speed
                        )
                    }
                }
                out.flush()
            }
            synchronized(statusLock) {
                _status.value = _status.value.copy(lastEvent = "Delivered $displayName")
            }
        } finally {
            synchronized(statusLock) {
                val remainingClients = (_status.value.activeClients - 1).coerceAtLeast(0)
                _status.value = _status.value.copy(
                    activeClients = remainingClients,
                    speedBytesPerSec = if (remainingClients == 0) 0L else _status.value.speedBytesPerSec
                )
            }
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        currentBundle = null
        currentPcItems = emptyList()
        synchronized(statusLock) {
            _status.value = HttpServerStatus()
        }
    }

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun urlEncodeSegment(segment: String): String {
        return runCatching {
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }.getOrDefault("file")
    }

    companion object {
        private const val TAG = "LocalHttpApkServer"
        private const val DEFAULT_PC_SUBTITLE = "Click any file below to download it to your PC."
        private const val DEFAULT_PC_DOWNLOAD_LABEL = "Download"
        const val DEFAULT_SAFETY_NOTE =
            "Local hotspot only — anyone connected to this Wi-Fi can download these files. Stop sharing when you are done."
    }
}
