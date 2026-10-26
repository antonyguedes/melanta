package com.melanta.core.ai.engine

import com.melanta.core.ai.prompt.LlmPrompt
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class BackendPreference { Auto, Gpu, Cpu }

enum class BackendKind { Gpu, Cpu }

data class ModelConfig(
    val modelFile: File,
    val backend: BackendPreference = BackendPreference.Auto,
    val maxNumTokens: Int = 4096,
    /** Template de chat que substitui o embutido no modelo. */
    val chatTemplate: String? = null,
)

/** Parâmetros da seção 3.4: quase greedy, seed fixa e saída curta. */
data class GenParams(
    val temperature: Double = 0.1,
    val topK: Int = 1,
    val topP: Double = 1.0,
    val seed: Int = 42,
    val maxOutputTokens: Int = 128,
    /** JSON Schema para constrained decoding. `null` deixa a saída livre. */
    val responseSchema: String? = null,
    /** Modelos com modo de raciocínio (Qwen3.x, Gemma 4) respondem direto, sem gastar tokens pensando. */
    val enableThinking: Boolean = false,
)

sealed interface EngineState {
    data object Unloaded : EngineState
    data class Loading(val modelFile: File) : EngineState
    data class Ready(val modelFile: File, val backend: BackendKind, val loadMillis: Long) : EngineState
    data class Error(val modelFile: File, val message: String) : EngineState
}

/** Métricas da última geração. Os campos do motor são nulos quando ele não as fornece. */
data class GenerationStats(
    val timeToFirstChunkMillis: Long?,
    val totalMillis: Long,
    val chunks: Int,
    val prefillTokens: Int? = null,
    val decodeTokens: Int? = null,
    val prefillTokensPerSecond: Double? = null,
    val decodeTokensPerSecond: Double? = null,
    val cancelled: Boolean = false,
)

interface LlmEngine {
    val state: StateFlow<EngineState>

    /** Métricas da geração mais recente, inclusive quando ela foi cortada pelo coletor. */
    val lastStats: StateFlow<GenerationStats?>

    suspend fun load(config: ModelConfig)

    /** Stream de texto incremental. Cancelar a coleta interrompe a geração no motor. */
    fun generate(prompt: LlmPrompt, params: GenParams): Flow<String>

    suspend fun unload()

    /** Marca a GPU como inutilizável para [modelFile]: as próximas cargas em Auto vão direto para a CPU. */
    fun blockGpu(modelFile: File) = Unit
}
