package com.melanta.core.automation.action

/** Ação tipada que o [ActionExecutor] sabe executar. Contrato da seção 1.5 do plano. */
sealed interface AgentAction {
    data class Click(val target: Target) : AgentAction
    data class Type(val target: Target?, val text: String, val submit: Boolean) : AgentAction
    data class Scroll(val direction: Direction, val target: Target?) : AgentAction

    /** Tipos fechados: o modelo nunca escolhe uma action ou um componente arbitrário. */
    data class LaunchIntent(val spec: IntentSpec) : AgentAction
    data class Navigate(val key: NavKey) : AgentAction
    data class Done(val success: Boolean, val message: String) : AgentAction
}

sealed interface Target {
    /** Índice vindo do [com.melanta.core.automation.screen.ScreenSnapshot] (preferido). */
    data class ById(val index: Int) : Target
    data class ByText(val text: String) : Target
    data class ByViewId(val resourceId: String) : Target
    data class ByCoordinates(val x: Int, val y: Int) : Target
}

enum class Direction { Up, Down, Left, Right }

enum class NavKey { Back, Home, Recents }

sealed interface IntentSpec {
    data class OpenApp(val name: String) : IntentSpec
    data class Alarm(val hour: Int, val minute: Int, val label: String?) : IntentSpec
    data class Timer(val seconds: Int, val label: String? = null) : IntentSpec
    data class Url(val url: String) : IntentSpec
    data class Dial(val number: String) : IntentSpec
    data class WebSearch(val query: String) : IntentSpec

    /** Abre o formulário de novo contato já preenchido; salvar continua sendo um toque na tela. */
    data class AddContact(val name: String, val phone: String?) : IntentSpec
}

sealed interface ActionResult {
    data object Ok : ActionResult
    data class Error(val reason: String) : ActionResult
}
