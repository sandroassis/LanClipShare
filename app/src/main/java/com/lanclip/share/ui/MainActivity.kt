package com.lanclip.share.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanclip.share.model.InstalledAppInfo
import com.lanclip.share.model.PeerDevice
import com.lanclip.share.model.TransferContentType
import com.lanclip.share.model.TransferDirection
import com.lanclip.share.model.TransferItem
import com.lanclip.share.model.TransferStatus
import com.lanclip.share.network.NetworkUtils
import com.lanclip.share.util.ApkExtractorHelper
import com.lanclip.share.util.ClipboardHelper
import com.lanclip.share.util.QrCodeHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val darkScheme = darkColorScheme(
                primary = Color(0xFF38BDF8),
                onPrimary = Color(0xFF062032),
                primaryContainer = Color(0xFF0C4A6E),
                onPrimaryContainer = Color(0xFFE0F2FE),
                secondary = Color(0xFF4ADE80),
                secondaryContainer = Color(0xFF14532D),
                background = Color(0xFF0B1120),
                surface = Color(0xFF111827),
                surfaceVariant = Color(0xFF1E293B)
            )
            MaterialTheme(colorScheme = darkScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LanClipShareScreen(
                        viewModel = viewModel,
                        onRequestInitialPermissions = { requestRuntimePermissionsIfNeeded() }
                    )
                }
            }
        }

        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            runCatching {
                viewModel.refreshClipboardFromSystem(triggerAutoSendIfEnabled = true)
            }
        }
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null) return
        runCatching {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val sharedStream: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    if (sharedStream != null) {
                        viewModel.sendSelectedFiles(listOf(sharedStream))
                    } else if (!sharedText.isNullOrBlank()) {
                        viewModel.updateDraftClipboardText(sharedText)
                        viewModel.sendCurrentClipboard()
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    }
                    if (uris.isNotEmpty()) {
                        viewModel.sendSelectedFiles(uris)
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        runCatching {
            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), 900)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanClipShareScreen(
    viewModel: MainViewModel,
    onRequestInitialPermissions: () -> Unit
) {
    val context = LocalContext.current
    val peers by viewModel.peersFlow.collectAsState()
    val transfers by viewModel.transfersFlow.collectAsState()
    val selectedPeerId by viewModel.selectedPeerId.collectAsState()
    val localIp by viewModel.localIpAddress.collectAsState()
    val deviceName by viewModel.customDeviceName.collectAsState()
    val clipboardText by viewModel.currentClipboardText.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isLoadingApps by viewModel.isLoadingApps.collectAsState()
    val includeSystemApps by viewModel.includeSystemApps.collectAsState()
    val autoCopyReceived by viewModel.autoCopyReceived.collectAsState()
    val autoSendOnFocus by viewModel.autoSendOnFocus.collectAsState()
    val bgServiceEnabled by viewModel.backgroundServiceEnabled.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showQrModal by remember { mutableStateOf(false) }
    var showManualIpModal by remember { mutableStateOf(false) }

    // Inicializa rede e permissões após o primeiro frame da interface estar visível
    LaunchedEffect(Unit) {
        viewModel.ensureStartedAfterUiReady()
        onRequestInitialPermissions()
    }

    // Carrega a lista de APKs apenas quando o usuário abre a aba de APKs
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1) {
            viewModel.ensureAppsLoaded()
        }
    }

    val multipleFilesPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendSelectedFiles(uris)
        }
    }

    val photosPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendSelectedFiles(uris)
        }
    }

    val activeTargetPeer = remember(peers, selectedPeerId) {
        peers.firstOrNull { it.id == selectedPeerId }
            ?: peers.firstOrNull { it.isFavorite }
            ?: peers.firstOrNull()
    }

    val localDeviceSnapshot = remember(deviceName, localIp) {
        viewModel.coordinator.getLocalPeerDeviceSnapshot()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LanClip Share",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (localIp.isNotBlank()) {
                                "$deviceName • IP: $localIp"
                            } else {
                                "$deviceName • Conectando ao Wi-Fi..."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showQrModal = true }) {
                        Icon(Icons.Default.QrCode2, contentDescription = "Meu QR Code")
                    }
                    IconButton(onClick = { showManualIpModal = true }) {
                        Icon(Icons.Default.AddLink, contentDescription = "Conectar por IP")
                    }
                    IconButton(onClick = { viewModel.refreshNetworkDiscovery() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar Rede")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.ContentPaste, contentDescription = null) },
                    label = { Text("Transferir") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Android, contentDescription = null) },
                    label = { Text("Enviar APK") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        BadgedBox(badge = {}) {
                            Icon(Icons.Default.Devices, contentDescription = null)
                        }
                    },
                    label = { Text("Dispositivos (${peers.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Ajustes") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PeerSelectorStrip(
                peers = peers,
                activePeer = activeTargetPeer,
                onSelectPeer = { viewModel.selectTargetPeer(it.id) },
                onOpenManualDialog = { showManualIpModal = true },
                onScanQr = {
                    QrCodeHelper.startQrCodeScanner(
                        context = context,
                        onScanned = { raw ->
                            val added = viewModel.addManualIpOrQr(raw)
                            if (added != null) {
                                Toast.makeText(
                                    context,
                                    "Conectado a ${added.displayTitle} (${added.ipAddress})",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(context, "QR Code inválido: $raw", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = { showManualIpModal = true }
                    )
                }
            )

            when (selectedTab) {
                0 -> ClipboardAndFilesTab(
                    clipboardText = clipboardText,
                    activePeer = activeTargetPeer,
                    transfers = transfers,
                    onClipboardChange = { viewModel.updateDraftClipboardText(it) },
                    onReadSystemClipboard = { viewModel.refreshClipboardFromSystem(false) },
                    onSendClipboard = { viewModel.sendCurrentClipboard() },
                    onPickAnyFiles = { multipleFilesPicker.launch(arrayOf("*/*")) },
                    onPickPhotos = {
                        photosPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    onPickSpreadsheets = {
                        multipleFilesPicker.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                                "text/csv",
                                "application/pdf",
                                "*/*"
                            )
                        )
                    },
                    onClearHistory = { viewModel.clearHistory() },
                    onSwitchToApkTab = { selectedTab = 1 }
                )
                1 -> InstalledApksTab(
                    apps = installedApps,
                    isLoading = isLoadingApps,
                    includeSystem = includeSystemApps,
                    activePeer = activeTargetPeer,
                    onToggleSystemApps = { viewModel.loadInstalledApplications(it) },
                    onSendApk = { app -> viewModel.sendInstalledApk(app) }
                )
                2 -> DevicesAndPairingTab(
                    peers = peers,
                    activePeer = activeTargetPeer,
                    localDevice = localDeviceSnapshot,
                    onSelectPeer = { viewModel.selectTargetPeer(it.id) },
                    onToggleFavorite = { viewModel.toggleFavoritePeer(it.id) },
                    onShowQrModal = { showQrModal = true },
                    onShowManualIpModal = { showManualIpModal = true },
                    onScanQrCode = {
                        QrCodeHelper.startQrCodeScanner(
                            context = context,
                            onScanned = { raw -> viewModel.addManualIpOrQr(raw) },
                            onError = { showManualIpModal = true }
                        )
                    }
                )
                3 -> SettingsTab(
                    deviceName = deviceName,
                    autoCopyReceived = autoCopyReceived,
                    autoSendOnFocus = autoSendOnFocus,
                    bgServiceEnabled = bgServiceEnabled,
                    onUpdateDeviceName = { viewModel.coordinator.updateDeviceName(it) },
                    onToggleAutoCopy = { viewModel.coordinator.setAutoCopyReceived(it) },
                    onToggleAutoSendFocus = { viewModel.coordinator.setAutoSendOnFocus(it) },
                    onToggleBgService = { viewModel.setBackgroundServiceEnabled(it) }
                )
            }
        }
    }

    if (showQrModal) {
        MyQrCodeDialog(
            localDevice = localDeviceSnapshot,
            onDismiss = { showQrModal = false }
        )
    }

    if (showManualIpModal) {
        ManualIpConnectDialog(
            onDismiss = { showManualIpModal = false },
            onConnect = { ipOrUri ->
                val added = viewModel.addManualIpOrQr(ipOrUri)
                if (added != null) {
                    Toast.makeText(
                        context,
                        "Dispositivo ${added.ipAddress} adicionado!",
                        Toast.LENGTH_SHORT
                    ).show()
                    showManualIpModal = false
                } else {
                    Toast.makeText(
                        context,
                        "Digite um IP válido (ex: 192.168.1.15)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}

@Composable
private fun PeerSelectorStrip(
    peers: List<PeerDevice>,
    activePeer: PeerDevice?,
    onSelectPeer: (PeerDevice) -> Unit,
    onOpenManualDialog: () -> Unit,
    onScanQr: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = if (peers.isNotEmpty()) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))

            if (peers.isEmpty()) {
                Text(
                    text = "Procurando tablet/celular no Wi-Fi...",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                AssistChip(
                    onClick = onScanQr,
                    label = { Text("QR Code") },
                    leadingIcon = {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                AssistChip(
                    onClick = onOpenManualDialog,
                    label = { Text("IP Manual") }
                )
            } else {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(peers, key = { it.id }) { peer ->
                        val isSelected = activePeer?.id == peer.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectPeer(peer) },
                            label = {
                                Text(
                                    text = "${peer.displayTitle} (${peer.ipAddress})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (peer.isFavorite) Icons.Default.Star else Icons.Default.PhonelinkRing,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardAndFilesTab(
    clipboardText: String,
    activePeer: PeerDevice?,
    transfers: List<TransferItem>,
    onClipboardChange: (String) -> Unit,
    onReadSystemClipboard: () -> Unit,
    onSendClipboard: () -> Unit,
    onPickAnyFiles: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickSpreadsheets: () -> Unit,
    onClearHistory: () -> Unit,
    onSwitchToApkTab: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Área de Transferência (Clipboard)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = onReadSystemClipboard) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ler Atual")
                        }
                    }

                    OutlinedTextField(
                        value = clipboardText,
                        onValueChange = onClipboardChange,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        placeholder = {
                            Text("Cole ou digite qualquer texto, link, código ou nota para enviar instantaneamente...")
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSendClipboard,
                            modifier = Modifier.weight(1f),
                            enabled = clipboardText.isNotBlank()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (activePeer != null) {
                                    "Enviar para ${activePeer.name}"
                                } else {
                                    "Enviar Clipboard"
                                }
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                ClipboardHelper.copyToSystemClipboard(context, clipboardText)
                                Toast.makeText(context, "Copiado!", Toast.LENGTH_SHORT).show()
                            },
                            enabled = clipboardText.isNotBlank()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copiar")
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Enviar Arquivos na Velocidade do Wi-Fi",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onPickPhotos,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fotos/Vídeos")
                        }
                        FilledTonalButton(
                            onClick = onPickSpreadsheets,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Planilhas/PDF")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onPickAnyFiles,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Qualquer Arquivo")
                        }
                        OutlinedButton(
                            onClick = onSwitchToApkTab,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enviar App (APK)")
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Histórico de Transferências (${transfers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (transfers.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) {
                        Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Limpar")
                    }
                }
            }
        }

        if (transfers.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhuma transferência ainda.\nCopie um texto ou escolha arquivos para enviar entre o Xiaomi e o Motorola.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            items(transfers, key = { it.id }) { item ->
                TransferHistoryCard(item = item)
            }
        }
    }
}

@Composable
private fun TransferHistoryCard(item: TransferItem) {
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = when (item.contentType) {
                    TransferContentType.CLIPBOARD_TEXT -> Icons.Default.ContentPaste
                    TransferContentType.IMAGE -> Icons.Default.Image
                    TransferContentType.SPREADSHEET -> Icons.Default.TableChart
                    TransferContentType.APK -> Icons.Default.Android
                    TransferContentType.FILE -> Icons.Default.Description
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val dirLabel = if (item.direction == TransferDirection.SENT) "Enviado para" else "Recebido de"
                    val sizeLabel = NetworkUtils.formatBytes(item.totalBytes)
                    val speedLabel = if (item.speedMbps > 0.05) {
                        String.format(Locale.getDefault(), " • %.1f MB/s", item.speedMbps)
                    } else ""
                    Text(
                        text = "$dirLabel ${item.peerName} • $sizeLabel$speedLabel • ${timeFmt.format(Date(item.timestampMs))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                when (item.status) {
                    TransferStatus.IN_PROGRESS -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                    TransferStatus.COMPLETED -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Concluído",
                        tint = Color(0xFF4ADE80)
                    )
                    TransferStatus.FAILED -> Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Falhou",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            AnimatedVisibility(visible = item.status == TransferStatus.IN_PROGRESS) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "${(item.progressFraction * 100).toInt()}% (${NetworkUtils.formatBytes(item.transferredBytes)} / ${NetworkUtils.formatBytes(item.totalBytes)})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (item.errorMessage != null) {
                Text(
                    text = item.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (item.status == TransferStatus.COMPLETED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.contentType == TransferContentType.CLIPBOARD_TEXT && !item.textContent.isNullOrBlank()) {
                        FilledTonalButton(
                            onClick = {
                                ClipboardHelper.copyToSystemClipboard(context, item.textContent)
                                Toast.makeText(context, "Texto copiado!", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copiar Texto")
                        }
                    } else if (!item.savedFilePath.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                ApkExtractorHelper.shareReceivedFile(context, item.savedFilePath, item.mimeType)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Compartilhar")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                ApkExtractorHelper.openReceivedFile(context, item.savedFilePath, item.mimeType)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (item.contentType == TransferContentType.APK) "Instalar APK" else "Abrir Arquivo"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledApksTab(
    apps: List<InstalledAppInfo>,
    isLoading: Boolean,
    includeSystem: Boolean,
    activePeer: PeerDevice?,
    onToggleSystemApps: (Boolean) -> Unit,
    onSendApk: (InstalledAppInfo) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Enviar Aplicativo Inteiro (.APK)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Escolha qualquer app instalado neste aparelho para enviar o instalador .APK diretamente para o outro dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Buscar aplicativo pelo nome...") }
            )
            FilterChip(
                selected = includeSystem,
                onClick = { onToggleSystemApps(!includeSystem) },
                label = { Text("Apps Sistema") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Carregando aplicativos instalados...")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val iconBitmap by produceState<Bitmap?>(initialValue = null, key1 = app.packageName) {
                        value = ApkExtractorHelper.loadAppIconBitmap(context, app.packageName)
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bmp = iconBitmap
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = app.appName,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Android,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "v${app.versionName} • ${NetworkUtils.formatBytes(app.apkSizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }

                            Button(onClick = { onSendApk(app) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (activePeer != null) "Enviar APK" else "Enviar")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevicesAndPairingTab(
    peers: List<PeerDevice>,
    activePeer: PeerDevice?,
    localDevice: PeerDevice,
    onSelectPeer: (PeerDevice) -> Unit,
    onToggleFavorite: (PeerDevice) -> Unit,
    onShowQrModal: () -> Unit,
    onShowManualIpModal: () -> Unit,
    onScanQrCode: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Este Dispositivo (${localDevice.displayTitle})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "IP na Rede Wi-Fi: ${localDevice.ipAddress.ifBlank { "Detectando..." }}  •  Porta TCP: ${localDevice.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onShowQrModal,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Meu QR Code")
                        }
                        FilledTonalButton(
                            onClick = onScanQrCode,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ler QR Code")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onShowManualIpModal,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Digitar IP Manualmente (Fallback)")
                    }
                }
            }
        }

        item {
            Text(
                text = "Dispositivos Encontrados na Rede (${peers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (peers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Nenhum dispositivo detectado ainda",
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "1. Certifique-se de que o tablet Xiaomi e o celular Motorola estão no mesmo Wi-Fi.\n" +
                                "2. A descoberta automática usa mDNS + Broadcast UDP.\n" +
                                "3. Caso seu roteador tenha isolamento de clientes AP, toque em 'Meu QR Code' em um aparelho e 'Ler QR Code' (ou 'Digitar IP') no outro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        } else {
            items(peers, key = { it.id }) { peer ->
                val isSelected = activePeer?.id == peer.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectPeer(peer) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = peer.displayTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "IP: ${peer.ipAddress}:${peer.port}" +
                                    if (peer.isManualOrQr) " • Fixado (QR/IP)" else " • Auto Wi-Fi",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(onClick = { onToggleFavorite(peer) }) {
                            Icon(
                                imageVector = if (peer.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Favoritar",
                                tint = if (peer.isFavorite) Color(0xFFFBBF24) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(
    deviceName: String,
    autoCopyReceived: Boolean,
    autoSendOnFocus: Boolean,
    bgServiceEnabled: Boolean,
    onUpdateDeviceName: (String) -> Unit,
    onToggleAutoCopy: (Boolean) -> Unit,
    onToggleAutoSendFocus: (Boolean) -> Unit,
    onToggleBgService: (Boolean) -> Unit
) {
    var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Identificação na Rede",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = {
                            nameDraft = it
                            onUpdateDeviceName(it)
                        },
                        label = { Text("Nome do Dispositivo (ex: Tablet Xiaomi / Motorola)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Comportamento da Área de Transferência",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-copiar textos recebidos", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Quando o outro aparelho enviar um texto, ele já entra direto no Ctrl+V deste dispositivo.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(checked = autoCopyReceived, onCheckedChange = onToggleAutoCopy)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-enviar ao abrir o app", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Envia automaticamente o texto recém-copiado assim que você traz o LanClip Share para a tela.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(checked = autoSendOnFocus, onCheckedChange = onToggleAutoSendFocus)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Manter serviço ativo em 2º plano", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Exibe notificação discreta para receber arquivos e APKs a qualquer momento sem fechar no HyperOS/MIUI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Switch(checked = bgServiceEnabled, onCheckedChange = onToggleBgService)
                    }
                }
            }
        }
    }
}

@Composable
private fun MyQrCodeDialog(
    localDevice: PeerDevice,
    onDismiss: () -> Unit
) {
    val qrUri = remember(localDevice) { NetworkUtils.buildQrPairingUri(localDevice) }
    val qrBitmap: Bitmap? = remember(qrUri) { QrCodeHelper.generateQrCodeBitmap(qrUri, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parear via QR Code ou IP") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code de Conexão",
                        modifier = Modifier
                            .size(230.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = localDevice.displayTitle,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IP: ${localDevice.ipAddress.ifBlank { "Sem Wi-Fi" }}:${localDevice.port}",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Aponte a câmera pelo botão 'Ler QR Code' no outro dispositivo ou digite o IP acima.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}

@Composable
private fun ManualIpConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit
) {
    var ipInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conectar por IP ou URI") },
        text = {
            Column {
                Text(
                    text = "Digite o endereço IP local exibido no topo do outro aparelho (ex: 192.168.1.15):",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = ipInput,
                    onValueChange = { ipInput = it },
                    placeholder = { Text("192.168.1.15") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConnect(ipInput) }) {
                Text("Conectar e Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
