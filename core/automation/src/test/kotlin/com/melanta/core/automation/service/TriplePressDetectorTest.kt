package com.melanta.core.automation.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TriplePressDetectorTest {

    @Test
    fun `tres toques dentro da janela disparam e zeram`() {
        val detector = TriplePressDetector(presses = 3, windowMs = 1_500)
        assertEquals(listOf(false, false, true, false), listOf(0L, 400L, 900L, 1_000L).map(detector::onPress))
    }

    @Test
    fun `toques espacados demais nao disparam`() {
        val detector = TriplePressDetector(presses = 3, windowMs = 1_500)
        assertEquals(listOf(false, false, false, false), listOf(0L, 1_000L, 2_000L, 3_600L).map(detector::onPress))
    }
}
