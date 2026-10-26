package com.melanta.core.ai.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.melanta.core.ai.prompt.ChatMessage
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.common.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [LlmEngine] sobre o LiteRT-LM. Cada geração usa uma `Conversation` nova: o agente monta o
 * prompt inteiro a cada passo (tela atual + histórico compacto), então não há estado a manter.
 *
 * Todas as chamadas nativas de carga/descarga passam por um dispatcher serial. Quem garante uma
 * geração por vez é o [ModelManager].
 */
// ExperimentalFlags e BenchmarkInfo são marcados como experimentais no LiteRT-LM 0.18.
@OptIn(ExperimentalApi::class)
@Singleton
class LiteRtLmEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
) : LlmEngine {

    private val inference = dispatchers.io.limitedParallelism(1)
    private val cleanupScope = CoroutineScope(SupervisorJob() + inference)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    override val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var chatTemplate: String? = null

    @Volatile
    private var abandonCurrentEngine = false

    // Fora do cacheDir: o sistema pode limpar o cache, o que forçaria regravar centenas de MB a cada carga.
    private val engineCacheDir: File by lazy { File(context.noBackupFilesDir, "litertlm").apply { mkdirs() } }
    private val prefs by lazy { context.getSharedPreferences("litertlm_engine", Context.MODE_PRIVATE) }

    override suspend fun load(config: ModelConfig) = withContext(inference) {
        closeEngine()
        _state.value = EngineState.Loading(config.modelFile)
        val start = SystemClock.elapsedRealtime()
        cleanStaleCaches(config.modelFile)
        recoverFromNativeCrash(config.modelFile)
        val candidates = when (config.backend) {
            BackendPreference.Auto -> buildList {
                if (gpuAllowedInAuto(config.modelFile)) add(BackendKind.Gpu)
                add(BackendKind.Cpu)
            }
            BackendPreference.Gpu -> listOf(BackendKind.Gpu)
            BackendPreference.Cpu -> listOf(BackendKind.Cpu)
        }
        var lastError: Throwable? = null
        for (kind in candidates) {
            try {
                requireCacheSpace(config.modelFile, kind)
                engine = create(config, kind)
                chatTemplate = config.chatTemplate
                if (kind == BackendKind.Gpu) prefs.edit().remove(gpuBlockedKey(config.modelFile)).apply()
                _state.value = EngineState.Ready(config.modelFile, kind, SystemClock.elapsedRealtime() - start)
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao iniciar o backend $kind para ${config.modelFile.name}", e)
                lastError = e
            }
        }
        val message = lastError?.message ?: "Falha desconhecida ao carregar o modelo"
        _state.value = EngineState.Error(config.modelFile, message)
        throw ModelLoadException(message, lastError)
    }

    private fun create(config: ModelConfig, kind: BackendKind): Engine {
        // Lida na criação do Engine; habilita BenchmarkInfo (TTFT, tokens/s) por conversa.
        ExperimentalFlags.enableBenchmark = true
        val backend = when (kind) {
            BackendKind.Gpu -> Backend.GPU()
            BackendKind.Cpu -> Backend.CPU()
        }
        val engine = Engine(
            EngineConfig(
                modelPath = config.modelFile.absolutePath,
                backend = backend,
                maxNumTokens = config.maxNumTokens,
                cacheDir = engineCacheDir.absolutePath,
            ),
        )
        // O nativo aborta o processo em algumas falhas (ex.: disco cheio ao gravar o cache de pesos)
        // em vez de lançar exceção. A sentinela sobrevive ao crash e é tratada na próxima carga.
        val sentinel = initPendingKey(config.modelFile, kind)
        prefs.edit().putBoolean(sentinel, true).commit()
        try {
            engine.initialize()
        } catch (e: Exception) {
            runCatching { engine.close() }
            throw e
        } finally {
            prefs.edit().remove(sentinel).commit()
        }
        return engine
    }

    /** Uma sentinela que sobrou indica crash nativo na última carga: o cache parcial pode estar corrompido. */
    private fun recoverFromNativeCrash(modelFile: File) {
        val crashed = BackendKind.entries.filter { prefs.getBoolean(initPendingKey(modelFile, it), false) }
        if (crashed.isEmpty()) return
        Log.w(TAG, "Crash nativo na última carga de ${modelFile.name} ($crashed); limpando o cache")
        cacheFilesFor(modelFile).forEach { it.deleteRecursively() }
        prefs.edit().apply {
            crashed.forEach { remove(initPendingKey(modelFile, it)) }
            if (BackendKind.Gpu in crashed) putBoolean(gpuBlockedKey(modelFile), true)
        }.commit()
    }

    private fun gpuAllowedInAuto(modelFile: File): Boolean {
        if (isEmulator) {
            // GPU de emulador é renderização por software (SwiftShader): trava o decode em vez de acelerar.
            Log.i(TAG, "Emulador detectado; usando CPU")
            return false
        }
        if (prefs.getBoolean(gpuBlockedKey(modelFile), false)) {
            Log.w(TAG, "GPU bloqueada para ${modelFile.name} após crash nativo; usando CPU")
            return false
        }
        return hasCacheSpace(modelFile, BackendKind.Gpu)
    }

    /** Na primeira carga cada backend grava um cache de pesos quase do tamanho do modelo. Sem espaço, o nativo aborta. */
    private fun requireCacheSpace(modelFile: File, kind: BackendKind) {
        if (!hasCacheSpace(modelFile, kind)) {
            throw InsufficientStorageException(engineCacheDir.usableSpace, estimatedCacheBytes(modelFile) + CACHE_MARGIN_BYTES)
        }
    }

    private fun hasCacheSpace(modelFile: File, kind: BackendKind): Boolean {
        val marker = if (kind == BackendKind.Gpu) GPU_CACHE_MARKER else CPU_CACHE_MARKER
        if (cacheFilesFor(modelFile).any { marker in it.name }) return true
        return engineCacheDir.usableSpace >= estimatedCacheBytes(modelFile) + CACHE_MARGIN_BYTES
    }

    private fun estimatedCacheBytes(modelFile: File) = (modelFile.length() * CACHE_SIZE_RATIO).toLong()

    private fun cacheFilesFor(modelFile: File) =
        engineCacheDir.listFiles().orEmpty().filter { it.name.startsWith(modelFile.name) }

    /** Remove caches compilados de outros modelos: cada um pode ocupar centenas de MB. */
    private fun cleanStaleCaches(modelFile: File) {
        engineCacheDir.listFiles().orEmpty()
            .filterNot { it.name.startsWith(modelFile.name) }
            .forEach { it.deleteRecursively() }
    }

    private val isEmulator: Boolean by lazy {
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic") ||
            Build.PRODUCT.startsWith("sdk_gphone")
    }

    private fun initPendingKey(modelFile: File, kind: BackendKind) =
        "init_pending:$kind:${modelFile.name}:${modelFile.length()}"

    private fun gpuBlockedKey(modelFile: File) = "gpu_blocked:${modelFile.name}:${modelFile.length()}"

    override fun generate(prompt: LlmPrompt, params: GenParams): Flow<String> = callbackFlow {
        val engine = engine ?: throw IllegalStateException("Modelo não carregado")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(prompt.system),
                initialMessages = prompt.messages.dropLast(1).map { it.toLiteRt() },
                samplerConfig = SamplerConfig(params.topK, params.topP, params.temperature, params.seed),
                automaticToolCalling = false,
                maxOutputToken = params.maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = params.enableThinking),
                enableResponseFormat = params.responseSchema != null,
                chatTemplate = chatTemplate,
            ),
        )
        val finished = CompletableDeferred<Unit>()
        val start = SystemClock.elapsedRealtime()
        var firstChunkAt: Long? = null
        var chunks = 0

        fun publishStats(cancelled: Boolean) {
            val bench = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
            _lastStats.value = GenerationStats(
                timeToFirstChunkMillis = firstChunkAt?.minus(start),
                totalMillis = SystemClock.elapsedRealtime() - start,
                chunks = chunks,
                prefillTokens = bench?.lastPrefillTokenCount,
                decodeTokens = bench?.lastDecodeTokenCount,
                prefillTokensPerSecond = bench?.lastPrefillTokensPerSecond,
                decodeTokensPerSecond = bench?.lastDecodeTokensPerSecond,
                cancelled = cancelled,
            )
        }

        conversation.sendMessageAsync(
            prompt.messages.last().toLiteRt(),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val text = message.text()
                    if (text.isEmpty()) return
                    if (firstChunkAt == null) firstChunkAt = SystemClock.elapsedRealtime()
                    chunks++
                    trySend(text)
                }

                override fun onDone() {
                    publishStats(cancelled = false)
                    finished.complete(Unit)
                    close()
                }

                override fun onError(throwable: Throwable) {
                    finished.complete(Unit)
                    close(throwable)
                }
            },
            responseFormat = params.responseSchema?.let(ResponseFormat::json),
        )

        awaitClose {
            if (!finished.isCompleted) {
                // Coletor cancelou (corte no stream ou kill switch): interrompe o decode nativo.
                runCatching { conversation.cancelProcess() }
                publishStats(cancelled = true)
            }
            // Só fecha a conversa depois que o nativo confirmar o fim, para não liberar memória em uso.
            cleanupScope.launch {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) { finished.await() }
                runCatching { conversation.close() }
            }
        }
    }.flowOn(inference)

    override fun blockGpu(modelFile: File) {
        prefs.edit().putBoolean(gpuBlockedKey(modelFile), true).apply()
        // O engine atual pode estar com uma tarefa nativa presa na GPU: fechá-lo bloquearia a carga
        // na CPU para sempre. Ele é descartado em segundo plano na próxima troca.
        abandonCurrentEngine = true
    }

    override suspend fun unload() = withContext(inference) {
        closeEngine()
        _state.value = EngineState.Unloaded
    }

    private suspend fun closeEngine() {
        val old = engine ?: return
        engine = null
        if (abandonCurrentEngine) {
            abandonCurrentEngine = false
            val modelFile = (state.value as? EngineState.Ready)?.modelFile
            Thread({
                runCatching { old.close() }
                // Só depois de fechado o cache de GPU deixa de estar mapeado e o espaço volta.
                modelFile?.let { file -> cacheFilesFor(file).filter { GPU_CACHE_MARKER in it.name }.forEach { it.delete() } }
            }, "litertlm-abandoned").apply { isDaemon = true }.start()
            return
        }
        // Conversas ainda fechando precisam terminar antes do Engine que as criou.
        cleanupScope.coroutineContext.job.children.toList().joinAll()
        runCatching { old.close() }
    }

    private fun ChatMessage.toLiteRt(): Message = when (role) {
        ChatMessage.Role.User -> Message.user(text)
        ChatMessage.Role.Model -> Message.model(text)
    }

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private companion object {
        const val TAG = "LiteRtLmEngine"
        const val CLOSE_TIMEOUT_MS = 5_000L
        const val CACHE_MARGIN_BYTES = 256L * 1024 * 1024

        /** Medido no Qwen2.5-1.5B q8 (1,60 GB): o cache XNNPACK completo ocupa 1,55 GB. */
        const val CACHE_SIZE_RATIO = 1.0
        const val CPU_CACHE_MARKER = "xnnpack_cache"
        const val GPU_CACHE_MARKER = "mldrift"
    }
}

class ModelLoadException(message: String, cause: Throwable?) : Exception(message, cause)

class InsufficientStorageException(val availableBytes: Long, val requiredBytes: Long) :
    Exception(
        "Espaço insuficiente para a primeira carga: ${availableBytes / MB} MB livres, " +
            "~${requiredBytes / MB} MB necessários para o cache de pesos",
    ) {
    private companion object {
        const val MB = 1024 * 1024
    }
}
