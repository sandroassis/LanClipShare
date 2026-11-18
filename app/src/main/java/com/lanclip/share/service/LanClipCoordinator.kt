package com.lanclip.share.service

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lanclip.share.model.InstalledAppInfo
import com.lanclip.share.model.PeerDevice
import com.lanclip.share.network.HybridDiscoveryManager
import com.lanclip.share.network.LanTransferEngine
import com.lanclip.share.network.NetworkUtils
import com.lanclip.share.util.ClipboardHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

/**
 * Coordenador central (Singleton) que mantém a descoberta na rede local, o servidor TCP de recebimento
 * e as configurações sincronizadas entre a Activity, o ForegroundService e o QuickSettingsTile.
 * Todas as chamadas de rede/disco rodam em Dispatchers.IO para nunca travar a UI.
 */
class LanClipCoordinator private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("lanclip_prefs", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceId: String = prefs.getString("device_id", null) ?: run {
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        generated
    }

    private val _customDeviceName = MutableStateFlow(
        prefs.getString("device_name", null) ?: NetworkUtils.getDefaultDeviceName()
    )
    val customDeviceName: StateFlow<String> = _customDeviceName.asStateFlow()

    private val _autoCopyReceivedClipboard = MutableStateFlow(
        prefs.getBoolean("auto_copy_received", true)
    )
    val autoCopyReceivedClipboard: StateFlow<Boolean> = _autoCopyReceivedClipboard.asStateFlow()

    private val _autoSendClipboardOnFocus = MutableStateFlow(
        prefs.getBoolean("auto_send_on_focus", false)
    )
    val autoSendClipboardOnFocus: StateFlow<Boolean> = _autoSendClipboardOnFocus.asStateFlow()

    private val _localIpAddress = MutableStateFlow("")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _selectedPeerId = MutableStateFlow<String?>(null)
    val selectedPeerId: StateFlow<String?> = _selectedPeerId.asStateFlow()

    private var lastSyncedClipboardText: String? = null
    @Volatile
    private var engineStarted = false

    val discoveryManager = HybridDiscoveryManager(
        context = appContext,
        scope = scope,
        localDeviceProvider = { getLocalPeerDeviceSnapshot() }
    )

    val transferEngine = LanTransferEngine(
        context = appContext,
        scope = scope,
        localDeviceProvider = { getLocalPeerDeviceSnapshot() },
        onClipboardReceived = { text, senderName ->
            lastSyncedClipboardText = text
            if (_autoCopyReceivedClipboard.value) {
                mainHandler.post {
                    ClipboardHelper.copyToSystemClipboard(appContext, text)
                    Toast.makeText(
                        appContext,
                        "📋 Área de transferência recebida de $senderName!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                showToastOnMain("📋 Novo texto recebido de $senderName")
            }
        },
        onPeerContacted = { id, name, brand, ip, port ->
            discoveryManager.onPeerSeenFromTransfer(id, name, brand, ip, port)
        }
    )

    init {
        scope.launch(Dispatchers.IO) {
            _localIpAddress.value = NetworkUtils.getLocalIpv4Address(appContext).orEmpty()
            restoreSavedManualPeers()
        }
    }

    fun startEngine() {
        scope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getLocalIpv4Address(appContext).orEmpty()
            _localIpAddress.value = ip
            if (!engineStarted) {
                engineStarted = true
                transferEngine.startServer(NetworkUtils.DEFAULT_TCP_PORT)
                discoveryManager.start()
            } else {
                discoveryManager.triggerImmediatePing()
            }
        }
    }

    fun stopEngine() {
        engineStarted = false
        scope.launch(Dispatchers.IO) {
            discoveryManager.stop()
            transferEngine.stopServer()
        }
    }

    fun refreshLocalIp() {
        scope.launch(Dispatchers.IO) {
            val ip = NetworkUtils.getLocalIpv4Address(appContext).orEmpty()
            _localIpAddress.value = ip
            discoveryManager.triggerImmediatePing()
        }
    }

    /**
     * Leitura rápida em memória sem realizar chamadas bloqueantes de NetworkInterface na UI thread.
     */
    fun getLocalPeerDeviceSnapshot(): PeerDevice {
        return PeerDevice(
            id = deviceId,
            name = _customDeviceName.value,
            brand = NetworkUtils.getDeviceBrand(),
            ipAddress = _localIpAddress.value,
            port = NetworkUtils.DEFAULT_TCP_PORT
        )
    }

    fun updateDeviceName(newName: String) {
        val trimmed = newName.trim().ifEmpty { NetworkUtils.getDefaultDeviceName() }
        _customDeviceName.value = trimmed
        prefs.edit().putString("device_name", trimmed).apply()
        discoveryManager.triggerImmediatePing()
    }

    fun setAutoCopyReceived(enabled: Boolean) {
        _autoCopyReceivedClipboard.value = enabled
        prefs.edit().putBoolean("auto_copy_received", enabled).apply()
    }

    fun setAutoSendOnFocus(enabled: Boolean) {
        _autoSendClipboardOnFocus.value = enabled
        prefs.edit().putBoolean("auto_send_on_focus", enabled).apply()
    }

    fun selectPeer(peerId: String?) {
        _selectedPeerId.value = peerId
    }

    fun resolveTargetPeers(explicitPeer: PeerDevice? = null): List<PeerDevice> {
        if (explicitPeer != null) return listOf(explicitPeer)
        val peers = discoveryManager.peersFlow.value
        val selectedId = _selectedPeerId.value
        if (selectedId != null) {
            peers.firstOrNull { it.id == selectedId }?.let { return listOf(it) }
        }
        val favorites = peers.filter { it.isFavorite }
        if (favorites.isNotEmpty()) return favorites
        return peers.take(1)
    }

    fun addManualOrQrPeer(rawInput: String): PeerDevice? {
        val parsed = NetworkUtils.parsePairingString(rawInput) ?: return null
        discoveryManager.addOrUpdateManualPeer(parsed)
        _selectedPeerId.value = parsed.id
        saveManualPeers()
        return parsed
    }

    fun toggleFavoritePeer(peerId: String) {
        discoveryManager.toggleFavorite(peerId)
        saveManualPeers()
    }

    fun sendClipboardToTargets(
        text: String,
        explicitPeer: PeerDevice? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val targets = resolveTargetPeers(explicitPeer)
        if (targets.isEmpty()) {
            showToastOnMain("Nenhum dispositivo conectado. Adicione o IP/QR Code na aba Dispositivos ou aguarde a busca Wi-Fi.")
            onComplete?.invoke(false)
            return
        }
        lastSyncedClipboardText = text
        scope.launch(Dispatchers.IO) {
            var anySuccess = false
            for (target in targets) {
                val res = transferEngine.sendClipboardText(target, text)
                if (res.isSuccess) anySuccess = true
            }
            if (anySuccess) {
                showToastOnMain("📋 Área de transferência enviada para ${targets.joinToString { it.name }}")
            } else {
                showToastOnMain("⚠️ Falha ao conectar em ${targets.first().ipAddress}")
            }
            mainHandler.post { onComplete?.invoke(anySuccess) }
        }
    }

    fun onAppFocusGainedCheckClipboard(): String? {
        val currentText = ClipboardHelper.readCurrentClipboardText(appContext)
        if (!currentText.isNullOrBlank() &&
            _autoSendClipboardOnFocus.value &&
            currentText != lastSyncedClipboardText &&
            discoveryManager.peersFlow.value.isNotEmpty()
        ) {
            sendClipboardToTargets(currentText)
        }
        return currentText
    }

    fun sendFilesToTargets(uris: List<Uri>, explicitPeer: PeerDevice? = null) {
        val targets = resolveTargetPeers(explicitPeer)
        if (targets.isEmpty()) {
            showToastOnMain("Selecione ou conecte seu outro dispositivo antes de enviar arquivos.")
            return
        }
        scope.launch(Dispatchers.IO) {
            for (target in targets) {
                for (uri in uris) {
                    transferEngine.sendUriFile(target, uri)
                }
            }
        }
    }

    fun sendApkToTargets(appInfo: InstalledAppInfo, explicitPeer: PeerDevice? = null) {
        val targets = resolveTargetPeers(explicitPeer)
        if (targets.isEmpty()) {
            showToastOnMain("Conecte o outro dispositivo na aba Dispositivos antes de enviar o APK.")
            return
        }
        scope.launch(Dispatchers.IO) {
            for (target in targets) {
                showToastOnMain("📦 Enviando ${appInfo.appName}.apk para ${target.name}...")
                transferEngine.sendInstalledApk(target, appInfo)
            }
        }
    }

    private fun saveManualPeers() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val saved = discoveryManager.peersFlow.value.filter { it.isFavorite || it.isManualOrQr }
                val arr = JSONArray()
                saved.forEach { arr.put(it.toJson()) }
                prefs.edit().putString("saved_peers", arr.toString()).apply()
            }
        }
    }

    private fun restoreSavedManualPeers() {
        runCatching {
            val raw = prefs.getString("saved_peers", null) ?: return
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val peer = PeerDevice.fromJson(obj).copy(isManualOrQr = true, isFavorite = true)
                discoveryManager.addOrUpdateManualPeer(peer)
            }
        }
    }

    private fun showToastOnMain(message: String) {
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        @Volatile
        private var instance: LanClipCoordinator? = null

        fun getInstance(context: Context): LanClipCoordinator {
            return instance ?: synchronized(this) {
                instance ?: LanClipCoordinator(context.applicationContext).also { instance = it }
            }
        }
    }
}
