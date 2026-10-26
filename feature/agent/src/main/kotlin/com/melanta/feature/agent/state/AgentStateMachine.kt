package com.melanta.feature.agent.state

import com.melanta.feature.agent.state.AgentState.Phase

/**
 * Transições válidas do agente (seção 1.4 do plano).
 *
 * Em relação ao diagrama, `Failed` e `Cancelled` são aceitos a partir de qualquer estado ativo:
 * uma exceção ou o kill switch podem acontecer em qualquer fase, inclusive durante a carga do modelo.
 */
internal object AgentStateMachine {

    fun canTransition(from: AgentState, to: AgentState): Boolean {
        if (to !is AgentState.Idle && from !is AgentState.Idle && to.task != from.task) return false
        if (from.isActive && (to is AgentState.Failed || to is AgentState.Cancelled)) return true

        return when (from) {
            AgentState.Idle -> to is AgentState.LoadingModel
            is AgentState.LoadingModel -> to is AgentState.Running && to.phase == Phase.Perceiving && to.step == 0
            is AgentState.Running -> when (to) {
                is AgentState.Running -> isNextPhase(from, to)
                is AgentState.AwaitingConfirmation -> from.phase == Phase.Reasoning && to.step == from.step
                is AgentState.Finished -> from.phase == Phase.Acting
                else -> false
            }
            is AgentState.AwaitingConfirmation ->
                to is AgentState.Running && to.phase == Phase.Acting && to.step == from.step
            is AgentState.Finished, is AgentState.Failed, is AgentState.Cancelled -> to is AgentState.Idle
        }
    }

    private fun isNextPhase(from: AgentState.Running, to: AgentState.Running): Boolean = when (from.phase) {
        Phase.Perceiving -> to.phase == Phase.Reasoning && to.step == from.step
        // Reasoning -> Reasoning: re-prompt corretivo após JSON inválido.
        Phase.Reasoning -> (to.phase == Phase.Reasoning || to.phase == Phase.Acting) && to.step == from.step
        Phase.Acting -> to.phase == Phase.Perceiving && to.step == from.step + 1
    }
}
