package com.lanclip.share.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.lanclip.share.model.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ApkExtractorHelper {

    /**
     * Lista os aplicativos instalados de forma leve (sem carregar centenas de Bitmaps de ícones na RAM de uma vez).
     */
    suspend fun loadInstalledApps(
        context: Context,
        includeSystemApps: Boolean = false
    ): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }

            val result = mutableListOf<InstalledAppInfo>()
            for (pkg in packages) {
                val appInfo = pkg.applicationInfo ?: continue
                val sourceDir = appInfo.sourceDir ?: continue
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                if (!includeSystemApps && isSystem) continue

                val apkFile = File(sourceDir)
                if (!apkFile.exists()) continue

                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(pkg.packageName)

                result.add(
                    InstalledAppInfo(
                        packageName = pkg.packageName,
                        appName = label,
                        versionName = pkg.versionName ?: "1.0",
                        apkSourcePath = sourceDir,
                        apkSizeBytes = apkFile.length(),
                        isSystemApp = isSystem,
                        icon = null
                    )
                )
            }
            result.sortedBy { it.appName.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * Carrega o ícone de um único app sob demanda quando ele aparece na lista.
     */
    suspend fun loadAppIconBitmap(context: Context, packageName: String): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96)
            }.getOrNull()
        }

    /**
     * Abre qualquer arquivo recebido (Foto, Planilha Excel/CSV, PDF) ou inicia o instalador de pacotes se for .APK.
     */
    fun openReceivedFile(context: Context, filePath: String, mimeType: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Arquivo não encontrado: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val resolvedMime = when {
                file.name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
                mimeType.isNotBlank() && mimeType != "*/*" -> mimeType
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Abrir ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { err ->
            Toast.makeText(
                context,
                "Não foi possível abrir o arquivo: ${err.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Compartilha um arquivo recebido com outros apps do sistema.
     */
    fun shareReceivedFile(context: Context, filePath: String, mimeType: String) {
        val file = File(filePath)
        if (!file.exists()) return
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartilhar ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
