package com.melanta.core.ai.cloud

import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.common.SensitiveContent
import javax.inject.Inject

/**
 * Tira do prompt o que não deve sair do aparelho no modo Nuvem. É uma redução de risco, não uma
 * garantia: nomes e o texto comum da tela continuam indo, e o consentimento diz isso ao usuário.
 *
 * O pedido fica como o usuário escreveu, e o que ele mesmo citou no pedido não é mascarado: sem isso
 * o modelo não reconheceria na tela o número ou o e-mail que deve usar.
 */
class ScreenRedactor @Inject constructor() {

    fun redact(input: PromptInput, includeNotificationBodies: Boolean): PromptInput {
        if (SensitiveContent.isBlockedPackage(input.screen.packageName)) throw CloudSensitiveScreenException()
        val task = TaskValues(input.task)
        return input.copy(
            history = input.history.map { mask(it, task) },
            screen = input.screen.copy(
                title = input.screen.title?.let { mask(it, task) },
                lines = input.screen.lines.map { it.copy(text = redactLine(it.text, task)) },
            ),
            notifications = input.notifications.map { line ->
                if (includeNotificationBodies) mask(line, task) else line.substringBefore(':') + ": (conteúdo oculto)"
            },
        )
    }

    private fun redactLine(line: String, task: TaskValues): String {
        val match = LINE.matchEntire(line) ?: return mask(line, task)
        val (prefix, role, label, flags) = match.destructured
        val hidden = role == "edit" && (
            "password" in flags.split(' ') ||
                SensitiveContent.SENSITIVE_FIELD.containsMatchIn(SensitiveContent.normalize(label))
            )
        val safeLabel = if (hidden) HIDDEN_FIELD else mask(label, task)
        return "$prefix$role \"$safeLabel\"$flags"
    }

    private fun mask(text: String, task: TaskValues): String = text
        .replace(LABELED_SECRET, "$1 «oculto»")
        .replace(EMAIL) { if (task.mentions(it.value)) it.value else "«e-mail»" }
        .replace(LONG_NUMBER) { if (task.mentionsDigits(it.value)) it.value else "«número»" }
        .replace(LOCAL_PHONE) { if (task.mentionsDigits(it.value)) it.value else "«número»" }

    private class TaskValues(task: String) {
        private val lower = task.lowercase()
        private val digits = task.filter(Char::isDigit)

        fun mentions(value: String) = value.lowercase() in lower

        fun mentionsDigits(value: String) = value.filter(Char::isDigit) in digits
    }

    private companion object {
        const val HIDDEN_FIELD = "«campo sensível oculto»"

        /** `[3] edit "rótulo" click focused`; o rótulo pode conter aspas. */
        val LINE = Regex("""^(\[\d+]\s+)(\w+)\s+"(.*)"((?:\s+\w+)*)\s*$""")

        val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

        /** 10 dígitos ou mais, com separadores: telefone com DDD, CPF, CNPJ, cartão, conta. */
        val LONG_NUMBER = Regex("""\+?\d(?:[\s().-]?\d){9,}""")

        /** Celular ou fixo sem DDD: 98765-4321. */
        val LOCAL_PHONE = Regex("""\b\d{4,5}-\d{4}\b""")

        /** "Seu código é 123456", "senha: abc123". */
        val LABELED_SECRET = Regex(
            """(?i)\b(c[oó]digo|code|otp|token|senha|password|pin)\b(\s*(?:[:=]|[eé]|is|de verifica[cç][aã]o)?\s*)\S*\d\S*""",
        )
    }
}
