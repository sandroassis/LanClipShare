package com.lanclip.share.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.lanclip.share.service.LanClipCoordinator
import com.lanclip.share.util.ClipboardHelper

/**
 * Activity transparente ultra-rápida invocada pelo Tile das Configurações Rápidas (Quick Settings)
 * ou pelo botão "Enviar Clipboard" da notificação.
 *
 * Por que ela existe?
 * No Android 10 (API 29) ou superior, o sistema operacional bloqueia a leitura do Clipboard
 * por serviços em segundo plano sem foco de janela. Ao abrir esta Activity transparente por ~150ms,
 * o app ganha foco de janela (`onWindowFocusChanged(true)`), lê o texto copiado e o envia
 * imediatamente para o outro dispositivo na rede Wi-Fi!
 */
class QuickSendActivity : ComponentActivity() {

    private var hasHandledFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanClipCoordinator.getInstance(this).startEngine()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasHandledFocus) {
            hasHandledFocus = true
            val clipText = ClipboardHelper.readCurrentClipboardText(this)
            if (clipText.isNullOrBlank()) {
                Toast.makeText(
                    this,
                    "A área de transferência está vazia.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                overridePendingTransition(0, 0)
            } else {
                LanClipCoordinator.getInstance(this).sendClipboardToTargets(clipText) {
                    finish()
                    overridePendingTransition(0, 0)
                }
            }
        }
    }
}
