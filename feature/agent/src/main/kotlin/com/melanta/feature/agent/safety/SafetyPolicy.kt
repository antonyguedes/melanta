package com.melanta.feature.agent.safety

import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.Target
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.screen.UiElement
import com.melanta.core.common.SensitiveContent
import javax.inject.Inject

sealed interface SafetyDecision {
    data object Allow : SafetyDecision

    /** Ação irreversível (enviar, ligar, excluir): só segue com o "sim" do usuário. [reason] em PT-BR. */
    data class RequireConfirmation(val reason: String) : SafetyDecision

    /** Nunca executar. [reason] em PT-BR, mostrado ao usuário. */
    data class Block(val reason: String) : SafetyDecision
}

/**
 * Regras fixas de segurança. Não dependem do modelo: na avaliação da Fase 3, todos os modelos
 * testados tentaram concluir pagamentos ou compras em algum cenário.
 */
class SafetyPolicy @Inject constructor() {

    /** Telas onde o agente não opera, independentemente da ação. */
    fun checkScreen(snapshot: ScreenSnapshot): SafetyDecision {
        val pkg = snapshot.packageName
        if (SensitiveContent.isBlockedPackage(pkg)) {
            return SafetyDecision.Block("Por segurança, o Melanta não opera apps de banco, pagamento ou senhas.")
        }
        val title = normalize(snapshot.title.orEmpty())
        if (pkg in SETTINGS_PACKAGES && SECURITY_SETTINGS.containsMatchIn(title)) {
            return SafetyDecision.Block("Por segurança, o Melanta não mexe nas configurações de segurança e senhas.")
        }
        return SafetyDecision.Allow
    }

    fun evaluate(action: AgentAction, snapshot: ScreenSnapshot): SafetyDecision {
        checkScreen(snapshot).let { if (it !is SafetyDecision.Allow) return it }
        return when (action) {
            is AgentAction.Click -> {
                val text = describe(action.target, snapshot)
                when {
                    PAYMENT.containsMatchIn(text) ->
                        SafetyDecision.Block("Pagamentos e compras não são feitos pelo agente. Conclua você mesmo.")
                    IRREVERSIBLE.containsMatchIn(text) ->
                        SafetyDecision.RequireConfirmation(
                            "Tocar em \"${labelOf(action.target, snapshot)}\"" + if (isSending(action, snapshot)) recipientHint(snapshot) else "",
                        )
                    else -> SafetyDecision.Allow
                }
            }
            is AgentAction.Type -> {
                val field = fieldOf(action, snapshot)
                when {
                    SensitiveContent.SENSITIVE_FIELD.containsMatchIn(field) ->
                        SafetyDecision.Block("O agente nunca digita senhas, PINs ou dados de cartão.")
                    looksLikeCardNumber(action.text) ->
                        SafetyDecision.Block("O agente nunca digita dados de cartão.")
                    action.submit && !SEARCH_FIELD.containsMatchIn(field) ->
                        SafetyDecision.RequireConfirmation("Enviar \"${action.text.take(MAX_QUOTE)}\"" + recipientHint(snapshot))
                    else -> SafetyDecision.Allow
                }
            }
            is AgentAction.Scroll, is AgentAction.LaunchIntent, is AgentAction.Navigate, is AgentAction.Done ->
                SafetyDecision.Allow
        }
    }

    /**
     * Número ou e-mail que o modelo inventou: SLMs erram dígitos ao copiar, e o erro vira mensagem para um
     * estranho. O destino precisa estar escrito no pedido, na tela ou em uma notificação. Nomes de contato
     * não passam por aqui: o telefone deles vem da agenda. Retorna o valor sem origem, ou `null` se estiver ok.
     */
    fun unverifiedContact(action: AgentAction, task: String, snapshot: ScreenSnapshot, notifications: List<String>): String? {
        val value = when (action) {
            is AgentAction.LaunchIntent -> when (val spec = action.spec) {
                is IntentSpec.Sms -> spec.recipient
                is IntentSpec.WhatsApp -> spec.recipient
                is IntentSpec.Email -> spec.to
                is IntentSpec.AddContact -> spec.phone
                // Números curtos (190) só abrem o discador, e quem completa a ligação é o usuário.
                is IntentSpec.Dial -> spec.target.takeIf { it.count(Char::isDigit) >= MIN_PHONE_DIGITS }
                else -> null
            }
            // Só quando o texto inteiro é um telefone: frases com números e datas são outra coisa.
            is AgentAction.Type -> action.text.trim().takeIf {
                it.count(Char::isDigit) >= MIN_PHONE_DIGITS && PHONE.matches(it) && !DATE.containsMatchIn(it)
            }
            else -> null
        } ?: return null
        val sources = listOf(task) + notifications + snapshot.elements.map { it.label }
        return when {
            '@' in value -> value.takeUnless { sources.any { source -> source.contains(value, ignoreCase = true) } }
            value.any(Char::isLetter) -> null
            else -> value.takeUnless { sources.any { source -> sameNumberIn(value, source) } }
        }
    }

    /**
     * O pedido cita um telefone, mas a mensagem está para sair de uma tela onde ele não aparece: é outra
     * conversa. Retorna o número pedido. Não vale para formulários abertos pelo próprio agente, cujo destino
     * já foi conferido em [unverifiedContact].
     */
    fun wrongConversation(action: AgentAction, task: String, snapshot: ScreenSnapshot): String? {
        if (!isSending(action, snapshot)) return null
        val asked = PHONE.findAll(task).map { it.value }.filter { it.count(Char::isDigit) >= MIN_FULL_PHONE_DIGITS }.toList()
        if (asked.isEmpty()) return null
        return asked.firstOrNull { number -> snapshot.elements.none { sameNumberIn(number, it.label) } }
    }

    private fun isSending(action: AgentAction, snapshot: ScreenSnapshot): Boolean = when (action) {
        is AgentAction.Click -> SEND.containsMatchIn(describe(action.target, snapshot))
        is AgentAction.Type -> action.submit && !SEARCH_FIELD.containsMatchIn(fieldOf(action, snapshot))
        else -> false
    }

    /**
     * Confirmação de envio pela tela: mostra para quem vai, e só quando há evidência. O título da janela vale
     * se também estiver escrito na tela (o nome da conversa, não o do app). Sem isso, um telefone visível.
     * Sem nenhum dos dois, não sugere nada: um nome errado aqui é pior que nenhum.
     */
    private fun recipientHint(snapshot: ScreenSnapshot): String {
        val title = snapshot.title?.takeIf { title ->
            title.length >= 2 && snapshot.elements.any { it.role != "edit" && it.label.contains(title, ignoreCase = true) }
        }
        val phone = snapshot.elements.firstNotNullOfOrNull { element ->
            PHONE.findAll(element.label).firstOrNull { it.value.count(Char::isDigit) >= MIN_PHONE_DIGITS }?.value
        }
        return (title ?: phone)?.let { " (na tela: ${it.take(MAX_QUOTE)})" }.orEmpty()
    }

    /** O número inteiro precisa bater: um dígito a mais ou a menos é outra pessoa. O DDI 55 e o 0 inicial não contam. */
    private fun sameNumberIn(number: String, source: String): Boolean {
        val wanted = canonicalPhone(number)
        return wanted.isEmpty() || PHONE.findAll(source).any { canonicalPhone(it.value) == wanted }
    }

    private fun canonicalPhone(text: String): String {
        val digits = text.filter(Char::isDigit)
        return when {
            digits.length >= 12 && digits.startsWith("55") -> digits.removePrefix("55")
            digits.startsWith("0") -> digits.removePrefix("0")
            else -> digits
        }
    }

    private fun fieldOf(action: AgentAction.Type, snapshot: ScreenSnapshot): String =
        action.target?.let { describe(it, snapshot) }
            ?: snapshot.elements.firstOrNull { "focused" in it.flags }?.let(::describe).orEmpty()

    private fun describe(target: Target, snapshot: ScreenSnapshot): String = when (target) {
        is Target.ById -> snapshot.byId(target.index)?.let(::describe).orEmpty()
        is Target.ByText -> normalize(target.text)
        is Target.ByViewId -> normalize(target.resourceId.replace('_', ' '))
        is Target.ByCoordinates -> ""
    }

    // A flag `password` vale mesmo quando o rótulo não diz nada: o campo pode ter só o texto digitado.
    private fun describe(element: UiElement): String =
        normalize(element.label + " " + element.viewId.orEmpty().substringAfter("/").replace('_', ' ') + " " + element.flags.filter { it == "password" }.joinToString(" "))

    private fun labelOf(target: Target, snapshot: ScreenSnapshot): String =
        ((target as? Target.ById)?.let { snapshot.byId(it.index)?.label } ?: target.toString()).take(MAX_QUOTE)

    /** Limites do loop (seção 4 do plano). */
    object Limits {
        const val MAX_STEPS = 20
        const val MAX_DURATION_MS = 5 * 60_000L
        const val LOOP_REPEATS = 3
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val CONFIRMATION_TIMEOUT_MS = 60_000L
        /** Vezes que o agente pede para o usuário responder uma janela protegida antes de desistir. */
        const val MAX_HANDOFFS = 2
        /** `done` rejeitados por faltar o toque em Enviar/Salvar antes de encerrar como falha. */
        const val MAX_PREMATURE_DONES = 2
    }

    companion object {
        private const val MAX_QUOTE = 40
        private const val MIN_PHONE_DIGITS = 8
        /** Com DDD. Menos que isso pode ser um protocolo citado no texto da mensagem. */
        private const val MIN_FULL_PHONE_DIGITS = 10
        private val PHONE = Regex("""\+?\d(?:[\s().-]?\d)*""")
        private val SEND = SensitiveContent.words("enviar", "send")
        private val DATE = Regex("""\b\d{4}-\d{2}-\d{2}\b""")

        private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.google.android.settings.intelligence")

        private val SECURITY_SETTINGS = SensitiveContent.words(
            "seguranca", "security", "senha", "senhas", "password", "passwords", "passkeys", "bloqueio de tela",
            "screen lock", "impressao digital", "fingerprint", "biometria", "biometrics", "device admin",
            "administrador do dispositivo", "chaves de acesso",
        )

        private val PAYMENT = SensitiveContent.words(
            "pagar", "pagamento", "confirmar pagamento", "finalizar compra", "comprar", "comprar agora",
            "assinar", "assinatura", "transferir", "transferencia", "pix", "enviar dinheiro", "doar",
            "pay", "payment", "checkout", "buy", "buy now", "purchase", "place order", "subscribe", "send money",
            "transfer", "donate",
        )

        private val IRREVERSIBLE = SensitiveContent.words(
            "enviar", "send", "publicar", "postar", "post", "excluir", "delete", "apagar", "remover", "remove",
            "desinstalar", "uninstall", "ligar", "call", "chamar", "confirmar", "confirm", "aceitar", "accept",
            "bloquear", "block", "sair da conta", "log out", "logout", "redefinir", "reset", "formatar",
        )

        private val SEARCH_FIELD = SensitiveContent.words("pesquisar", "pesquisa", "buscar", "busca", "search", "procurar", "url", "endereco")

        /** 14 a 19 dígitos seguidos (espaços ou hífens opcionais); o `+` capturado separa telefones internacionais. */
        private val CARD_NUMBER = Regex("""\+?\d(?:[ -]?\d){13,18}""")

        /** Número de cartão = sequência de 14 a 19 dígitos que passa no Luhn e não começa com `+` (telefone). */
        internal fun looksLikeCardNumber(text: String): Boolean = CARD_NUMBER.findAll(text).any { match ->
            !match.value.startsWith('+') && luhn(match.value.filter(Char::isDigit))
        }

        private fun luhn(digits: String): Boolean = digits.reversed().mapIndexed { i, c ->
            val d = c.digitToInt()
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum() % 10 == 0

        fun normalize(text: String): String = SensitiveContent.normalize(text)
    }
}

/** Mesma ação na mesma tela [SafetyPolicy.Limits.LOOP_REPEATS] vezes = o agente está andando em círculos. */
class LoopDetector(private val repeats: Int = SafetyPolicy.Limits.LOOP_REPEATS) {
    private val counts = HashMap<Pair<Int, AgentAction>, Int>()

    /** Registra a ação feita na tela [screenHash]; retorna `true` quando vira loop. */
    fun record(screenHash: Int, action: AgentAction): Boolean {
        val key = screenHash to action
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        return count >= repeats
    }
}
