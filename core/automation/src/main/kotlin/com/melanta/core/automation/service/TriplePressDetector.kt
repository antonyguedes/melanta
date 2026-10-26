package com.melanta.core.automation.service

/** Detecta N toques dentro de uma janela de tempo (kill switch físico: Volume− três vezes). */
class TriplePressDetector(
    private val presses: Int = 3,
    private val windowMs: Long = 1_500,
) {
    private val times = ArrayDeque<Long>()

    /** Registra um toque em [nowMs]; retorna `true` quando completa a sequência (e zera). */
    fun onPress(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        if (times.size < presses) return false
        times.clear()
        return true
    }
}
