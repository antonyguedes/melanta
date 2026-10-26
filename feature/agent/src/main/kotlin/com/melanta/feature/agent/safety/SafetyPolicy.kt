package com.melanta.feature.agent.safety

import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Target
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.screen.UiElement
import java.text.Normalizer
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
        if (BLOCKED_PACKAGE_PREFIXES.any { pkg == it || pkg.startsWith("$it.") }) {
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
                        SafetyDecision.RequireConfirmation("Tocar em \"${labelOf(action.target, snapshot)}\"")
                    else -> SafetyDecision.Allow
                }
            }
            is AgentAction.Type -> {
                val field = action.target?.let { describe(it, snapshot) }
                    ?: snapshot.elements.firstOrNull { "focused" in it.flags }?.let(::describe).orEmpty()
                when {
                    SENSITIVE_FIELD.containsMatchIn(field) ->
                        SafetyDecision.Block("O agente nunca digita senhas, PINs ou dados de cartão.")
                    looksLikeCardNumber(action.text) ->
                        SafetyDecision.Block("O agente nunca digita dados de cartão.")
                    action.submit && !SEARCH_FIELD.containsMatchIn(field) ->
                        SafetyDecision.RequireConfirmation("Enviar \"${action.text.take(MAX_QUOTE)}\"")
                    else -> SafetyDecision.Allow
                }
            }
            is AgentAction.Scroll, is AgentAction.LaunchIntent, is AgentAction.Navigate, is AgentAction.Done ->
                SafetyDecision.Allow
        }
    }

    private fun describe(target: Target, snapshot: ScreenSnapshot): String = when (target) {
        is Target.ById -> snapshot.byId(target.index)?.let(::describe).orEmpty()
        is Target.ByText -> normalize(target.text)
        is Target.ByViewId -> normalize(target.resourceId.replace('_', ' '))
        is Target.ByCoordinates -> ""
    }

    private fun describe(element: UiElement): String =
        normalize(element.label + " " + element.viewId.orEmpty().substringAfter("/").replace('_', ' '))

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

        /** Bancos, carteiras, cripto, gerenciadores de senha e autenticadores (prefixo de pacote). */
        val BLOCKED_PACKAGE_PREFIXES = listOf(
            "com.nu.production", "br.com.intermedium", "com.itau", "br.com.itau", "com.bradesco", "br.com.bradesco",
            "com.santander", "br.com.santander", "br.com.bb.android", "br.gov.caixa", "br.com.gabba.Caixa",
            "com.c6bank", "br.com.original", "com.btgpactual", "com.mercadopago.wallet", "com.picpay",
            "com.paypal", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.revolut",
            "com.transferwise", "com.binance", "com.coinbase", "br.gov.meugovbr",
            "com.x8bit.bitwarden", "com.lastpass", "com.agilebits.onepassword", "com.dashlane",
            "keepass2android", "com.authy", "com.google.android.apps.authenticator2", "com.azure.authenticator",
        )

        private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.google.android.settings.intelligence")

        private fun words(vararg terms: String) =
            Regex(terms.joinToString("|", prefix = "\\b(", postfix = ")\\b") { Regex.escape(it) })

        private val SECURITY_SETTINGS = words(
            "seguranca", "security", "senha", "senhas", "password", "passwords", "passkeys", "bloqueio de tela",
            "screen lock", "impressao digital", "fingerprint", "biometria", "biometrics", "device admin",
            "administrador do dispositivo", "chaves de acesso",
        )

        private val PAYMENT = words(
            "pagar", "pagamento", "confirmar pagamento", "finalizar compra", "comprar", "comprar agora",
            "assinar", "assinatura", "transferir", "transferencia", "pix", "enviar dinheiro", "doar",
            "pay", "payment", "checkout", "buy", "buy now", "purchase", "place order", "subscribe", "send money",
            "transfer", "donate",
        )

        private val IRREVERSIBLE = words(
            "enviar", "send", "publicar", "postar", "post", "excluir", "delete", "apagar", "remover", "remove",
            "desinstalar", "uninstall", "ligar", "call", "chamar", "confirmar", "confirm", "aceitar", "accept",
            "bloquear", "block", "sair da conta", "log out", "logout", "redefinir", "reset", "formatar",
        )

        private val SENSITIVE_FIELD = words(
            "senha", "password", "passcode", "pin", "cvv", "cvc", "codigo de seguranca", "security code",
            "numero do cartao", "card number", "cartao", "token", "codigo de verificacao", "verification code", "otp",
        )

        private val SEARCH_FIELD = words("pesquisar", "pesquisa", "buscar", "busca", "search", "procurar", "url", "endereco")

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

        fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
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
