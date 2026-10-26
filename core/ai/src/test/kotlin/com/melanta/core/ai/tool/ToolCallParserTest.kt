package com.melanta.core.ai.tool

import com.melanta.core.ai.prompt.ScreenTargets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Corpus de saídas reais (e realistas) de SLMs pequenos: cercas de markdown, tokens especiais,
 * aspas simples, chaves sem aspas, aliases de ferramentas, formatos achatados e saídas truncadas.
 */
class ToolCallParserTest {

    private val parser = ToolCallParser()
    private val screenIds = ParseContext(validIds = (1..9).toSet())

    private data class Ok(val raw: String, val tool: String, val args: String, val thought: String? = null)

    private val successCorpus = listOf(
        // Formato ideal e variações de embalagem
        Ok("""{"thought":"Ana's chat is visible","tool":"click","args":{"id":3}}""", "click", """{"id":3}""", "Ana's chat is visible"),
        Ok("```json\n{\"tool\":\"click\",\"args\":{\"id\":3}}\n```", "click", """{"id":3}"""),
        Ok("Sure! Here is the action:\n{\"tool\":\"click\",\"args\":{\"id\":3}}\nLet me know if you need more.", "click", """{"id":3}"""),
        Ok("""{"tool":"back","args":{}}<|im_end|>""", "back", "{}"),
        Ok("""{"tool":"back","args":{}}<end_of_turn>""", "back", "{}"),
        Ok("<tool_call>\n{\"name\": \"click\", \"arguments\": {\"id\": 4}}\n</tool_call>", "click", """{"id":4}"""),
        Ok("<think>The send button is id 2</think>{\"tool\":\"click\",\"args\":{\"id\":2}}", "click", """{"id":2}"""),
        Ok("""[{"tool":"back","args":{}}]""", "back", "{}"),
        Ok("{\"tool\":\"click\",\"args\":{\"id\":1}}\n{\"tool\":\"click\",\"args\":{\"id\":2}}", "click", """{"id":1}"""),

        // Erros de sintaxe que o reparo leve resolve
        Ok("""{'thought': 'open app', 'tool': 'intent', 'args': {'kind': 'open_app', 'value': 'WhatsApp'}}""", "intent", """{"kind":"open_app","value":"WhatsApp"}""", "open app"),
        Ok("""{"tool":"click","args":{"id":3,},}""", "click", """{"id":3}"""),
        Ok("""{tool: "click", args: {id: 3}}""", "click", """{"id":3}"""),
        Ok("""{tool: click, args: {id: 3}}""", "click", """{"id":3}"""),
        Ok("""{"tool":"type","args":{"id":2,"text":"oi","enter":True}}""", "type", """{"id":2,"text":"oi","enter":true}"""),
        Ok("{\"tool\":\"type\",\"args\":{\"id\":2,\"text\":\"linha1\nlinha2\"}}", "type", """{"id":2,"text":"linha1\nlinha2","enter":false}"""),
        Ok("""{"thought":"use {curly} and \"quotes\"","tool":"back","args":{}}""", "back", "{}", """use {curly} and "quotes""""),

        // Saída truncada pelo limite de tokens
        Ok("""{"thought":"need to type message","tool":"type","args":{"id":2,"text":"chego em 10""", "type", """{"id":2,"text":"chego em 10","enter":false}"""),
        Ok("""{"tool":"scroll","args":{"dir":"down",""", "scroll", """{"dir":"down"}"""),

        // Coerção de tipos
        Ok("""{"tool":"click","args":{"id":"3"}}""", "click", """{"id":3}"""),
        Ok("""{"tool":"click","args":{"id":"[3]"}}""", "click", """{"id":3}"""),
        Ok("""{"tool":"click","args":{"id":3.0}}""", "click", """{"id":3}"""),
        Ok("""{"tool":"click","args":{"element":5}}""", "click", """{"id":5}"""),
        Ok("""{"tool":"done","args":{"ok":"false","msg":"Não consegui abrir o app"}}""", "done", """{"ok":false,"msg":"Não consegui abrir o app"}"""),

        // Formatos alternativos de objeto
        Ok("""{"tool":"click","id":3}""", "click", """{"id":3}"""),
        Ok("""{"action":"click","id":3}""", "click", """{"id":3}"""),
        Ok("""{"type":"click","id":3}""", "click", """{"id":3}"""),
        Ok("""{"action":{"tool":"click","args":{"id":3}}}""", "click", """{"id":3}"""),
        Ok("""{"click":{"id":7}}""", "click", """{"id":7}"""),
        Ok("""{"click":7}""", "click", """{"id":7}"""),
        Ok("""{"name":"click","arguments":"{\"id\": 3}"}""", "click", """{"id":3}"""),
        Ok("""{"reasoning":"go back to list","tool":"back"}""", "back", "{}", "go back to list"),
        Ok("""{"tool":"CLICK","args":{"id":3}}""", "click", """{"id":3}"""),

        // Chamada estilo função, sem JSON
        Ok("click(3)", "click", """{"id":3}"""),
        Ok("""type(id=2, text="oi")""", "type", """{"id":2,"text":"oi","enter":false}"""),
        Ok("back()", "back", "{}"),

        // Aliases de ferramentas
        Ok("""{"tool":"tap","args":{"id":5}}""", "click", """{"id":5}"""),
        Ok("""{"tool":"press","args":{"id":5}}""", "click", """{"id":5}"""),
        Ok("""{"tool":"input","args":{"id":2,"value":"chego em 10 min"}}""", "type", """{"id":2,"text":"chego em 10 min","enter":false}"""),
        Ok("""{"tool":"type","args":{"id":2,"text":"oi","submit":"yes"}}""", "type", """{"id":2,"text":"oi","enter":true}"""),
        Ok("""{"tool":"finish","args":{"success":true,"message":"Alarme criado"}}""", "done", """{"ok":true,"msg":"Alarme criado"}"""),
        Ok("""{"tool":"answer","args":{"text":"Pronto"}}""", "done", """{"ok":true,"msg":"Pronto"}"""),
        Ok("""{"tool":"done","args":{"status":"failed","msg":"App bloqueado"}}""", "done", """{"ok":false,"msg":"App bloqueado"}"""),
        Ok("""{"tool":"go_back"}""", "back", "{}"),
        Ok("""{"tool":"scroll_down","args":{}}""", "scroll", """{"dir":"down"}"""),
        Ok("""{"tool":"scroll","args":{"direction":"UP"}}""", "scroll", """{"dir":"up"}"""),
        Ok("""{"tool":"scroll","args":{}}""", "scroll", """{"dir":"down"}"""),
        Ok("""{"tool":"swipe","args":{"dir":"left","id":5}}""", "scroll", """{"dir":"left","id":5}"""),

        // Intents
        Ok("""{"tool":"open","args":{"app":"Spotify"}}""", "intent", """{"kind":"open_app","value":"Spotify"}"""),
        Ok("""{"tool":"open_app","args":{"name":"Chrome"}}""", "intent", """{"kind":"open_app","value":"Chrome"}"""),
        Ok("""{"open_app":"Spotify"}""", "intent", """{"kind":"open_app","value":"Spotify"}"""),
        Ok("""{"tool":"set_alarm","args":{"time":"07:00 Trabalho"}}""", "intent", """{"kind":"alarm","value":"07:00 Trabalho"}"""),
        Ok("""{"tool":"intent","args":{"kind":"timer","value":300}}""", "intent", """{"kind":"timer","value":"300"}"""),
        Ok("""{"tool":"intent","args":{"kind":"search","value":"clima hoje"}}""", "intent", """{"kind":"web_search","value":"clima hoje"}"""),
        Ok("""{"tool":"intent","args":{"type":"dial","value":"11999999999"}}""", "intent", """{"kind":"dial","value":"11999999999"}"""),
        Ok("""{"tool":"web_search","args":{"query":"previsão do tempo"}}""", "intent", """{"kind":"web_search","value":"previsão do tempo"}"""),
    )

    private data class Fail(val raw: String, val error: ParseError, val context: ParseContext = ParseContext())

    private val failureCorpus = listOf(
        Fail("", ParseError.NoJson),
        Fail("I will click the send button.", ParseError.NoJson),
        Fail("hello(3)", ParseError.NoJson),
        Fail("""{"tool": "click", "args": {"id": }""", ParseError.MalformedJson),
        Fail("""{"tool":"fly","args":{}}""", ParseError.UnknownTool),
        Fail("""{"thought":"hmm","args":{"id":3}}""", ParseError.MissingTool),
        Fail("""{"tool":"click","args":{}}""", ParseError.InvalidArgs),
        Fail("""{"tool":"type","args":{"id":2}}""", ParseError.InvalidArgs),
        Fail("""{"tool":"intent","args":{"kind":"alarm"}}""", ParseError.InvalidArgs),
        Fail("""{"tool":"intent","args":{"kind":"fax","value":"oi"}}""", ParseError.InvalidArgs),
        Fail("""{"tool":"click","args":{"id":42}}""", ParseError.UnknownId, ParseContext(validIds = setOf(1, 2, 3))),
        Fail("""{"tool":"type","args":{"id":0,"text":"x"}}""", ParseError.UnknownId, ParseContext(validIds = setOf(1, 2, 3))),
        Fail("""{"tool":"type","args":{"id":3,"text":"x"}}""", ParseError.WrongTarget, ParseContext(setOf(1, 2, 3), ScreenTargets(setOf(1, 3), setOf(2), emptySet()))),
        Fail("""{"tool":"click","args":{"id":2}}""", ParseError.WrongTarget, ParseContext(setOf(1, 2, 3), ScreenTargets(setOf(1, 3), setOf(2), emptySet()))),
    )

    @Test
    fun `corpus tem mais de 40 saidas malformadas ou variantes`() {
        assertTrue(successCorpus.size + failureCorpus.size > 40)
    }

    @Test
    fun `corpus de sucesso normaliza para a forma canonica`() {
        val failures = successCorpus.mapNotNull { case ->
            when (val result = parser.parse(case.raw, screenIds)) {
                is ParseResult.Success -> {
                    val expectedArgs = Json.parseToJsonElement(case.args) as JsonObject
                    when {
                        result.call.name != case.tool -> "${case.raw}\n  tool ${result.call.name} != ${case.tool}"
                        result.call.args != expectedArgs -> "${case.raw}\n  args ${result.call.args} != $expectedArgs"
                        case.thought != null && result.call.thought != case.thought ->
                            "${case.raw}\n  thought ${result.call.thought} != ${case.thought}"
                        else -> null
                    }
                }
                is ParseResult.Failure -> "${case.raw}\n  falhou: ${result.error} ${result.detail}"
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} casos falharam:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `corpus de falha retorna o erro esperado`() {
        val failures = failureCorpus.mapNotNull { case ->
            when (val result = parser.parse(case.raw, case.context)) {
                is ParseResult.Success -> "${case.raw}\n  deveria falhar, virou ${result.call}"
                is ParseResult.Failure ->
                    if (result.error != case.error) "${case.raw}\n  ${result.error} != ${case.error}" else null
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} casos falharam:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `json limpo nao e marcado como reparado`() {
        val result = parser.parse("""{"tool":"click","args":{"id":3}}""") as ParseResult.Success
        assertEquals(false, result.repaired)
        val repaired = parser.parse("""{tool: "click", args: {id: 3}}""") as ParseResult.Success
        assertEquals(true, repaired.repaired)
    }

    @Test
    fun `id fora da tela gera dica para o re-prompt`() {
        val result = parser.parse("""{"tool":"click","args":{"id":42}}""", ParseContext(setOf(1, 2))) as ParseResult.Failure
        assertTrue(result.detail, result.detail.contains("42") && result.detail.contains("SCREEN"))
    }

    @Test
    fun `exemplo do system prompt e valido`() {
        val result = parser.parse(
            """{"thought":"alarm intent is faster than opening Clock","tool":"intent","args":{"kind":"alarm","value":"07:30 Gym"}}""",
        )
        assertTrue(result is ParseResult.Success)
    }
}
