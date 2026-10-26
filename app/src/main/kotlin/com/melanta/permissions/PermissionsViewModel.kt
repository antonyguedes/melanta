package com.melanta.permissions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.automation.service.AccessibilityBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val checker: PermissionChecker,
    bridge: AccessibilityBridge,
) : ViewModel() {

    private val snapshot = MutableStateFlow(checker.snapshot())

    val state: StateFlow<PermissionSnapshot> =
        combine(snapshot, bridge.isConnected) { current, connected ->
            current.copy(accessibilityConnected = connected)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshot.value)

    /** Chamado em ON_RESUME: o usuário volta das Configurações sem callback de resultado. */
    fun refresh() {
        snapshot.value = checker.snapshot()
    }
}
