package com.melanta.core.automation.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Ponte entre o grafo do Hilt e a instância do [AgentAccessibilityService], que é criada pelo sistema.
 * Nulo enquanto o serviço estiver desativado ou desconectado.
 */
@Singleton
class AccessibilityBridge @Inject constructor() {

    private val _service = MutableStateFlow<AgentAccessibilityService?>(null)
    val service: StateFlow<AgentAccessibilityService?> = _service.asStateFlow()

    val isConnected: Flow<Boolean> = service.map { it != null }.distinctUntilChanged()

    private val _killRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Pedidos de parada vindos do hardware (Volume− três vezes). Quem roda o agente coleta. */
    val killRequests: SharedFlow<Unit> = _killRequests.asSharedFlow()

    internal fun requestKill() {
        _killRequests.tryEmit(Unit)
    }

    internal fun attach(service: AgentAccessibilityService) {
        _service.value = service
    }

    internal fun detach(service: AgentAccessibilityService) {
        _service.compareAndSet(service, null)
    }
}
