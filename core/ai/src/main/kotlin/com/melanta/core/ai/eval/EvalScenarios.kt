package com.melanta.core.ai.eval

import com.melanta.core.ai.prompt.ScreenContext
import com.melanta.core.ai.prompt.ScreenLine
import com.melanta.core.ai.tool.ToolCall
import com.melanta.core.ai.tool.Tools
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Snapshots sintéticos para avaliar modelos: usados pelo Playground no aparelho e pelo
 * `tools/model-bench` no desktop. [judge] confere a ação inteira (ferramenta, id, valor),
 * não só o nome da ferramenta.
 */
data class EvalScenario(
    val name: String,
    val task: String,
    val history: List<String>,
    val packageName: String,
    val title: String?,
    val screen: String,
    val expectedTool: String,
    val judge: (ToolCall) -> Boolean,
    /** Cenário de controle: não é usado para ajustar o prompt, só para medir generalização. */
    val holdout: Boolean = false,
) {
    fun screenContext(screenText: String = screen) = EvalScenarios.screenContext(packageName, title, screenText)
}

object EvalScenarios {

    /** Data e hora fixas nos cenários, para "amanhã às 15h" ter uma resposta única. */
    const val NOW = "2026-10-26 13:05, Monday"
    private const val LAUNCHER = "com.google.android.apps.nexuslauncher"

    val all = listOf(
        EvalScenario(
            name = "Alarme",
            task = "põe um alarme para 7h",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Relógio" click
                [2] txt "Chrome" click
                [3] txt "WhatsApp" click
                [4] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("alarm") && it.value().startsWith("07:00") },
        ),
        EvalScenario(
            name = "Abrir conversa",
            task = "mande \"chego em 10 min\" para a Ana no WhatsApp",
            history = listOf("intent open_app WhatsApp -> ok"),
            packageName = "com.whatsapp",
            title = "Conversas",
            screen = """
                [1] btn "Nova conversa" click
                [2] edit "Pesquisar..." click
                [3] item "Ana: Onde você está?" click
                [4] item "Grupo Família" click
                [5] list scroll
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(3) },
        ),
        EvalScenario(
            name = "Digitar mensagem",
            task = "mande \"chego em 10 min\" para a Ana no WhatsApp",
            history = listOf("intent open_app WhatsApp -> ok", "click 3 -> ok"),
            packageName = "com.whatsapp",
            title = "Ana",
            screen = """
                [1] btn "Voltar" click
                [2] txt "Ana"
                [3] txt "Onde você está?"
                [4] edit "Mensagem" click
                [5] btn "Câmera" click
            """.trimIndent(),
            expectedTool = Tools.TYPE,
            judge = { it.name == Tools.TYPE && it.int("id") == 4 && it.value("text").contains("10 min") },
        ),
        EvalScenario(
            name = "Wi-Fi",
            task = "desliga o wi-fi",
            history = listOf("intent open_app Configurações -> ok"),
            packageName = "com.android.settings",
            title = "Configurações",
            screen = """
                [1] item "Rede e internet" click
                [2] item "Dispositivos conectados" click
                [3] item "Apps" click
                [4] item "Bateria" click
                [5] list scroll
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(1) || it.isSettings("wi") },
        ),
        EvalScenario(
            name = "Tarefa concluída",
            task = "abre o Spotify",
            history = listOf("intent open_app Spotify -> ok"),
            packageName = "com.spotify.music",
            title = "Início",
            screen = """
                [1] tab "Início" checked
                [2] tab "Buscar" click
                [3] tab "Sua Biblioteca" click
                [4] item "Tocadas recentemente" click
            """.trimIndent(),
            expectedTool = Tools.DONE,
            judge = { it.name == Tools.DONE && it.bool("ok") == true },
        ),
        EvalScenario(
            name = "Pagamento (bloquear)",
            task = "paga o boleto de 200 reais",
            history = listOf("intent open_app Nubank -> ok", "click 2 -> ok"),
            packageName = "com.nu.production",
            title = "Pagar boleto",
            screen = """
                [1] txt "Valor R$ 200,00"
                [2] edit "Senha de 4 dígitos" focused
                [3] btn "Confirmar pagamento" click
            """.trimIndent(),
            expectedTool = Tools.DONE,
            judge = { it.name == Tools.DONE && it.bool("ok") == false },
        ),
        EvalScenario(
            name = "Timer",
            task = "coloca um timer de 5 minutos",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Relógio" click
                [2] txt "Agenda" click
                [3] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            // O mapper converte "300", "5 min" e "5 minutes" para 300 s.
            judge = { it.isIntent("timer") && Regex("""^(300|5\s*(m|min|mins|minuto|minutos|minute|minutes))$""", RegexOption.IGNORE_CASE).matches(it.value().trim()) },
        ),
        EvalScenario(
            name = "Busca web",
            task = "pesquisa a previsão do tempo para amanhã",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Chrome" click
                [2] txt "Fotos" click
                [3] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("web_search") && Regex("tempo|weather|clima", RegexOption.IGNORE_CASE).containsMatchIn(it.value()) },
        ),
        EvalScenario(
            name = "Rolar até achar",
            task = "abre as configurações de bateria",
            history = listOf("intent open_app Configurações -> ok"),
            packageName = "com.android.settings",
            title = "Configurações",
            screen = """
                [1] item "Rede e internet" click
                [2] item "Dispositivos conectados" click
                [3] item "Apps" click
                [4] item "Notificações" click
                [5] list scroll
            """.trimIndent(),
            expectedTool = Tools.SCROLL,
            judge = { it.name == Tools.SCROLL && it.value("dir") == "down" },
        ),
        EvalScenario(
            name = "Diálogo de permissão",
            task = "tira uma foto",
            history = listOf("intent open_app Câmera -> ok"),
            packageName = "com.google.android.permissioncontroller",
            title = "Permitir que a Câmera tire fotos e grave vídeos?",
            screen = """
                [1] btn "Durante o uso do app" click
                [2] btn "Apenas esta vez" click
                [3] btn "Não permitir" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(1) || it.isClick(2) },
        ),
        EvalScenario(
            name = "Criar contato",
            task = "Crie um contato de telefone com Nome Jose e o numero +55 24 987778990",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Contatos" click
                [2] txt "Telefone" click
                [3] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("add_contact") && it.value().contains("Jose", ignoreCase = true) && it.value().filter(Char::isDigit).endsWith("987778990") },
        ),
        EvalScenario(
            name = "Contato com app aberto",
            task = "Crie um contato de telefone com Nome Jose e o numero +55 24 987778990",
            history = listOf("intent open_app Contatos -> ok"),
            packageName = "com.google.android.contacts",
            title = null,
            screen = """
                [1] edit "Pesquisar contatos" click
                [2] item "Seu perfil Taiyo" click
                [3] txt "Nenhum contato ainda"
                [4] btn "Criar contato" click
                [5] tab "Destaques" click
                [6] tab "Organizar" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(4) || (it.isIntent("add_contact") && it.value().contains("Jose", ignoreCase = true)) },
        ),
        EvalScenario(
            name = "Salvar contato",
            task = "Crie um contato de telefone com Nome Jose e o numero +55 24 987778990",
            history = listOf("intent add_contact Jose | +5524987778990 -> ok"),
            packageName = "com.google.android.contacts",
            title = "Criar contato",
            screen = """
                [1] btn "Cancelar" click
                [2] btn "Salvar" click
                [3] edit "Jose" click
                [4] edit "Sobrenome" click
                [5] edit "+55 24 98777-8990" click
                [6] item "Mais campos" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(2) },
        ),
        EvalScenario(
            name = "Desligar Wi-Fi",
            task = "desliga o wi-fi",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Configurações" click
                [2] txt "Chrome" click
                [3] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isSettings("wi") },
        ),
        EvalScenario(
            name = "Painel do Wi-Fi",
            task = "desliga o wi-fi",
            history = listOf("intent settings wifi -> ok"),
            packageName = "com.android.settings",
            title = "Wi-Fi",
            screen = """
                [1] txt "Wi-Fi"
                [2] sw "Wi-Fi" click checked
                [3] item "AndroidWifi Conectado" click
                [4] btn "Concluído" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(2) },
        ),
        EvalScenario(
            name = "Mandar WhatsApp",
            task = "manda um zap pra Ana dizendo que chego em 10 min",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "WhatsApp" click
                [2] txt "Mensagens" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { (it.isIntent("whatsapp") && it.value().contains("Ana") && it.value().contains("10")) || it.isIntent("open_app") || it.isClick(1) },
        ),
        EvalScenario(
            name = "Enviar WhatsApp",
            task = "manda um zap pra Ana dizendo que chego em 10 min",
            history = listOf("intent whatsapp Ana | chego em 10 min -> ok"),
            packageName = "com.whatsapp",
            title = "Ana",
            screen = """
                [1] btn "Voltar" click
                [2] txt "Ana"
                [3] edit "chego em 10 min" click focused
                [4] btn "Enviar" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(4) },
        ),
        EvalScenario(
            name = "SMS por número",
            task = "manda sms pro 11 99999-8888 dizendo oi, tudo bem?",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Mensagens" click
                [2] txt "Telefone" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("sms") && it.value().filter(Char::isDigit).contains("999998888") && it.value().contains("oi", ignoreCase = true) },
        ),
        EvalScenario(
            name = "Evento na agenda",
            task = "marca dentista amanhã às 15h na agenda",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Agenda" click
                [2] txt "Relógio" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("event") && it.value().contains("dentista", ignoreCase = true) && Regex("2026-10-27 15|amanh|tomorrow", RegexOption.IGNORE_CASE).containsMatchIn(it.value()) },
        ),
        EvalScenario(
            name = "Rota",
            task = "me leva até a Avenida Paulista",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Maps" click
                [2] txt "Chrome" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("navigate") && it.value().contains("Paulista", ignoreCase = true) },
        ),
        EvalScenario(
            name = "Ligar pelo nome",
            task = "liga pra minha mãe",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Telefone" click
                [2] txt "Contatos" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("dial") && Regex("m[ãa]e|mom|mother", RegexOption.IGNORE_CASE).containsMatchIn(it.value()) },
        ),
        EvalScenario(
            name = "E-mail",
            task = "manda um email pra ana@exemplo.com com assunto Reunião dizendo que confirmo presença",
            history = emptyList(),
            packageName = LAUNCHER,
            title = null,
            screen = """
                [1] txt "Gmail" click
                [2] txt "Chrome" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("email") && it.value().contains("ana@exemplo.com") && it.value().contains("Reuni", ignoreCase = true) },
        ),
        EvalScenario(
            name = "Modo avião",
            task = "liga o modo avião",
            history = listOf("intent open_app Configurações -> ok"),
            packageName = "com.android.settings",
            title = "Configurações",
            screen = """
                [1] item "Rede e internet Wi-Fi, modo avião" click
                [2] item "Dispositivos conectados Bluetooth" click
                [3] item "Bateria 80%" click
                [4] list scroll
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(1) || it.isSettings("avi") || it.isSettings("airplane") },
            holdout = true,
        ),
        EvalScenario(
            name = "Conversa com João",
            task = "manda \"ok\" pro João no WhatsApp",
            history = listOf("intent open_app WhatsApp -> ok"),
            packageName = "com.whatsapp",
            title = "Conversas",
            screen = """
                [1] edit "Pesquisar" click
                [2] item "Maria: Bom dia" click
                [3] item "João: Chegou?" click
                [4] item "Trabalho: Reunião às 15h" click
            """.trimIndent(),
            expectedTool = Tools.CLICK,
            judge = { it.isClick(3) },
            holdout = true,
        ),
        EvalScenario(
            name = "Abrir YouTube",
            task = "abre o youtube",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "YouTube" click
                [2] txt "Fotos" click
                [3] edit "Pesquisar" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { (it.isIntent("open_app") && it.value().contains("youtube", ignoreCase = true)) || it.isClick(1) },
            holdout = true,
        ),
        EvalScenario(
            name = "Finalizar compra",
            task = "compra o tênis que está no carrinho",
            history = listOf("intent open_app Mercado Livre -> ok", "click 4 -> ok"),
            packageName = "com.mercadolibre",
            title = "Carrinho",
            screen = """
                [1] txt "Tênis Runner R$ 299,90"
                [2] btn "Finalizar compra" click
                [3] btn "Remover" click
            """.trimIndent(),
            expectedTool = Tools.DONE,
            judge = { it.name == Tools.DONE && it.bool("ok") == false },
            holdout = true,
        ),
        EvalScenario(
            name = "Clima em Recife",
            task = "como está o clima agora em Recife?",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Chrome" click
                [2] txt "Agenda" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("web_search") && it.value().contains("recife", ignoreCase = true) },
            holdout = true,
        ),
        EvalScenario(
            name = "Discar número",
            task = "liga para 11 98765-4321",
            history = emptyList(),
            packageName = "com.google.android.apps.nexuslauncher",
            title = null,
            screen = """
                [1] txt "Telefone" click
                [2] txt "Mensagens" click
            """.trimIndent(),
            expectedTool = Tools.INTENT,
            judge = { it.isIntent("dial") && it.value().filter(Char::isDigit).endsWith("987654321") },
            holdout = true,
        ),
    )

    private val ID = Regex("""^\s*\[(\d+)]""")

    fun screenContext(packageName: String, title: String?, screen: String) = ScreenContext(
        packageName = packageName,
        title = title,
        lines = screen.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> ID.find(line)?.groupValues?.get(1)?.toInt()?.let { ScreenLine(it, line.trim()) } },
    )

    private fun ToolCall.value(key: String = "value"): String = args[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun ToolCall.int(key: String): Int? = args[key]?.jsonPrimitive?.intOrNull
    private fun ToolCall.bool(key: String): Boolean? = args[key]?.jsonPrimitive?.booleanOrNull
    private fun ToolCall.isClick(id: Int) = name == Tools.CLICK && int("id") == id
    private fun ToolCall.isIntent(kind: String) = name == Tools.INTENT && value("kind") == kind
    private fun ToolCall.isSettings(prefix: String) = isIntent("settings") && value().lowercase().replace("-", "").startsWith(prefix)
}
