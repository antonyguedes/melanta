package com.melanta.core.ai.tool

import com.melanta.core.ai.prompt.ScreenTargets
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Ids válidos do snapshot atual. `null` desliga a validação (ex.: testes de formato).
 * Com [targets], `click`/`type`/`scroll` só aceitam ids que suportam aquela ação.
 */
data class ParseContext(val validIds: Set<Int>? = null, val targets: ScreenTargets? = null)

sealed interface ParseResult {
    data class Success(val call: ToolCall, val repaired: Boolean) : ParseResult

    /** [detail] é uma dica curta, em inglês, usada no re-prompt corretivo. */
    data class Failure(val error: ParseError, val detail: String) : ParseResult
}

enum class ParseError { NoJson, MalformedJson, MissingTool, UnknownTool, InvalidArgs, UnknownId, WrongTarget }

/**
 * Converte a saída crua do SLM em [ToolCall], do passo mais barato para o mais caro (seção 3.4 do plano):
 * limpeza -> extração do primeiro objeto -> parse estrito -> reparo leve -> normalização semântica -> validação.
 */
class ToolCallParser @Inject constructor() {

    private val json = Json { isLenient = false }

    fun parse(raw: String, context: ParseContext = ParseContext()): ParseResult {
        val cleaned = clean(raw)
        val tracker = JsonObjectTracker.firstObject(cleaned)
        val candidate = tracker.completedObject()
            ?: parseFunctionStyle(cleaned)
            ?: return ParseResult.Failure(ParseError.NoJson, "No JSON object found.")

        var repaired = !tracker.isClosed
        val element = decode(candidate)
            ?: decode(JsonRepair.repair(candidate))?.also { repaired = true }
            ?: return ParseResult.Failure(ParseError.MalformedJson, "Malformed JSON.")

        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return ParseResult.Failure(ParseError.MalformedJson, "Expected a JSON object.")

        return normalize(obj, context, repaired)
    }

    private fun decode(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    // --- Limpeza -----------------------------------------------------------------------------

    private fun clean(raw: String): String = raw
        .replace(SPECIAL_TOKENS, "")
        .replace(TOOL_CALL_TAGS, "")
        .replace(CODE_FENCE, "")
        .trim()

    /** `click(3)`, `click(id=3)`, `type(id=2, text="oi")`: alguns modelos respondem assim sem JSON. */
    private fun parseFunctionStyle(text: String): String? {
        val match = FUNCTION_CALL.find(text) ?: return null
        val name = match.groupValues[1]
        if (resolveAlias(name) == null) return null
        val inner = match.groupValues[2].trim()
        val args = when {
            inner.isEmpty() -> "{}"
            inner.all { it.isDigit() } -> "{\"id\":$inner}"
            inner.contains('=') || inner.contains(':') -> "{" + inner.replace('=', ':') + "}"
            else -> "{\"value\":${JsonPrimitive(inner.trim('"', '\''))}}"
        }
        return "{\"tool\":\"$name\",\"args\":$args}"
    }

    // --- Normalização ------------------------------------------------------------------------

    private fun normalize(root: JsonObject, context: ParseContext, repaired: Boolean): ParseResult {
        val obj = unwrap(root)
        val thought = THOUGHT_KEYS.firstNotNullOfOrNull { obj.string(it) }

        var rawName = NAME_KEYS.firstNotNullOfOrNull { obj.string(it) }
            // {"type":"click","id":3}: "type" usado como nome da ferramenta.
            ?: obj.string("type")?.takeIf { resolveAlias(it) != null }
        var args: JsonObject? = ARGS_KEYS.firstNotNullOfOrNull { obj.argsObject(it) }

        if (rawName == null) {
            // Ferramenta como chave: {"click":{"id":3}} ou {"click":3}.
            val entry = obj.entries.firstOrNull { (key, _) -> resolveAlias(key) != null }
                ?: return ParseResult.Failure(ParseError.MissingTool, "Missing \"tool\".")
            rawName = entry.key
            args = when (val value = entry.value) {
                is JsonObject -> value
                is JsonPrimitive -> buildJsonObject { put(if (coerceId(value) != null) "id" else "value", value) }
                else -> JsonObject(emptyMap())
            }
        }
        if (args == null) {
            // Formato achatado: {"tool":"click","id":3}.
            args = JsonObject(obj.filterKeys { it !in NAME_KEYS && it !in THOUGHT_KEYS })
        }

        val alias = resolveAlias(rawName)
            ?: return ParseResult.Failure(ParseError.UnknownTool, "Unknown tool \"$rawName\". Use one of: ${Tools.ALL.joinToString()}.")

        return try {
            val canonical = canonicalArgs(alias, args, context)
            ParseResult.Success(ToolCall(thought, alias.tool, canonical), repaired)
        } catch (e: InvalidArgs) {
            ParseResult.Failure(e.error, e.message.orEmpty())
        }
    }

    /** Desce em invólucros como {"action":{...}} ou {"tool_call":{...}} até achar o nome da ferramenta. */
    private fun unwrap(obj: JsonObject, depth: Int = 0): JsonObject {
        if (depth >= 2 || NAME_KEYS.any { obj.string(it) != null }) return obj
        val inner = obj.values.singleOrNull { it is JsonObject } as? JsonObject ?: return obj
        val wrapperKey = obj.entries.first { it.value === inner }.key
        if (resolveAlias(wrapperKey) != null) return obj // {"click":{...}} é ferramenta, não invólucro.
        return unwrap(inner, depth + 1)
    }

    private fun canonicalArgs(alias: Alias, args: JsonObject, context: ParseContext): JsonObject = when (alias.tool) {
        Tools.CLICK -> buildJsonObject {
            put("id", args.requireId(context, "click", context.targets?.click, "clickable"))
        }
        Tools.TYPE -> buildJsonObject {
            args.optionalId(context, context.targets?.type, "an edit field")?.let { put("id", it) }
            val text = TEXT_KEYS.firstNotNullOfOrNull { args.string(it) }
                ?: throw InvalidArgs(ParseError.InvalidArgs, "type needs \"text\".")
            put("text", text)
            put("enter", ENTER_KEYS.firstNotNullOfOrNull { args.bool(it) } ?: false)
        }
        Tools.SCROLL -> buildJsonObject {
            val dir = alias.impliedValue
                ?: DIR_KEYS.firstNotNullOfOrNull { args.string(it) }?.let(::normalizeDirection)
                ?: "down"
            put("dir", dir)
            args.optionalId(context, context.targets?.scroll, "scrollable")?.let { put("id", it) }
        }
        Tools.INTENT -> buildJsonObject {
            val kind = alias.impliedValue
                ?: KIND_KEYS.firstNotNullOfOrNull { args.string(it) }?.let(::normalizeIntentKind)
                ?: throw InvalidArgs(ParseError.InvalidArgs, "intent needs \"kind\": ${Tools.INTENT_KINDS.joinToString("|")}.")
            if (kind !in Tools.INTENT_KINDS) {
                throw InvalidArgs(ParseError.InvalidArgs, "Unknown intent kind \"$kind\". Use: ${Tools.INTENT_KINDS.joinToString("|")}.")
            }
            // add_contact às vezes vem com nome e telefone em campos separados: junta no formato "Nome | telefone".
            val contact = if (kind == "add_contact") {
                listOfNotNull(args.string("name"), args.string("phone") ?: args.string("number"))
                    .takeIf { it.size == 2 }?.joinToString(" | ")
            } else {
                null
            }
            val value = (contact ?: VALUE_KEYS.firstNotNullOfOrNull { args.string(it) })?.takeIf { it.isNotBlank() }
                ?: throw InvalidArgs(ParseError.InvalidArgs, "intent needs a non-empty \"value\".")
            put("kind", kind)
            put("value", value)
        }
        Tools.BACK -> JsonObject(emptyMap())
        Tools.DONE -> buildJsonObject {
            put("ok", OK_KEYS.firstNotNullOfOrNull { args.bool(it) } ?: true)
            put("msg", MSG_KEYS.firstNotNullOfOrNull { args.string(it) }.orEmpty())
        }
        else -> error("Ferramenta sem normalização: ${alias.tool}")
    }

    private fun JsonObject.requireId(context: ParseContext, tool: String, allowed: Set<Int>?, what: String): Int =
        optionalId(context, allowed, what) ?: throw InvalidArgs(ParseError.InvalidArgs, "$tool needs \"id\" from SCREEN.")

    private fun JsonObject.optionalId(context: ParseContext, allowed: Set<Int>?, what: String): Int? {
        val id = ID_KEYS.firstNotNullOfOrNull { key -> this[key]?.let(::coerceId) } ?: return null
        val valid = context.validIds
        if (valid != null && id !in valid) {
            throw InvalidArgs(ParseError.UnknownId, "id $id is not on SCREEN. Use only ids listed in SCREEN.")
        }
        if (allowed != null && id !in allowed) {
            throw InvalidArgs(ParseError.WrongTarget, "id $id is not $what. Choose one of: ${allowed.joinToString()}.")
        }
        return id
    }

    private class InvalidArgs(val error: ParseError, message: String) : Exception(message)

    private data class Alias(val tool: String, val impliedValue: String? = null)

    private fun resolveAlias(name: String): Alias? = ALIASES[name.trim().lowercase().replace('-', '_').replace(' ', '_')]

    private fun normalizeDirection(raw: String): String = when (raw.trim().lowercase()) {
        "up", "top", "backward", "backwards", "previous" -> "up"
        "left" -> "left"
        "right" -> "right"
        else -> "down"
    }

    private fun normalizeIntentKind(raw: String): String {
        val key = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        return INTENT_KIND_ALIASES[key] ?: key
    }

    private fun coerceId(element: JsonElement): Int? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.doubleOrNull?.let { d -> if (d % 1.0 == 0.0) return d.toInt() }
        return primitive.contentOrNull?.trim()?.trim('[', ']', '#')?.toIntOrNull()
    }

    private fun JsonObject.string(key: String): String? = when (val value = this[key]) {
        is JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    private fun JsonObject.bool(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "1", "yes", "y", "sim", "success", "succeeded", "ok", "done", "completed" -> true
            "0", "no", "n", "nao", "não", "fail", "failed", "failure", "error", "impossible" -> false
            else -> null
        }
    }

    /** `args` pode vir como objeto ou como string com JSON dentro (estilo OpenAI `arguments`). */
    private fun JsonObject.argsObject(key: String): JsonObject? = when (val value = this[key]) {
        is JsonObject -> value
        is JsonPrimitive -> value.contentOrNull
            ?.takeIf { it.trimStart().startsWith("{") }
            ?.let { decode(it) ?: decode(JsonRepair.repair(it)) } as? JsonObject
        else -> null
    }

    private companion object {
        val SPECIAL_TOKENS = Regex("""<\|[^|>]{1,40}\|>|</?(?:end_of_turn|start_of_turn|eos|bos|s)>""")
        val TOOL_CALL_TAGS = Regex("""</?tool_call>""")
        val CODE_FENCE = Regex("""```[a-zA-Z]*""")
        val FUNCTION_CALL = Regex("""^\s*([a-zA-Z_]+)\s*\(([^()]*)\)\s*$""", RegexOption.MULTILINE)

        val THOUGHT_KEYS = listOf("thought", "thoughts", "think", "reason", "reasoning", "rationale")
        val NAME_KEYS = listOf("tool", "name", "action", "function", "tool_name", "command")
        val ARGS_KEYS = listOf("args", "arguments", "parameters", "params", "input", "action_input")
        val ID_KEYS = listOf("id", "element", "element_id", "index", "idx", "target")
        val TEXT_KEYS = listOf("text", "value", "content", "input_text", "string", "message")
        val ENTER_KEYS = listOf("enter", "submit", "press_enter")
        val DIR_KEYS = listOf("dir", "direction")
        val KIND_KEYS = listOf("kind", "type", "intent", "action")
        val VALUE_KEYS = listOf("value", "app", "app_name", "name", "query", "url", "number", "phone", "time", "seconds", "text", "package")
        val OK_KEYS = listOf("ok", "success", "status", "done", "completed", "result")
        val MSG_KEYS = listOf("msg", "message", "answer", "text", "reply", "response", "result")

        val ALIASES: Map<String, Alias> = buildMap {
            listOf("click", "tap", "press", "touch", "select", "click_element", "tap_element")
                .forEach { put(it, Alias(Tools.CLICK)) }
            listOf("type", "input", "write", "input_text", "type_text", "enter_text", "fill", "set_text")
                .forEach { put(it, Alias(Tools.TYPE)) }
            put("scroll", Alias(Tools.SCROLL))
            put("swipe", Alias(Tools.SCROLL))
            Tools.SCROLL_DIRECTIONS.forEach { put("scroll_$it", Alias(Tools.SCROLL, it)) }
            put("intent", Alias(Tools.INTENT))
            put("launch_intent", Alias(Tools.INTENT))
            listOf("open", "open_app", "launch", "launch_app", "start_app").forEach { put(it, Alias(Tools.INTENT, "open_app")) }
            listOf("alarm", "set_alarm").forEach { put(it, Alias(Tools.INTENT, "alarm")) }
            listOf("timer", "set_timer").forEach { put(it, Alias(Tools.INTENT, "timer")) }
            listOf("url", "open_url", "browse").forEach { put(it, Alias(Tools.INTENT, "url")) }
            listOf("dial", "call", "phone_call").forEach { put(it, Alias(Tools.INTENT, "dial")) }
            listOf("web_search", "search", "google").forEach { put(it, Alias(Tools.INTENT, "web_search")) }
            listOf("add_contact", "create_contact", "new_contact", "save_contact").forEach { put(it, Alias(Tools.INTENT, "add_contact")) }
            listOf("back", "go_back", "navigate_back", "press_back").forEach { put(it, Alias(Tools.BACK)) }
            listOf("done", "finish", "finished", "answer", "complete", "stop", "end", "respond", "reply")
                .forEach { put(it, Alias(Tools.DONE)) }
        }

        val INTENT_KIND_ALIASES = mapOf(
            "app" to "open_app", "open" to "open_app", "launch" to "open_app", "open_app" to "open_app",
            "set_alarm" to "alarm", "set_timer" to "timer",
            "search" to "web_search", "google" to "web_search", "websearch" to "web_search",
            "call" to "dial", "phone" to "dial",
            "link" to "url", "open_url" to "url", "browser" to "url", "website" to "url",
            "contact" to "add_contact", "create_contact" to "add_contact", "new_contact" to "add_contact",
        )
    }
}
