package com.melanta.feature.agent.action

import com.melanta.core.ai.tool.ToolCall
import com.melanta.core.ai.tool.Tools
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Direction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.NavKey
import com.melanta.core.automation.action.SettingsPage
import com.melanta.core.automation.action.Target
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface MapResult {
    data class Success(val action: AgentAction) : MapResult

    /** [detail] em inglês, curto, para o re-prompt corretivo. */
    data class Failure(val detail: String) : MapResult
}

/**
 * Ponte entre o `:core:ai` (ToolCall genérico) e o `:core:automation` (AgentAction tipado).
 * O [com.melanta.core.ai.tool.ToolCallParser] já normalizou nomes e tipos; aqui entra a semântica
 * dos valores (horário do alarme, duração do timer, link, telefone).
 */
class ToolCallMapper internal constructor(private val now: () -> LocalDateTime) {

    @Inject
    constructor() : this(LocalDateTime::now)

    /** [task] serve só para completar valores ambíguos (ex.: timer "5" quando o pedido diz "5 minutos"). */
    fun map(call: ToolCall, task: String = ""): MapResult {
        val args = call.args
        return when (call.name) {
            Tools.CLICK -> args.int("id")?.let { MapResult.Success(AgentAction.Click(Target.ById(it))) }
                ?: MapResult.Failure("click needs id.")
            Tools.TYPE -> {
                val text = args.string("text") ?: return MapResult.Failure("type needs text.")
                MapResult.Success(AgentAction.Type(args.int("id")?.let(Target::ById), text, args.bool("enter") ?: false))
            }
            Tools.SCROLL -> {
                val direction = when (args.string("dir")) {
                    "up" -> Direction.Up
                    "left" -> Direction.Left
                    "right" -> Direction.Right
                    else -> Direction.Down
                }
                MapResult.Success(AgentAction.Scroll(direction, args.int("id")?.let(Target::ById)))
            }
            Tools.INTENT -> intent(args.string("kind").orEmpty(), args.string("value").orEmpty().trim(), task)
            Tools.BACK -> MapResult.Success(AgentAction.Navigate(NavKey.Back))
            Tools.DONE -> MapResult.Success(AgentAction.Done(args.bool("ok") ?: true, args.string("msg").orEmpty()))
            else -> MapResult.Failure("Unknown tool ${call.name}.")
        }
    }

    private fun intent(kind: String, value: String, task: String): MapResult {
        if (value.isEmpty()) return MapResult.Failure("intent needs a value.")
        val spec = when (kind) {
            "open_app" -> IntentSpec.OpenApp(value)
            "alarm" -> parseAlarm(value) ?: return MapResult.Failure("alarm value must be \"HH:MM label\".")
            "timer" -> (durationFromTask(value, task) ?: parseDuration(value))?.let { IntentSpec.Timer(it) }
                ?: return MapResult.Failure("timer value must be seconds between 1 and 86400.")
            "url" -> parseUrl(value) ?: return MapResult.Failure("url must be an https link.")
            // Número ou nome do contato: o executor busca o telefone na agenda.
            "dial" -> parsePhone(value) ?: IntentSpec.Dial(value)
            "web_search" -> IntentSpec.WebSearch(value)
            "add_contact" -> parseContact(value) ?: return MapResult.Failure("add_contact value must be \"Name | phone\".")
            "settings" -> parseSettings(value)?.let(IntentSpec::Settings)
                ?: return MapResult.Failure("settings value must be one of: ${SETTINGS_NAMES.joinToString("|")}.")
            "sms", "whatsapp" -> {
                val (recipient, text) = parts(value, 2).takeIf { it[0].isNotBlank() && it[1].isNotBlank() }
                    ?: return MapResult.Failure("$kind value must be \"contact or phone | text\".")
                if (kind == "sms") IntentSpec.Sms(recipient, text) else IntentSpec.WhatsApp(recipient, text)
            }
            "email" -> {
                val (to, subject, body) = parts(value, 3)
                if (!to.contains('@')) return MapResult.Failure("email value must be \"address | subject | body\".")
                IntentSpec.Email(to, subject, body)
            }
            "event" -> parseEvent(value, now()) ?: return MapResult.Failure("event value must be \"Title | YYYY-MM-DD HH:MM\".")
            "navigate" -> IntentSpec.Navigate(value)
            else -> return MapResult.Failure("Unknown intent kind $kind.")
        }
        return MapResult.Success(AgentAction.LaunchIntent(spec))
    }

    companion object {
        /** Sinônimos (PT e EN) de cada página de Configurações. */
        private val SETTINGS_SYNONYMS: Map<SettingsPage, List<String>> = mapOf(
            SettingsPage.Wifi to listOf("wifi", "wi fi", "wireless", "rede wifi"),
            SettingsPage.Internet to listOf("internet", "rede", "network", "dados moveis", "mobile data"),
            SettingsPage.Bluetooth to listOf("bluetooth"),
            SettingsPage.Display to listOf("display", "tela", "brilho", "brightness", "tema escuro", "dark mode", "dark theme"),
            SettingsPage.Sound to listOf("sound", "som", "volume", "toque", "ringtone", "vibracao", "vibration"),
            SettingsPage.Battery to listOf("battery", "bateria", "economia de energia", "battery saver"),
            SettingsPage.Location to listOf("location", "localizacao", "gps"),
            SettingsPage.Apps to listOf("apps", "aplicativos", "applications"),
            SettingsPage.Notifications to listOf("notifications", "notificacoes"),
            SettingsPage.Storage to listOf("storage", "armazenamento"),
            SettingsPage.Airplane to listOf("airplane", "modo aviao", "aviao", "airplane mode", "flight mode"),
            SettingsPage.DateTime to listOf("date", "time", "data", "hora", "data e hora", "date and time"),
            SettingsPage.Language to listOf("language", "idioma", "lingua"),
        )
        private val SETTINGS_NAMES = SETTINGS_SYNONYMS.values.map { it.first() }

        internal fun parseSettings(value: String): SettingsPage? {
            val v = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            SETTINGS_SYNONYMS.entries.firstOrNull { (_, names) -> v in names }?.let { return it.key }
            return SETTINGS_SYNONYMS.entries.firstOrNull { (_, names) -> names.any { name -> name in v } }?.key
        }

        /** "a | b | c" em exatamente [n] partes; a última recebe o resto (o texto pode conter "|"). */
        private fun parts(value: String, n: Int): List<String> {
            val split = value.split('|', limit = n).map { it.trim() }
            return split + List(n - split.size) { "" }
        }

        private val EVENT_DATE_TIME = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "dd/MM/yyyy HH:mm", "yyyy-MM-dd HH'h'mm")
            .map { DateTimeFormatter.ofPattern(it) }
        private val INLINE_WHEN = Regex(
            """\s((?:hoje|today|amanha|amanhã|tomorrow)\s+(?:(?:as|às|at)\s+)?\d{1,2}(?:\s*[:h.]\s*\d{0,2})?(?:\s*(?:am|pm))?""" +
                """|\d{4}-\d{2}-\d{2}[ T]\d{1,2}:\d{2}|\d{2}/\d{2}/\d{4}\s+\d{1,2}:\d{2})\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val EVENT_VERB = Regex(
            """^(?:marca|marcar|marque|agenda|agendar|agende|cria|criar|crie|adiciona|adicionar)\s+(?:(?:um|uma)\s+)?(?:evento\s+)?(?:(?:de|do|da)\s+)?""",
            RegexOption.IGNORE_CASE,
        )
        private val RELATIVE_DAY = Regex("""^(hoje|today|amanha|amanhã|tomorrow)\s+(?:as\s+|às\s+|at\s+)?(.+)$""", RegexOption.IGNORE_CASE)

        /**
         * "Reunião | 2026-10-27 15:00", "Dentista | amanhã 15:00" ou "Café | 09:30" (hoje).
         * Sem o "|", a data no fim do texto separa o título ("Marca dentista amanhã às 15h").
         */
        internal fun parseEvent(value: String, now: LocalDateTime): IntentSpec.Event? {
            var (title, whenText) = parts(value, 2)
            if (whenText.isBlank()) {
                val m = INLINE_WHEN.find(title) ?: return null
                whenText = m.groupValues[1]
                title = title.substring(0, m.range.first).replace(EVENT_VERB, "").trim()
                    .replaceFirstChar { it.uppercase() }
            }
            if (title.isBlank() || whenText.isBlank()) return null
            EVENT_DATE_TIME.firstNotNullOfOrNull { format ->
                runCatching { LocalDateTime.parse(whenText, format) }.getOrNull()
            }?.let { return IntentSpec.Event(title, it) }
            val relative = RELATIVE_DAY.find(whenText)
            val dayOffset = when (relative?.groupValues?.get(1)?.lowercase()) {
                null, "hoje", "today" -> 0L
                else -> 1L
            }
            val time = parseAlarm(relative?.groupValues?.get(2) ?: whenText) ?: return null
            if (time.label != null) return null
            return IntentSpec.Event(title, now.toLocalDate().plusDays(dayOffset).atTime(time.hour, time.minute))
        }
        private val ALARM = Regex("""^(\d{1,2})(?:\s*[:h.]\s*(\d{2})?)?\s*(am|pm)?\s*(.*)$""", RegexOption.IGNORE_CASE)
        private const val UNITS =
            "h|hr|hrs|hora|horas|hour|hours|m|min|mins|minuto|minutos|minute|minutes|s|seg|sec|secs|segundo|segundos|second|seconds"
        private val DURATION_PART = Regex("""(\d+)\s*($UNITS)?""")
        private val DURATION_FULL = Regex("""^(?:\d+\s*(?:$UNITS)?\s*)+$""")
        private val TASK_DURATION = Regex("""\d+\s*(?:$UNITS)\b(?:\s*(?:e|and)?\s*\d+\s*(?:$UNITS)\b)*""")
        private val CLOCK_DURATION = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")

        /** "07:30 Academia", "7h", "7:00 pm", "19h30 Remédio". */
        internal fun parseAlarm(value: String): IntentSpec.Alarm? {
            val m = ALARM.find(value.trim()) ?: return null
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
            when (m.groupValues[3].lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            val label = m.groupValues[4].trim().trim('"', '\'').takeIf { it.isNotEmpty() }
            return IntentSpec.Alarm(hour, minute, label)
        }

        /** "300", "5 min", "1h30m", "90s", "01:30" (mm:ss), "1:00:00". */
        internal fun parseDuration(value: String): Int? {
            val v = value.trim().lowercase().replace(Regex("""\s+(e|and)\s+"""), " ")
            CLOCK_DURATION.find(v)?.let { m ->
                val (a, b, c) = m.destructured
                val seconds = if (c.isEmpty()) a.toInt() * 60 + b.toInt() else a.toInt() * 3600 + b.toInt() * 60 + c.toInt()
                return seconds.takeIf { it in 1..MAX_TIMER_SECONDS }
            }
            v.toIntOrNull()?.let { return it.takeIf { s -> s in 1..MAX_TIMER_SECONDS } }
            if (!DURATION_FULL.matches(v)) return null
            val parts = DURATION_PART.findAll(v).toList()
            val total = parts.sumOf { part ->
                val n = part.groupValues[1].toInt()
                when (part.groupValues[2].firstOrNull()) {
                    'h' -> n * 3600
                    'm' -> n * 60
                    's' -> n
                    else -> n
                }
            }
            return total.takeIf { it in 1..MAX_TIMER_SECONDS }
        }

        /**
         * O modelo às vezes manda só o número ("5") de um pedido como "timer de 5 minutos".
         * Se o número aparece no pedido com unidade, vale a duração escrita no pedido.
         */
        internal fun durationFromTask(value: String, task: String): Int? {
            val n = value.trim().toIntOrNull() ?: return null
            return TASK_DURATION.findAll(task.lowercase())
                .filter { m -> DURATION_PART.findAll(m.value).any { it.groupValues[1].toInt() == n } }
                .firstNotNullOfOrNull { parseDuration(it.value) }
        }

        internal fun parseUrl(value: String): IntentSpec.Url? {
            val url = when {
                value.startsWith("https://") -> value
                value.startsWith("http://") -> return null
                value.contains("://") -> return null
                value.contains('.') && !value.contains(' ') -> "https://$value"
                else -> return null
            }
            return IntentSpec.Url(url)
        }

        private val PHONE_IN_TEXT = Regex("""\+?\d[\d\s().-]{5,}\d""")

        /** "Jose | +55 24 98777-8990", "Jose, +55 24 987778990", "Jose +5524987778990" ou só "Jose". */
        internal fun parseContact(value: String): IntentSpec.AddContact? {
            val phoneMatch = PHONE_IN_TEXT.findAll(value).lastOrNull()
            val phone = phoneMatch?.value?.let { raw -> raw.filter { it.isDigit() || it == '+' } }
                ?.takeIf { digits -> digits.count { it.isDigit() } in 3..15 }
            val name = (if (phone != null) value.removeRange(phoneMatch.range) else value)
                .trim().trim('|', ',', ';', ':', '-', ' ').trim()
            if (name.isEmpty()) return null
            return IntentSpec.AddContact(name, phone)
        }

        internal fun parsePhone(value: String): IntentSpec.Dial? {
            val digits = value.filter { it.isDigit() || it == '+' }
            return if (digits.count { it.isDigit() } in 3..15) IntentSpec.Dial(digits) else null
        }

        private const val MAX_TIMER_SECONDS = 24 * 3600
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
