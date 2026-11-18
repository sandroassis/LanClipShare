package com.lanclip.share.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.lanclip.share.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Gerenciador híbrido de descoberta na mesma rede:
 * 1. UDP Broadcast periódico (porta 45454) -> Funciona mesmo quando mDNS é bloqueado entre Xiaomi e Motorola.
 * 2. Android NSD (mDNS / Zeroconf) -> Descoberta de serviço padrão _lanclip._tcp.
 * 3. Adição direta por IP manual ou QR Code (com ping UDP/TCP de verificação).
 */
class HybridDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localDeviceProvider: () -> PeerDevice
) {
    private val peersMap = ConcurrentHashMap<String, PeerDevice>()
    private val _peersFlow = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peersFlow: StateFlow<List<PeerDevice>> = _peersFlow.asStateFlow()

    private var udpListenerJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var cleanupJob: Job? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        acquireMulticastLock()
        startUdpListener()
        startUdpBroadcastBeacon()
        startNsdDiscovery()
        startStalePeerCleanup()
    }

    fun stop() {
        udpListenerJob?.cancel()
        udpBeaconJob?.cancel()
        cleanupJob?.cancel()
        stopNsdDiscovery()
        releaseMulticastLock()
    }

    /**
     * Dispara um anúncio imediato na rede e opcionalmente para um IP específico (útil após ler QR Code ou digitar IP).
     */
    fun triggerImmediatePing(targetIp: String? = null) {
        scope.launch(Dispatchers.IO) {
            sendUdpBeaconPacket(targetIp)
        }
    }

    /**
     * Registra manualmente um dispositivo (via QR Code ou IP digitado) e envia um pacote de apresentação para ele.
     */
    fun addOrUpdateManualPeer(peer: PeerDevice) {
        val local = localDeviceProvider()
        if (peer.id == local.id || (peer.ipAddress == local.ipAddress && local.ipAddress.isNotBlank())) {
            return
        }
        val existing = peersMap[peer.id] ?: peersMap.values.firstOrNull { it.ipAddress == peer.ipAddress }
        val merged = peer.copy(
            id = existing?.id ?: peer.id,
            lastSeenMs = System.currentTimeMillis(),
            isManualOrQr = true,
            isFavorite = true
        )
        peersMap[merged.id] = merged
        publishPeers()
        triggerImmediatePing(merged.ipAddress)
    }

    fun toggleFavorite(peerId: String) {
        val current = peersMap[peerId] ?: return
        peersMap[peerId] = current.copy(isFavorite = !current.isFavorite)
        publishPeers()
    }

    fun onPeerSeenFromTransfer(senderId: String, senderName: String, senderBrand: String, senderIp: String, senderPort: Int) {
        val local = localDeviceProvider()
        if (senderId == local.id || senderIp.isBlank()) return
        val key = senderId.ifBlank { "$senderIp:$senderPort" }
        val existing = peersMap[key]
        peersMap[key] = PeerDevice(
            id = key,
            name = senderName.ifBlank { existing?.name ?: "Dispositivo ($senderIp)" },
            brand = senderBrand.ifBlank { existing?.brand.orEmpty() },
            ipAddress = senderIp,
            port = if (senderPort > 0) senderPort else NetworkUtils.DEFAULT_TCP_PORT,
            lastSeenMs = System.currentTimeMillis(),
            isManualOrQr = existing?.isManualOrQr ?: false,
            isFavorite = existing?.isFavorite ?: false
        )
        publishPeers()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (multicastLock == null) {
                multicastLock = wifi?.createMulticastLock("LanClipShareMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun startUdpListener() {
        udpListenerJob?.cancel()
        udpListenerJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(NetworkUtils.DEFAULT_UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val senderIp = packet.address?.hostAddress.orEmpty()
                    handleIncomingBeacon(raw, senderIp)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun startUdpBroadcastBeacon() {
        udpBeaconJob?.cancel()
        udpBeaconJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendUdpBeaconPacket(targetIp = null)
                // Também envia unicast para dispositivos adicionados manualmente ou favoritos
                peersMap.values.filter { it.isManualOrQr || it.isFavorite }.forEach { peer ->
                    sendUdpBeaconPacket(targetIp = peer.ipAddress)
                }
                delay(3500L)
            }
        }
    }

    private fun sendUdpBeaconPacket(targetIp: String?) {
        val local = localDeviceProvider()
        val payload = JSONObject().apply {
            put("type", "LANCLIP_BEACON")
            put("id", local.id)
            put("name", local.name)
            put("brand", local.brand)
            put("ipAddress", local.ipAddress)
            put("port", local.port)
        }.toString().toByteArray(Charsets.UTF_8)

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply { broadcast = true }
            val targets = if (!targetIp.isNullOrBlank()) {
                listOf(targetIp)
            } else {
                NetworkUtils.getBroadcastAddresses()
            }
            for (addrStr in targets) {
                runCatching {
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(addrStr),
                        NetworkUtils.DEFAULT_UDP_DISCOVERY_PORT
                    )
                    socket.send(packet)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket?.close() }
        }
    }

    private fun handleIncomingBeacon(raw: String, packetIp: String) {
        runCatching {
            val json = JSONObject(raw)
            if (json.optString("type") != "LANCLIP_BEACON") return
            val peer = PeerDevice.fromJson(json, fallbackIp = packetIp)
            val local = localDeviceProvider()
            if (peer.id == local.id || peer.ipAddress.isBlank()) return

            // Remove entrada temporária de IP manual se o mesmo IP respondeu com ID real
            peersMap.entries.removeIf { (k, v) ->
                k != peer.id && v.ipAddress == peer.ipAddress && k == "${v.ipAddress}:${v.port}"
            }

            val existing = peersMap[peer.id]
            peersMap[peer.id] = peer.copy(
                lastSeenMs = System.currentTimeMillis(),
                isManualOrQr = existing?.isManualOrQr ?: false,
                isFavorite = existing?.isFavorite ?: false
            )
            publishPeers()
        }
    }

    private fun startNsdDiscovery() {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = manager
            val local = localDeviceProvider()

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "LanClip_${local.brand}_${local.id.takeLast(4)}"
                serviceType = NetworkUtils.NSD_SERVICE_TYPE
                port = local.port
            }

            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo?) {}
                override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceUnregistered(info: NsdServiceInfo?) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
            }
            registrationListener = regListener
            runCatching {
                manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
            }

            val discListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {}
                override fun onServiceFound(service: NsdServiceInfo?) {
                    val s = service ?: return
                    if (!s.serviceType.contains("_lanclip")) return
                    if (s.serviceName.endsWith(local.id.takeLast(4))) return
                    runCatching {
                        manager.resolveService(s, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                val hostIp = resolved?.host?.hostAddress ?: return
                                triggerImmediatePing(hostIp)
                            }
                        })
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            }
            discoveryListener = discListener
            runCatching {
                manager.discoverServices(NetworkUtils.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopNsdDiscovery() {
        val manager = nsdManager ?: return
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
    }

    private fun startStalePeerCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(5000L)
                val now = System.currentTimeMillis()
                // Mantém dispositivos favoritos/manuais e remove os descobertos que sumiram há mais de 25s
                val removed = peersMap.entries.removeIf { (_, peer) ->
                    !peer.isFavorite && !peer.isManualOrQr && (now - peer.lastSeenMs > 25_000L)
                }
                if (removed) {
                    publishPeers()
                }
            }
        }
    }

    private fun publishPeers() {
        val sorted = peersMap.values.sortedWith(
            compareByDescending<PeerDevice> { it.isFavorite }
                .thenByDescending { it.lastSeenMs }
        )
        _peersFlow.value = sorted
    }
}
