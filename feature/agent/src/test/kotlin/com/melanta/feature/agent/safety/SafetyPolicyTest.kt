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
