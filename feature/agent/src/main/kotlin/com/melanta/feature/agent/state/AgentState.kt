package com.melanta.feature.agent.state

sealed interface AgentState {

    data object Idle : AgentState

    data class LoadingModel(val task: String) : AgentState

    data class Running(
        val task: String,
        val step: Int,
        val phase: Phase,
        val lastThought: String? = null,
    ) : AgentState

    data class AwaitingConfirmation(
        val task: String,
        val step: Int,
        val description: String,
    ) : AgentState

    data class Finished(val task: String, val message: String) : AgentState

    data class Failed(val task: String, val reason: String) : AgentState

    data class Cancelled(val task: String) : AgentState

    enum class Phase { Perceiving, Reasoning, Acting }
}

val AgentState.task: String?
    get() = when (this) {
        AgentState.Idle -> null
        is AgentState.LoadingModel -> task
        is AgentState.Running -> task
        is AgentState.AwaitingConfirmation -> task
        is AgentState.Finished -> task
        is AgentState.Failed -> task
        is AgentState.Cancelled -> task
    }

/** Há um loop em andamento que o kill switch pode interromper. */
val AgentState.isActive: Boolean
    get() = this is AgentState.LoadingModel || this is AgentState.Running || this is AgentState.AwaitingConfirmation

val AgentState.isTerminal: Boolean
    get() = this is AgentState.Finished || this is AgentState.Failed || this is AgentState.Cancelled
