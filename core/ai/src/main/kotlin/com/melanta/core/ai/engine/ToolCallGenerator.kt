package com.melanta.core.ai.engine

import android.os.SystemClock
import com.melanta.core.ai.prompt.BuiltPrompt
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.ai.prompt.PromptBuilder
import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.tool.ParseContext
import com.melanta.core.ai.tool.ParseResult
import com.melanta.core.ai.tool.ToolCall
import com.melanta.core.ai.tool.ToolCallParser
import com.melanta.core.ai.tool.ToolCallSchema
import com.melanta.core.ai.tool.untilFirstJsonObject
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Um passo de raciocínio: monta o prompt, gera com corte no stream, faz o parse e, se a saída
 * for inválida, repete com re-prompt corretivo (no máximo [MAX_CORRECTIONS] vezes).
 */
class ToolCallGenerator @Inject constructor(
    private val modelManager: ModelManager,
    private val promptBuilder: PromptBuilder,
    private val parser: ToolCallParser,
) {
    data class Attempt(
        val raw: String,
        val result: ParseResult,
        val timeToFirstChunkMillis: Long?,
        val totalMillis: Long,
        val engineStats: GenerationStats?,
    )

    data class Outcome(val prompt: BuiltPrompt, val attempts: List<Attempt>) {
        val call: ToolCall? get() = (attempts.lastOrNull()?.result as? ParseResult.Success)?.call
    }

    /**
     * @param constrained usa o JSON Schema de [ToolCallSchema] no motor (constrained decoding).
     * @param onChunk recebe o texto parcial de cada tentativa, para UI ao vivo.
     */
    suspend fun decide(
        input: PromptInput,
        constrained: Boolean,
        onChunk: (attempt: Int, textSoFar: String) -> Unit = { _, _ -> },
    ): Outcome {
        val built = promptBuilder.build(input)
        val context = ParseContext(built.visibleIds, built.targets)
        val params = GenParams(responseSchema = if (constrained) ToolCallSchema.build(built.targets) else null)

        val attempts = mutableListOf<Attempt>()
        var prompt: LlmPrompt = built.prompt
        repeat(1 + MAX_CORRECTIONS) { index ->
            val attempt = generateOnce(prompt, params, context) { onChunk(index, it) }
            attempts += attempt
            val result = attempt.result
            if (result !is ParseResult.Failure) return Outcome(built, attempts)
            prompt = promptBuilder.correction(prompt, attempt.raw, result.detail)
        }
        return Outcome(built, attempts)
    }

    private suspend fun generateOnce(
        prompt: LlmPrompt,
        params: GenParams,
        context: ParseContext,
        onChunk: (String) -> Unit,
    ): Attempt {
        val previousStats = modelManager.lastStats.value
        val raw = StringBuilder()
        val start = SystemClock.elapsedRealtime()
        var firstChunk: Long? = null
        modelManager.generate(prompt, params).untilFirstJsonObject().collect { chunk ->
            if (firstChunk == null) firstChunk = SystemClock.elapsedRealtime() - start
            raw.append(chunk)
            onChunk(raw.toString())
        }
        val total = SystemClock.elapsedRealtime() - start
        // Num corte de stream o motor publica as métricas de forma assíncrona, logo após cancelar.
        val stats = withTimeoutOrNull(STATS_WAIT_MILLIS) {
            modelManager.lastStats.first { it != null && it !== previousStats }
        }
        return Attempt(raw.toString(), parser.parse(raw.toString(), context), firstChunk, total, stats)
    }

    companion object {
        const val MAX_CORRECTIONS = 2
        private const val STATS_WAIT_MILLIS = 500L
    }
}
