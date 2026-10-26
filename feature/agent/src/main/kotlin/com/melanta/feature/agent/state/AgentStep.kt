package com.melanta.feature.agent.state

/** Um passo da linha do tempo exibida ao usuário: o que o modelo pensou, o que fez e o resultado. */
data class AgentStep(
    val index: Int,
    val thought: String?,
    /** Descrição da ação em PT-BR ("Tocar em “Ana”", "Abrir WhatsApp"). */
    val action: String,
    val status: Status,
) {
    sealed interface Status {
        data object Running : Status
        data object AwaitingConfirmation : Status
        data object Done : Status
        data class Failed(val reason: String) : Status
        data class Blocked(val reason: String) : Status
        data object Denied : Status
    }
}
