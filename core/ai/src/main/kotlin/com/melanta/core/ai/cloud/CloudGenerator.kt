package com.melanta.core.ai.cloud

import com.melanta.core.ai.engine.GenParams
import com.melanta.core.ai.engine.GenerationStats
import com.melanta.core.ai.engine.TextGenerator
import com.melanta.core.ai.prompt.ChatMessage
import com.melanta.core.ai.prompt.LlmPrompt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Cliente de OpenAI Chat Completions com streaming SSE. Um único protocolo atende OpenAI, Groq,
 * OpenRouter, Gemini e servidores compatíveis.
 *
 * Provedores aceitam subconjuntos diferentes dos parâmetros: quando um pedido volta com 400, tenta de
 * novo com menos exigências ([Compat]) e lembra o nível que funcionou para aquele modelo.
 */
class CloudGenerator(
    private val endpoint: suspend () -> CloudEndpoint,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TextGenerator {

    private enum class Compat { StrictSchema, JsonObject, PlainText, Bare }

    private val _lastStats = MutableStateFlow<GenerationStats?>(null)
    override val lastStats: StateFlow<GenerationStats?> = _lastStats.asStateFlow()

    private val compatByModel = ConcurrentHashMap<String, Compat>()

    override fun generate(prompt: LlmPrompt, params: GenParams): Flow<String> = channelFlow {
        val target = endpoint()
        val start = System.nanoTime()
        var firstChunk: Long? = null
        var chunks = 0
        var usage: SseEvent.Usage? = null
        var completed = false
        try {
            stream(target, prompt, params) { event ->
                when (event) {
                    is SseEvent.Text -> {
                        if (firstChunk == null) firstChunk = millisSince(start)
                        chunks++
                        send(event.text)
                    }
                    is SseEvent.Usage -> usage = event
                    is SseEvent.Failure -> throw CloudUnavailableException(scrub("O provedor falhou: ${event.message}", target))
                }
            }
            completed = true
        } finally {
            val total = millisSince(start)
            val decodeMillis = total - (firstChunk ?: total)
            _lastStats.value = GenerationStats(
                timeToFirstChunkMillis = firstChunk,
                totalMillis = total,
                chunks = chunks,
                prefillTokens = usage?.promptTokens,
                decodeTokens = usage?.completionTokens,
                decodeTokensPerSecond = usage?.completionTokens?.takeIf { decodeMillis > 0 }?.let { it * 1000.0 / decodeMillis },
                cancelled = !completed,
            )
        }
    }.flowOn(io)

    /** Chamada sem custo de tokens para conferir a chave. Retorna a latência em milissegundos. */
    suspend fun validate(target: CloudEndpoint): Long = withContext(io) {
        val start = System.nanoTime()
        val connection = open("${target.baseUrl.trimEnd('/')}/models", target)
        try {
            val code = connection.responseCode
            when {
                code == 401 || code == 403 -> throw CloudAuthException()
                code == 429 -> throw rateLimit(connection)
                code >= 500 -> throw CloudUnavailableException("O provedor está fora do ar (HTTP $code).")
            }
            // Nem todo servidor compatível tem /models: fora os erros acima, a conexão e a chave estão ok.
            millisSince(start)
        } catch (e: IOException) {
            throw CloudUnavailableException("Não foi possível conectar ao provedor.", e)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun ProducerScope<String>.stream(
        target: CloudEndpoint,
        prompt: LlmPrompt,
        params: GenParams,
        onEvent: suspend (SseEvent) -> Unit,
    ) {
        val key = "${target.baseUrl}|${target.model}"
        val initial = if (params.responseSchema != null) Compat.StrictSchema else Compat.PlainText
        var level = maxOf(initial, compatByModel[key] ?: initial)
        while (true) {
            val connection = open("${target.baseUrl.trimEnd('/')}/chat/completions", target)
            // A leitura bloqueia e não enxerga o cancelamento: fechar a conexão é o que a interrompe.
            val closer = launch {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "text/event-stream")
                val body = requestBody(target.model, prompt, params, level).toString().toByteArray()
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }

                val code = connection.responseCode
                if (code in 200..299) {
                    readResponse(connection, onEvent)
                    return
                }
                val next = Compat.entries.getOrNull(level.ordinal + 1)
                if (code != 400 || next == null) throw errorFor(code, connection, target)
                level = next
                compatByModel[key] = next
            } catch (e: IOException) {
                ensureActive()
                throw CloudUnavailableException("Não foi possível falar com o provedor. Confira a internet.", e)
            } finally {
                closer.cancel()
                connection.disconnect()
            }
        }
    }

    private suspend fun readResponse(connection: HttpURLConnection, onEvent: suspend (SseEvent) -> Unit) {
        // Alguns servidores ignoram `stream` e respondem com o JSON completo.
        if (connection.contentType.orEmpty().contains("application/json")) {
            CloudWire.parseFullResponse(connection.inputStream.bufferedReader().use { it.readText() }).forEach { onEvent(it) }
            return
        }
        connection.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: return
                if (CloudWire.isDone(line)) return
                CloudWire.parseSseLine(line).forEach { onEvent(it) }
            }
        }
    }

    private fun open(url: String, target: CloudEndpoint): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Um redirecionamento reenviaria a tela e a chave para um destino que o usuário não autorizou.
            instanceFollowRedirects = false
            target.apiKey?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun requestBody(model: String, prompt: LlmPrompt, params: GenParams, level: Compat): JsonObject = buildJsonObject {
        put("model", model)
        put("stream", true)
        put("messages", buildJsonArray {
            add(message("system", prompt.system))
            prompt.messages.forEach { add(message(if (it.role == ChatMessage.Role.User) "user" else "assistant", it.text)) }
        })
        if (level == Compat.Bare) return@buildJsonObject

        putJsonObject("stream_options") { put("include_usage", true) }
        put("seed", params.seed)
        if (REASONING_MODEL.containsMatchIn(model)) {
            // Estes modelos gastam a cota de saída raciocinando e, na OpenAI, recusam temperature e top_p.
            if (!params.enableThinking) put("reasoning_effort", "low")
            val limit = maxOf(params.maxOutputTokens, MIN_REASONING_OUTPUT_TOKENS)
            put(if (OPENAI_REASONING_MODEL.containsMatchIn(model)) "max_completion_tokens" else "max_tokens", limit)
        } else {
            put("temperature", params.temperature)
            put("top_p", params.topP)
            put("max_tokens", params.maxOutputTokens)
        }
        when (level) {
            Compat.StrictSchema -> params.responseSchema?.let { schema ->
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "agent_action")
                        put("strict", true)
                        put("schema", Json.parseToJsonElement(schema))
                    }
                }
            }
            Compat.JsonObject -> putJsonObject("response_format") { put("type", "json_object") }
            Compat.PlainText, Compat.Bare -> Unit
        }
    }

    private fun message(role: String, text: String) = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun errorFor(code: Int, connection: HttpURLConnection, target: CloudEndpoint): CloudException = when {
        code == 401 || code == 403 -> CloudAuthException()
        code == 429 -> rateLimit(connection)
        code >= 500 -> CloudUnavailableException("O provedor está fora do ar (HTTP $code).")
        code in 300..399 -> CloudRequestException("O provedor pediu um redirecionamento, que o Melanta não segue. Confira o endereço.")
        else -> CloudRequestException(scrub("O provedor recusou o pedido (HTTP $code). ${errorDetail(connection)}", target).trim())
    }

    private fun rateLimit(connection: HttpURLConnection) =
        CloudRateLimitException(connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull())

    private fun errorDetail(connection: HttpURLConnection): String = runCatching {
        val text = connection.errorStream?.bufferedReader()?.use { it.readText().take(MAX_ERROR_BODY) }.orEmpty()
        CloudWire.errorMessage(text).orEmpty().take(MAX_ERROR_DETAIL)
    }.getOrDefault("")

    /** Provedores às vezes ecoam a chave nas mensagens de erro, que vão para a tela e para o log local. */
    private fun scrub(text: String, target: CloudEndpoint): String =
        target.apiKey?.takeIf { it.isNotEmpty() }?.let { text.replace(it, "***") } ?: text

    private fun millisSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_ERROR_BODY = 2_000
        const val MAX_ERROR_DETAIL = 200
        const val MIN_REASONING_OUTPUT_TOKENS = 1_024
        val OPENAI_REASONING_MODEL = Regex("""(^|/)(o\d|gpt-5)""")
        val REASONING_MODEL = Regex("""(^|/)(o\d|gpt-5|gemini-2\.5)""")
    }
}

internal sealed interface SseEvent {
    data class Text(val text: String) : SseEvent
    data class Usage(val promptTokens: Int?, val completionTokens: Int?) : SseEvent
    data class Failure(val message: String) : SseEvent
}

/** Formato de resposta do protocolo, sem rede, para ser testado diretamente. */
internal object CloudWire {
    private val json = Json { ignoreUnknownKeys = true }

    fun isDone(line: String) = line.removePrefix("data:").trim() == "[DONE]" && line.startsWith("data:")

    /** Uma linha do stream. Comentários (`: PROCESSING`), linhas vazias e JSON quebrado são ignorados. */
    fun parseSseLine(line: String): List<SseEvent> {
        if (!line.startsWith("data:")) return emptyList()
        val root = parse(line.removePrefix("data:").trim()) ?: return emptyList()
        return events(root, "delta")
    }

    fun parseFullResponse(body: String): List<SseEvent> = parse(body)?.let { events(it, "message") }.orEmpty()

    fun errorMessage(body: String): String? = parse(body)?.let(::failureOf)

    private fun events(root: JsonObject, contentField: String): List<SseEvent> = buildList {
        failureOf(root)?.let { add(SseEvent.Failure(it)) }
        val choices = runCatching { root["choices"] as? JsonArray }.getOrNull()
        val content = (choices?.firstOrNull() as? JsonObject)?.get(contentField)?.objectOrNull()?.get("content")?.stringOrNull()
        if (!content.isNullOrEmpty()) add(SseEvent.Text(content))
        root["usage"]?.objectOrNull()?.let { usage ->
            add(SseEvent.Usage(usage["prompt_tokens"]?.intOrNullSafe(), usage["completion_tokens"]?.intOrNullSafe()))
        }
    }

    private fun failureOf(root: JsonObject): String? = when (val error = root["error"]) {
        null -> null
        is JsonObject -> error["message"]?.stringOrNull() ?: "erro desconhecido"
        else -> error.stringOrNull() ?: "erro desconhecido"
    }

    private fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonElement.objectOrNull() = this as? JsonObject

    private fun JsonElement.stringOrNull() = runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun JsonElement.intOrNullSafe() = runCatching { jsonPrimitive.intOrNull }.getOrNull()
}
