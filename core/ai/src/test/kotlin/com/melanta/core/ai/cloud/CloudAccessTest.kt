package com.melanta.core.ai.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeSettings(initial: InferencePreferences = InferencePreferences()) : InferenceSettings {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<InferencePreferences> = flow
    override suspend fun current() = flow.value
    override suspend fun update(transform: (InferencePreferences) -> InferencePreferences) = flow.update(transform)
}

class CloudAccessTest {

    private val keys = object : ApiKeyStore {
        val map = mutableMapOf<String, String>()
        override fun get(destinationId: String) = map[destinationId]
        override fun put(destinationId: String, apiKey: String) { map[destinationId] = apiKey }
        override fun remove(destinationId: String) { map.remove(destinationId) }
    }

    private suspend fun assertNotReady(access: CloudAccess) {
        try {
            access.endpoint()
            fail("esperava CloudNotReadyException")
        } catch (_: CloudNotReadyException) {
        }
    }

    @Test
    fun `sem consentimento para o destino nada e liberado`() = runTest {
        val config = CloudConfig(CloudProvider.Groq)
        keys.put(config.destinationId, "k")
        val settings = FakeSettings(InferencePreferences(mode = InferenceMode.Cloud, cloud = config))
        val access = CloudAccess(settings, keys)
        assertNotReady(access)
        assertFalse(access.isReady(settings.current()))

        settings.update { it.copy(consentedDestinations = setOf(config.destinationId)) }
        assertEquals("k", access.endpoint().apiKey)
        assertTrue(access.isReady(settings.current()))
    }

    @Test
    fun `trocar o endereco exige novo consentimento e nao leva a chave junto`() = runTest {
        val original = CloudConfig(CloudProvider.Custom, "https://a.example/v1", "m")
        keys.put(original.destinationId, "chave-de-a")
        val moved = original.copy(baseUrl = "https://b.example/v1")
        assertNotEquals(original.destinationId, moved.destinationId)

        val settings = FakeSettings(InferencePreferences(mode = InferenceMode.Cloud, cloud = moved, consentedDestinations = setOf(original.destinationId)))
        val access = CloudAccess(settings, keys)
        assertNotReady(access)

        settings.update { it.copy(consentedDestinations = it.consentedDestinations + moved.destinationId) }
        assertNull(access.endpoint().apiKey)
    }

    @Test
    fun `provedor com preset exige chave`() = runTest {
        val config = CloudConfig(CloudProvider.OpenAi)
        val settings = FakeSettings(InferencePreferences(cloud = config, consentedDestinations = setOf(config.destinationId)))
        assertNotReady(CloudAccess(settings, keys))
    }

    @Test
    fun `endereco sem criptografia e recusado`() {
        assertTrue(CloudConfig.isAllowedUrl("https://api.exemplo.com/v1"))
        assertTrue(CloudConfig.isAllowedUrl("http://10.0.2.2:8123/v1"))
        assertFalse(CloudConfig.isAllowedUrl("http://api.exemplo.com/v1"))
        assertFalse(CloudConfig.isAllowedUrl("http://192.168.0.10:11434/v1"))
        assertFalse(CloudConfig.isAllowedUrl("ftp://api.exemplo.com"))
        assertFalse(CloudConfig.isAllowedUrl("https://usuario:senha@api.exemplo.com/v1"))
        assertFalse(CloudConfig.isAllowedUrl("não é url"))
        assertTrue(CloudConfig(CloudProvider.Custom, "http://api.exemplo.com/v1", "m").problem() != null)
        CloudProvider.entries.filter { it != CloudProvider.Custom }.forEach { assertNull(it.name, CloudConfig(it).problem()) }
    }

    @Test
    fun `retry insiste no que pode passar e desiste do resto`() = runTest {
        var calls = 0
        assertEquals("ok", withCloudRetry { if (++calls < 3) throw CloudUnavailableException("x") else "ok" })
        assertEquals(3, calls)

        calls = 0
        try {
            withCloudRetry<Unit> { calls++; throw CloudUnavailableException("x") }
            fail()
        } catch (_: CloudUnavailableException) {
            assertEquals(3, calls)
        }

        calls = 0
        try {
            withCloudRetry<Unit> { calls++; throw CloudAuthException() }
            fail()
        } catch (_: CloudAuthException) {
            assertEquals(1, calls)
        }

        calls = 0
        try {
            withCloudRetry<Unit> { calls++; throw CloudRateLimitException(retryAfterSeconds = 600) }
            fail()
        } catch (_: CloudRateLimitException) {
            assertEquals("espera longa demais: avisa logo", 1, calls)
        }
    }
}
