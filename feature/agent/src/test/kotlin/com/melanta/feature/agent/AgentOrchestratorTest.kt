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
        onDecide: (PromptInput) -> Unit = {},
    ): AgentOrchestrator {
        var perceived = 0
        var decided = 0
        val perception = object : ScreenPerception {
            override suspend fun snapshot() = screens(perceived++)
            override fun recentNotifications() = emptyList<String>()
        }
        val reasoner = ActionReasoner { input: PromptInput ->
            onDecide(input)
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
    fun `intent terminal conclui sozinha, sem outra chamada ao modelo`() = runTest {
        begin()
        orchestrator(
            screens = { screen("launcher", "Relógio", hashSalt = it) },
            decisions = listOf(
                """{"thought":"alarme","tool":"intent","args":{"kind":"alarm","value":"07:00"}}""",
                """{"thought":"nunca chamado","tool":"back","args":{}}""",
            ),
        ).run(task)

        assertEquals(AgentState.Finished(task, "Alarme criado para 07:00."), controller.state.value)
        assertEquals(listOf<AgentAction>(AgentAction.LaunchIntent(IntentSpec.Alarm(7, 0, null))), executed)
        assertEquals(listOf("Criar alarme para 07:00"), controller.steps.value.map { it.action })
    }

    @Test
    fun `caminho com varios passos conclui com a mensagem do modelo`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.whatsapp", "Ana", "edit:Mensagem", hashSalt = it) },
            decisions = listOf(
                """{"thought":"abrir conversa","tool":"click","args":{"id":1}}""",
                """{"thought":"feito","tool":"done","args":{"ok":true,"msg":"Conversa aberta."}}""",
            ),
        ).run(task)

        assertEquals(AgentState.Finished(task, "Conversa aberta."), controller.state.value)
        assertEquals(listOf("Tocar em “Ana”", "Concluir"), controller.steps.value.map { it.action })
        assertTrue(controller.steps.value.all { it.status == AgentStep.Status.Done })
    }

    @Test
    fun `toque sem efeito aparece no historico enviado ao modelo`() = runTest {
        begin()
        val prompts = mutableListOf<PromptInput>()
        orchestrator(
            screens = { screen("com.app", "Botão") },
            decisions = listOf(
                """{"thought":"tocar","tool":"click","args":{"id":1}}""",
                """{"thought":"desistir","tool":"done","args":{"ok":false,"msg":"Nada acontece."}}""",
            ),
            onDecide = { prompts += it },
        ).run(task)

        assertEquals(listOf("click \"Botão\" -> ok, but the SCREEN did not change"), prompts[1].history)
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
    fun `janela protegida pausa para o usuario responder e depois continua`() = runTest {
        begin()
        val decided = mutableListOf<PromptInput>()
        val agent = orchestrator(
            screens = { if (it == 0) ScreenSnapshot.empty(Bounds(0, 0, 1080, 2400)).copy(protectedWindow = "Permitir notificações?") else screen("com.google.android.youtube", "Início") },
            decisions = listOf("""{"thought":"aberto","tool":"done","args":{"ok":true,"msg":"YouTube aberto."}}"""),
            onDecide = { decided += it },
        )
        val job = launch { agent.run(task) }
        runCurrent()
        val awaiting = controller.state.value as AgentState.AwaitingConfirmation
        assertTrue(awaiting.description, "Permitir notificações?" in awaiting.description)
        assertTrue(decided.isEmpty())

        controller.confirm(true)
        advanceUntilIdle()
        job.join()

        assertEquals(AgentState.Finished(task, "YouTube aberto."), controller.state.value)
        assertEquals(1, decided.size)
    }

    @Test
    fun `janela protegida que nao some encerra depois de pedir duas vezes`() = runTest {
        begin()
        val agent = orchestrator(
            screens = { ScreenSnapshot.empty(Bounds(0, 0, 1080, 2400)).copy(protectedWindow = "Permitir?") },
            decisions = listOf("""{"thought":"nunca","tool":"back","args":{}}"""),
        )
        val job = launch { agent.run(task) }
        repeat(2) {
            runCurrent()
            controller.confirm(true)
        }
        advanceUntilIdle()
        job.join()

        assertTrue(controller.state.value.toString(), controller.state.value is AgentState.Failed)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `done logo apos abrir formulario e rejeitado ate tocar em salvar`() = runTest {
        begin()
        val histories = mutableListOf<List<String>>()
        orchestrator(
            screens = { screen("com.google.android.contacts", "Salvar", "edit:Nome", hashSalt = it) },
            decisions = listOf(
                """{"thought":"contato","tool":"intent","args":{"kind":"add_contact","value":"Jose | +5524987778990"}}""",
                """{"thought":"pronto?","tool":"done","args":{"ok":true,"msg":"Contato criado."}}""",
                """{"thought":"salvar","tool":"click","args":{"id":1}}""",
                """{"thought":"pronto","tool":"done","args":{"ok":true,"msg":"Contato criado."}}""",
            ),
            onDecide = { histories += it.history.toList() },
        ).run(task)

        assertEquals(AgentState.Finished(task, "Contato “Jose” salvo."), controller.state.value)
        assertEquals("""intent add_contact Jose | +5524987778990 -> ok, next: tap "Salvar"""", histories[1].single())
        assertTrue(histories.toString(), histories[2].last().startsWith("done -> rejected"))
        assertEquals(2, executed.size)
    }

    @Test
    fun `done insistente sem enviar termina como falha`() = runTest {
        begin()
        orchestrator(
            screens = { screen("com.google.android.apps.messaging", "Enviar", hashSalt = it) },
            decisions = listOf(
                """{"thought":"sms","tool":"intent","args":{"kind":"sms","value":"11999998888|oi"}}""",
                """{"thought":"pronto","tool":"done","args":{"ok":true,"msg":"SMS enviado."}}""",
            ),
        ).run(task)

        val state = controller.state.value
        assertTrue(state.toString(), state is AgentState.Failed && "Enviar" in state.reason)
    }

    @Test
    fun `toque em interruptor informa o novo estado no historico`() = runTest {
        begin()
        fun wifi(on: Boolean) = ScreenSnapshot(
            packageName = "com.android.settings",
            title = null,
            elements = listOf(UiElement(1, "sw", "Wi-Fi", listOfNotNull("click", "checked".takeIf { on }, "unchecked".takeIf { !on }), Bounds(900, 300, 1040, 400), null, 4, null)),
            screenBounds = Bounds(0, 0, 1080, 2400),
        )
        val histories = mutableListOf<List<String>>()
        orchestrator(
            screens = { wifi(on = it == 0) },
            decisions = listOf(
                """{"thought":"desligar","tool":"click","args":{"id":1}}""",
                """{"thought":"pronto","tool":"done","args":{"ok":true,"msg":"Wi-Fi desligado."}}""",
            ),
            onDecide = { histories += it.history.toList() },
        ).run(task)

        assertEquals("""click "Wi-Fi" -> ok, switch is now OFF""", histories[1].single())
    }

    private fun wifiPanel(on: Boolean) = ScreenSnapshot(
        packageName = "com.android.settings",
        title = null,
        elements = listOf(UiElement(1, "sw", "Wi‑Fi", listOf("click", if (on) "checked" else "unchecked"), Bounds(900, 300, 1040, 400), null, 4, null)),
        screenBounds = Bounds(0, 0, 1080, 2400),
    )

    @Test
    fun `interruptor do pedido no estado pedido conclui sem novo toque`() = runTest {
        val wifiTask = "Desliga o wi-fi"
        assertTrue(controller.start(wifiTask))
        orchestrator(
            screens = { wifiPanel(on = it == 0) },
            decisions = listOf("""{"thought":"desligar","tool":"click","args":{"id":1}}"""),
        ).run(wifiTask)

        assertEquals(AgentState.Finished(wifiTask, "Wi‑Fi desligado."), controller.state.value)
        assertEquals(1, executed.size)
    }

    @Test
    fun `interruptor ja no estado pedido nao e tocado`() = runTest {
        val wifiTask = "Desliga o wi-fi"
        assertTrue(controller.start(wifiTask))
        orchestrator(
            screens = { wifiPanel(on = false) },
            decisions = listOf("""{"thought":"desligar","tool":"click","args":{"id":1}}"""),
        ).run(wifiTask)

        assertEquals(AgentState.Finished(wifiTask, "Wi‑Fi já estava desligado."), controller.state.value)
        assertTrue(executed.isEmpty())
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
                """{"thought":"abrir","tool":"intent","args":{"kind":"open_app","value":"Agenda"}}""",
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
            ),
        ).run(task)

        assertEquals(AgentState.Finished(task, "Alarme criado para 07:00."), controller.state.value)
        assertTrue(controller.steps.value.first().status is AgentStep.Status.Failed)
        assertEquals(1, executed.size)
    }
}
