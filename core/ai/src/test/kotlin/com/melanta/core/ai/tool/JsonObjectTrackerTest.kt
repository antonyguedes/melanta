package com.melanta.core.ai.tool

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonObjectTrackerTest {

    @Test
    fun `fecha so quando o objeto externo fecha`() {
        val tracker = JsonObjectTracker()
        assertFalse(tracker.feed("""ok {"tool":"click","args":{"id":3}"""))
        assertTrue(tracker.feed("} trailing"))
        assertEquals("""{"tool":"click","args":{"id":3}}""", tracker.closedObject())
    }

    @Test
    fun `chaves dentro de strings e escapes nao contam, mesmo divididos entre trechos`() {
        val tracker = JsonObjectTracker()
        val chunks = listOf("{\"thought\":\"a } b \\\"", " { c\",\"tool\":\"ba", "ck\",\"args\":{}}")
        val closedAt = chunks.indexOfFirst { tracker.feed(it) }
        assertEquals(2, closedAt)
        Json.parseToJsonElement(tracker.closedObject()!!)
    }

    @Test
    fun `aspas simples tambem delimitam strings`() {
        val tracker = JsonObjectTracker.firstObject("""{'thought': 'a } b', 'tool': 'back'}""")
        assertTrue(tracker.isClosed)
    }

    @Test
    fun `objeto truncado e completado`() {
        assertEquals(
            """{"tool":"type","args":{"text":"chego em"}}""",
            JsonObjectTracker.firstObject("""{"tool":"type","args":{"text":"chego em""").completedObject(),
        )
        assertEquals(
            """{"tool":"scroll","args":{"dir":"down"}}""",
            JsonObjectTracker.firstObject("""{"tool":"scroll","args":{"dir":"down",""").completedObject(),
        )
        assertEquals(
            """{"tool":"click","args":{"id":null}}""",
            JsonObjectTracker.firstObject("""{"tool":"click","args":{"id":""").completedObject(),
        )
    }

    @Test
    fun `sem chave de abertura nao ha objeto`() {
        assertNull(JsonObjectTracker.firstObject("click the button").completedObject())
    }

    @Test
    fun `corte no stream para de coletar a fonte quando o objeto fecha`() = runTest {
        var produced = 0
        val source = flow {
            for (chunk in listOf("Sure ", """{"tool":""", """"back","args":{}""", "}", " extra", " never")) {
                produced++
                emit(chunk)
            }
        }

        val collected = source.untilFirstJsonObject().toList()

        assertEquals(listOf("Sure ", """{"tool":""", """"back","args":{}""", "}"), collected)
        assertEquals(4, produced)
    }
}
