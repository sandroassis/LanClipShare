package com.lanclip.share.network

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.lanclip.share.model.InstalledAppInfo
import com.lanclip.share.model.PeerDevice
import com.lanclip.share.model.TransferContentType
import com.lanclip.share.model.TransferDirection
import com.lanclip.share.model.TransferHeader
import com.lanclip.share.model.TransferItem
import com.lanclip.share.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Motor de transferência TCP direto na rede local:
 * - Sem limite de tamanho de arquivo (faz streaming com buffer de 64 KB).
 * - Transfere Textos da Área de Transferência, Fotos, Planilhas, PDFs, ZIPs e Aplicativos inteiros (.APK).
 * - Salva arquivos recebidos em Downloads/LanClipShare para fácil acesso no Xiaomi e no Motorola.
 */
class LanTransferEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localDeviceProvider: () -> PeerDevice,
    private val onClipboardReceived: (text: String, senderName: String) -> Unit,
    private val onPeerContacted: (id: String, name: String, brand: String, ip: String, port: Int) -> Unit
) {
    private val _transfersFlow = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfersFlow: StateFlow<List<TransferItem>> = _transfersFlow.asStateFlow()

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    fun startServer(port: Int = NetworkUtils.DEFAULT_TCP_PORT) {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val srv = ServerSocket()
                srv.reuseAddress = true
                srv.bind(InetSocketAddress(port))
                serverSocket = srv
                while (isActive) {
                    val client = srv.accept()
                    launch(Dispatchers.IO) {
                        handleIncomingConnection(client)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverSocket?.close() }
            }
        }
    }

    fun stopServer() {
        serverJob?.cancel()
        runCatching { serverSocket?.close() }
    }

    /**
     * Envia texto da área de transferência instantaneamente para o dispositivo alvo.
     */
    suspend fun sendClipboardText(target: PeerDevice, text: String): Result<TransferItem> =
        withContext(Dispatchers.IO) {
            val local = localDeviceProvider()
            val bytes = text.toByteArray(Charsets.UTF_8)
            val transferId = UUID.randomUUID().toString()
            val header = TransferHeader(
                transferId = transferId,
                contentType = TransferContentType.CLIPBOARD_TEXT,
                fileName = "Clipboard.txt",
                mimeType = "text/plain",
                payloadSize = bytes.size.toLong(),
                textContent = text,
                senderId = local.id,
                senderName = local.name,
                senderBrand = local.brand,
                senderPort = local.port
            )

            val initialItem = TransferItem(
                id = transferId,
                contentType = TransferContentType.CLIPBOARD_TEXT,
                title = text.take(80).replace("\n", " "),
                mimeType = "text/plain",
                totalBytes = bytes.size.toLong(),
                transferredBytes = 0L,
                status = TransferStatus.IN_PROGRESS,
                direction = TransferDirection.SENT,
                peerName = target.displayTitle,
                textContent = text
            )
            upsertTransfer(initialItem)

            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(target.ipAddress, target.port), 5000)
                    val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val headerBytes = header.toJsonString().toByteArray(Charsets.UTF_8)
                    out.writeInt(headerBytes.size)
                    out.write(headerBytes)
                    out.flush()
                }
                val completed = initialItem.copy(
                    transferredBytes = bytes.size.toLong(),
                    status = TransferStatus.COMPLETED
                )
                upsertTransfer(completed)
                completed
            }.onFailure { err ->
                upsertTransfer(
                    initialItem.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = err.localizedMessage ?: "Falha na conexão com ${target.ipAddress}"
                    )
                )
            }
        }

    /**
     * Envia qualquer arquivo selecionado ou recebido via Share Sheet (foto, planilha, PDF, vídeo, etc.).
     */
    suspend fun sendUriFile(target: PeerDevice, uri: Uri): Result<TransferItem> =
        withContext(Dispatchers.IO) {
            val local = localDeviceProvider()
            val meta = queryUriMetadata(uri)
            val contentType = NetworkUtils.classifyContentType(meta.fileName, meta.mimeType)
            val transferId = UUID.randomUUID().toString()

            val header = TransferHeader(
                transferId = transferId,
                contentType = contentType,
                fileName = meta.fileName,
                mimeType = meta.mimeType,
                payloadSize = meta.sizeBytes,
                textContent = null,
                senderId = local.id,
                senderName = local.name,
                senderBrand = local.brand,
                senderPort = local.port
            )

            val initialItem = TransferItem(
                id = transferId,
                contentType = contentType,
                title = meta.fileName,
                mimeType = meta.mimeType,
                totalBytes = meta.sizeBytes,
                transferredBytes = 0L,
                status = TransferStatus.IN_PROGRESS,
                direction = TransferDirection.SENT,
                peerName = target.displayTitle,
                sourceUri = uri
            )
            upsertTransfer(initialItem)

            runCatching {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado")
                inputStream.use { rawIn ->
                    streamPayloadToPeer(target, header, initialItem, BufferedInputStream(rawIn))
                }
            }.onFailure { err ->
                upsertTransfer(
                    initialItem.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = err.localizedMessage ?: "Erro ao enviar arquivo"
                    )
                )
            }
        }

    /**
     * Extrai e envia o arquivo .APK completo de um aplicativo instalado diretamente para o outro dispositivo.
     */
    suspend fun sendInstalledApk(target: PeerDevice, appInfo: InstalledAppInfo): Result<TransferItem> =
        withContext(Dispatchers.IO) {
            val local = localDeviceProvider()
            val apkFile = File(appInfo.apkSourcePath)
            val sizeBytes = apkFile.length()
            val transferId = UUID.randomUUID().toString()

            val header = TransferHeader(
                transferId = transferId,
                contentType = TransferContentType.APK,
                fileName = appInfo.exportFileName,
                mimeType = "application/vnd.android.package-archive",
                payloadSize = sizeBytes,
                textContent = null,
                senderId = local.id,
                senderName = local.name,
                senderBrand = local.brand,
                senderPort = local.port
            )

            val initialItem = TransferItem(
                id = transferId,
                contentType = TransferContentType.APK,
                title = "${appInfo.appName} (${appInfo.versionName}).apk",
                mimeType = "application/vnd.android.package-archive",
                totalBytes = sizeBytes,
                transferredBytes = 0L,
                status = TransferStatus.IN_PROGRESS,
                direction = TransferDirection.SENT,
                peerName = target.displayTitle,
                savedFilePath = apkFile.absolutePath
            )
            upsertTransfer(initialItem)

            runCatching {
                FileInputStream(apkFile).use { fis ->
                    streamPayloadToPeer(target, header, initialItem, BufferedInputStream(fis))
                }
            }.onFailure { err ->
                upsertTransfer(
                    initialItem.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = err.localizedMessage ?: "Erro ao enviar APK"
                    )
                )
            }
        }

    private fun streamPayloadToPeer(
        target: PeerDevice,
        header: TransferHeader,
        initialItem: TransferItem,
        inputStream: BufferedInputStream
    ): TransferItem {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(target.ipAddress, target.port), 6000)
            socket.sendBufferSize = 64 * 1024
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))

            val headerBytes = header.toJsonString().toByteArray(Charsets.UTF_8)
            out.writeInt(headerBytes.size)
            out.write(headerBytes)

            val buffer = ByteArray(64 * 1024)
            var totalSent = 0L
            val startTime = System.currentTimeMillis()
            var lastUiUpdate = startTime

            while (true) {
                val read = inputStream.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
                totalSent += read

                val now = System.currentTimeMillis()
                if (now - lastUiUpdate >= 150L) {
                    val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                    val speedMbps = (totalSent / (1024.0 * 1024.0)) / elapsedSec
                    upsertTransfer(
                        initialItem.copy(
                            transferredBytes = totalSent,
                            speedMbps = speedMbps
                        )
                    )
                    lastUiUpdate = now
                }
            }
            out.flush()

            val totalElapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
            val avgSpeed = (totalSent / (1024.0 * 1024.0)) / totalElapsedSec
            val completed = initialItem.copy(
                transferredBytes = totalSent,
                totalBytes = if (initialItem.totalBytes > 0) initialItem.totalBytes else totalSent,
                speedMbps = avgSpeed,
                status = TransferStatus.COMPLETED
            )
            upsertTransfer(completed)
            return completed
        }
    }

    private fun handleIncomingConnection(socket: Socket) {
        socket.use { client ->
            client.receiveBufferSize = 64 * 1024
            val remoteIp = client.inetAddress?.hostAddress.orEmpty()
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 64 * 1024))

            val headerSize = input.readInt()
            if (headerSize <= 0 || headerSize > 1024 * 1024) return
            val headerBytes = ByteArray(headerSize)
            input.readFully(headerBytes)
            val header = TransferHeader.fromJsonString(String(headerBytes, Charsets.UTF_8))

            onPeerContacted(
                header.senderId,
                header.senderName,
                header.senderBrand,
                remoteIp,
                header.senderPort
            )

            val senderLabel = if (header.senderBrand.isNotBlank() &&
                !header.senderName.contains(header.senderBrand, ignoreCase = true)
            ) {
                "${header.senderBrand} • ${header.senderName}"
            } else {
                header.senderName
            }

            if (header.contentType == TransferContentType.CLIPBOARD_TEXT) {
                val text = header.textContent.orEmpty()
                val item = TransferItem(
                    id = header.transferId,
                    contentType = TransferContentType.CLIPBOARD_TEXT,
                    title = text.take(80).replace("\n", " "),
                    mimeType = "text/plain",
                    totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                    transferredBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                    status = TransferStatus.COMPLETED,
                    direction = TransferDirection.RECEIVED,
                    peerName = senderLabel,
                    textContent = text
                )
                upsertTransfer(item)
                onClipboardReceived(text, senderLabel)
                return
            }

            // Recebimento de Arquivo / Foto / Planilha / APK
            val targetDir = getOutputDirectory()
            val destFile = createUniqueFile(targetDir, header.fileName)
            val initialItem = TransferItem(
                id = header.transferId,
                contentType = header.contentType,
                title = destFile.name,
                mimeType = header.mimeType,
                totalBytes = header.payloadSize,
                transferredBytes = 0L,
                status = TransferStatus.IN_PROGRESS,
                direction = TransferDirection.RECEIVED,
                peerName = senderLabel,
                savedFilePath = destFile.absolutePath
            )
            upsertTransfer(initialItem)

            try {
                BufferedOutputStream(FileOutputStream(destFile), 64 * 1024).use { fileOut ->
                    val buffer = ByteArray(64 * 1024)
                    var totalReceived = 0L
                    val startTime = System.currentTimeMillis()
                    var lastUiUpdate = startTime

                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fileOut.write(buffer, 0, read)
                        totalReceived += read

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 150L) {
                            val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                            val speedMbps = (totalReceived / (1024.0 * 1024.0)) / elapsedSec
                            upsertTransfer(
                                initialItem.copy(
                                    transferredBytes = totalReceived,
                                    speedMbps = speedMbps
                                )
                            )
                            lastUiUpdate = now
                        }
                    }
                    fileOut.flush()

                    val totalElapsedSec = ((System.currentTimeMillis() - startTime) / 1000.0).coerceAtLeast(0.1)
                    val avgSpeed = (totalReceived / (1024.0 * 1024.0)) / totalElapsedSec
                    upsertTransfer(
                        initialItem.copy(
                            transferredBytes = totalReceived,
                            totalBytes = if (header.payloadSize > 0) header.payloadSize else totalReceived,
                            speedMbps = avgSpeed,
                            status = TransferStatus.COMPLETED
                        )
                    )
                }
            } catch (e: Exception) {
                upsertTransfer(
                    initialItem.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "Falha ao receber arquivo"
                    )
                )
            }
        }
    }

    private fun getOutputDirectory(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(publicDownloads, "LanClipShare")
        if (dir.exists() || dir.mkdirs()) {
            return dir
        }
        val appExternal = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "LanClipShare")
        appExternal.mkdirs()
        return appExternal
    }

    private fun createUniqueFile(dir: File, originalName: String): File {
        val sanitized = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "arquivo_recebido" }
        var candidate = File(dir, sanitized)
        if (!candidate.exists()) return candidate

        val dotIndex = sanitized.lastIndexOf('.')
        val base = if (dotIndex > 0) sanitized.substring(0, dotIndex) else sanitized
        val ext = if (dotIndex > 0) sanitized.substring(dotIndex) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_($counter)$ext")
            counter++
        }
        return candidate
    }

    private data class UriMetadata(val fileName: String, val sizeBytes: Long, val mimeType: String)

    private fun queryUriMetadata(uri: Uri): UriMetadata {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "*/*"
        var name = "arquivo_${System.currentTimeMillis()}"
        var size = 0L

        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            name = cursor.getString(nameIdx)
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
        }
        return UriMetadata(fileName = name, sizeBytes = size, mimeType = mimeType)
    }

    fun clearHistory() {
        _transfersFlow.value = emptyList()
    }

    private fun upsertTransfer(item: TransferItem) {
        _transfersFlow.update { current ->
            val index = current.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                current.toMutableList().apply { set(index, item) }
            } else {
                listOf(item) + current
            }
        }
    }
}
