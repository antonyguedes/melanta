package com.melanta.feature.agent

import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.prompt.ScreenContext
import com.melanta.core.ai.prompt.ScreenLine
import com.melanta.core.ai.tool.ToolCall
import com.melanta.core.automation.action.ActionResult
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Direction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.NavKey
import com.melanta.core.automation.action.SettingsPage
import com.melanta.core.automation.action.Target
import com.melanta.core.automation.screen.AccessibilityUnavailableException
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.screen.UiElement
import com.melanta.feature.agent.action.MapResult
import com.melanta.feature.agent.action.ToggleGoal
import com.melanta.feature.agent.action.ToolCallMapper
import com.melanta.feature.agent.log.AgentRunLogger
import com.melanta.feature.agent.loop.ActionActuator
import com.melanta.feature.agent.loop.ActionReasoner
import com.melanta.feature.agent.loop.AgentSetupException
import com.melanta.feature.agent.loop.ModelPreparer
import com.melanta.feature.agent.loop.ScreenPerception
import com.melanta.feature.agent.safety.LoopDetector
import com.melanta.feature.agent.safety.SafetyDecision
import com.melanta.feature.agent.safety.SafetyPolicy
import com.melanta.feature.agent.safety.SafetyPolicy.Limits
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentState.Phase
import com.melanta.feature.agent.state.AgentStep
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Loop ReAct: perceber -> raciocinar -> validar -> agir -> esperar a tela estabilizar.
 *
 * Termina com `done`, com um bloqueio da [SafetyPolicy], com a negação de uma confirmação, ao
 * atingir um limite (passos, tempo, loop, erros seguidos) ou quando o kill switch cancela o `Job`.
 */
class AgentOrchestrator @Inject constructor(
    private val controller: AgentController,
    private val preparer: ModelPreparer,
    private val perception: ScreenPerception,
    private val reasoner: ActionReasoner,
    private val actuator: ActionActuator,
    private val mapper: ToolCallMapper,
    private val safety: SafetyPolicy,
    private val logger: AgentRunLogger,
) {
    private sealed interface End {
        data class Finished(val message: String) : End
        data class Failed(val reason: String) : End
        data object Denied : End
    }

    suspend fun run(task: String) {
        logger.begin(task)
        try {
            val end = try {
                withTimeout(Limits.MAX_DURATION_MS) { loop(task) }
            } catch (e: TimeoutCancellationException) {
                End.Failed("Tempo limite de ${Limits.MAX_DURATION_MS / 60_000} minutos atingido.")
            } catch (e: AgentSetupException) {
                End.Failed(e.message.orEmpty())
            } catch (e: AccessibilityUnavailableException) {
                End.Failed("A Acessibilidade do Melanta foi desligada. Reative em Permissões.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Erro inesperado (ex.: falha nativa do modelo): encerra com mensagem e fica registrado no log.
                logger.event("error", "type" to e.javaClass.simpleName, "message" to e.message)
                End.Failed("Erro inesperado: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
            }
            when (end) {
                is End.Finished -> controller.moveTo(AgentState.Finished(task, end.message))
                is End.Failed -> controller.fail(end.reason)
                End.Denied -> controller.moveTo(AgentState.Cancelled(task))
            }
            logger.event("end", "result" to end.toString())
        } catch (e: CancellationException) {
            logger.event("end", "result" to "Cancelled")
            throw e
        } finally {
            logger.end()
        }
    }

    private suspend fun loop(task: String): End {
        preparer.prepare()
        val history = mutableListOf<String>()
        val loopDetector = LoopDetector()
        var consecutiveErrors = 0
        var executedActions = 0
        var succeededActions = 0
        var lastAction: AgentAction? = null
        var lastScreenHash: Int? = null
        var handoffs = 0
        var lastToggle: UiElement? = null
        // Intent que abre um formulário pronto (SMS, contato...) mas só conclui com um toque em Enviar/Salvar.
        var awaitingTap: IntentSpec? = null
        var prematureDones = 0
        val toggleGoal = ToggleGoal.from(task)

        for (step in 0 until Limits.MAX_STEPS) {
            // 1. perceber
            controller.moveTo(AgentState.Running(task, step, Phase.Perceiving))
            val snapshot = perception.snapshot()
            // Diálogo do sistema que o agente não enxerga (ex.: permissão): só o usuário pode responder.
            snapshot.protectedWindow?.let { window ->
                if (++handoffs > Limits.MAX_HANDOFFS) return End.Failed("A janela “$window” continua aberta. Responda e peça de novo.")
                logger.event("handoff", "step" to step, "window" to window)
                val description = "Responda você: “$window”. Depois toque em Permitir para eu continuar."
                // A máquina de estados só aceita confirmação a partir de Reasoning e volta por Acting.
                controller.moveTo(AgentState.Running(task, step, Phase.Reasoning))
                if (!controller.requestConfirmation(task, step, description, Limits.CONFIRMATION_TIMEOUT_MS)) return End.Denied
                controller.moveTo(AgentState.Running(task, step, Phase.Acting))
                continue
            }
            (safety.checkScreen(snapshot) as? SafetyDecision.Block)?.let { return End.Failed(it.reason) }
            // Toque, digitação ou rolagem que não mudou nada: o modelo precisa saber para tentar outra coisa.
            if (lastAction.isUiGesture() && snapshot.hash == lastScreenHash && history.lastOrNull()?.endsWith(OK) == true) {
                history[history.lastIndex] = history.last() + NO_CHANGE
            } else if (lastToggle != null && history.lastOrNull()?.endsWith(OK) == true) {
                findToggle(lastToggle, snapshot)?.let { now ->
                    // O interruptor do pedido chegou ao estado pedido: concluído, sem perguntar ao modelo.
                    if (toggleGoal != null && toggleGoal.matches(lastToggle, task) && toggleGoal.reachedBy(now)) {
                        // Finished só vale a partir de Acting.
                        controller.moveTo(AgentState.Running(task, step, Phase.Reasoning))
                        controller.moveTo(AgentState.Running(task, step, Phase.Acting))
                        return End.Finished("${now.label} ${if (toggleGoal.on) "ligado" else "desligado"}.")
                    }
                    // SLMs não ligam "toquei no interruptor" a "agora está desligado" só pela tela: diz explicitamente.
                    history[history.lastIndex] = history.last() + ", switch is now ${if (ToggleGoal.isOn(now)) "ON" else "OFF"}"
                }
            }

            // Formulário pronto (SMS, contato...): aponta o botão final, que o SLM sozinho não acha.
            awaitingTap?.let { spec ->
                val last = history.lastOrNull()
                val button = snapshot.elements.firstOrNull { "click" in it.flags && spec.finalButtonPattern().containsMatchIn(it.label) }
                if (button != null && last != null && NEXT_HINT !in last) {
                    history[history.lastIndex] = "$last$NEXT_HINT\"${button.label}\""
                }
            }

            // 2. raciocinar
            controller.moveTo(AgentState.Running(task, step, Phase.Reasoning))
            val outcome = reasoner.decide(
                PromptInput(task, history, snapshot.toScreenContext(), perception.recentNotifications(), now()),
            )
            val call = outcome.call ?: return End.Failed("O modelo não conseguiu decidir a próxima ação.")
            controller.moveTo(AgentState.Running(task, step, Phase.Reasoning, call.thought))
            logger.event("decision", "step" to step, "thought" to call.thought, "tool" to call.name, "args" to call.args, "attempts" to outcome.attempts.size)

            val action = when (val mapped = mapper.map(call, task)) {
                is MapResult.Success -> mapped.action
                is MapResult.Failure -> {
                    // Valor semanticamente inválido (ex.: horário 25:00): vira erro no histórico para o modelo corrigir.
                    controller.addStep(AgentStep(step, call.thought, call.name, AgentStep.Status.Failed(mapped.detail)))
                    controller.moveTo(AgentState.Running(task, step, Phase.Acting, call.thought))
                    history += "${call.name} ${call.args} -> invalid: ${mapped.detail}"
                    if (++consecutiveErrors >= Limits.MAX_CONSECUTIVE_ERRORS) {
                        return End.Failed("O modelo errou o formato da ação várias vezes seguidas.")
                    }
                    continue
                }
            }

            // 3. validar
            controller.addStep(AgentStep(step, call.thought, describe(action, snapshot), AgentStep.Status.Running))
            when (val decision = safety.evaluate(action, snapshot)) {
                is SafetyDecision.Block -> {
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Blocked(decision.reason)) }
                    logger.event("blocked", "step" to step, "reason" to decision.reason)
                    return End.Failed(decision.reason)
                }
                is SafetyDecision.RequireConfirmation -> {
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.AwaitingConfirmation) }
                    val approved = controller.requestConfirmation(task, step, decision.reason, Limits.CONFIRMATION_TIMEOUT_MS)
                    logger.event("confirmation", "step" to step, "approved" to approved)
                    if (!approved) {
                        controller.updateStep(step) { it.copy(status = AgentStep.Status.Denied) }
                        return End.Denied
                    }
                }
                SafetyDecision.Allow -> Unit
            }

            // 4. agir
            controller.moveTo(AgentState.Running(task, step, Phase.Acting, call.thought))
            if (action is AgentAction.Done) {
                // SLMs às vezes declaram sucesso depois de só errar. Sem nenhuma ação bem-sucedida, não é sucesso.
                if (action.success && executedActions > 0 && succeededActions == 0) {
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Failed("Nenhuma ação deu certo")) }
                    return End.Failed("O agente disse que concluiu, mas nenhuma das ações deu certo.")
                }
                // E também declaram "enviado" logo após abrir a mensagem pronta, sem tocar em Enviar.
                val pending = awaitingTap
                if (action.success && pending != null) {
                    val button = pending.finalButton()
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Failed("Ainda falta tocar em $button")) }
                    logger.event("premature_done", "step" to step, "spec" to pending.toString())
                    if (++prematureDones > Limits.MAX_PREMATURE_DONES) {
                        return End.Failed("O agente disse que concluiu, mas não tocou em $button. Confira a tela.")
                    }
                    history += "done -> rejected: nothing was sent or saved yet. Tap the ${pending.finalButtonEn()} button on the SCREEN first"
                    continue
                }
                controller.updateStep(step) { it.copy(status = AgentStep.Status.Done) }
                return if (action.success) {
                    End.Finished(action.message.ifBlank { "Pronto." })
                } else {
                    End.Failed(action.message.ifBlank { "Não foi possível concluir a tarefa." })
                }
            }
            val target = ((action as? AgentAction.Click)?.target as? Target.ById)?.let { snapshot.byId(it.index) }
            if (target != null && toggleGoal != null && toggleGoal.matches(target, task) && toggleGoal.reachedBy(target)) {
                // Tocar agora inverteria o que o usuário pediu.
                controller.updateStep(step) { it.copy(status = AgentStep.Status.Done) }
                return End.Finished("${target.label} já estava ${if (toggleGoal.on) "ligado" else "desligado"}.")
            }
            val result = actuator.execute(action, snapshot)
            executedActions++
            if (result == ActionResult.Ok) succeededActions++
            // 5. tela estável
            actuator.awaitIdle(action)
            logger.event("action", "step" to step, "action" to action.toString(), "result" to result.toString(), "screen" to snapshot.hash)

            history += historyLine(call, action, snapshot, result)
            lastAction = action
            lastScreenHash = snapshot.hash
            lastToggle = target?.takeIf { it.role in ToggleGoal.TOGGLE_ROLES }
            when (result) {
                ActionResult.Ok -> {
                    consecutiveErrors = 0
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Done) }
                    // Intents que concluem a tarefa sozinhas: dispensa uma chamada ao modelo só para dizer "pronto".
                    val spec = (action as? AgentAction.LaunchIntent)?.spec
                    if (spec != null && spec.isTerminal()) return End.Finished(finishedMessage(spec))
                    // Formulário preenchido pelo intent + toque no botão final (Enviar/Salvar) = tarefa feita.
                    val form = awaitingTap
                    if (form != null && action is AgentAction.Click && target != null &&
                        form.finalButtonPattern().containsMatchIn(target.label)
                    ) {
                        return End.Finished(formDoneMessage(form))
                    }
                    when {
                        spec != null -> awaitingTap = spec.takeIf { it.needsFinalTap() }
                        action is AgentAction.Click || (action is AgentAction.Type && action.submit) -> awaitingTap = null
                    }
                }
                is ActionResult.Error -> {
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Failed(result.reason)) }
                    if (++consecutiveErrors >= Limits.MAX_CONSECUTIVE_ERRORS) {
                        return End.Failed("Várias ações seguidas falharam. Última: ${result.reason}")
                    }
                }
            }
            if (loopDetector.record(snapshot.hash, action)) {
                return End.Failed("O agente repetiu a mesma ação na mesma tela; parei para não ficar em loop.")
            }
        }
        return End.Failed("Limite de ${Limits.MAX_STEPS} passos atingido.")
    }

    /** Linha do HISTORY no prompt. Usa o rótulo do elemento: ids mudam de uma tela para outra. */
    private fun historyLine(call: ToolCall, action: AgentAction, snapshot: ScreenSnapshot, result: ActionResult): String {
        val what = when (action) {
            is AgentAction.Click -> "click \"${label(action.target, snapshot)}\""
            is AgentAction.Type -> "type \"${action.text}\"" + if (action.submit) " + enter" else ""
            is AgentAction.Scroll -> "scroll ${action.direction.name.lowercase()}"
            is AgentAction.LaunchIntent -> "intent ${call.args["kind"]} ${call.args["value"]}".replace("\"", "")
            is AgentAction.Navigate -> action.key.name.lowercase()
            is AgentAction.Done -> "done"
        }
        val outcome = when (result) {
            ActionResult.Ok -> "ok"
            is ActionResult.Error -> "failed: ${result.reason}"
        }
        return "$what -> $outcome"
    }

    /** Descrição em PT-BR para a linha do tempo da UI. */
    private fun describe(action: AgentAction, snapshot: ScreenSnapshot): String = when (action) {
        is AgentAction.Click -> "Tocar em “${label(action.target, snapshot)}”"
        is AgentAction.Type -> "Digitar “${action.text}”" + if (action.submit) " e enviar" else ""
        is AgentAction.Scroll -> "Rolar para " + when (action.direction) {
            Direction.Up -> "cima"
            Direction.Down -> "baixo"
            Direction.Left -> "a esquerda"
            Direction.Right -> "a direita"
        }
        is AgentAction.LaunchIntent -> when (val spec = action.spec) {
            is IntentSpec.OpenApp -> "Abrir ${spec.name}"
            is IntentSpec.Alarm -> "Criar alarme para %02d:%02d".format(spec.hour, spec.minute) + (spec.label?.let { " ($it)" } ?: "")
            is IntentSpec.Timer -> "Iniciar timer de " + if (spec.seconds % 60 == 0) "${spec.seconds / 60} min" else "${spec.seconds} s"
            is IntentSpec.Url -> "Abrir ${spec.url}"
            is IntentSpec.Dial -> "Abrir o discador com ${spec.target}"
            is IntentSpec.WebSearch -> "Pesquisar “${spec.query}”"
            is IntentSpec.AddContact -> "Abrir novo contato: ${spec.name}" + (spec.phone?.let { " ($it)" } ?: "")
            is IntentSpec.Settings -> "Abrir configurações: ${settingsLabel(spec.page)}"
            is IntentSpec.Sms -> "Escrever SMS para ${spec.recipient}: “${spec.text}”"
            is IntentSpec.WhatsApp -> "Escrever no WhatsApp para ${spec.recipient}: “${spec.text}”"
            is IntentSpec.Email -> "Escrever e-mail para ${spec.to}"
            is IntentSpec.Event -> "Criar evento “${spec.title}” em ${spec.start.format(EVENT_FORMAT)}"
            is IntentSpec.Navigate -> "Navegar até ${spec.destination}"
        }
        is AgentAction.Navigate -> when (action.key) {
            NavKey.Back -> "Voltar"
            NavKey.Home -> "Ir para a tela inicial"
            NavKey.Recents -> "Abrir apps recentes"
        }
        is AgentAction.Done -> if (action.success) "Concluir" else "Desistir"
    }

    /**
     * Alarme, timer, busca, link, rota e discador concluem o pedido assim que abrem. As demais (contato,
     * mensagem, e-mail, evento, Configurações) ainda precisam de um toque, como Salvar ou Enviar.
     */
    private fun IntentSpec.isTerminal(): Boolean = when (this) {
        is IntentSpec.Alarm, is IntentSpec.Timer, is IntentSpec.WebSearch, is IntentSpec.Url,
        is IntentSpec.Navigate, is IntentSpec.Dial -> true
        else -> false
    }

    private fun finishedMessage(spec: IntentSpec): String = when (spec) {
        is IntentSpec.Alarm -> "Alarme criado para %02d:%02d.".format(spec.hour, spec.minute)
        is IntentSpec.Timer -> "Timer de " + (if (spec.seconds % 60 == 0) "${spec.seconds / 60} min" else "${spec.seconds} s") + " iniciado."
        is IntentSpec.WebSearch -> "Abri a pesquisa por “${spec.query}”."
        is IntentSpec.Url -> "Abri ${spec.url}."
        is IntentSpec.Navigate -> "Abri a rota até ${spec.destination}."
        is IntentSpec.Dial -> "Abri o discador com ${spec.target}; toque em ligar para completar."
        else -> "Pronto."
    }

    private fun formDoneMessage(spec: IntentSpec): String = when (spec) {
        is IntentSpec.AddContact -> "Contato “${spec.name}” salvo."
        is IntentSpec.Sms -> "SMS enviado para ${spec.recipient}."
        is IntentSpec.WhatsApp -> "Mensagem enviada no WhatsApp para ${spec.recipient}."
        is IntentSpec.Email -> "E-mail enviado para ${spec.to}."
        is IntentSpec.Event -> "Evento “${spec.title}” salvo."
        else -> "Pronto."
    }

    private fun settingsLabel(page: SettingsPage): String = when (page) {
        SettingsPage.Wifi -> "Wi-Fi"
        SettingsPage.Internet -> "internet"
        SettingsPage.Bluetooth -> "Bluetooth"
        SettingsPage.Display -> "tela"
        SettingsPage.Sound -> "som"
        SettingsPage.Battery -> "bateria"
        SettingsPage.Location -> "localização"
        SettingsPage.Apps -> "apps"
        SettingsPage.Notifications -> "notificações"
        SettingsPage.Storage -> "armazenamento"
        SettingsPage.Airplane -> "modo avião"
        SettingsPage.DateTime -> "data e hora"
        SettingsPage.Language -> "idioma"
    }

    /** Formulários abertos já preenchidos que só valem depois de Enviar/Salvar. */
    private fun IntentSpec.needsFinalTap(): Boolean = when (this) {
        is IntentSpec.Sms, is IntentSpec.WhatsApp, is IntentSpec.Email, is IntentSpec.AddContact, is IntentSpec.Event -> true
        else -> false
    }

    private fun IntentSpec.finalButton() = if (this is IntentSpec.AddContact || this is IntentSpec.Event) "Salvar" else "Enviar"

    private fun IntentSpec.finalButtonPattern() =
        if (this is IntentSpec.AddContact || this is IntentSpec.Event) SAVE_BUTTON else SEND_BUTTON

    private fun IntentSpec.finalButtonEn() = if (this is IntentSpec.AddContact || this is IntentSpec.Event) "Save" else "Send"

    /** Mesmo interruptor na tela nova: por rótulo, senão por posição. */
    private fun findToggle(toggle: UiElement, snapshot: ScreenSnapshot): UiElement? {
        val candidates = snapshot.elements.filter { it.role == toggle.role }
        return candidates.firstOrNull { toggle.label.isNotBlank() && it.label == toggle.label }
            ?: candidates.firstOrNull { it.bounds == toggle.bounds }
    }

    private fun AgentAction?.isUiGesture() =
        this is AgentAction.Click || this is AgentAction.Type || this is AgentAction.Scroll

    /** "2026-10-26 13:05, Monday": em inglês, como o resto do prompt. */
    private fun now(): String = LocalDateTime.now().format(NOW_FORMAT)

    private fun label(target: Target, snapshot: ScreenSnapshot): String = when (target) {
        is Target.ById -> snapshot.byId(target.index)?.label?.ifBlank { null } ?: "elemento ${target.index}"
        is Target.ByText -> target.text
        is Target.ByViewId -> target.resourceId
        is Target.ByCoordinates -> "${target.x},${target.y}"
    }

    private companion object {
        const val OK = "-> ok"
        const val NO_CHANGE = ", but the SCREEN did not change"
        const val NEXT_HINT = ", next: tap "
        val SEND_BUTTON = Regex("""^(send|enviar)\b""", RegexOption.IGNORE_CASE)
        val SAVE_BUTTON = Regex("""^(save|salvar|guardar|done|concluído|concluido)\b""", RegexOption.IGNORE_CASE)
        val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm, EEEE", Locale.US)
        val EVENT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    }

    private fun ScreenSnapshot.toScreenContext() = ScreenContext(
        packageName = packageName,
        title = title,
        lines = elements.map { ScreenLine(it.id, it.line(), it.priority) },
    )
}
