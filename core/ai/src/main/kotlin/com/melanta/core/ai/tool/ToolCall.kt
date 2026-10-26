package com.melanta.core.ai.tool

import kotlinx.serialization.json.JsonObject

/**
 * Chamada de ferramenta já normalizada pelo [ToolCallParser].
 *
 * [name] é sempre um dos nomes canônicos de [Tools] e [args] segue o formato canônico de cada
 * ferramenta (ids como inteiros, flags como booleanos). O `:feature:agent` converte isso em `AgentAction`.
 */
data class ToolCall(
    val thought: String?,
    val name: String,
    val args: JsonObject,
)

object Tools {
    const val CLICK = "click"
    const val TYPE = "type"
    const val SCROLL = "scroll"
    const val INTENT = "intent"
    const val BACK = "back"
    const val DONE = "done"

    val ALL = listOf(CLICK, TYPE, SCROLL, INTENT, BACK, DONE)

    val SCROLL_DIRECTIONS = listOf("up", "down", "left", "right")

    val INTENT_KINDS = listOf(
        "open_app", "alarm", "timer", "url", "dial", "web_search", "add_contact",
        "settings", "sms", "whatsapp", "email", "event", "navigate",
    )

    /** Intents cujo valor tem várias partes ("Destinatário | texto"), na ordem dos campos aceitos. */
    val MULTI_FIELD_INTENTS: Map<String, List<List<String>>> = mapOf(
        "add_contact" to listOf(listOf("name"), listOf("phone", "number")),
        "sms" to listOf(listOf("to", "recipient", "contact", "name", "number", "phone"), listOf("text", "message", "body")),
        "whatsapp" to listOf(listOf("to", "recipient", "contact", "name", "number", "phone"), listOf("text", "message", "body")),
        "email" to listOf(listOf("to", "recipient", "email"), listOf("subject", "title"), listOf("body", "text", "message")),
        "event" to listOf(listOf("title", "name"), listOf("datetime", "start", "date", "time")),
    )
}
