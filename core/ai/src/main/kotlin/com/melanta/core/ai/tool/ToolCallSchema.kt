package com.melanta.core.ai.tool

import com.melanta.core.ai.prompt.ScreenTargets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema do formato de saída para constrained decoding (LLGuidance no LiteRT-LM).
 *
 * Com [ScreenTargets], cada ferramenta só aceita ids que fazem sentido para ela: `click` em
 * elementos clicáveis, `type` em campos editáveis, `scroll` em contêineres roláveis. Ferramentas
 * sem alvo na tela saem do schema. Sem alvos (`null`), qualquer inteiro é aceito.
 */
object ToolCallSchema {

    fun build(targets: ScreenTargets?): String {
        val variants = buildJsonArray {
            if (targets == null || targets.click.isNotEmpty()) {
                add(variant(Tools.CLICK, mapOf("id" to ids(targets?.click)), required = listOf("id")))
            }
            if (targets == null || targets.type.isNotEmpty()) {
                add(
                    variant(
                        Tools.TYPE,
                        mapOf("id" to ids(targets?.type), "text" to string(minLength = 1), "enter" to boolean()),
                        required = listOf("id", "text"),
                    ),
                )
            }
            add(
                variant(
                    Tools.SCROLL,
                    buildMap {
                        put("dir", enum(Tools.SCROLL_DIRECTIONS))
                        if (targets == null || targets.scroll.isNotEmpty()) put("id", ids(targets?.scroll))
                    },
                    required = listOf("dir"),
                ),
            )
            add(
                variant(
                    Tools.INTENT,
                    mapOf("kind" to enum(Tools.INTENT_KINDS), "value" to string(minLength = 1)),
                    required = listOf("kind", "value"),
                ),
            )
            add(variant(Tools.BACK, emptyMap(), required = emptyList()))
            add(variant(Tools.DONE, mapOf("ok" to boolean(), "msg" to string()), required = listOf("ok", "msg")))
        }
        return buildJsonObject { put("anyOf", variants) }.toString()
    }

    private fun ids(allowed: Set<Int>?): JsonObject = if (allowed == null) {
        buildJsonObject { put("type", "integer") }
    } else {
        buildJsonObject { put("enum", JsonArray(allowed.sorted().map(::JsonPrimitive))) }
    }

    private fun variant(tool: String, args: Map<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("thought") {
                put("type", "string")
                put("maxLength", MAX_THOUGHT_CHARS)
            }
            putJsonObject("tool") { put("const", tool) }
            putJsonObject("args") {
                put("type", "object")
                put("properties", JsonObject(args))
                putJsonArray("required") { required.forEach { add(it) } }
                put("additionalProperties", false)
            }
        }
        putJsonArray("required") {
            add("thought")
            add("tool")
            add("args")
        }
        put("additionalProperties", false)
    }

    private fun string(minLength: Int? = null) = buildJsonObject {
        put("type", "string")
        minLength?.let { put("minLength", it) }
    }

    private fun boolean() = buildJsonObject { put("type", "boolean") }

    private fun enum(values: List<String>) = buildJsonObject {
        putJsonArray("enum") { values.forEach { add(it) } }
    }

    private const val MAX_THOUGHT_CHARS = 80
}
