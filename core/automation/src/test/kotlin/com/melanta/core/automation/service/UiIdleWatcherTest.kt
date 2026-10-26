package com.melanta.core.automation.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiIdleWatcherTest {

    @Test
    fun `tela parada libera apos o tempo minimo de acomodacao`() = runTest {
        val watcher = UiIdleWatcher { currentTime }
        assertTrue(watcher.awaitIdle(quietMs = 300, timeoutMs = 3000, minSettleMs = 500))
        assertEquals(500, currentTime)
    }

    @Test
    fun `eventos adiam a liberacao`() = runTest {
        val watcher = UiIdleWatcher { currentTime }
        launch {
            repeat(4) {
                kotlinx.coroutines.delay(200)
                watcher.onEvent()
            }
        }
        assertTrue(watcher.awaitIdle(quietMs = 300, timeoutMs = 3000))
        assertEquals(1100, currentTime)
    }

    @Test
    fun `tela que nunca para estoura o teto`() = runTest {
        val watcher = UiIdleWatcher { currentTime }
        val animation = launch {
            while (true) {
                kotlinx.coroutines.delay(100)
                watcher.onEvent()
            }
        }
        assertFalse(watcher.awaitIdle(quietMs = 300, timeoutMs = 3000))
        assertEquals(3000, currentTime)
        animation.cancel()
    }
}
