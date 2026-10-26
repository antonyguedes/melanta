package com.melanta.feature.agent.action

import com.melanta.core.automation.screen.UiElement
import java.text.Normalizer

/**
 * Estado que o pedido quer para um interruptor ("Desliga o wi-fi" = Wi-Fi desligado).
 *
 * SLMs não acompanham o estado de um interruptor entre passos e ficam ligando e desligando.
 * Com o objetivo explícito, o orquestrador encerra assim que o interruptor certo chega lá.
 */
data class ToggleGoal(val on: Boolean) {

    /** O interruptor é o do pedido: alguma palavra do rótulo aparece no texto da tarefa. */
    fun matches(element: UiElement, task: String): Boolean {
        if (element.role !in TOGGLE_ROLES) return false
        val compactTask = compact(task)
        return normalize(element.label).split(' ', '/')
            .map(::compact)
            .any { it.length >= MIN_WORD && it !in STOPWORDS && it in compactTask }
    }

    fun reachedBy(element: UiElement): Boolean = isOn(element) == on

    companion object {
        val TOGGLE_ROLES = setOf("sw", "chk")
        private const val MIN_WORD = 3
        private val STOPWORDS = setOf("use", "usar", "the", "com", "para", "modo", "mode")

        private val OFF = Regex("""\b(desliga\w*|desativ\w*|apag\w*|turn off|switch off|disable\w*)\b""")
        private val ON = Regex("""\b(liga|ligue|ligar|ative|ativa|ativar|acend\w*|turn on|switch on|enable\w*)\b""")
        // "Liga para a Ana" é ligação, não interruptor.
        private val CALL = Regex("""\bliga\w*\s+(para|pra|pro)\b""")

        fun from(task: String): ToggleGoal? {
            val t = normalize(task)
            return when {
                OFF.containsMatchIn(t) -> ToggleGoal(on = false)
                CALL.containsMatchIn(t) -> null
                ON.containsMatchIn(t) -> ToggleGoal(on = true)
                else -> null
            }
        }

        fun isOn(element: UiElement): Boolean = "checked" in element.flags

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        private fun compact(text: String): String = normalize(text).filter(Char::isLetterOrDigit)
    }
}
