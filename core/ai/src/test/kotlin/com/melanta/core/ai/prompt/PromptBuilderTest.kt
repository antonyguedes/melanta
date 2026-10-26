package com.melanta.core.ai.prompt

import com.melanta.core.ai.tool.ToolCallSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    private val whatsapp = ScreenContext(
        packageName = "com.whatsapp",
        title = "Conversas",
        lines = listOf(
            ScreenLine(1, """[1] btn "Nova conversa" click""", priority = 1),
            ScreenLine(2, """[2] edit "Pesquisar..." focused""", priority = 2),
            ScreenLine(3, """[3] item "Ana: Onde você está?" click""", priority = 2),
            ScreenLine(4, """[4] item "Grupo Família" click""", priority = 2),
            ScreenLine(5, """[5] list scroll""", priority = 0),
        ),
    )

    @Test
    fun `turno do usuario segue o template da secao 3_3`() {
        val built = builder.build(
            PromptInput(
                task = "mande \"chego em 10 min\" para a Ana no WhatsApp",
                history = listOf("intent open_app WhatsApp -> ok", "click 4 -> ok"),
                screen = whatsapp,
                notifications = listOf("""WhatsApp: Ana "Onde você está?""""),
            ),
        )

        val expected = """
            TASK: mande "chego em 10 min" para a Ana no WhatsApp
            HISTORY:
            1. intent open_app WhatsApp -> ok
            2. click 4 -> ok
            SCREEN (com.whatsapp | Conversas):
            [1] btn "Nova conversa" click
            [2] edit "Pesquisar..." focused
            [3] item "Ana: Onde você está?" click
            [4] item "Grupo Família" click
            [5] list scroll
            NOTIFS: WhatsApp: Ana "Onde você está?"
        """.trimIndent()
        assertEquals(expected, built.prompt.messages.single().text)
        assertEquals(AgentPrompt.SYSTEM, built.prompt.system)
        assertEquals(setOf(1, 2, 3, 4, 5), built.visibleIds)
        assertEquals(0, built.hiddenLines)
    }

    @Test
    fun `historico vazio e historico longo`() {
        val empty = builder.build(PromptInput("abrir câmera", emptyList(), whatsapp)).prompt.messages.single().text
        assertTrue(empty.contains("HISTORY: none"))

        val long = builder.build(PromptInput("x", (1..7).map { "click $it -> ok" }, whatsapp)).prompt.messages.single().text
        assertTrue(long.contains("1. click 4 -> ok"))
        assertTrue(long.contains("4. click 7 -> ok"))
        assertFalse(long.contains("click 3 -> ok"))
    }

    @Test
    fun `tela grande e cortada por prioridade mantendo a ordem de leitura`() {
        val lines = (1..300).map { i ->
            ScreenLine(i, """[$i] item "Elemento número $i com um rótulo comprido" click""", priority = if (i % 10 == 0) 5 else 1)
        }
        val built = builder.build(PromptInput("x", emptyList(), ScreenContext("com.app", null, lines)))
        val user = built.prompt.messages.single().text

        assertTrue(built.hiddenLines > 0)
        assertTrue("os de prioridade alta sobrevivem", (10..300 step 10).all { it in built.visibleIds })
        assertTrue(user.contains("(+${built.hiddenLines} more elements, scroll to see)"))
        val order = Regex("""^\[(\d+)]""", RegexOption.MULTILINE).findAll(user).map { it.groupValues[1].toInt() }.toList()
        assertEquals(order.sorted(), order)
        assertTrue(built.estimatedTokens <= PromptBudget().contextTokens - PromptBudget().outputTokens)
    }

    @Test
    fun `re-prompt corretivo repete o turno e mostra a resposta invalida`() {
        val first = builder.build(PromptInput("x", emptyList(), whatsapp)).prompt
        val retry = builder.correction(first, "I will click 9", "id 9 is not on SCREEN.")

        assertEquals(3, retry.messages.size)
        assertEquals(ChatMessage.Role.Model, retry.messages[1].role)
        assertEquals("Invalid: id 9 is not on SCREEN. Reply with ONLY the JSON object.", retry.messages[2].text)
    }

    @Test
    fun `alvos por acao vem do papel e das flags`() {
        val targets = builder.build(PromptInput("x", emptyList(), whatsapp)).targets
        assertEquals(setOf(1, 3, 4), targets.click)
        assertEquals(setOf(2), targets.type)
        assertEquals(setOf(5), targets.scroll)
        assertEquals(emptySet<ScreenAction>(), ScreenAction.infer("""[7] btn "Enviar" click off"""))
    }

    @Test
    fun `schema restringe cada ferramenta aos seus alvos`() {
        val targets = ScreenTargets(click = setOf(3, 1), type = setOf(2), scroll = emptySet())
        val variants = Json.parseToJsonElement(ToolCallSchema.build(targets)).jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject }

        val clickIds = variants.first { it.toolConst() == "click" }.argProperty("id")["enum"] as JsonArray
        assertEquals(listOf(1, 3), clickIds.map { it.jsonPrimitive.content.toInt() })
        val typeIds = variants.first { it.toolConst() == "type" }.argProperty("id")["enum"] as JsonArray
        assertEquals(listOf(2), typeIds.map { it.jsonPrimitive.content.toInt() })
        val scrollArgs = variants.first { it.toolConst() == "scroll" }["properties"]!!.jsonObject["args"]!!.jsonObject["properties"]!!.jsonObject
        assertFalse("sem contêiner rolável, scroll não aceita id", "id" in scrollArgs)

        val noTargets = ScreenTargets(emptySet(), emptySet(), emptySet())
        val tools = Json.parseToJsonElement(ToolCallSchema.build(noTargets)).jsonObject["anyOf"]!!.jsonArray.map { it.jsonObject.toolConst() }
        assertEquals(listOf("scroll", "intent", "back", "done"), tools)
    }

    private fun JsonObject.toolConst() =
        this["properties"]!!.jsonObject["tool"]!!.jsonObject["const"]!!.jsonPrimitive.content

    private fun JsonObject.argProperty(name: String) =
        this["properties"]!!.jsonObject["args"]!!.jsonObject["properties"]!!.jsonObject[name]!!.jsonObject
}
