package com.lanclip.share.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanclip.share.model.InstalledAppInfo
import com.lanclip.share.model.PeerDevice
import com.lanclip.share.service.LanClipCoordinator
import com.lanclip.share.service.LanClipService
import com.lanclip.share.util.ApkExtractorHelper
import com.lanclip.share.util.ClipboardHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val coordinator = LanClipCoordinator.getInstance(appContext)

    val peersFlow = coordinator.discoveryManager.peersFlow
    val transfersFlow = coordinator.transferEngine.transfersFlow
    val selectedPeerId = coordinator.selectedPeerId
    val localIpAddress = coordinator.localIpAddress
    val customDeviceName = coordinator.customDeviceName
    val autoCopyReceived = coordinator.autoCopyReceivedClipboard
    val autoSendOnFocus = coordinator.autoSendClipboardOnFocus

    private val _currentClipboardText = MutableStateFlow("")
    val currentClipboardText: StateFlow<String> = _currentClipboardText.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _includeSystemApps = MutableStateFlow(false)
    val includeSystemApps: StateFlow<Boolean> = _includeSystemApps.asStateFlow()

    private val _backgroundServiceEnabled = MutableStateFlow(true)
    val backgroundServiceEnabled: StateFlow<Boolean> = _backgroundServiceEnabled.asStateFlow()

    private var hasInitializedEngine = false
    private var hasLoadedAppsOnce = false

    fun ensureStartedAfterUiReady() {
        if (hasInitializedEngine) return
        hasInitializedEngine = true
        viewModelScope.launch(Dispatchers.IO) {
            coordinator.startEngine()
            LanClipService.start(appContext)
        }
    }

    fun ensureAppsLoaded() {
        if (hasLoadedAppsOnce) return
        hasLoadedAppsOnce = true
        loadInstalledApplications(false)
    }

    fun refreshClipboardFromSystem(triggerAutoSendIfEnabled: Boolean = false) {
        val text = if (triggerAutoSendIfEnabled) {
            coordinator.onAppFocusGainedCheckClipboard()
        } else {
            ClipboardHelper.readCurrentClipboardText(appContext)
        }
        if (!text.isNullOrBlank()) {
            _currentClipboardText.value = text
        }
    }

    fun updateDraftClipboardText(newText: String) {
        _currentClipboardText.value = newText
    }

    fun sendCurrentClipboard(explicitPeer: PeerDevice? = null) {
        val text = _currentClipboardText.value.trim()
        if (text.isEmpty()) return
        ClipboardHelper.copyToSystemClipboard(appContext, text)
        coordinator.sendClipboardToTargets(text, explicitPeer)
    }

    fun sendSelectedFiles(uris: List<Uri>, explicitPeer: PeerDevice? = null) {
        if (uris.isEmpty()) return
        coordinator.sendFilesToTargets(uris, explicitPeer)
    }

    fun sendInstalledApk(appInfo: InstalledAppInfo, explicitPeer: PeerDevice? = null) {
        coordinator.sendApkToTargets(appInfo, explicitPeer)
    }

    fun loadInstalledApplications(includeSystem: Boolean = _includeSystemApps.value) {
        _includeSystemApps.value = includeSystem
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            _installedApps.value = ApkExtractorHelper.loadInstalledApps(
                context = appContext,
                includeSystemApps = includeSystem
            )
            _isLoadingApps.value = false
        }
    }

    fun addManualIpOrQr(rawInput: String): PeerDevice? {
        return coordinator.addManualOrQrPeer(rawInput)
    }

    fun selectTargetPeer(peerId: String?) {
        coordinator.selectPeer(peerId)
    }

    fun toggleFavoritePeer(peerId: String) {
        coordinator.toggleFavoritePeer(peerId)
    }

    fun refreshNetworkDiscovery() {
        coordinator.refreshLocalIp()
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        _backgroundServiceEnabled.value = enabled
        if (enabled) {
            LanClipService.start(appContext)
        } else {
            LanClipService.stop(appContext)
        }
    }

    fun clearHistory() {
        coordinator.transferEngine.clearHistory()
    }
}
