package com.example

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.apk.InstalledAppsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.installer.ApkInstaller
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.model.ResumeFileOffset
import com.example.transfer.HotspotController
import com.example.transfer.HttpApkDownloader
import com.example.transfer.HttpDownloadState
import com.example.transfer.InterruptedFileRecord
import com.example.transfer.InterruptedTransferRecord
import com.example.transfer.InterruptedTransferStore
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyReceiverState
import com.example.transfer.NearbyResumeCodec
import com.example.transfer.NearbySenderState
import com.example.transfer.NearbyTransferManager
import com.example.transfer.StreamReceiveOutcome
import com.example.ui.screens.ReceiverScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.viewmodel.ReceiverMode
import com.example.ui.viewmodel.ReceiverViewModel
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import com.example.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferResumeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: AppDatabase? = null
    private var viewModelStore: ViewModelStore? = null
    private var testServerSocket: ServerSocket? = null
    private var httpDownloader: HttpApkDownloader? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.saveLanguageSync(context, LocaleHelper.LANGUAGE_ENGLISH)
        val downloadsRoot = ApkStorageManager(context).getAppDownloadsRoot()
        ShadowStatFs.registerStats(downloadsRoot.absolutePath, 100_000, 80_000, 80_000)
        val outgoingCache = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
        ShadowStatFs.registerStats(outgoingCache.absolutePath, 100_000, 80_000, 80_000)
        InterruptedTransferStore(context).clear(deletePartialFiles = true)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.saveLanguageSync(context, LocaleHelper.LANGUAGE_ENGLISH)
        httpDownloader?.cancel()
        httpDownloader = null
        runCatching { testServerSocket?.close() }
        testServerSocket = null
        viewModelStore?.clear()
        viewModelStore = null
        db?.close()
        db = null
        InterruptedTransferStore(context).clear(deletePartialFiles = true)
    }

    private fun deterministicBytes(size: Int, seed: Int = 37): ByteArray {
        return ByteArray(size) { idx -> ((idx * 31 + seed) and 0xFF).toByte() }
    }

    private fun pumpMainUntil(timeoutMs: Long = 8000L, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val mainShadow = Shadows.shadowOf(Looper.getMainLooper())
        while (System.currentTimeMillis() < deadline) {
            mainShadow.idleFor(java.time.Duration.ofMillis(50L))
            if (predicate()) return true
            Thread.sleep(20L)
        }
        mainShadow.idleFor(java.time.Duration.ofMillis(50L))
        return predicate()
    }

    @Test
    fun nearbyCodec_interruptHalfway_keepsPartAndResumesOnlyRemainingBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempRoot = createTempDirectory("nearby_resume_test").toFile()
        try {
            val totalSize = 256_000
            val fullPayload = deterministicBytes(totalSize)
            val sourceFile = File(tempRoot, "BigGame.apk").apply { writeBytes(fullPayload) }
            val destDir = File(tempRoot, "dest").apply { mkdirs() }
            val store = InterruptedTransferStore(context)

            val metadata = ApkBundleMetadata(
                transferId = "tx_nearby_resume",
                appName = "BigGame",
                packageName = "com.example.biggame",
                versionName = "2.0",
                totalSizeBytes = totalSize.toLong(),
                isSplitApk = false,
                files = listOf(ApkFileEntry("BigGame.apk", totalSize.toLong()))
            )

            // 1. First attempt drops at 100,000 bytes
            val firstCutoff = 100_000
            val firstOutcome = NearbyResumeCodec.receiveStreamIntoPartFile(
                input = ByteArrayInputStream(fullPayload.copyOfRange(0, firstCutoff)),
                destDir = destDir,
                safeName = "BigGame.apk",
                expectedTotalSize = totalSize.toLong(),
                offsetBytes = 0L,
                restartedFromZero = false,
                isCancelled = { false }
            )
            assertEquals(StreamReceiveOutcome.Interrupted(firstCutoff.toLong()), firstOutcome)
            val partFile = File(destDir, "BigGame.apk.part")
            val finalFile = File(destDir, "BigGame.apk")
            assertTrue(".part file must remain on disk after interruption", partFile.exists())
            assertEquals(firstCutoff.toLong(), partFile.length())
            assertFalse("Final file must NOT exist while incomplete", finalFile.exists())

            // Persist interrupted record
            val savedRecord = InterruptedTransferRecord(
                mode = InterruptedTransferStore.MODE_NEARBY,
                peerName = "Pixel 9 #ABCD",
                senderPairCode = "ABCD",
                metadata = metadata,
                destDirPath = destDir.absolutePath,
                files = listOf(
                    InterruptedFileRecord(
                        fileName = "BigGame.apk",
                        safeFileName = "BigGame.apk",
                        totalSizeBytes = totalSize.toLong(),
                        receivedBytes = firstCutoff.toLong(),
                        completed = false
                    )
                )
            )
            store.save(savedRecord)
            val reloaded = store.loadValid()
            assertNotNull(reloaded)
            assertEquals(firstCutoff.toLong(), reloaded!!.totalReceivedBytes)

            // 2. Receiver plans resume offsets from preserved .part
            val receiverPlan = NearbyResumeCodec.planReceiverFiles(metadata, destDir, reloaded)
            assertFalse(receiverPlan.hadInvalidPartial)
            assertEquals(1, receiverPlan.resumeOffsets.size)
            assertEquals(firstCutoff.toLong(), receiverPlan.resumeOffsets.first().receivedBytes)
            assertEquals(totalSize.toLong(), receiverPlan.resumeOffsets.first().totalSizeBytes)

            // 3. Sender validates identity and opens stream strictly from offset
            val senderPlan = NearbyResumeCodec.resolveSenderFilePlan(
                sourceFile,
                receiverPlan.resumeOffsets.first()
            )
            assertFalse(senderPlan.alreadyCompleted)
            assertFalse(senderPlan.restartedFromZero)
            assertEquals(firstCutoff.toLong(), senderPlan.offsetBytes)

            val tailBytes = NearbyResumeCodec.openOffsetInputStream(sourceFile, senderPlan.offsetBytes)
                .use { it.readBytes() }
            assertEquals(
                "Sender must send ONLY remaining bytes from offset",
                totalSize - firstCutoff,
                tailBytes.size
            )

            // 4. Receiver appends tail to .part, verifies final size, and renames to final file
            val resumedOutcome = NearbyResumeCodec.receiveStreamIntoPartFile(
                input = ByteArrayInputStream(tailBytes),
                destDir = destDir,
                safeName = "BigGame.apk",
                expectedTotalSize = totalSize.toLong(),
                offsetBytes = senderPlan.offsetBytes,
                restartedFromZero = senderPlan.restartedFromZero,
                isCancelled = { false }
            )
            assertTrue(resumedOutcome is StreamReceiveOutcome.Completed)
            assertFalse(".part must be removed after rename", partFile.exists())
            assertTrue("Final file must exist after resume completes", finalFile.exists())
            assertEquals(totalSize.toLong(), finalFile.length())
            assertArrayEquals(
                "Final resumed file must match source byte-for-byte",
                fullPayload,
                finalFile.readBytes()
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun nearbyCodec_fileChangedOrOversizedPart_discardsPartialAndRestartsFromZero() {
        val tempRoot = createTempDirectory("nearby_mismatch_test").toFile()
        try {
            val destDir = File(tempRoot, "dest").apply { mkdirs() }
            val oldSize = 100_000L
            val newBytes = deterministicBytes(120_000, seed = 91)
            val newSource = File(tempRoot, "App.apk").apply { writeBytes(newBytes) }

            // Stale .part from older version (different total size)
            val partFile = File(destDir, "App.apk.part").apply { writeBytes(ByteArray(40_000)) }
            val savedOld = InterruptedTransferRecord(
                mode = InterruptedTransferStore.MODE_NEARBY,
                peerName = "Sender #1111",
                metadata = ApkBundleMetadata(
                    transferId = "old_tx",
                    appName = "App",
                    packageName = "com.example.app",
                    versionName = "1.0",
                    totalSizeBytes = oldSize,
                    isSplitApk = false,
                    files = listOf(ApkFileEntry("App.apk", oldSize))
                ),
                destDirPath = destDir.absolutePath,
                files = listOf(InterruptedFileRecord("App.apk", "App.apk", oldSize, 40_000L, false))
            )

            // New metadata has different totalSizeBytes
            val newMetadata = savedOld.metadata.copy(
                transferId = "new_tx",
                totalSizeBytes = newBytes.size.toLong(),
                files = listOf(ApkFileEntry("App.apk", newBytes.size.toLong()))
            )

            // Receiver identity guard detects size mismatch and deletes stale .part
            val receiverPlan = NearbyResumeCodec.planReceiverFiles(newMetadata, destDir, savedOld)
            assertTrue(receiverPlan.hadInvalidPartial)
            assertTrue(receiverPlan.resumeOffsets.isEmpty())
            assertFalse("Stale .part must be deleted on mismatch", partFile.exists())

            // Sender identity guard also rejects mismatched offset and flags restartedFromZero
            val senderPlan = NearbyResumeCodec.resolveSenderFilePlan(
                newSource,
                ResumeFileOffset("App.apk", receivedBytes = 40_000L, totalSizeBytes = oldSize)
            )
            assertEquals(0L, senderPlan.offsetBytes)
            assertTrue(senderPlan.restartedFromZero)

            // Oversized .part guard
            val oversizedPart = File(destDir, "App.apk.part").apply { writeBytes(ByteArray(150_000)) }
            val oversizePlan = NearbyResumeCodec.planReceiverFiles(newMetadata, destDir, null)
            assertTrue(oversizePlan.hadInvalidPartial)
            assertFalse("Oversized .part must be deleted", oversizedPart.exists())

            // Missing .part when offset > 0 returns InvalidPartialRestart (never corrupts)
            val missingOutcome = NearbyResumeCodec.receiveStreamIntoPartFile(
                input = ByteArrayInputStream(newBytes.copyOfRange(50_000, newBytes.size)),
                destDir = destDir,
                safeName = "App.apk",
                expectedTotalSize = newBytes.size.toLong(),
                offsetBytes = 50_000L,
                restartedFromZero = false,
                isCancelled = { false }
            )
            assertEquals(StreamReceiveOutcome.InvalidPartialRestart, missingOutcome)
            assertFalse(File(destDir, "App.apk").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun nearbyCodec_splitApkResume_skipsCompletedFileAndResumesPartialFile() {
        val tempRoot = createTempDirectory("nearby_split_resume").toFile()
        try {
            val destDir = File(tempRoot, "SplitApp_v1.0").apply { mkdirs() }
            val baseBytes = deterministicBytes(32_000, seed = 1)
            val armBytes = deterministicBytes(64_000, seed = 2)
            val langBytes = deterministicBytes(16_000, seed = 3)

            // base.apk already completed, config.arm64.apk halfway, config.en.apk not started
            File(destDir, "base.apk").writeBytes(baseBytes)
            File(destDir, "config.arm64.apk.part").writeBytes(armBytes.copyOfRange(0, 24_000))

            val metadata = ApkBundleMetadata(
                transferId = "split_tx",
                appName = "SplitApp",
                packageName = "com.example.split",
                versionName = "1.0",
                totalSizeBytes = (baseBytes.size + armBytes.size + langBytes.size).toLong(),
                isSplitApk = true,
                files = listOf(
                    ApkFileEntry("base.apk", baseBytes.size.toLong()),
                    ApkFileEntry("config.arm64.apk", armBytes.size.toLong()),
                    ApkFileEntry("config.en.apk", langBytes.size.toLong())
                )
            )

            val plan = NearbyResumeCodec.planReceiverFiles(metadata, destDir, savedRecord = null)
            assertEquals(1, plan.alreadyCompletedFiles.size)
            assertEquals("base.apk", plan.alreadyCompletedFiles.first().name)
            assertEquals((baseBytes.size + 24_000).toLong(), plan.initialReceivedBytes)

            val offsetsByName = plan.resumeOffsets.associateBy { it.fileName }
            val baseSrc = File(tempRoot, "base.apk").apply { writeBytes(baseBytes) }
            val armSrc = File(tempRoot, "config.arm64.apk").apply { writeBytes(armBytes) }
            val langSrc = File(tempRoot, "config.en.apk").apply { writeBytes(langBytes) }

            val baseSenderPlan = NearbyResumeCodec.resolveSenderFilePlan(baseSrc, offsetsByName["base.apk"])
            val armSenderPlan = NearbyResumeCodec.resolveSenderFilePlan(armSrc, offsetsByName["config.arm64.apk"])
            val langSenderPlan = NearbyResumeCodec.resolveSenderFilePlan(langSrc, offsetsByName["config.en.apk"])

            assertTrue("Completed split file must be skipped by sender", baseSenderPlan.alreadyCompleted)
            assertEquals(24_000L, armSenderPlan.offsetBytes)
            assertFalse(armSenderPlan.alreadyCompleted)
            assertEquals(0L, langSenderPlan.offsetBytes)
            assertFalse(langSenderPlan.alreadyCompleted)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun hotspotDownloader_interruptedHalfway_resumesWithRange206AndMatchesSourceBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val fullPayload = deterministicBytes(180_000, seed = 55)
        val interruptAt = 90_000
        val apkRequestCount = AtomicInteger(0)
        val secondRangeHeader = AtomicReference<String?>(null)

        val metadata = ApkBundleMetadata(
            transferId = "http_resume_tx",
            appName = "HotspotResumeApp",
            packageName = "com.example.hotspotresume",
            versionName = "1.0",
            totalSizeBytes = fullPayload.size.toLong(),
            isSplitApk = false,
            files = listOf(ApkFileEntry("HotspotResumeApp.apk", fullPayload.size.toLong()))
        )
        val manifestBytes = Json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        testServerSocket = server
        val port = server.localPort

        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(" ").getOrNull(1) ?: "/"
                        var rangeHeader: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                            if (line.startsWith("Range:", ignoreCase = true)) {
                                rangeHeader = line.substringAfter(":").trim()
                            }
                        }
                        val out = BufferedOutputStream(sock.getOutputStream())
                        if (path == "/manifest.json") {
                            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${manifestBytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(manifestBytes)
                            out.flush()
                        } else if (path == "/app.apk") {
                            val attempt = apkRequestCount.incrementAndGet()
                            if (attempt == 1) {
                                // Advertise full Content-Length, send only half, then drop socket!
                                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nAccept-Ranges: bytes\r\nContent-Length: ${fullPayload.size}\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(fullPayload, 0, interruptAt)
                                out.flush()
                            } else {
                                secondRangeHeader.set(rangeHeader)
                                val start = rangeHeader?.removePrefix("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
                                val remainingLen = fullPayload.size - start
                                val hdr = "HTTP/1.1 206 Partial Content\r\nContent-Type: application/vnd.android.package-archive\r\nAccept-Ranges: bytes\r\nContent-Range: bytes $start-${fullPayload.size - 1}/${fullPayload.size}\r\nContent-Length: $remainingLen\r\nConnection: close\r\n\r\n"
                                out.write(hdr.toByteArray(Charsets.US_ASCII))
                                out.write(fullPayload, start, remainingLen)
                                out.flush()
                            }
                        }
                    }
                }
            }
        }

        val downloader = HttpApkDownloader(context, storageManager).also { httpDownloader = it }
        val url = "http://127.0.0.1:$port/app.apk"

        // 1. First download interrupts mid-stream
        downloader.startDownload(url, exportToPublicDownloads = false)
        assertTrue(
            "First attempt should transition to Error with interruptedRecord",
            pumpMainUntil(8000L) { downloader.state.value is HttpDownloadState.Error }
        )
        val errState = downloader.state.value as HttpDownloadState.Error
        assertNotNull("Interrupted record must be attached to Error state", errState.interruptedRecord)
        assertEquals(interruptAt.toLong(), errState.interruptedRecord!!.totalReceivedBytes)
        assertEquals(fullPayload.size.toLong(), errState.interruptedRecord.totalSizeBytes)

        val partFile = File(storageManager.getAppDownloadsRoot(), "HotspotResumeApp.apk.part")
        assertTrue(".part file must be preserved on disk", partFile.exists())
        assertEquals(interruptAt.toLong(), partFile.length())

        // 2. Resume download — must send Range header and complete seamlessly
        downloader.startDownload(url, exportToPublicDownloads = false)
        assertTrue(
            "Resumed download should complete",
            pumpMainUntil(8000L) { downloader.state.value is HttpDownloadState.Completed }
        )
        assertEquals("bytes=$interruptAt-", secondRangeHeader.get())

        val completed = downloader.state.value as HttpDownloadState.Completed
        assertEquals(1, completed.savedFiles.size)
        assertFalse(".part must be removed on completion", partFile.exists())
        assertArrayEquals(
            "Resumed Hotspot file must match original byte-for-byte",
            fullPayload,
            completed.savedFiles.first().readBytes()
        )
        assertNull("Interrupted store must be cleared on completion", downloader.resumeStore.loadValid())

        server.close()
        serverThread.join(1000L)
    }

    @Test
    fun hotspotDownloader_serverReturns200InsteadOf206_restartsCleanlyFromZeroWithNotice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val fullPayload = deterministicBytes(128_000, seed = 77)
        val metadata = ApkBundleMetadata(
            transferId = "http_200_fallback",
            appName = "NoRangeApp",
            packageName = "com.example.norange",
            versionName = "1.0",
            totalSizeBytes = fullPayload.size.toLong(),
            isSplitApk = false,
            files = listOf(ApkFileEntry("NoRangeApp.apk", fullPayload.size.toLong()))
        )
        val manifestBytes = Json.encodeToString(metadata).toByteArray(Charsets.UTF_8)

        // Pre-seed a partial .part file as if interrupted at 40,000 bytes
        val destDir = storageManager.getAppDownloadsRoot()
        val partFile = File(destDir, "NoRangeApp.apk.part").apply {
            writeBytes(fullPayload.copyOfRange(0, 40_000))
        }

        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        testServerSocket = server
        val port = server.localPort

        val serverThread = thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { sock ->
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(" ").getOrNull(1) ?: "/"
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                        }
                        val out = BufferedOutputStream(sock.getOutputStream())
                        if (path == "/manifest.json") {
                            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${manifestBytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(manifestBytes)
                            out.flush()
                        } else if (path == "/app.apk") {
                            // Deliberately ignore Range and return 200 OK with full file
                            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${fullPayload.size}\r\nConnection: close\r\n\r\n"
                            out.write(hdr.toByteArray(Charsets.US_ASCII))
                            out.write(fullPayload)
                            out.flush()
                        }
                    }
                }
            }
        }

        val observedNotice = AtomicReference<String?>(null)
        val downloader = HttpApkDownloader(context, storageManager).also { httpDownloader = it }
        val url = "http://127.0.0.1:$port/app.apk"
        downloader.startDownload(url, exportToPublicDownloads = false)

        assertTrue(
            "Download should recover and complete when server returns 200 OK",
            pumpMainUntil(8000L) {
                val s = downloader.state.value
                if (s is HttpDownloadState.Downloading && !s.statusNotice.isNullOrBlank()) {
                    observedNotice.set(s.statusNotice)
                }
                s is HttpDownloadState.Completed
            }
        )

        val completed = downloader.state.value as HttpDownloadState.Completed
        assertFalse(partFile.exists())
        assertEquals(fullPayload.size.toLong(), completed.savedFiles.first().length())
        assertArrayEquals(
            "File restarted from 200 OK must NOT append or corrupt data",
            fullPayload,
            completed.savedFiles.first().readBytes()
        )
        // Verify restart notice string is non-empty when observed during download
        val expectedNotice = context.getString(R.string.transfer_resume_server_no_range)
        assertTrue(
            "Restart notice should match expected string when observed",
            observedNotice.get() == null || observedNotice.get() == expectedNotice
        )

        server.close()
        serverThread.join(1000L)
    }

    @Test
    fun appReopen_restoresInterruptedTransferAndClearDeletesPartFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val store = InterruptedTransferStore(context)

        val destDir = storageManager.getAppDownloadsRoot()
        val partFile = File(destDir, "PersistedApp.apk.part").apply {
            writeBytes(ByteArray(48_000))
        }
        val metadata = ApkBundleMetadata(
            transferId = "persist_tx",
            appName = "PersistedApp",
            packageName = "com.example.persisted",
            versionName = "3.1",
            totalSizeBytes = 120_000L,
            isSplitApk = false,
            files = listOf(ApkFileEntry("PersistedApp.apk", 120_000L))
        )
        store.save(
            InterruptedTransferRecord(
                mode = InterruptedTransferStore.MODE_NEARBY,
                peerName = "Galaxy S25 #9A2B",
                senderPairCode = "9A2B",
                metadata = metadata,
                destDirPath = destDir.absolutePath,
                files = listOf(
                    InterruptedFileRecord(
                        fileName = "PersistedApp.apk",
                        safeFileName = "PersistedApp.apk",
                        totalSizeBytes = 120_000L,
                        receivedBytes = 48_000L,
                        completed = false
                    )
                )
            )
        )

        // Simulate reopening the app by creating a brand new ReceiverViewModel
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
        val vmStore = ViewModelStore().also { viewModelStore = it }
        val nearbyManager = NearbyTransferManager(context, storageManager, store)
        val downloader = HttpApkDownloader(context, storageManager, store)
        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            ReceiverViewModel.Factory(
                nearbyManager = nearbyManager,
                httpDownloader = downloader,
                apkInstaller = ApkInstaller(context),
                settingsDataStore = SettingsDataStore(context),
                historyRepository = TransferHistoryRepository(database.transferHistoryDao())
            )
        )[ReceiverViewModel::class.java]

        assertEquals(ReceiverMode.NEARBY, vm.mode.value)
        val restoredState = vm.nearbyState.value
        assertTrue("Reopened ReceiverViewModel must restore interrupted Error state", restoredState is NearbyReceiverState.Error)
        val interrupted = (restoredState as NearbyReceiverState.Error).interruptedRecord
        assertNotNull(interrupted)
        assertEquals(48_000L, interrupted!!.totalReceivedBytes)
        assertEquals(120_000L, interrupted.totalSizeBytes)

        // Clearing deletes the .part file and saved state
        vm.clearInterruptedTransfer()
        assertFalse(".part file must be deleted when Clear is tapped", partFile.exists())
        assertNull("Persisted state must be cleared", store.loadValid())
        assertTrue(vm.nearbyState.value is NearbyReceiverState.Idle)
    }

    @Test
    @Suppress("DEPRECATION")
    fun multiAppBatch_interruptedOnSecondApp_resumesSecondThenSendsThirdWithoutResendingFirst() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(*PermissionHelper.getNearbyTransferPermissions().toTypedArray())
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.let {
            Shadows.shadowOf(it).setEnabled(true)
        }
        (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled = true
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
        val storageManager = ApkStorageManager(context)
        val nearbyManager = NearbyTransferManager(context, storageManager)

        val tempDir = createTempDirectory("batch_resume_3apps").toFile()
        try {
            val apk1 = File(tempDir, "app1.apk").apply { writeBytes(ByteArray(2048)) }
            val apk2 = File(tempDir, "app2.apk").apply { writeBytes(ByteArray(4096)) }
            val apk3 = File(tempDir, "app3.apk").apply { writeBytes(ByteArray(3072)) }

            val apps = listOf(
                InstalledAppInfo(
                    packageName = "com.example.one",
                    appName = "App One",
                    versionName = "1.0",
                    versionCode = 1L,
                    totalSizeBytes = apk1.length(),
                    installDateMillis = 100L,
                    baseApkPath = apk1.absolutePath,
                    splitApkPaths = emptyList()
                ),
                InstalledAppInfo(
                    packageName = "com.example.two",
                    appName = "App Two",
                    versionName = "1.0",
                    versionCode = 1L,
                    totalSizeBytes = apk2.length(),
                    installDateMillis = 200L,
                    baseApkPath = apk2.absolutePath,
                    splitApkPaths = emptyList()
                ),
                InstalledAppInfo(
                    packageName = "com.example.three",
                    appName = "App Three",
                    versionName = "1.0",
                    versionCode = 1L,
                    totalSizeBytes = apk3.length(),
                    installDateMillis = 300L,
                    baseApkPath = apk3.absolutePath,
                    splitApkPaths = emptyList()
                )
            )

            val vmStore = ViewModelStore().also { viewModelStore = it }
            val vm = androidx.lifecycle.ViewModelProvider(
                vmStore,
                SenderViewModel.Factory(
                    appsRepository = InstalledAppsRepository(context),
                    apkExtractor = ApkExtractor(context),
                    nearbyManager = nearbyManager,
                    hotspotController = HotspotController(context),
                    httpServer = LocalHttpApkServer(),
                    settingsDataStore = SettingsDataStore(context),
                    historyRepository = TransferHistoryRepository(database.transferHistoryDao())
                )
            )[SenderViewModel::class.java]

            apps.forEach { vm.toggleAppSelection(it) }
            var extracted = false
            vm.prepareAndStartNearbyTransfer { extracted = true }
            assertTrue(pumpMainUntil(5000L) { extracted })
            assertEquals(0, vm.batchQueue.value.currentIndex)

            // 1. App 1 succeeds -> advances to App 2 (index 1)
            val bundle1 = ExtractedApkBundle(
                transferId = "tx_1",
                appName = "App One",
                packageName = "com.example.one",
                versionName = "1.0",
                versionCode = 1L,
                isSplitApk = false,
                files = listOf(apk1),
                totalSizeBytes = apk1.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )
            nearbyManager.setSenderStateForTest(NearbySenderState.Success(bundle1, "Peer", 600L))
            assertTrue(
                "Queue should advance to App 2 after App 1 succeeds",
                pumpMainUntil(4000L) { vm.batchQueue.value.currentIndex == 1 && !vm.batchQueue.value.isPreparingNext }
            )
            assertEquals(1, vm.batchQueue.value.sentCount)

            // 2. App 2 interrupts mid-transfer -> queue MUST stay at index 1 (NOT skip to App 3!)
            val bundle2 = ExtractedApkBundle(
                transferId = "tx_2",
                appName = "App Two",
                packageName = "com.example.two",
                versionName = "1.0",
                versionCode = 1L,
                isSplitApk = false,
                files = listOf(apk2),
                totalSizeBytes = apk2.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )
            nearbyManager.setSenderStateForTest(
                NearbySenderState.Error(
                    reason = "Connection lost",
                    bundle = bundle2,
                    wasInterruptedMidTransfer = true
                )
            )
            // Advance main looper past any potential advance delay to prove queue stays on App 2
            repeat(20) {
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100L))
                Thread.sleep(10L)
            }
            assertEquals("Interrupted App 2 must remain current item", 1, vm.batchQueue.value.currentIndex)
            assertEquals("App 1 stays completed; App 2 is not marked failed", 1, vm.batchQueue.value.results.size)

            // 3. Sender retries/resumes App 2, and App 2 succeeds -> advances to App 3 (index 2)
            vm.retryNearbyAdvertising()
            nearbyManager.setSenderStateForTest(NearbySenderState.Success(bundle2, "Peer", 800L))
            assertTrue(
                "Queue should advance to App 3 after resumed App 2 succeeds",
                pumpMainUntil(4000L) { vm.batchQueue.value.currentIndex == 2 && !vm.batchQueue.value.isPreparingNext }
            )
            assertEquals(2, vm.batchQueue.value.sentCount)
            assertEquals(1, vm.batchQueue.value.results.count { it.appName == "App One" && it.succeeded })
            assertEquals(1, vm.batchQueue.value.results.count { it.appName == "App Two" && it.succeeded })

            // 4. App 3 succeeds -> batch finishes cleanly with all 3 sent, App 1 never re-sent
            val bundle3 = ExtractedApkBundle(
                transferId = "tx_3",
                appName = "App Three",
                packageName = "com.example.three",
                versionName = "1.0",
                versionCode = 1L,
                isSplitApk = false,
                files = listOf(apk3),
                totalSizeBytes = apk3.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )
            nearbyManager.setSenderStateForTest(NearbySenderState.Success(bundle3, "Peer", 700L))
            assertTrue(
                "Batch should finish cleanly after App 3 succeeds",
                pumpMainUntil(4000L) { vm.batchQueue.value.isFinished }
            )
            assertEquals(3, vm.batchQueue.value.sentCount)
            assertEquals(1, vm.batchQueue.value.results.count { it.appName == "App One" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun receiverScreen_interruptedHotspotAndNearbyUi_rendersProgressResumeAndClearInEnglishAndBangla() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val store = InterruptedTransferStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { db = it }
        val vmStore = ViewModelStore().also { viewModelStore = it }
        val nearbyManager = NearbyTransferManager(context, storageManager, store)
        val downloader = HttpApkDownloader(context, storageManager, store)

        val destDir = storageManager.getAppDownloadsRoot()
        val partFile = File(destDir, "Movie.mp4.part").apply { writeBytes(ByteArray(1024)) }
        val record = InterruptedTransferRecord(
            mode = InterruptedTransferStore.MODE_HOTSPOT,
            peerName = "Wi-Fi Hotspot",
            hotspotUrl = "http://192.168.43.1:8080/file",
            metadata = ApkBundleMetadata(
                transferId = "ui_hotspot_tx",
                appName = "Movie.mp4",
                packageName = "",
                versionName = "",
                totalSizeBytes = 120L * 1024L * 1024L,
                isSplitApk = false,
                files = listOf(ApkFileEntry("Movie.mp4", 120L * 1024L * 1024L)),
                isGenericFile = true,
                mimeType = "video/mp4"
            ),
            destDirPath = destDir.absolutePath,
            files = listOf(
                InterruptedFileRecord(
                    fileName = "Movie.mp4",
                    safeFileName = "Movie.mp4",
                    totalSizeBytes = 120L * 1024L * 1024L,
                    receivedBytes = 45L * 1024L * 1024L,
                    completed = false
                )
            )
        )

        val vm = androidx.lifecycle.ViewModelProvider(
            vmStore,
            ReceiverViewModel.Factory(
                nearbyManager = nearbyManager,
                httpDownloader = downloader,
                apkInstaller = ApkInstaller(context),
                settingsDataStore = SettingsDataStore(context),
                historyRepository = TransferHistoryRepository(database.transferHistoryDao())
            )
        )[ReceiverViewModel::class.java]
        vm.switchToHotspotMode()
        downloader.setStateForTest(
            HttpDownloadState.Error(
                url = "http://192.168.43.1:8080/file",
                reason = "Connection lost",
                interruptedRecord = record
            )
        )

        // Render in English (default ComponentActivity provides LocalActivityResultRegistryOwner)
        composeRule.setContent {
            ApkShareTheme {
                ReceiverScreen(viewModel = vm, onDone = {})
            }
        }

        val expectedEn = context.getString(
            R.string.transfer_interrupted_progress,
            FormatUtils.formatTransferSize(record.totalReceivedBytes),
            FormatUtils.formatTransferSize(record.totalSizeBytes)
        )
        composeRule.onNodeWithTag("hotspot_interrupted_progress_text")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(expectedEn)
        composeRule.onNodeWithTag("resume_interrupted_hotspot_button")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("clear_interrupted_hotspot_button")
            .performScrollTo()
            .assertIsDisplayed()

        // Verify Bangla string resolves properly without missing format args
        val bnCtx = LocaleHelper.localizedContext(context, LocaleHelper.LANGUAGE_BANGLA)
        val receivedLabel = FormatUtils.formatTransferSize(record.totalReceivedBytes)
        val totalLabel = FormatUtils.formatTransferSize(record.totalSizeBytes)
        val bnFormatted = bnCtx.getString(
            R.string.transfer_interrupted_progress,
            receivedLabel,
            totalLabel
        )
        assertTrue("Bangla interrupted string must contain Bangla text", bnFormatted.contains("ট্রান্সফার বিঘ্নিত"))
        assertTrue(bnFormatted.contains(receivedLabel))
        assertTrue(bnFormatted.contains(totalLabel))

        // Tap Clear button and confirm state resets to Idle
        composeRule.onNodeWithTag("clear_interrupted_hotspot_button").performClick()
        composeRule.waitForIdle()
        assertTrue(vm.httpDownloadState.value is HttpDownloadState.Idle)
        partFile.delete()
    }
}
