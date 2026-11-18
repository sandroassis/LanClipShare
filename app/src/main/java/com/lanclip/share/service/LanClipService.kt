package com.lanclip.share.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lanclip.share.R
import com.lanclip.share.ui.MainActivity
import com.lanclip.share.ui.QuickSendActivity

/**
 * Serviço em primeiro plano (Foreground Service) que mantém o Xiaomi e o Motorola
 * escutando transferências de área de transferência e arquivos na rede Wi-Fi mesmo em segundo plano.
 */
class LanClipService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val coordinator = LanClipCoordinator.getInstance(this)
        coordinator.startEngine()

        runCatching {
            val notification = buildNotification(coordinator.localIpAddress.value)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // Se o sistema operacional restringir o início de FGS em segundo plano, mantém o serviço sem crashar
            runCatching { stopSelf() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(ipAddress: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val quickSendIntent = Intent(this, QuickSendActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val quickSendPending = PendingIntent.getActivity(
            this,
            101,
            quickSendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LanClipService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (ipAddress.isNotBlank()) {
            "Ativo na rede Wi-Fi ($ipAddress) • Pronto para receber"
        } else {
            "Ativo na rede local • Pronto para receber"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentTitle("LanClip Share Ativo")
            .setContentText(statusText)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .addAction(R.drawable.ic_qs_tile, "Enviar Clipboard", quickSendPending)
            .addAction(0, "Desativar", stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sincronização na Rede Local",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o dispositivo visível para receber arquivos e textos na rede Wi-Fi"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "lanclip_background_channel"
        private const val NOTIFICATION_ID = 4545
        const val ACTION_STOP_SERVICE = "com.lanclip.share.STOP_SERVICE"

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, LanClipService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, LanClipService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            }
        }
    }
}
