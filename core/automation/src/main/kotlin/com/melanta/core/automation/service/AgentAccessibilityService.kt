package com.melanta.core.automation.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Serviço que o sistema cria quando o usuário ativa o Melanta em Acessibilidade.
 * Registra a conexão na [AccessibilityBridge], repassa eventos ao [UiIdleWatcher] e detecta o
 * kill switch físico (Volume− três vezes):
 * leitura e ação ficam no `ScreenReader` e no `ActionExecutor`.
 */
@AndroidEntryPoint
class AgentAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var bridge: AccessibilityBridge

    @Inject
    lateinit var idleWatcher: UiIdleWatcher

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Eventos do próprio app (Playground, overlay) não indicam mudança na tela operada.
        if (event != null && event.packageName != packageName) idleWatcher.onEvent()
    }

    override fun onInterrupt() = Unit

    private val volumeDownPresses = TriplePressDetector()

    /** Só observa: retornar `false` deixa o volume funcionar normalmente para o usuário. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (volumeDownPresses.onPress(event.eventTime)) bridge.requestKill()
        }
        return false
    }

    override fun onUnbind(intent: Intent?): Boolean {
        bridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach(this)
        super.onDestroy()
    }
}
