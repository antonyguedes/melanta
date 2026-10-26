package com.melanta.tools.bench

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.melanta.core.ai.eval.EvalScenarios
import com.melanta.core.ai.prompt.AgentPrompt
import com.melanta.core.ai.prompt.ChatTemplates
import com.melanta.core.ai.prompt.ChatMessage
import com.melanta.core.ai.prompt.LlmPrompt
import com.melanta.core.ai.prompt.PromptBuilder
import com.melanta.core.ai.prompt.PromptInput
import com.melanta.core.ai.tool.ParseContext
import com.melanta.core.ai.tool.ParseResult
import com.melanta.core.ai.tool.ToolCallParser
import com.melanta.core.ai.tool.ToolCallSchema
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.measureTimeMillis

/** Mesmos parâmetros do app (GenParams): quase greedy, seed fixa, saída de até 128 tokens, sem thinking. */
@OptIn(ExperimentalApi::class)
fun main(args: Array<String>) {
    // --export-scenarios arquivo.json: grava os cenários com o mesmo texto que o modelo recebe,
    // para avaliar outros decisores (ex.: o classificador Laya) fora da JVM.
    args.indexOf("--export-scenarios").takeIf { it >= 0 }?.let { index ->
        exportScenarios(File(args[index + 1]))
        return
    }
    val model = File(args.firstOrNull() ?: error("uso: <modelo.litertlm> [--free] [--threads N] [--template chatml-parts|arquivo.jinja] [--render]"))
    val constrained = "--free" !in args
    val threads = args.indexOf("--threads").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 8
    // Template de chat alternativo (`chatml-parts` ou um arquivo .jinja), para modelos cujo template
    // embutido não aceita o formato de conteúdo do LiteRT-LM 0.18.
    val chatTemplate = args.indexOf("--template").takeIf { it >= 0 }?.let { args[it + 1] }?.let {
        if (it == "chatml-parts") ChatTemplates.CHATML_CONTENT_PARTS else File(it).readText()
    }
    val renderOnly = "--render" in args
    val jsonOut = args.indexOf("--json").takeIf { it >= 0 }?.let { File(args[it + 1]) }
    val results = mutableListOf<JsonObject>()
    // --prompt v1: prompt original do plano; --system arquivo: variação para teste, sem recompilar.
    val system = args.indexOf("--system").takeIf { it >= 0 }?.let { File(args[it + 1]).readText().trimEnd() }
        ?: if (args.indexOf("--prompt").takeIf { it >= 0 }?.let { args[it + 1] } == "v1") PlanPromptV1.SYSTEM else AgentPrompt.SYSTEM
    val cacheDir = File(System.getProperty("user.home"), ".cache/melanta-bench/cache").apply { mkdirs() }

    Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
    ExperimentalFlags.enableBenchmark = true
    val builder = PromptBuilder()
    val parser = ToolCallParser()

    println("modelo: ${model.name} · JSON restrito: $constrained · CPU $threads threads")
    Engine(EngineConfig(model.absolutePath, Backend.CPU(threads), maxNumTokens = 4096, cacheDir = cacheDir.absolutePath)).use { engine ->
        val loadMs = measureTimeMillis { engine.initialize() }
        println("carga: $loadMs ms")

        var correct = 0
        var holdoutCorrect = 0
        var valid = 0
        var firstTry = 0
        val prefill = mutableListOf<Double>()
        val decode = mutableListOf<Double>()
        for (scenario in EvalScenarios.all) {
            val built = builder.build(PromptInput(scenario.task, scenario.history, scenario.screenContext(), now = EvalScenarios.NOW), system = system)
            val schema = if (constrained) ToolCallSchema.build(built.targets) else null
            var prompt: LlmPrompt = built.prompt
            var result: ParseResult? = null
            var attempts = 0
            var raw = ""
            val elapsed = measureTimeMillis {
                while (attempts < 3) {
                    attempts++
                    val conversation = engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(prompt.system),
                            initialMessages = prompt.messages.dropLast(1).map { it.toLiteRt() },
                            samplerConfig = SamplerConfig(1, 1.0, 0.1, 42),
                            automaticToolCalling = false,
                            maxOutputToken = 128,
                            thinkingConfig = ThinkingConfig(enableThinking = false),
                            enableResponseFormat = constrained,
                            chatTemplate = chatTemplate,
                        ),
                    )
                    if (renderOnly) {
                        // Mostra exatamente o texto que o modelo recebe, depois do template de chat.
                        println(conversation.renderPrefaceIntoString())
                        println(conversation.renderMessageIntoString(prompt.messages.last().toLiteRt()))
                        return
                    }
                    conversation.use {
                        raw = it.sendMessage(prompt.messages.last().toLiteRt(), responseFormat = schema?.let(ResponseFormat::json)).toString()
                        runCatching { it.getBenchmarkInfo() }.getOrNull()?.let { b ->
                            prefill += b.lastPrefillTokensPerSecond
                            decode += b.lastDecodeTokensPerSecond
                        }
                    }
                    val parsed = parser.parse(raw, ParseContext(built.visibleIds, built.targets))
                    result = parsed
                    if (parsed is ParseResult.Success) break
                    prompt = builder.correction(prompt, raw, (parsed as ParseResult.Failure).detail)
                }
            }
            val call = (result as? ParseResult.Success)?.call
            val ok = call != null && scenario.judge(call)
            if (call != null) valid++
            if (call != null && attempts == 1) firstTry++
            if (ok) correct++
            if (ok && scenario.holdout) holdoutCorrect++
            results += buildJsonObject {
                put("name", scenario.name)
                put("holdout", scenario.holdout)
                put("tool", call?.name)
                put("args", call?.args ?: JsonObject(emptyMap()))
                put("correct", ok)
                put("millis", elapsed)
            }
            val got = call?.let { "${it.name} ${it.args}" } ?: "inválido: ${raw.take(80).replace('\n', ' ')}"
            println("${if (ok) "✓" else "✗"} ${if (scenario.holdout) "[controle] " else ""}${scenario.name.padEnd(22)} ${elapsed.toString().padStart(6)} ms · $attempts tent. · $got")
        }
        val n = EvalScenarios.all.size
        val holdouts = EvalScenarios.all.count { it.holdout }
        val prompt = when (system) {
            AgentPrompt.SYSTEM -> "atual"
            PlanPromptV1.SYSTEM -> "v1"
            else -> "arquivo"
        }
        jsonOut?.writeText(JsonArray(results).toString())
        println("RESUMO ${model.name} (prompt $prompt): corretos $correct/$n · controle $holdoutCorrect/$holdouts · " +
            "válidos $valid/$n · 1ª tentativa $firstTry/$n · " +
            "prefill %.0f tok/s · decode %.1f tok/s".format(prefill.average(), decode.average()))
    }
}

private fun ChatMessage.toLiteRt(): Message = when (role) {
    ChatMessage.Role.User -> Message.user(text)
    ChatMessage.Role.Model -> Message.model(text)
}

private fun exportScenarios(out: File) {
    val builder = PromptBuilder()
    val scenarios = EvalScenarios.all.map { scenario ->
        val context = scenario.screenContext()
        val built = builder.build(PromptInput(scenario.task, scenario.history, context, now = EvalScenarios.NOW))
        buildJsonObject {
            put("name", scenario.name)
            put("holdout", scenario.holdout)
            put("task", scenario.task)
            put("expected_tool", scenario.expectedTool)
            // Mesmo texto de usuário que o modelo recebe (TASK, HISTORY, SCREEN).
            put("user", built.prompt.messages.last().text)
            put("elements", JsonArray(context.lines.map { line ->
                buildJsonObject {
                    put("id", line.id)
                    put("line", line.text)
                    put("actions", JsonArray(line.actions.map { JsonPrimitive(it.name) }))
                }
            }))
        }
    }
    out.writeText(JsonArray(scenarios).toString())
    println("${scenarios.size} cenários exportados para $out")
}
