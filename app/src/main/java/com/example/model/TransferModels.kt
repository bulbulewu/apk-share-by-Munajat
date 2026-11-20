package com.example.model

enum class TransferDirection {
    SENT,
    RECEIVED
}

enum class TransferMethod {
    NEARBY,
    HOTSPOT_HTTP
}

enum class TransferStatus {
    SUCCESS,
    FAILED,
    CANCELLED
}

data class TransferProgressState(
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val currentFileName: String = "",
    val completedFiles: Int = 0,
    val totalFiles: Int = 1,
    val elapsedMillis: Long = 0L
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

data class DiscoveredSenderDevice(
    val endpointId: String,
    val deviceName: String
)

data class PendingConnectionInfo(
    val endpointId: String,
    val deviceName: String,
    val authDigits: String,
    val isIncoming: Boolean
)
