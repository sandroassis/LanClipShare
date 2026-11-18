package com.lanclip.share.model

import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONObject

enum class TransferContentType {
    CLIPBOARD_TEXT,
    FILE,
    IMAGE,
    SPREADSHEET,
    APK
}

enum class TransferDirection {
    SENT,
    RECEIVED
}

enum class TransferStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

data class PeerDevice(
    val id: String,
    val name: String,
    val brand: String,
    val ipAddress: String,
    val port: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
    val isManualOrQr: Boolean = false,
    val isFavorite: Boolean = false
) {
    val displayTitle: String
        get() = if (brand.isNotBlank() && !name.contains(brand, ignoreCase = true)) {
            "$brand • $name"
        } else {
            name
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("brand", brand)
        put("ipAddress", ipAddress)
        put("port", port)
    }

    companion object {
        fun fromJson(json: JSONObject, fallbackIp: String? = null): PeerDevice {
            val ip = fallbackIp?.takeIf { it.isNotBlank() } ?: json.optString("ipAddress")
            return PeerDevice(
                id = json.optString("id", ip),
                name = json.optString("name", "Dispositivo Android"),
                brand = json.optString("brand", ""),
                ipAddress = ip,
                port = json.optInt("port", 45455)
            )
        }
    }
}

data class TransferHeader(
    val transferId: String,
    val contentType: TransferContentType,
    val fileName: String,
    val mimeType: String,
    val payloadSize: Long,
    val textContent: String?,
    val senderId: String,
    val senderName: String,
    val senderBrand: String,
    val senderPort: Int
) {
    fun toJsonString(): String = JSONObject().apply {
        put("transferId", transferId)
        put("contentType", contentType.name)
        put("fileName", fileName)
        put("mimeType", mimeType)
        put("payloadSize", payloadSize)
        if (textContent != null) put("textContent", textContent)
        put("senderId", senderId)
        put("senderName", senderName)
        put("senderBrand", senderBrand)
        put("senderPort", senderPort)
    }.toString()

    companion object {
        fun fromJsonString(raw: String): TransferHeader {
            val json = JSONObject(raw)
            val typeStr = json.optString("contentType", TransferContentType.FILE.name)
            val contentType = runCatching { TransferContentType.valueOf(typeStr) }
                .getOrDefault(TransferContentType.FILE)
            return TransferHeader(
                transferId = json.optString("transferId", System.currentTimeMillis().toString()),
                contentType = contentType,
                fileName = json.optString("fileName", "arquivo"),
                mimeType = json.optString("mimeType", "*/*"),
                payloadSize = json.optLong("payloadSize", 0L),
                textContent = if (json.has("textContent")) json.optString("textContent") else null,
                senderId = json.optString("senderId", ""),
                senderName = json.optString("senderName", "Dispositivo"),
                senderBrand = json.optString("senderBrand", ""),
                senderPort = json.optInt("senderPort", 45455)
            )
        }
    }
}

data class TransferItem(
    val id: String,
    val contentType: TransferContentType,
    val title: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedMbps: Double = 0.0,
    val status: TransferStatus,
    val direction: TransferDirection,
    val peerName: String,
    val textContent: String? = null,
    val savedFilePath: String? = null,
    val sourceUri: Uri? = null,
    val errorMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes <= 0L) {
            if (status == TransferStatus.COMPLETED) 1f else 0f
        } else {
            (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val apkSourcePath: String,
    val apkSizeBytes: Long,
    val isSystemApp: Boolean,
    val icon: Drawable? = null
) {
    val exportFileName: String
        get() {
            val safeName = appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val safeVer = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return "${safeName}_v${safeVer}.apk"
        }
}
