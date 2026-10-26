package com.melanta.feature.agent.state

import com.melanta.feature.agent.state.AgentState.Phase
import com.melanta.feature.agent.state.AgentStateMachine.canTransition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStateMachineTest {

    private val task = "põe um alarme para 7h"
    private fun running(step: Int, phase: Phase) = AgentState.Running(task, step, phase)

    @Test
    fun `caminho feliz completo`() {
        val path = listOf(
            AgentState.Idle,
            AgentState.LoadingModel(task),
            running(0, Phase.Perceiving),
            running(0, Phase.Reasoning),
            running(0, Phase.Acting),
            running(1, Phase.Perceiving),
            running(1, Phase.Reasoning),
            AgentState.AwaitingConfirmation(task, 1, "Enviar mensagem"),
            running(1, Phase.Acting),
            AgentState.Finished(task, "Pronto"),
            AgentState.Idle,
        )
        path.zipWithNext().forEach { (from, to) -> assertTrue("$from -> $to", canTransition(from, to)) }
    }

    @Test
    fun `re-prompt mantem Reasoning no mesmo passo`() {
        assertTrue(canTransition(running(2, Phase.Reasoning), running(2, Phase.Reasoning)))
    }

    @Test
    fun `kill switch e falha valem de qualquer estado ativo`() {
        val active = listOf(
            AgentState.LoadingModel(task),
            running(0, Phase.Perceiving),
            running(3, Phase.Reasoning),
            running(3, Phase.Acting),
            AgentState.AwaitingConfirmation(task, 3, "Ligar"),
        )
        active.forEach {
            assertTrue(canTransition(it, AgentState.Cancelled(task)))
            assertTrue(canTransition(it, AgentState.Failed(task, "erro")))
        }
    }

    @Test
    fun `estados terminais so voltam para Idle`() {
        val terminals = listOf(AgentState.Finished(task, "ok"), AgentState.Failed(task, "x"), AgentState.Cancelled(task))
        terminals.forEach { terminal ->
            assertTrue(canTransition(terminal, AgentState.Idle))
            assertFalse(canTransition(terminal, running(0, Phase.Perceiving)))
            assertFalse(canTransition(terminal, AgentState.Cancelled(task)))
            assertFalse(canTransition(terminal, AgentState.LoadingModel(task)))
        }
    }

    @Test
    fun `transicoes invalidas`() {
        assertFalse(canTransition(AgentState.Idle, running(0, Phase.Perceiving)))
        assertFalse(canTransition(AgentState.Idle, AgentState.Cancelled(task)))
        assertFalse(canTransition(AgentState.LoadingModel(task), running(0, Phase.Acting)))
        assertFalse(canTransition(running(0, Phase.Perceiving), running(0, Phase.Acting)))
        assertFalse(canTransition(running(0, Phase.Acting), running(0, Phase.Perceiving)))
        assertFalse(canTransition(running(0, Phase.Acting), running(2, Phase.Perceiving)))
        assertFalse(canTransition(running(0, Phase.Reasoning), AgentState.Finished(task, "ok")))
        assertFalse(canTransition(running(0, Phase.Perceiving), AgentState.AwaitingConfirmation(task, 0, "x")))
        assertFalse(canTransition(AgentState.AwaitingConfirmation(task, 1, "x"), running(2, Phase.Acting)))
    }

    @Test
    fun `nao troca de tarefa no meio do caminho`() {
        assertFalse(canTransition(AgentState.LoadingModel(task), AgentState.Running("outra", 0, Phase.Perceiving)))
        assertFalse(canTransition(running(0, Phase.Acting), AgentState.Cancelled("outra")))
    }
}
