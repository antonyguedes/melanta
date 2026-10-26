package com.melanta.core.ai.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.melanta.core.ai.model.InstalledModel
import com.melanta.core.ai.model.ModelCatalog
import com.melanta.core.ai.model.ModelStore
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InsufficientMemoryException(val availableBytes: Long, val requiredBytes: Long) :
    Exception("Memória insuficiente: ${availableBytes / MB} MB livres, ${requiredBytes / MB} MB necessários") {
    private companion object {
        const val MB = 1024 * 1024
    }
}

/**
 * Ciclo de vida do modelo: carga sob demanda, uma geração por vez e descarga quando ocioso
 * ou sob pressão de memória. É a única porta de entrada para o [LlmEngine].
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LlmEngine,
    private val store: ModelStore,
    dispatchers: DispatcherProvider,
) : ComponentCallbacks2 {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private var idleJob: Job? = null
    private var loadedConfig: ModelConfig? = null

    val state: StateFlow<EngineState> = engine.state
    val lastStats: StateFlow<GenerationStats?> = engine.lastStats

    init {
        context.registerComponentCallbacks(this)
    }

    /** Modelo usado quando nenhum é escolhido: o padrão, depois o catálogo em ordem, depois qualquer `.litertlm`. */
    fun preferredModel(): InstalledModel? {
        val installed = store.installed.value
        return installed.firstOrNull { it.spec == ModelCatalog.default }
            ?: ModelCatalog.all.firstNotNullOfOrNull { spec -> installed.firstOrNull { it.spec == spec } }
            ?: installed.firstOrNull { it.file.extension == "litertlm" }
            ?: installed.firstOrNull()
    }

    suspend fun ensureLoaded(model: InstalledModel, backend: BackendPreference = BackendPreference.Auto) {
        mutex.withLock {
            idleJob?.cancel()
            val config = ModelConfig(model.file, backend, chatTemplate = model.spec?.chatTemplate)
            if (state.value is EngineState.Ready && loadedConfig == config) {
                scheduleIdleUnload()
                return
            }
            checkMemory(model.file)
            loadedConfig = null
            engine.load(config)
            loadedConfig = config
            scheduleIdleUnload()
        }
    }

    /**
     * Gera com exclusividade: outra chamada espera esta terminar. O timer de ociosidade
     * fica parado enquanto o stream está sendo coletado.
     */
    fun generate(prompt: LlmPrompt, params: GenParams = GenParams()): Flow<String> = flow {
        mutex.withLock {
            idleJob?.cancel()
            try {
                emitAll(engine.generate(prompt, params))
            } finally {
                scheduleIdleUnload()
            }
        }
    }

    /**
     * Uma geração falhou na GPU (driver ruim, GPU emulada, buffer travado): bloqueia a GPU para este
     * modelo e recarrega na CPU. Retorna `false` se o modelo não estava na GPU, e aí não há o que tentar.
     */
    suspend fun fallbackToCpu(): Boolean = mutex.withLock {
        val config = loadedConfig ?: return@withLock false
        val ready = state.value as? EngineState.Ready ?: return@withLock false
        if (ready.backend != BackendKind.Gpu) return@withLock false
        Log.w(TAG, "Geração falhou na GPU com ${config.modelFile.name}; recarregando na CPU")
        engine.blockGpu(config.modelFile)
        loadedConfig = null
        engine.load(config.copy(backend = BackendPreference.Cpu))
        // Guarda a configuração pedida (ex.: Auto): o próximo ensureLoaded igual não recarrega.
        loadedConfig = config
        scheduleIdleUnload()
        true
    }

    suspend fun unload() {
        mutex.withLock {
            idleJob?.cancel()
            unloadLocked()
        }
    }

    private suspend fun unloadLocked() {
        if (state.value is EngineState.Unloaded) return
        engine.unload()
        loadedConfig = null
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MILLIS)
            mutex.withLock { unloadLocked() }
            Log.i(TAG, "Modelo descarregado após ${IDLE_UNLOAD_MILLIS / 1000}s ocioso")
        }
    }

    private fun checkMemory(file: File) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        val required = file.length() + MEMORY_HEADROOM_BYTES
        if (info.availMem < required) throw InsufficientMemoryException(info.availMem, required)
    }

    // Só descarrega sob pressão real e se não houver geração em andamento (o mutex estaria ocupado).
    @Deprecated("Deprecated in Java")
    override fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical) unloadIfIdle("onTrimMemory($level)")
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = unloadIfIdle("onLowMemory")

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun unloadIfIdle(reason: String) {
        scope.launch {
            if (!mutex.tryLock()) return@launch
            try {
                idleJob?.cancel()
                unloadLocked()
                Log.i(TAG, "Modelo descarregado: $reason")
            } finally {
                mutex.unlock()
            }
        }
    }

    private companion object {
        const val TAG = "ModelManager"
        const val IDLE_UNLOAD_MILLIS = 60_000L

        /** KV cache, ativações e margem para o resto do sistema, além dos pesos. */
        const val MEMORY_HEADROOM_BYTES = 512L * 1024 * 1024
    }
}
