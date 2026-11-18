package com.lanclip.share.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.os.Build
import com.lanclip.share.model.PeerDevice
import com.lanclip.share.model.TransferContentType
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.util.Locale

object NetworkUtils {

    const val DEFAULT_TCP_PORT = 45455
    const val DEFAULT_UDP_DISCOVERY_PORT = 45454
    const val NSD_SERVICE_TYPE = "_lanclip._tcp."

    fun getDeviceBrand(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        return manufacturer.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    fun getDefaultDeviceName(): String {
        val brand = getDeviceBrand()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(brand, ignoreCase = true)) {
            model
        } else {
            "$brand $model".trim()
        }
    }

    /**
     * Obtém o endereço IPv4 local da rede Wi-Fi / LAN (compatível com Xiaomi HyperOS/MIUI e Motorola).
     */
    fun getLocalIpv4Address(context: Context): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val linkProps: LinkProperties? = activeNetwork?.let { cm.getLinkProperties(it) }
            linkProps?.linkAddresses?.forEach { linkAddr ->
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress
                }
            }
        } catch (_: Exception) {
        }

        // Fallback inspecionando interfaces wlan0 / eth0 / ap0
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val prioritized = interfaces.sortedBy { iface ->
                val name = iface.name.lowercase(Locale.ROOT)
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("eth") -> 2
                    else -> 3
                }
            }
            for (iface in prioritized) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Retorna os endereços de broadcast da sub-rede local para descoberta UDP rápida.
     */
    fun getBroadcastAddresses(): List<String> {
        val addresses = mutableSetOf("255.255.255.255")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia: InterfaceAddress in iface.interfaceAddresses) {
                    val broadcast = ia.broadcast
                    if (broadcast is Inet4Address) {
                        broadcast.hostAddress?.let { addresses.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return addresses.toList()
    }

    /**
     * Gera o payload URI para conexão via QR Code.
     * Formato: lanclip://connect?ip=192.168.1.15&port=45455&id=xxx&name=Xiaomi+Pad&brand=Xiaomi
     */
    fun buildQrPairingUri(localDevice: PeerDevice): String {
        return Uri.Builder()
            .scheme("lanclip")
            .authority("connect")
            .appendQueryParameter("ip", localDevice.ipAddress)
            .appendQueryParameter("port", localDevice.port.toString())
            .appendQueryParameter("id", localDevice.id)
            .appendQueryParameter("name", localDevice.name)
            .appendQueryParameter("brand", localDevice.brand)
            .build()
            .toString()
    }

    /**
     * Interpreta tanto um URI lanclip://connect?... quanto um IP puro ("192.168.0.22" ou "192.168.0.22:45455").
     */
    fun parsePairingString(raw: String): PeerDevice? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("lanclip://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(trimmed)
                val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
                if (ip.isEmpty()) return null
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: DEFAULT_TCP_PORT
                val id = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: "$ip:$port"
                val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: "Dispositivo ($ip)"
                val brand = uri.getQueryParameter("brand").orEmpty()
                PeerDevice(
                    id = id,
                    name = name,
                    brand = brand,
                    ipAddress = ip,
                    port = port,
                    isManualOrQr = true,
                    isFavorite = true
                )
            }.getOrNull()
        }

        // Suporte a IP puro ou IP:PORTA
        val parts = trimmed.split(":")
        val ip = parts[0].trim()
        val ipv4Regex = Regex("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.?\\b){4}$")
        if (!ipv4Regex.matches(ip)) return null
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_TCP_PORT
        return PeerDevice(
            id = "$ip:$port",
            name = "Dispositivo ($ip)",
            brand = "LAN",
            ipAddress = ip,
            port = port,
            isManualOrQr = true,
            isFavorite = true
        )
    }

    fun classifyContentType(fileName: String, mimeType: String): TransferContentType {
        val lowerName = fileName.lowercase(Locale.ROOT)
        val lowerMime = mimeType.lowercase(Locale.ROOT)
        return when {
            lowerName.endsWith(".apk") || lowerMime == "application/vnd.android.package-archive" ->
                TransferContentType.APK
            lowerMime.startsWith("image/") ||
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") ->
                TransferContentType.IMAGE
            lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".ods") ||
                lowerMime.contains("spreadsheet") || lowerMime.contains("excel") || lowerMime.contains("csv") ->
                TransferContentType.SPREADSHEET
            else -> TransferContentType.FILE
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }
}
