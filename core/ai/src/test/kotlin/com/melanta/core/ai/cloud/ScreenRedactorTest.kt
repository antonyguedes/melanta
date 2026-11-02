package com.melanta.core.ai.cloud

import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.prompt.ScreenAction
import com.melanta.core.ai.prompt.ScreenContext
import com.melanta.core.ai.prompt.ScreenLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRedactorTest {

    private val redactor = ScreenRedactor()

    private fun input(
        vararg lines: String,
        task: String = "tarefa",
        pkg: String = "com.app",
        history: List<String> = emptyList(),
        notifications: List<String> = emptyList(),
    ) = PromptInput(task, history, ScreenContext(pkg, null, lines.mapIndexed { i, text -> ScreenLine(i + 1, text) }), notifications)

    private fun lines(input: PromptInput) = redactor.redact(input, includeNotificationBodies = false).screen.lines.map { it.text }

    @Test
    fun `campos sensiveis ficam ocultos e continuam aceitando acoes`() {
        val out = redactor.redact(
            input(
                """[1] edit "Senha" click focused""",
                """[2] edit "hunter2" click password""",
                """[3] edit "Número do cartão" click""",
                """[4] edit "Pesquisar" click""",
            ),
            includeNotificationBodies = false,
        ).screen.lines

        assertEquals("""[1] edit "«campo sensível oculto»" click focused""", out[0].text)
        assertEquals("""[2] edit "«campo sensível oculto»" click password""", out[1].text)
        assertEquals("""[3] edit "«campo sensível oculto»" click""", out[2].text)
        assertEquals("""[4] edit "Pesquisar" click""", out[3].text)
        assertEquals(setOf(ScreenAction.Click, ScreenAction.Type), out[0].actions)
    }

    @Test
    fun `mascara numeros, e-mails e codigos na tela`() {
        val out = lines(
            input(
                """[1] item "Ana +55 11 98765-4321" click""",
                """[2] txt "CPF 123.456.789-09"""",
                """[3] txt "Cartão 4111 1111 1111 1111"""",
                """[4] txt "ana.souza@exemplo.com.br"""",
                """[5] txt "Seu código é 482913"""",
                """[6] txt "senha: abc123"""",
                """[7] txt "Fone 98765-4321"""",
            ),
        )
        assertEquals("""[1] item "Ana «número»" click""", out[0])
        assertEquals("""[2] txt "CPF «número»"""", out[1])
        assertEquals("""[3] txt "Cartão «número»"""", out[2])
        assertEquals("""[4] txt "«e-mail»"""", out[3])
        assertFalse(out[4], out[4].contains("482913"))
        assertFalse(out[5], out[5].contains("abc123"))
        assertEquals("""[7] txt "Fone «número»"""", out[6])
    }

    @Test
    fun `texto comum, horas, datas e valores curtos nao mudam`() {
        val original = arrayOf(
            """[1] btn "Enviar" click""",
            """[2] txt "Reunião às 15:30 de 2026-10-27"""",
            """[3] txt "Bateria 87%"""",
            """[4] item "Wi‑Fi" click checked""",
        )
        assertEquals(original.toList(), lines(input(*original)))
    }

    @Test
    fun `o que o usuario citou no pedido nao e mascarado`() {
        val out = redactor.redact(
            input(
                """[1] item "11 99999-8888" click""",
                """[2] item "11 90000-0000" click""",
                """[3] txt "ana@ex.com"""",
                task = "Manda SMS para 11999998888 e copia ana@ex.com",
                history = listOf("intent sms 11999998888|oi -> ok", """click "Bia 11 90000-0000" -> ok"""),
            ),
            includeNotificationBodies = false,
        )
        assertEquals("""[1] item "11 99999-8888" click""", out.screen.lines[0].text)
        assertEquals("""[2] item "«número»" click""", out.screen.lines[1].text)
        assertEquals("""[3] txt "ana@ex.com"""", out.screen.lines[2].text)
        assertEquals("intent sms 11999998888|oi -> ok", out.history[0])
        assertEquals("""click "Bia «número»" -> ok""", out.history[1])
        assertEquals("Manda SMS para 11999998888 e copia ana@ex.com", out.task)
    }

    @Test
    fun `notificacoes vao so com o app, salvo se o usuario ativar`() {
        val source = input(notifications = listOf("""WhatsApp: Ana "me liga no 11 98765-4321""""))
        assertEquals(listOf("WhatsApp: (conteúdo oculto)"), redactor.redact(source, includeNotificationBodies = false).notifications)
        val withBody = redactor.redact(source, includeNotificationBodies = true).notifications.single()
        assertTrue(withBody, withBody.contains("Ana"))
        assertFalse(withBody, withBody.contains("98765"))
    }

    @Test
    fun `tela de app sensivel nao e enviada`() {
        listOf("com.itau.empresas", "com.x8bit.bitwarden", "com.nu.production").forEach { pkg ->
            assertThrows(CloudSensitiveScreenException::class.java) {
                redactor.redact(input("""[1] txt "Saldo"""", pkg = pkg), includeNotificationBodies = true)
            }
        }
    }
}
