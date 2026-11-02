package com.melanta.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melanta.core.ai.cloud.ApiKeyStore
import com.melanta.core.ai.cloud.CloudAccess
import com.melanta.core.ai.cloud.CloudConfig
import com.melanta.core.ai.cloud.CloudException
import com.melanta.core.ai.cloud.CloudGenerator
import com.melanta.core.ai.cloud.CloudProvider
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferencePreferences
import com.melanta.core.ai.cloud.InferenceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val millis: Long) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

data class EngineSettingsUiState(
    val preferences: InferencePreferences = InferencePreferences(),
    val hasKey: Boolean = false,
    val test: ConnectionTest = ConnectionTest.Idle,
    /** Pedido de ligar a nuvem aguardando o aceite do envio da tela. */
    val askingConsent: Boolean = false,
)

@HiltViewModel
class EngineSettingsViewModel @Inject constructor(
    private val settings: InferenceSettings,
    private val keys: ApiKeyStore,
    private val access: CloudAccess,
    private val generator: CloudGenerator,
) : ViewModel() {

    private val _state = MutableStateFlow(EngineSettingsUiState())
    val state: StateFlow<EngineSettingsUiState> = _state.asStateFlow()
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            settings.state.collect { prefs ->
                _state.update { it.copy(preferences = prefs, hasKey = keys.get(prefs.cloud.destinationId) != null) }
            }
        }
    }

    fun selectLocal() = save { it.copy(mode = InferenceMode.Local) }

    /** O consentimento vale por destino: um provedor novo sempre passa pelo aviso antes de receber a tela. */
    fun selectCloud() {
        if (_state.value.preferences.hasConsent) {
            save { it.copy(mode = InferenceMode.Cloud) }
        } else {
            _state.update { it.copy(askingConsent = true) }
        }
    }

    fun acceptConsent() {
        _state.update { it.copy(askingConsent = false) }
        save { it.copy(mode = InferenceMode.Cloud, consentedDestinations = it.consentedDestinations + it.cloud.destinationId) }
    }

    fun declineConsent() = _state.update { it.copy(askingConsent = false) }

    fun selectProvider(provider: CloudProvider) = saveCloud { CloudConfig(provider) }

    fun setBaseUrl(value: String) = saveCloud { it.copy(baseUrl = value.trim()) }

    fun setModel(value: String) = saveCloud { it.copy(model = value.trim()) }

    fun saveKey(value: String) {
        val key = value.trim()
        if (key.isEmpty()) return
        keys.put(_state.value.preferences.cloud.destinationId, key)
        _state.update { it.copy(hasKey = true, test = ConnectionTest.Idle) }
    }

    fun removeKey() {
        keys.remove(_state.value.preferences.cloud.destinationId)
        _state.update { it.copy(hasKey = false, test = ConnectionTest.Idle) }
    }

    fun setSendNotificationBodies(value: Boolean) = save { it.copy(sendNotificationBodies = value) }

    fun setLocalFallback(value: Boolean) = save { it.copy(localFallback = value) }

    /** Confere o endereço e a chave. Nenhum conteúdo da tela é enviado neste teste. */
    fun testConnection() {
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _state.update { it.copy(test = ConnectionTest.Running) }
            val result = try {
                ConnectionTest.Ok(generator.validate(access.connectionEndpoint(_state.value.preferences.cloud)))
            } catch (e: CloudException) {
                ConnectionTest.Failed(e.message.orEmpty())
            }
            _state.update { it.copy(test = result) }
        }
    }

    private fun saveCloud(transform: (CloudConfig) -> CloudConfig) = save { it.copy(cloud = transform(it.cloud)) }

    private fun save(transform: (InferencePreferences) -> InferencePreferences) {
        _state.update { it.copy(test = ConnectionTest.Idle) }
        viewModelScope.launch { settings.update(transform) }
    }
}
