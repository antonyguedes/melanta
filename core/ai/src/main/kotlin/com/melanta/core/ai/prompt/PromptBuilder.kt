package com.melanta.core.ai.prompt

import javax.inject.Inject
import kotlin.math.ceil

/** O que dá para fazer com um elemento, derivado do papel e das flags da linha. */
enum class ScreenAction {
    Click,
    Type,
    Scroll,
    ;

    companion object {
        private val LINE = Regex("""^\[\d+]\s+(\w+)(?:\s+"[^"]*")?(.*)$""")

        /** `[2] edit "Pesquisar" click focused` -> {Type, Click}. Elementos `off` não aceitam nada. */
        fun infer(line: String): Set<ScreenAction> {
            val match = LINE.find(line.trim()) ?: return emptySet()
            val role = match.groupValues[1]
            val flags = match.groupValues[2].split(' ').filter { it.isNotBlank() }.toSet()
            if ("off" in flags) return emptySet()
            return buildSet {
                if ("click" in flags) add(Click)
                if (role == "edit") add(Type)
                if ("scroll" in flags) add(Scroll)
            }
        }
    }
}

/**
 * Uma linha da tela no formato `[idx] <role> "<label>" <flags>`, produzida pelo `ScreenParser` (Fase 3).
 * Quanto maior a [priority], mais tarde a linha é descartada quando a tela não cabe no orçamento.
 */
data class ScreenLine(
    val id: Int,
    val text: String,
    val priority: Int = 0,
    val actions: Set<ScreenAction> = ScreenAction.infer(text),
)

/** Ids por ação possível; restringem o schema e a validação do parser. */
data class ScreenTargets(val click: Set<Int>, val type: Set<Int>, val scroll: Set<Int>) {
    companion object {
        fun of(lines: List<ScreenLine>) = ScreenTargets(
            click = lines.filter { ScreenAction.Click in it.actions }.mapTo(sortedSetOf()) { it.id },
            type = lines.filter { ScreenAction.Type in it.actions }.mapTo(sortedSetOf()) { it.id },
            scroll = lines.filter { ScreenAction.Scroll in it.actions }.mapTo(sortedSetOf()) { it.id },
        )
    }
}

data class ScreenContext(
    val packageName: String,
    val title: String? = null,
    val lines: List<ScreenLine>,
)

data class PromptInput(
    val task: String,
    /** Ações anteriores, uma por linha, já formatadas (`intent open_app WhatsApp -> ok`). */
    val history: List<String>,
    val screen: ScreenContext,
    val notifications: List<String> = emptyList(),
    /** Data e hora atuais ("2026-10-26 13:05, Monday"), para o modelo resolver "amanhã às 15h". */
    val now: String? = null,
)

/** Orçamento da seção 3.4: contexto de 4096 tokens com teto fixo para tela e saída. */
data class PromptBudget(
    val contextTokens: Int = 4096,
    val outputTokens: Int = 128,
    val maxScreenTokens: Int = 1800,
    val maxHistory: Int = 4,
    /** Folga para os tokens do template de chat e erro de estimativa. */
    val safetyMargin: Int = 96,
)

/** Estimativa conservadora: ~3 caracteres por token cobre inglês, português e o formato de tela. */
object TokenEstimator {
    fun estimate(text: String): Int = ceil(text.length / 3.0).toInt()
}

/** Resultado do [PromptBuilder]: o prompt e quais ids sobraram na tela depois do corte. */
data class BuiltPrompt(
    val prompt: LlmPrompt,
    val visibleIds: Set<Int>,
    val targets: ScreenTargets,
    val hiddenLines: Int,
    val estimatedTokens: Int,
)

class PromptBuilder @Inject constructor() {

    fun build(input: PromptInput, budget: PromptBudget = PromptBudget(), system: String = AgentPrompt.SYSTEM): BuiltPrompt {
        val header = buildString {
            append("TASK: ").append(input.task.trim()).append('\n')
            input.now?.let { append("NOW: ").append(it).append('\n') }
            val history = input.history.takeLast(budget.maxHistory)
            if (history.isEmpty()) {
                append("HISTORY: none\n")
            } else {
                append("HISTORY:\n")
                history.forEachIndexed { i, line -> append(i + 1).append(". ").append(line).append('\n') }
            }
            append("SCREEN (").append(input.screen.packageName)
            input.screen.title?.takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
            append("):\n")
        }
        val footer = if (input.notifications.isEmpty()) "" else "NOTIFS: " + input.notifications.joinToString(" | ")

        val fixedTokens = TokenEstimator.estimate(system) + TokenEstimator.estimate(header) +
            TokenEstimator.estimate(footer) + budget.outputTokens + budget.safetyMargin
        val screenBudget = minOf(budget.maxScreenTokens, budget.contextTokens - fixedTokens).coerceAtLeast(0)
        val kept = fitScreen(input.screen.lines, screenBudget)
        val hidden = input.screen.lines.size - kept.size

        val user = buildString {
            append(header)
            kept.forEach { append(it.text).append('\n') }
            if (hidden > 0) append("(+").append(hidden).append(" more elements, scroll to see)\n")
            if (footer.isNotEmpty()) append(footer).append('\n')
        }.trimEnd()

        return BuiltPrompt(
            prompt = LlmPrompt(system, listOf(ChatMessage(ChatMessage.Role.User, user))),
            visibleIds = kept.mapTo(mutableSetOf()) { it.id },
            targets = ScreenTargets.of(kept),
            hiddenLines = hidden,
            estimatedTokens = TokenEstimator.estimate(system) + TokenEstimator.estimate(user),
        )
    }

    /** Re-prompt corretivo: repete o turno, mostra a resposta inválida e pede só o JSON. */
    fun correction(previous: LlmPrompt, badOutput: String, detail: String): LlmPrompt =
        previous.copy(
            messages = previous.messages + listOf(
                ChatMessage(ChatMessage.Role.Model, badOutput.trim().take(MAX_ECHOED_OUTPUT)),
                ChatMessage(ChatMessage.Role.User, AgentPrompt.correction(detail)),
            ),
        )

    /** Descarta as linhas de menor prioridade (as últimas, em empate) e mantém a ordem de leitura. */
    private fun fitScreen(lines: List<ScreenLine>, budgetTokens: Int): List<ScreenLine> {
        val cost = lines.map { TokenEstimator.estimate(it.text) + 1 }
        var total = cost.sum()
        if (total <= budgetTokens) return lines
        val dropOrder = lines.indices.sortedWith(compareBy<Int> { lines[it].priority }.thenByDescending { it })
        val dropped = mutableSetOf<Int>()
        for (index in dropOrder) {
            if (total <= budgetTokens) break
            dropped += index
            total -= cost[index]
        }
        return lines.filterIndexed { index, _ -> index !in dropped }
    }

    private companion object {
        const val MAX_ECHOED_OUTPUT = 400
    }
}
