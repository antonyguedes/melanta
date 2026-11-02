package com.melanta.core.ai.engine

import com.melanta.core.ai.prompt.LlmPrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * O que o agente precisa de um motor de inferência: gerar texto em stream e medir a geração.
 * Implementado pelo modelo local ([ModelManager]) e pelo provedor remoto (`CloudGenerator`).
 */
interface TextGenerator {
    /** Métricas da geração mais recente, inclusive quando ela foi cortada pelo coletor. */
    val lastStats: StateFlow<GenerationStats?>

    /** Stream de texto incremental. Cancelar a coleta interrompe a geração. */
    fun generate(prompt: LlmPrompt, params: GenParams = GenParams()): Flow<String>
}
