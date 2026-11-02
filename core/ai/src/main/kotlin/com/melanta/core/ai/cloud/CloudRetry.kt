package com.melanta.core.ai.cloud

import kotlinx.coroutines.delay

/**
 * Repete só o que pode passar sozinho (limite de requisições, rede, 5xx), com espera crescente.
 * Limites de requisição costumam valer por minuto: sem um prazo do provedor, as esperas somam mais que isso.
 */
suspend fun <T> withCloudRetry(
    maxRetries: Int = 2,
    baseDelayMillis: Long = 1_000,
    maxWaitMillis: Long = 60_000,
    rateLimitRetries: Int = 3,
    rateLimitBaseDelayMillis: Long = 5_000,
    block: suspend () -> T,
): T {
    var attempt = 0
    var rateLimited = 0
    while (true) {
        try {
            return block()
        } catch (e: CloudRateLimitException) {
            // 5 s, 15 s, 45 s. Se o provedor pedir uma espera maior que a tarefa aguenta, melhor avisar logo.
            val wait = e.retryAfterSeconds?.times(1_000) ?: (rateLimitBaseDelayMillis * POWERS_OF_3[rateLimited.coerceAtMost(2)])
            if (rateLimited >= rateLimitRetries || wait > maxWaitMillis) throw e
            rateLimited++
            delay(wait)
        } catch (e: CloudUnavailableException) {
            if (attempt >= maxRetries) throw e
            delay(baseDelayMillis shl attempt)
            attempt++
        }
    }
}

private val POWERS_OF_3 = longArrayOf(1, 3, 9)
