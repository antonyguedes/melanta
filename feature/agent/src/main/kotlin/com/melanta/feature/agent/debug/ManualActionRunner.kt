package com.melanta.feature.agent.debug

import com.melanta.core.ai.tool.ParseContext
import com.melanta.core.ai.tool.ParseResult
import com.melanta.core.ai.tool.ToolCallParser
import com.melanta.core.automation.action.ActionExecutor
import com.melanta.core.automation.action.ActionResult
import com.melanta.core.automation.screen.ScreenReader
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.service.UiIdleWatcher
import com.melanta.feature.agent.action.MapResult
import com.melanta.feature.agent.action.ToolCallMapper
import javax.inject.Inject

/**
 * Um passo do agente sem o modelo: lê a tela, interpreta um JSON escrito à mão, executa e
 * espera a UI estabilizar. Base do painel de debug da Fase 3 e do teste por adb.
 */
class ManualActionRunner @Inject constructor(
    private val reader: ScreenReader,
    private val parser: ToolCallParser,
    private val mapper: ToolCallMapper,
    private val executor: ActionExecutor,
    private val idleWatcher: UiIdleWatcher,
) {
    data class Report(
        val before: ScreenSnapshot,
        val parse: ParseResult,
        val mapped: MapResult?,
        val result: ActionResult?,
        val settled: Boolean?,
        val after: ScreenSnapshot?,
    ) {
        fun describe(): String = buildString {
            when (parse) {
                is ParseResult.Success -> appendLine("parse: ${parse.call.name} ${parse.call.args}")
                is ParseResult.Failure -> appendLine("parse: ${parse.error} ${parse.detail}")
            }
            when (mapped) {
                is MapResult.Success -> appendLine("ação: ${mapped.action}")
                is MapResult.Failure -> appendLine("ação inválida: ${mapped.detail}")
                null -> Unit
            }
            result?.let { appendLine("resultado: $it") }
            settled?.let { appendLine(if (it) "tela estável" else "tela não estabilizou em 3 s") }
            after?.let { appendLine(dump(it)) }
        }.trimEnd()
    }

    suspend fun snapshot(): ScreenSnapshot = reader.snapshot()

    suspend fun run(json: String): Report {
        val before = reader.snapshot()
        val parse = parser.parse(json, ParseContext(before.elements.mapTo(mutableSetOf()) { it.id }))
        if (parse !is ParseResult.Success) return Report(before, parse, null, null, null, null)
        val mapped = mapper.map(parse.call)
        if (mapped !is MapResult.Success) return Report(before, parse, mapped, null, null, null)
        val result = executor.execute(mapped.action, before)
        val settled = idleWatcher.awaitIdle()
        return Report(before, parse, mapped, result, settled, reader.snapshot())
    }

    companion object {
        fun dump(snapshot: ScreenSnapshot): String = buildString {
            append("SCREEN (").append(snapshot.packageName)
            snapshot.title?.let { append(" | ").append(it) }
            append("):")
            snapshot.lines().forEach { append('\n').append(it) }
            if (snapshot.droppedElements > 0) append("\n(+${snapshot.droppedElements} cortados)")
        }
    }
}
