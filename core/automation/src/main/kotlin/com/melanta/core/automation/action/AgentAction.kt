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

    /** [target] é um número ou o nome de um contato. Só abre o discador: ligar é sempre um toque do usuário. */
    data class Dial(val target: String) : IntentSpec
    data class WebSearch(val query: String) : IntentSpec

    /** Abre o formulário de novo contato já preenchido; salvar continua sendo um toque na tela. */
    data class AddContact(val name: String, val phone: String?) : IntentSpec

    /** Página (ou painel) de Configurações; evita navegar pelos menus. */
    data class Settings(val page: SettingsPage) : IntentSpec

    /** Abre o app de SMS com o texto preenchido. Enviar é um toque na tela (e pede confirmação). */
    data class Sms(val recipient: String, val text: String) : IntentSpec

    /** Abre a conversa do WhatsApp com o texto preenchido (wa.me). Enviar é um toque na tela. */
    data class WhatsApp(val recipient: String, val text: String) : IntentSpec

    data class Email(val to: String, val subject: String, val body: String) : IntentSpec

    /** Abre o formulário de evento da agenda preenchido; salvar é um toque na tela. */
    data class Event(val title: String, val start: java.time.LocalDateTime) : IntentSpec

    /** Inicia a navegação até [destination] no app de mapas. */
    data class Navigate(val destination: String) : IntentSpec
}

enum class SettingsPage { Wifi, Internet, Bluetooth, Display, Sound, Battery, Location, Apps, Notifications, Storage, Airplane, DateTime, Language }

sealed interface ActionResult {
    data object Ok : ActionResult
    data class Error(val reason: String) : ActionResult
}
