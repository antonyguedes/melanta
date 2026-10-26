package com.melanta.core.ai.tool

/**
 * Reparo leve dos erros de JSON mais comuns em SLMs, sem regex sobre o texto inteiro:
 * - strings com aspas simples -> aspas duplas;
 * - chaves e valores sem aspas (`{tool: click}`) -> com aspas;
 * - `True`/`False`/`None` -> `true`/`false`/`null`;
 * - vírgula sobrando antes de `}` ou `]`;
 * - quebras de linha cruas dentro de strings -> `\n`.
 */
internal object JsonRepair {

    private val LITERALS = mapOf(
        "true" to "true", "True" to "true", "TRUE" to "true",
        "false" to "false", "False" to "false", "FALSE" to "false",
        "null" to "null", "None" to "null", "NULL" to "null",
    )

    fun repair(input: String): String {
        val out = StringBuilder(input.length + 16)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '"' || c == '\'' -> i = copyString(input, i, out)
                c == ',' && nextNonSpace(input, i + 1).let { it == '}' || it == ']' || it == null } -> i++
                // Letra colada em dígito faz parte de um número (1e5): copia como está.
                (c.isLetter() || c == '_') && out.lastOrNull()?.isDigit() != true -> {
                    val end = wordEnd(input, i)
                    val word = input.substring(i, end)
                    val isKey = nextNonSpace(input, end) == ':'
                    out.append(
                        when {
                            isKey -> "\"$word\""
                            word in LITERALS -> LITERALS.getValue(word)
                            else -> "\"$word\""
                        },
                    )
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Copia a string que começa em [start] como string JSON com aspas duplas. Retorna o índice após ela. */
    private fun copyString(input: String, start: Int, out: StringBuilder): Int {
        val quote = input[start]
        out.append('"')
        var i = start + 1
        while (i < input.length) {
            val c = input[i]
            when {
                c == '\\' && i + 1 < input.length -> {
                    val next = input[i + 1]
                    // \' não é escape válido em JSON.
                    if (next == '\'') out.append('\'') else out.append(c).append(next)
                    i += 2
                    continue
                }
                c == quote -> {
                    out.append('"')
                    return i + 1
                }
                c == '"' -> out.append("\\\"")
                c == '\n' -> out.append("\\n")
                c == '\r' -> Unit
                c == '\t' -> out.append("\\t")
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
        return i
    }

    private fun wordEnd(input: String, start: Int): Int {
        var i = start
        while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_' || input[i] == '.')) i++
        return i
    }

    private fun nextNonSpace(input: String, from: Int): Char? {
        var i = from
        while (i < input.length && input[i].isWhitespace()) i++
        return input.getOrNull(i)
    }
}
