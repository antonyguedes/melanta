package com.melanta.core.ai.download

import com.melanta.core.common.DispatcherProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HttpRangeDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = Random(42).nextBytes(700_000)
    private lateinit var server: HttpServer
    private var honorRange = true
    private val rangeHeaders = mutableListOf<String?>()

    private val downloader = HttpRangeDownloader(
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val io: CoroutineDispatcher = Dispatchers.IO
        },
    )

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/model.task") { exchange ->
                val range = exchange.requestHeaders.getFirst("Range")
                rangeHeaders += range
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLong()
                if (honorRange && start != null) {
                    if (start >= payload.size) {
                        exchange.sendResponseHeaders(416, -1)
                    } else {
                        val body = payload.copyOfRange(start.toInt(), payload.size)
                        exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                        exchange.sendResponseHeaders(206, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                } else {
                    exchange.sendResponseHeaders(200, payload.size.toLong())
                    exchange.responseBody.use { it.write(payload) }
                }
                exchange.close()
            }
            createContext("/missing") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun `download completo sem arquivo parcial`() = runTest {
        val target = File(tmp.root, "m.part")
        var lastProgress = 0L

        downloader.download(url("/model.task"), target, payload.size.toLong()) { downloaded, _ ->
            lastProgress = downloaded
        }

        assertArrayEquals(payload, target.readBytes())
        assertEquals(payload.size.toLong(), lastProgress)
        assertEquals(listOf<String?>(null), rangeHeaders)
    }

    @Test
    fun `retoma a partir do arquivo parcial com Range`() = runTest {
        val target = File(tmp.root, "m.part").apply { writeBytes(payload.copyOfRange(0, 250_000)) }

        downloader.download(url("/model.task"), target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf<String?>("bytes=250000-"), rangeHeaders)
    }

    @Test
    fun `apos desligamento abrupto volta ao ultimo checkpoint antes de retomar`() = runTest {
        // 300.000 bytes confirmados em disco; depois disso, lixo que nunca foi gravado de verdade.
        val target = File(tmp.root, "m.part").apply { writeBytes(payload.copyOfRange(0, 300_000) + ByteArray(50_000)) }
        File(target.path + HttpRangeDownloader.CHECKPOINT_SUFFIX).writeText("300000")

        downloader.download(url("/model.task"), target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf<String?>("bytes=300000-"), rangeHeaders)
        assertEquals(payload.size.toString(), File(target.path + HttpRangeDownloader.CHECKPOINT_SUFFIX).readText())
    }

    @Test
    fun `recomeca do zero quando o servidor ignora o Range`() = runTest {
        honorRange = false
        val target = File(tmp.root, "m.part").apply { writeBytes(ByteArray(1000) { 7 }) }

        downloader.download(url("/model.task"), target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `nao faz requisicao quando o parcial ja esta completo`() = runTest {
        val target = File(tmp.root, "m.part").apply { writeBytes(payload) }

        downloader.download(url("/model.task"), target, payload.size.toLong())

        assertTrue(rangeHeaders.isEmpty())
        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `parcial maior que o esperado e descartado`() = runTest {
        val target = File(tmp.root, "m.part").apply { writeBytes(payload + ByteArray(10)) }

        downloader.download(url("/model.task"), target, payload.size.toLong())

        assertArrayEquals(payload, target.readBytes())
        assertEquals(listOf<String?>(null), rangeHeaders)
    }

    @Test
    fun `416 apaga o parcial e falha com erro nao permanente`() = runTest {
        // Tamanho esperado errado faz o cliente pedir um range além do fim do arquivo.
        val target = File(tmp.root, "m.part").apply { writeBytes(payload) }
        try {
            downloader.download(url("/model.task"), target, expectedSize = payload.size + 100L)
            fail("deveria lançar HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(416, e.code)
            assertFalse(e.isPermanent)
        }
        assertFalse(target.exists())
    }

    @Test
    fun `404 e erro permanente`() = runTest {
        try {
            downloader.download(url("/missing"), File(tmp.root, "x.part"), expectedSize = null)
            fail("deveria lançar HttpStatusException")
        } catch (e: HttpStatusException) {
            assertEquals(404, e.code)
            assertTrue(e.isPermanent)
        }
    }

    @Test
    fun `sha256 confere com MessageDigest`() = runTest {
        val file = File(tmp.root, "h.bin").apply { writeBytes(payload) }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        assertEquals(expected, file.sha256Hex())
    }
}
