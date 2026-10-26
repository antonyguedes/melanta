package com.melanta.feature.agent

import com.melanta.core.ai.engine.ToolCallGenerator
import com.melanta.core.ai.prompt.BuiltPrompt
import com.melanta.core.ai.prompt.ChatMessage
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.prompt.ScreenTargets
import com.melanta.core.ai.tool.ParseResult
import com.melanta.core.ai.tool.ToolCallParser
import com.melanta.core.automation.action.ActionResult
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.screen.Bounds
import com.melanta.core.automation.screen.ScreenSnapshot
import com.melanta.core.automation.screen.UiElement
import com.melanta.feature.agent.action.ToolCallMapper
import com.melanta.feature.agent.log.AgentRunLogger
import com.melanta.feature.agent.loop.ActionActuator
import com.melanta.feature.agent.loop.ActionReasoner
import com.melanta.feature.agent.loop.AgentSetupException
import com.melanta.feature.agent.loop.ModelPreparer
import com.melanta.feature.agent.loop.ScreenPerception
import com.melanta.feature.agent.safety.SafetyPolicy
import com.melanta.feature.agent.state.AgentState
import com.melanta.feature.agent.state.AgentStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentOrchestratorTest {

    private val task = "tarefa de teste"
    private val parser = ToolCallParser()
    private val controller = AgentController { }
    private val executed = mutableListOf<AgentAction>()

    private fun screen(pkg: String, vararg labels: String, hashSalt: Int = 0) = ScreenSnapshot(
        packageName = pkg,
        title = null,
        elements = labels.mapIndexed { i, label ->
            UiElement(i + 1, if (label.startsWith("edit:")) "edit" else "btn", label.removePrefix("edit:"), listOf("click"), Bounds(0, i * 100 + hashSalt, 100, i * 100 + 90), null, 4, null)
        },
        screenBounds = Bounds(0, 0, 1080, 2400),
    )

    private fun outcome(json: String): ToolCallGenerator.Outcome {
        val prompt = LlmPrompt("system", listOf(ChatMessage(ChatMessage.Role.User, "user")))
        val built = BuiltPrompt(prompt, emptySet(), ScreenTargets(emptySet(), emptySet(), emptySet()), 0, 0)
        val result = parser.parse(json)
        return ToolCallGenerator.Outcome(built, listOf(ToolCallGenerator.Attempt(json, result, 1, 1, null)))
    }

    private fun orchestrator(
        screens: (step: Int) -> ScreenSnapshot,
        decisions: List<String>,
        preparer: ModelPreparer = ModelPreparer { },
        actionResult: ActionResult = ActionResult.Ok,
        reasonerGate: CompletableDeferred<Unit>? = null,
    ): AgentOrchestrator {
        var perceived = 0
        var decided = 0
        val perception = object : ScreenPerception {
            override suspend fun snapshot() = screens(perceived++)
            override fun recentNotifications() = emptyList<String>()
        }
        val reasoner = ActionReasoner { _: PromptInput ->
            reasonerGate?.await()
            outcome(decisions[minOf(decided++, decisions.lastIndex)])
        }
        val actuator = object : ActionActuator {
            override suspend fun execute(action: AgentAction, snapshot: ScreenSnapshot): ActionResult {
                executed += action
                return actionResult
            }
            override suspend fun awaitIdle(after: AgentAction) = true
        }
        val logger = object : AgentRunLogger {
            override fun begin(task: String) = Unit
            override fun event(type: String, vararg fields: Pair<String, Any?>) = Unit
            override fun end() = Unit
        }
        return AgentOrchestrator(controller, preparer, perception, reasoner, actuator, ToolCallMapper(), SafetyPolicy(), logger)
    }

    private fun begin() = assertTrue(controller.start(task))

    @Test
    fun `caminho feliz executa a acao e conclui com a mensagem do modelo`() = runTest {
        begin()
        orchestrator(
            screens = { screen("launcher", "Relógio", hashSalt = it) },
            decisions = listOf(
                """{"thought":"alarme","tool":"intent","args":{"kind":"alarm","value":"07:00"}}""",
                """{"thought":"feito","tool":"done","args":{"ok":true,"msg":"Alarme criado para 7h."}}""",
            ),
        ).run(task)

        assertEquals(AgentState.Finished(task, "Alarme criado para 7h."), controller.state.value)
        assertEquals(listOf<AgentAction>(AgentAction.LaunchIntent(IntentSpec.Alarm(7, 0, null))), executed)
        assertEquals(listOf("Criar alarme para 07:00", "Concluir"), controller.steps.value.map { it.action })
        assertTrue(controller.steps.value.all { it.status == AgentStep.Status.Done })
    }

    @Test
    fun `compra e bloqueada mesmo que o modelo mande tocar`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.loja", "Tênis R$ 299", "Finalizar compra") },
            decisions = listOf("""{"thought":"comprar","tool":"click","args":{"id":2}}"""),
        ).run(task)

        val state = controller.state.value
        assertTrue(state is AgentState.Failed && state.reason.contains("Pagamentos"))
        assertTrue(executed.isEmpty())
        assertTrue(controller.steps.value.single().status is AgentStep.Status.Blocked)
    }

    @Test
    fun `app de banco e bloqueado antes de chamar o modelo`() = runTest {
        begin()
        orchestrator(screens = { screen("com.nu.production", "Pagar") }, decisions = listOf("""{"tool":"back"}""")).run(task)

        assertTrue(controller.state.value is AgentState.Failed)
        assertTrue(controller.steps.value.isEmpty())
    }

    @Test
    fun `envio pede confirmacao e executa quando aprovado`() = runTest {
        begin()
        val agent = orchestrator(
            screens = { screen("com.whatsapp", "edit:Mensagem", hashSalt = it) },
            decisions = listOf(
                """{"thought":"enviar","tool":"type","args":{"id":1,"text":"chego em 10 min","enter":true}}""",
                """{"thought":"pronto","tool":"done","args":{"ok":true,"msg":"Mensagem enviada."}}""",
            ),
        )
        val job = launch { agent.run(task) }
        runCurrent()
        assertTrue(controller.state.value is AgentState.AwaitingConfirmation)
        assertTrue(executed.isEmpty())

        controller.confirm(true)
        advanceUntilIdle()
        job.join()

        assertEquals(AgentState.Finished(task, "Mensagem enviada."), controller.state.value)
        assertEquals(1, executed.size)
    }

    @Test
    fun `confirmacao negada cancela sem executar`() = runTest {
        begin()
        val agent = orchestrator(
            screens = { screen("com.app", "Excluir conta") },
            decisions = listOf("""{"thought":"excluir","tool":"click","args":{"id":1}}"""),
        )
        val job = launch { agent.run(task) }
        runCurrent()
        controller.confirm(false)
        job.join()

        assertEquals(AgentState.Cancelled(task), controller.state.value)
        assertTrue(executed.isEmpty())
        assertEquals(AgentStep.Status.Denied, controller.steps.value.single().status)
    }

    @Test
    fun `sem resposta a confirmacao e negada depois do timeout`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.app", "Excluir conta") },
            decisions = listOf("""{"thought":"excluir","tool":"click","args":{"id":1}}"""),
        ).run(task)

        assertEquals(AgentState.Cancelled(task), controller.state.value)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `mesma acao na mesma tela tres vezes interrompe por loop`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.app", "Abrir") },
            decisions = listOf("""{"thought":"de novo","tool":"click","args":{"id":1}}"""),
        ).run(task)

        val state = controller.state.value
        assertTrue(state is AgentState.Failed && state.reason.contains("loop"))
        assertEquals(3, executed.size)
    }

    @Test
    fun `limite de passos`() = runTest {
        begin()
        orchestrator(
            // Cada rolagem mostra itens novos: a tela muda, então não é loop.
            screens = { screen("com.app", "Item $it") },
            decisions = listOf("""{"thought":"procurar","tool":"scroll","args":{"dir":"down"}}"""),
        ).run(task)

        val state = controller.state.value
        assertTrue(state is AgentState.Failed && state.reason.contains("${SafetyPolicy.Limits.MAX_STEPS} passos"))
        assertEquals(SafetyPolicy.Limits.MAX_STEPS, executed.size)
    }

    @Test
    fun `erros seguidos de execucao encerram`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.app", "Item", hashSalt = it) },
            decisions = listOf("""{"thought":"tocar","tool":"click","args":{"id":1}}"""),
            actionResult = ActionResult.Error("Alvo não encontrado"),
        ).run(task)

        val state = controller.state.value
        assertTrue(state is AgentState.Failed && state.reason.contains("Alvo não encontrado"))
        assertEquals(SafetyPolicy.Limits.MAX_CONSECUTIVE_ERRORS, executed.size)
    }

    @Test
    fun `done com sucesso depois de so errar vira falha`() = runTest {
        begin()
        orchestrator(
            screens = { screen("launcher", "Relógio", hashSalt = it) },
            decisions = listOf(
                """{"thought":"abrir","tool":"intent","args":{"kind":"open_app","value":"com.google.android.calendar"}}""",
                """{"thought":"feito","tool":"done","args":{"ok":true,"msg":"Alarm set."}}""",
            ),
            actionResult = ActionResult.Error("App não encontrado"),
        ).run(task)

        val state = controller.state.value
        assertTrue(state is AgentState.Failed && state.reason.contains("nenhuma das ações"))
    }

    @Test
    fun `kill switch durante o raciocinio cancela o loop`() = runTest {
        begin()
        val gate = CompletableDeferred<Unit>()
        val agent = orchestrator(
            screens = { screen("com.app", "Item") },
            decisions = listOf("""{"thought":"x","tool":"click","args":{"id":1}}"""),
            reasonerGate = gate,
        )
        val job = launch { agent.run(task) }
        controller.attach(job)
        runCurrent()
        assertTrue(controller.state.value is AgentState.Running)

        controller.stop()
        job.join()

        assertEquals(AgentState.Cancelled(task), controller.state.value)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `sem modelo instalado falha com mensagem para o usuario`() = runTest {
        begin()
        orchestrator(
            screens = { screen("launcher") },
            decisions = listOf("""{"tool":"back"}"""),
            preparer = ModelPreparer { throw AgentSetupException("Nenhum modelo instalado.") },
        ).run(task)

        assertEquals(AgentState.Failed(task, "Nenhum modelo instalado."), controller.state.value)
    }

    @Test
    fun `valor de intent invalido volta ao modelo como erro e ele corrige`() = runTest {
        begin()
        orchestrator(
            screens = { screen("launcher", "Relógio", hashSalt = it) },
            decisions = listOf(
                """{"thought":"alarme","tool":"intent","args":{"kind":"alarm","value":"25:00"}}""",
                """{"thought":"alarme","tool":"intent","args":{"kind":"alarm","value":"07:00"}}""",
                """{"thought":"feito","tool":"done","args":{"ok":true,"msg":"Pronto."}}""",
            ),
        ).run(task)

        assertEquals(AgentState.Finished(task, "Pronto."), controller.state.value)
        assertTrue(controller.steps.value.first().status is AgentStep.Status.Failed)
        assertEquals(1, executed.size)
    }
}
