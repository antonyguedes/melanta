package com.melanta.feature.agent.safety

import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Direction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.Target
import com.melanta.core.automation.screen.Bounds
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.screen.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyPolicyTest {

    private val policy = SafetyPolicy()

    private fun screen(pkg: String, vararg labels: String, title: String? = null, focused: Int? = null) = ScreenSnapshot(
        packageName = pkg,
        title = title,
        elements = labels.mapIndexed { i, label ->
            UiElement(i + 1, "btn", label, listOfNotNull("click", "focused".takeIf { focused == i + 1 }), Bounds(0, i * 100, 100, i * 100 + 90), null, 4, null)
        },
        screenBounds = Bounds(0, 0, 1080, 2400),
    )

    private fun click(id: Int) = AgentAction.Click(Target.ById(id))

    @Test
    fun `apps de banco e senhas sao bloqueados em qualquer acao`() {
        listOf("com.nu.production", "com.itau.empresas", "com.x8bit.bitwarden", "br.com.bb.android").forEach { pkg ->
            assertTrue(pkg, policy.checkScreen(screen(pkg, "Início")) is SafetyDecision.Block)
            assertTrue(pkg, policy.evaluate(AgentAction.Scroll(Direction.Down, null), screen(pkg)) is SafetyDecision.Block)
        }
        assertEquals(SafetyDecision.Allow, policy.checkScreen(screen("com.nubankfake.game")))
    }

    @Test
    fun `configuracoes de seguranca sao bloqueadas, as demais nao`() {
        assertTrue(policy.checkScreen(screen("com.android.settings", title = "Segurança e privacidade")) is SafetyDecision.Block)
        assertTrue(policy.checkScreen(screen("com.android.settings", title = "Passwords & passkeys")) is SafetyDecision.Block)
        assertEquals(SafetyDecision.Allow, policy.checkScreen(screen("com.android.settings", title = "Rede e internet")))
    }

    @Test
    fun `tocar em pagar ou comprar e bloqueado`() {
        listOf("Finalizar compra", "Confirmar pagamento", "Pagar com Pix", "Buy now", "Place order", "Assinar Premium").forEach { label ->
            assertTrue(label, policy.evaluate(click(1), screen("com.loja", label)) is SafetyDecision.Block)
        }
    }

    @Test
    fun `acoes irreversiveis pedem confirmacao`() {
        listOf("Enviar", "Send", "Excluir conversa", "Ligar", "Publicar").forEach { label ->
            val decision = policy.evaluate(click(1), screen("com.app", label))
            assertTrue(label, decision is SafetyDecision.RequireConfirmation)
        }
        assertEquals(SafetyDecision.Allow, policy.evaluate(click(1), screen("com.app", "Ana: Onde você está?")))
        assertEquals(SafetyDecision.Allow, policy.evaluate(click(1), screen("com.app", "Callback de teste")))
    }

    @Test
    fun `digitar senha, pin ou cartao e bloqueado`() {
        listOf("Senha", "Senha de 4 dígitos", "PIN", "CVV", "Número do cartão").forEach { label ->
            val decision = policy.evaluate(AgentAction.Type(Target.ById(1), "1234", submit = false), screen("com.app", label))
            assertTrue(label, decision is SafetyDecision.Block)
        }
        val card = AgentAction.Type(Target.ById(1), "4111 1111 1111 1111", submit = false)
        assertTrue(policy.evaluate(card, screen("com.app", "Observação")) is SafetyDecision.Block)
    }

    @Test
    fun `campo marcado como senha e bloqueado mesmo sem rotulo sensivel`() {
        val field = UiElement(1, "edit", "Entrar", listOf("click", "password"), Bounds(0, 0, 10, 10), null, 5, null)
        val snapshot = ScreenSnapshot("com.app", null, listOf(field), Bounds(0, 0, 1080, 2400))
        assertTrue(policy.evaluate(AgentAction.Type(Target.ById(1), "abc", submit = false), snapshot) is SafetyDecision.Block)
    }

    @Test
    fun `destino precisa vir do pedido, da tela ou de uma notificacao`() {
        val task = "Manda SMS para 11 99999-8888 e copia ana@ex.com"
        val blank = screen("com.app", "Observação")
        fun unverified(spec: IntentSpec, snapshot: ScreenSnapshot = blank, notifications: List<String> = emptyList()) =
            policy.unverifiedContact(AgentAction.LaunchIntent(spec), task, snapshot, notifications)

        // O dígito a mais do teste de ponta a ponta.
        assertEquals("119999988888", unverified(IntentSpec.Sms("119999988888", "oi")))
        assertEquals(null, unverified(IntentSpec.Sms("11999998888", "oi")))
        assertEquals("um dígito a menos também é outra pessoa", "1199999888", unverified(IntentSpec.Sms("1199999888", "oi")))
        assertEquals("formatação e DDI não mudam o número", null, unverified(IntentSpec.Sms("+55 (11) 99999-8888", "oi")))
        assertEquals("11888887777", unverified(IntentSpec.WhatsApp("11888887777", "oi")))
        assertEquals(null, unverified(IntentSpec.WhatsApp("11888887777", "oi"), screen("com.app", "Bia 11 88888-7777")))
        assertEquals(null, unverified(IntentSpec.Sms("11888887777", "oi"), notifications = listOf("""Bia: "me liga no 11 88888-7777"""")))
        assertEquals("nome vem da agenda", null, unverified(IntentSpec.Sms("Ana", "oi")))
        assertEquals("21977776666", unverified(IntentSpec.AddContact("Jose", "21977776666")))
        assertEquals(null, unverified(IntentSpec.AddContact("Jose", null)))
        assertEquals("21977776666", unverified(IntentSpec.Dial("21977776666")))
        assertEquals("número curto só abre o discador", null, unverified(IntentSpec.Dial("190")))
        assertEquals("bia@ex.com", unverified(IntentSpec.Email("bia@ex.com", "a", "b")))
        assertEquals(null, unverified(IntentSpec.Email("ANA@ex.com", "a", "b")))
        assertEquals("21977776666", policy.unverifiedContact(AgentAction.Type(Target.ById(1), "21977776666", submit = false), task, blank, emptyList()))
        assertEquals(null, policy.unverifiedContact(AgentAction.Type(Target.ById(1), "oi, tudo bem?", submit = false), task, blank, emptyList()))
        listOf("2026-11-02 15:00", "2026-11-02", "pedido 12345678 entregue").forEach {
            assertEquals(it, null, policy.unverifiedContact(AgentAction.Type(Target.ById(1), it, submit = false), task, blank, emptyList()))
        }
    }

    @Test
    fun `enviar por uma conversa que nao e a pedida e recusado`() {
        val task = "Manda SMS para 11 99999-8888 dizendo oi"
        val send = AgentAction.Type(Target.ById(1), "oi", submit = true)
        val wrong = screen("com.google.android.apps.messaging", "Mensagem", "119999988888")
        val right = screen("com.google.android.apps.messaging", "Mensagem", "11 99999-8888")

        assertEquals("11 99999-8888", policy.wrongConversation(send, task, wrong))
        assertEquals(null, policy.wrongConversation(send, task, right))
        assertEquals("só digitar não envia", null, policy.wrongConversation(AgentAction.Type(Target.ById(1), "oi", submit = false), task, wrong))
        assertEquals("sem número no pedido, não há o que conferir", null, policy.wrongConversation(send, "Manda oi para a Ana", wrong))
        assertEquals("11 99999-8888", policy.wrongConversation(AgentAction.Click(Target.ById(1)), task, screen("com.app", "Send SMS", "119999988888")))
        assertEquals(null, policy.wrongConversation(AgentAction.Click(Target.ById(1)), task, screen("com.app", "Voltar", "119999988888")))

        val decision = policy.evaluate(send, wrong) as SafetyDecision.RequireConfirmation
        assertTrue(decision.reason, "119999988888" in decision.reason)
    }

    @Test
    fun `telefone com DDI ou numero que nao passa no Luhn nao e cartao`() {
        assertTrue(SafetyPolicy.looksLikeCardNumber("5500-0000-0000-0004"))
        assertTrue(SafetyPolicy.looksLikeCardNumber("cartão 378282246310005"))
        listOf("+55 24 987778990", "+5511987654321", "4111 1111 1111 1112", "11 98765-4321").forEach {
            assertTrue(it, !SafetyPolicy.looksLikeCardNumber(it))
        }
        val phone = AgentAction.Type(Target.ById(1), "+55 24 987778990", submit = false)
        assertEquals(SafetyDecision.Allow, policy.evaluate(phone, screen("com.google.android.contacts", "Telefone")))
    }

    @Test
    fun `enter em campo de mensagem pede confirmacao, em busca nao`() {
        val send = AgentAction.Type(Target.ById(1), "chego em 10 min", submit = true)
        assertTrue(policy.evaluate(send, screen("com.whatsapp", "Mensagem")) is SafetyDecision.RequireConfirmation)
        assertEquals(SafetyDecision.Allow, policy.evaluate(send, screen("com.android.chrome", "Pesquisar ou digitar URL")))
        val focusedSearch = AgentAction.Type(null, "bateria", submit = true)
        assertEquals(SafetyDecision.Allow, policy.evaluate(focusedSearch, screen("com.android.settings", "Search settings", focused = 1)))
        assertEquals(SafetyDecision.Allow, policy.evaluate(AgentAction.Type(Target.ById(1), "oi", submit = false), screen("com.whatsapp", "Mensagem")))
    }

    @Test
    fun `intents e navegacao passam, inclusive o discador`() {
        val s = screen("launcher", "Relógio")
        listOf(
            AgentAction.LaunchIntent(IntentSpec.Alarm(7, 0, null)),
            AgentAction.LaunchIntent(IntentSpec.Dial("11999998888")),
            AgentAction.Done(true, "ok"),
        ).forEach { assertEquals(SafetyDecision.Allow, policy.evaluate(it, s)) }
    }

    @Test
    fun `detector de loop dispara na terceira repeticao na mesma tela`() {
        val detector = LoopDetector(repeats = 3)
        assertFalse(detector.record(1, click(1)))
        assertFalse(detector.record(2, click(1)))
        assertFalse(detector.record(1, click(1)))
        assertTrue(detector.record(1, click(1)))
    }
}
