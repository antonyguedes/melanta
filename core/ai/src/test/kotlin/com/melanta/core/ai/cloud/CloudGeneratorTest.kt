package com.melanta.core.ai.cloud

import com.melanta.core.ai.engine.GenParams
import com.melanta.core.ai.prompt.ChatMessage
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.ai.tool.untilFirstJsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CloudGeneratorTest {

    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<String>()
    private val auth = CopyOnWriteArrayList<String?>()
    private var respond: (HttpExchange) -> Unit = {}

    private val prompt = LlmPrompt("system", listOf(ChatMessage(ChatMessage.Role.User, "oi")))
    private val generator by lazy {
        CloudGenerator({ CloudEndpoint("http://127.0.0.1:${server.address.port}/v1/", "m", SECRET) })
    }

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/v1/") { exchange ->
                bodies += exchange.requestBody.bufferedReader().readText()
                auth += exchange.requestHeaders.getFirst("Authorization")
                respond(exchange)
            }
            start()
        }
    }

    @After
    fun tearDown() = server.stop(0)

    private fun sse(exchange: HttpExchange, vararg lines: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out -> lines.forEach { out.write("$it\n\n".toByteArray()) } }
    }

    private fun status(exchange: HttpExchange, code: Int, body: String = "", vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
    }

    private fun delta(text: String) = """data: {"choices":[{"delta":{"content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(text))}}}]}"""

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("esperava ${T::class.simpleName}")
        error("inalcançável")
    }

    @Test
    fun `stream de texto com chave, parametros e metricas`() = runBlocking {
        respond = {
            sse(it, ": PROCESSING", delta("{\"to"), delta("ol\":1}"), """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4}}""", "data: [DONE]")
        }
        val text = generator.generate(prompt, GenParams(maxOutputTokens = 77, responseSchema = """{"type":"object"}""")).toList().joinToString("")

        assertEquals("""{"tool":1}""", text)
        assertEquals("Bearer $SECRET", auth.single())
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("77", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val stats = generator.lastStats.value!!
        assertEquals(4, stats.decodeTokens)
        assertEquals(10, stats.prefillTokens)
        assertFalse(stats.cancelled)
    }

    @Test
    fun `400 reduz as exigencias e lembra o nivel que funcionou`() = runBlocking {
        respond = { exchange ->
            if (bodies.last().contains("json_schema")) status(exchange, 400, """{"error":{"message":"schema inválido"}}""") else sse(exchange, delta("ok"), "data: [DONE]")
        }
        val params = GenParams(responseSchema = """{"type":"object"}""")
        assertEquals(listOf("ok"), generator.generate(prompt, params).toList())
        assertEquals(2, bodies.size)
        assertTrue(bodies[1].contains("json_object"))

        generator.generate(prompt, params).toList()
        assertEquals("não repete o pedido recusado", 3, bodies.size)
        assertTrue(bodies[2].contains("json_object"))
    }

    @Test
    fun `erros viram excecoes tipadas sem expor a chave`() = runBlocking {
        respond = { status(it, 401, """{"error":{"message":"bad key $SECRET"}}""") }
        val auth = assertFails<CloudAuthException> { generator.generate(prompt).toList() }
        assertFalse(auth.message!!.contains(SECRET))

        respond = { status(it, 429, "", "Retry-After" to "7") }
        assertEquals(7L, assertFails<CloudRateLimitException> { generator.generate(prompt).toList() }.retryAfterSeconds)

        respond = { status(it, 429, """[{"error":{"code":429,"details":[{"retryDelay":"37s"}]}}]""") }
        assertEquals("prazo no corpo do erro, como no Gemini", 38L, assertFails<CloudRateLimitException> { generator.generate(prompt).toList() }.retryAfterSeconds)

        respond = { status(it, 429, """{"error":{"details":[{"retryDelay":"56105s"}]}}""") }
        val quota = assertFails<CloudRateLimitException> { generator.generate(prompt).toList() }
        assertTrue(quota.message!!, "cota" in quota.message!! && "16 h" in quota.message!!)

        respond = { status(it, 503) }
        assertFails<CloudUnavailableException> { generator.generate(prompt).toList() }

        respond = { status(it, 404, """{"error":{"message":"modelo $SECRET não existe"}}""") }
        val request = assertFails<CloudRequestException> { generator.generate(prompt).toList() }
        assertFalse(request.message!!, request.message!!.contains(SECRET))
        assertTrue(request.message!!, request.message!!.contains("não existe"))

        respond = { sse(it, """data: {"error":{"message":"caiu $SECRET"}}""") }
        val midStream = assertFails<CloudUnavailableException> { generator.generate(prompt).toList() }
        assertFalse(midStream.message!!, midStream.message!!.contains(SECRET))
    }

    @Test
    fun `redirecionamento nao e seguido`() = runBlocking {
        respond = { status(it, 302, "", "Location" to "http://127.0.0.1:${server.address.port}/v1/outro") }
        assertFails<CloudRequestException> { generator.generate(prompt).toList() }
        assertEquals(1, bodies.size)
    }

    @Test
    fun `sem servidor vira indisponivel`() = runBlocking {
        val port = server.address.port
        server.stop(0)
        val offline = CloudGenerator({ CloudEndpoint("http://127.0.0.1:$port/v1", "m", null) })
        assertFails<CloudUnavailableException> { offline.generate(prompt).toList() }
        Unit
    }

    @Test
    fun `resposta sem stream tambem funciona`() = runBlocking {
        respond = { status(it, 200, """{"choices":[{"message":{"content":"inteiro"}}]}""", "Content-Type" to "application/json") }
        assertEquals(listOf("inteiro"), generator.generate(prompt).toList())
    }

    @Test
    fun `corte no primeiro JSON fecha a conexao sem esperar o resto do stream`() = runBlocking {
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        respond = { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("${delta("{\"a\":1}")}\n\n".toByteArray())
            out.flush()
            try {
                // Continua escrevendo até o cliente fechar, ou até o teste acabar.
                while (!release.await(50, TimeUnit.MILLISECONDS)) {
                    out.write(": keepalive\n\n".toByteArray())
                    out.flush()
                }
            } catch (_: java.io.IOException) {
                closed.countDown()
            }
        }
        val text = withTimeout(10_000) { generator.generate(prompt).untilFirstJsonObject().toList().joinToString("") }

        assertEquals("""{"a":1}""", text)
        assertTrue("a conexão deveria ter sido fechada", closed.await(10, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(2_000) { while (generator.lastStats.value == null) kotlinx.coroutines.delay(10) }
        assertTrue(generator.lastStats.value!!.cancelled)
    }

    @Test
    fun `validate confere a chave sem gastar geracao`() = runBlocking {
        val target = CloudEndpoint("http://127.0.0.1:${server.address.port}/v1", "m", SECRET)
        respond = { status(it, 404) }
        assertTrue(generator.validate(target) >= 0)
        respond = { status(it, 401) }
        assertFails<CloudAuthException> { generator.validate(target) }
        assertNull(generator.lastStats.value)
    }

    @Test
    fun `toString do destino nao mostra a chave`() {
        assertFalse(CloudEndpoint("https://x", "m", SECRET).toString().contains(SECRET))
    }

    private companion object {
        const val SECRET = "sk-segredo-123"
    }
}
