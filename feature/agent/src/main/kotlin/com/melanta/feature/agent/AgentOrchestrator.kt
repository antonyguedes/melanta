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
import com.melanta.core.automation.action.Target
import com.melanta.core.automation.screen.AccessibilityUnavailableException
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.feature.agent.action.MapResult
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

        for (step in 0 until Limits.MAX_STEPS) {
            // 1. perceber
            controller.moveTo(AgentState.Running(task, step, Phase.Perceiving))
            val snapshot = perception.snapshot()
            (safety.checkScreen(snapshot) as? SafetyDecision.Block)?.let { return End.Failed(it.reason) }

            // 2. raciocinar
            controller.moveTo(AgentState.Running(task, step, Phase.Reasoning))
            val outcome = reasoner.decide(
                PromptInput(task, history, snapshot.toScreenContext(), perception.recentNotifications()),
            )
            val call = outcome.call ?: return End.Failed("O modelo não conseguiu decidir a próxima ação.")
            controller.moveTo(AgentState.Running(task, step, Phase.Reasoning, call.thought))
            logger.event("decision", "step" to step, "thought" to call.thought, "tool" to call.name, "args" to call.args, "attempts" to outcome.attempts.size)

            val action = when (val mapped = mapper.map(call)) {
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
                controller.updateStep(step) { it.copy(status = AgentStep.Status.Done) }
                return if (action.success) {
                    End.Finished(action.message.ifBlank { "Pronto." })
                } else {
                    End.Failed(action.message.ifBlank { "Não foi possível concluir a tarefa." })
                }
            }
            val result = actuator.execute(action, snapshot)
            executedActions++
            if (result == ActionResult.Ok) succeededActions++
            // 5. tela estável
            actuator.awaitIdle(action)
            logger.event("action", "step" to step, "action" to action.toString(), "result" to result.toString(), "screen" to snapshot.hash)

            history += historyLine(call, action, snapshot, result)
            when (result) {
                ActionResult.Ok -> {
                    consecutiveErrors = 0
                    controller.updateStep(step) { it.copy(status = AgentStep.Status.Done) }
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
            is IntentSpec.Dial -> "Abrir o discador com ${spec.number}"
            is IntentSpec.WebSearch -> "Pesquisar “${spec.query}”"
            is IntentSpec.AddContact -> "Abrir novo contato: ${spec.name}" + (spec.phone?.let { " ($it)" } ?: "")
        }
        is AgentAction.Navigate -> when (action.key) {
            NavKey.Back -> "Voltar"
            NavKey.Home -> "Ir para a tela inicial"
            NavKey.Recents -> "Abrir apps recentes"
        }
        is AgentAction.Done -> if (action.success) "Concluir" else "Desistir"
    }

    private fun label(target: Target, snapshot: ScreenSnapshot): String = when (target) {
        is Target.ById -> snapshot.byId(target.index)?.label?.ifBlank { null } ?: "elemento ${target.index}"
        is Target.ByText -> target.text
        is Target.ByViewId -> target.resourceId
        is Target.ByCoordinates -> "${target.x},${target.y}"
    }

    private fun ScreenSnapshot.toScreenContext() = ScreenContext(
        packageName = packageName,
        title = title,
        lines = elements.map { ScreenLine(it.id, it.line(), it.priority) },
    )
}
