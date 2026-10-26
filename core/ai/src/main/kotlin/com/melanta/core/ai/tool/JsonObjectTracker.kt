package com.melanta.core.ai.tool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * Acompanha, caractere a caractere, o primeiro objeto `{...}` de um texto.
 *
 * Respeita strings com aspas duplas ou simples e escapes, então chaves dentro de strings não
 * contam. Texto antes da primeira `{` é ignorado.
 */
internal class JsonObjectTracker {
    private val text = StringBuilder()
    private val closers = ArrayDeque<Char>()
    private var quote: Char? = null
    private var escaped = false

    var start: Int = -1
        private set

    /** Índice exclusivo do fim do primeiro objeto, ou -1 se ele ainda não fechou. */
    var end: Int = -1
        private set

    val isClosed: Boolean get() = end >= 0

    /** Retorna `true` quando o primeiro objeto fecha (inclusive se fechou neste trecho). */
    fun feed(chunk: String): Boolean {
        if (isClosed) return true
        for (c in chunk) {
            val index = text.length
            text.append(c)
            if (start < 0) {
                if (c == '{') {
                    start = index
                    closers.addLast('}')
                }
                continue
            }
            val q = quote
            if (q != null) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == q -> quote = null
                }
                continue
            }
            when (c) {
                '"', '\'' -> quote = c
                '{' -> closers.addLast('}')
                '[' -> closers.addLast(']')
                '}', ']' -> {
                    if (closers.lastOrNull() == c) closers.removeLast()
                    if (closers.isEmpty()) {
                        end = index + 1
                        return true
                    }
                }
            }
        }
        return false
    }

    /** O primeiro objeto completo, ou `null` se ele não começou ou não fechou. */
    fun closedObject(): String? = if (isClosed) text.substring(start, end) else null

    /**
     * Fecha um objeto truncado (geração cortada pelo limite de tokens): fecha a string aberta
     * e as chaves/colchetes pendentes. Retorna `null` se nenhum objeto começou.
     */
    fun completedObject(): String? {
        closedObject()?.let { return it }
        if (start < 0) return null
        return buildString {
            append(text, start, text.length)
            if (escaped) setLength(length - 1)
            quote?.let { append(it) }
            setLength(trimEnd().length)
            when {
                endsWith(',') -> setLength(length - 1)
                endsWith(':') -> append("null")
            }
            closers.reversed().forEach { append(it) }
        }
    }

    companion object {
        fun firstObject(text: String): JsonObjectTracker = JsonObjectTracker().apply { feed(text) }
    }
}

/**
 * Corte no stream: repassa os trechos e para de coletar assim que o primeiro objeto JSON fecha.
 * O cancelamento da coleta interrompe a geração no motor, economizando tokens e latência.
 */
fun Flow<String>.untilFirstJsonObject(): Flow<String> = flow {
    val tracker = JsonObjectTracker()
    emitAll(
        this@untilFirstJsonObject.transformWhile { chunk ->
            emit(chunk)
            !tracker.feed(chunk)
        },
    )
}
