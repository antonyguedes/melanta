package com.melanta.core.automation.service

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

/**
 * Espera a UI estabilizar depois de uma ação: [QUIET_MS] sem eventos de acessibilidade,
 * com teto de [TIMEOUT_MS] para telas que nunca param (vídeo, animações).
 */
@Singleton
class UiIdleWatcher internal constructor(private val clock: () -> Long) {

    @Inject
    constructor() : this(SystemClock::elapsedRealtime)

    private val lastEventAt = AtomicLong(clock())

    fun onEvent() {
        lastEventAt.set(clock())
    }

    /**
     * Retorna `true` se a tela ficou estável, `false` se o teto estourou. Espera pelo menos
     * [minSettleMs]: animações de janela (bottom sheets, transições) não geram eventos de acessibilidade.
     */
    suspend fun awaitIdle(
        quietMs: Long = QUIET_MS,
        timeoutMs: Long = TIMEOUT_MS,
        minSettleMs: Long = MIN_SETTLE_MS,
    ): Boolean {
        val start = clock()
        // Dá tempo para a ação gerar os primeiros eventos antes de medir o silêncio.
        lastEventAt.set(maxOf(lastEventAt.get(), start))
        while (true) {
            val now = clock()
            val quietFor = now - lastEventAt.get()
            if (quietFor >= quietMs && now - start >= minSettleMs) return true
            if (now - start >= timeoutMs) return false
            val untilQuiet = quietMs - quietFor
            val untilSettled = minSettleMs - (now - start)
            delay(minOf(maxOf(untilQuiet, untilSettled), timeoutMs - (now - start)).coerceAtLeast(1))
        }
    }

    companion object {
        const val QUIET_MS = 300L
        const val MIN_SETTLE_MS = 500L
        const val TIMEOUT_MS = 3_000L
    }
}
