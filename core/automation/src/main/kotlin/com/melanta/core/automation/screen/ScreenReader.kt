package com.melanta.core.automation.screen

import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import com.melanta.core.automation.service.AccessibilityBridge
import com.melanta.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AccessibilityUnavailableException :
    IllegalStateException("O serviço de acessibilidade do Melanta não está conectado")

/** Tira um [ScreenSnapshot] das janelas atuais via [AgentAccessibilityService][com.melanta.core.automation.service.AgentAccessibilityService]. */
class ScreenReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: AccessibilityBridge,
    private val parser: ScreenParser,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun snapshot(): ScreenSnapshot = withContext(dispatchers.default) {
        withTimeout(READ_TIMEOUT_MS) {
            val service = bridge.service.value ?: throw AccessibilityUnavailableException()
            val windows = service.windows.mapNotNull(::toUiWindow).ifEmpty {
                // Sem flagRetrieveInteractiveWindows efetivo, cai para a janela ativa.
                listOfNotNull(
                    service.rootInActiveWindow?.let { root ->
                        val node = AccessibilityUiNode(root)
                        UiWindow(WindowKind.Application, 0, root.packageName?.toString(), null, node.bounds, node)
                    },
                )
            }
            parser.parse(windows, context.packageName, screenBounds())
        }
    }

    private fun toUiWindow(window: AccessibilityWindowInfo): UiWindow? {
        val root = window.root ?: return null
        val rect = Rect().also(window::getBoundsInScreen)
        val kind = when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.Application
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.System
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.InputMethod
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.Overlay
            else -> WindowKind.Other
        }
        return UiWindow(
            kind = kind,
            layer = window.layer,
            packageName = root.packageName?.toString(),
            title = window.title?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            root = AccessibilityUiNode(root),
        )
    }

    private fun screenBounds(): Bounds {
        val rect = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return Bounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private companion object {
        const val READ_TIMEOUT_MS = 2_000L
    }
}
