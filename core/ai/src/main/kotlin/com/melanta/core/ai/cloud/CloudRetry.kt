package com.melanta.core.ai.cloud

import kotlinx.coroutines.delay

/** Repete só o que pode passar sozinho (limite de requisições, rede, 5xx), com espera crescente. */
suspend fun <T> withCloudRetry(
    maxRetries: Int = 2,
    baseDelayMillis: Long = 1_000,
    maxWaitMillis: Long = 15_000,
    block: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CloudRateLimitException) {
            val wait = e.retryAfterSeconds?.times(1_000) ?: (baseDelayMillis shl attempt)
            // O provedor pediu uma espera maior que a tarefa aguenta: melhor avisar logo.
            if (attempt >= maxRetries || wait > maxWaitMillis) throw e
            delay(wait)
        } catch (e: CloudUnavailableException) {
            if (attempt >= maxRetries) throw e
            delay(baseDelayMillis shl attempt)
        }
        attempt++
    }
}
