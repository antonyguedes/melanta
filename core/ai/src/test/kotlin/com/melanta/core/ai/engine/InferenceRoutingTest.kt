package com.melanta.core.ai.engine

import com.melanta.core.ai.cloud.CloudConfig
import com.melanta.core.ai.cloud.FakeSettings
import com.melanta.core.ai.cloud.InferenceMode
import com.melanta.core.ai.cloud.InferencePreferences
import com.melanta.core.ai.cloud.ScreenRedactor
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.ai.prompt.PromptBudget
import com.melanta.core.ai.prompt.PromptBuilder
import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.prompt.ScreenContext
import com.melanta.core.ai.prompt.ScreenLine
import com.melanta.core.ai.tool.ToolCallParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceRoutingTest {

    private class Recorder(private val reply: String = VALID) : TextGenerator {
        val prompts = mutableListOf<LlmPrompt>()
        val params = mutableListOf<GenParams>()
        override val lastStats = MutableStateFlow<GenerationStats?>(null)
        override fun generate(prompt: LlmPrompt, params: GenParams): Flow<String> = flow {
            prompts += prompt
            this@Recorder.params += params
            emit(reply)
            lastStats.value = GenerationStats(1, 1, 1)
        }
    }

    private val local = Recorder()
    private val cloud = Recorder()
    private val settings = FakeSettings()
    private val router = InferenceRouter(settings, local, cloud, ScreenRedactor())
    private val generator = ToolCallGenerator(router, PromptBuilder(), ToolCallParser())

    private val secretScreen = PromptInput(
        task = "tarefa",
        history = (1..10).map { "acao $it -> ok" },
        screen = ScreenContext("com.app", null, listOf(ScreenLine(1, """[1] btn "Ana 11 98765-4321" click"""))),
    )

    private suspend fun useCloud() = settings.update { InferencePreferences(mode = InferenceMode.Cloud, cloud = CloudConfig()) }

    @Test
    fun `modo local e o padrao e o conteudo vai intacto`() = runTest {
        generator.decide(secretScreen, constrained = true)

        assertTrue(cloud.prompts.isEmpty())
        val user = local.prompts.single().messages.last().text
        assertTrue(user.contains("11 98765-4321"))
        assertFalse("orçamento local mantém 4 itens de histórico", user.contains("acao 6 "))
        assertEquals(PromptBudget().outputTokens, local.params.single().maxOutputTokens)
    }

    @Test
    fun `modo nuvem redige a tela e usa o orcamento maior`() = runTest {
        useCloud()
        generator.decide(secretScreen, constrained = true)

        assertTrue(local.prompts.isEmpty())
        val user = cloud.prompts.single().messages.last().text
        assertFalse(user, user.contains("98765"))
        assertTrue(user, user.contains("«número»"))
        assertTrue("orçamento da nuvem mantém o histórico inteiro", user.contains("acao 1 "))
        assertEquals(PromptBudget.Cloud.outputTokens, cloud.params.single().maxOutputTokens)
    }

    @Test
    fun `correcao fica no mesmo motor e sem dados sensiveis`() = runTest {
        val invalidCloud = Recorder(reply = "isto não é json")
        val generator = ToolCallGenerator(InferenceRouter(settings, local, invalidCloud, ScreenRedactor()), PromptBuilder(), ToolCallParser())
        useCloud()
        val outcome = generator.decide(secretScreen, constrained = true)

        assertEquals(1 + ToolCallGenerator.MAX_CORRECTIONS, invalidCloud.prompts.size)
        assertEquals(1 + ToolCallGenerator.MAX_CORRECTIONS, outcome.attempts.size)
        assertTrue(local.prompts.isEmpty())
        invalidCloud.prompts.forEach { prompt -> prompt.messages.forEach { assertFalse(it.text, it.text.contains("98765")) } }
    }

    @Test
    fun `forceLocal nunca envia para a nuvem`() = runTest {
        useCloud()
        generator.decide(secretScreen, constrained = true, forceLocal = true)

        assertTrue(cloud.prompts.isEmpty())
        assertEquals(1, local.prompts.size)
    }

    private companion object {
        const val VALID = """{"thought":"t","tool":"click","args":{"id":1}}"""
    }
}
