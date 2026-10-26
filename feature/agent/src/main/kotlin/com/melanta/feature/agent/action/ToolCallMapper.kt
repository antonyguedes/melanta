package com.melanta.feature.agent.action

import com.melanta.core.ai.tool.ToolCall
import com.melanta.core.ai.tool.Tools
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Direction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.NavKey
import com.melanta.core.automation.action.Target
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
class ToolCallMapper @Inject constructor() {

    fun map(call: ToolCall): MapResult {
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
            Tools.INTENT -> intent(args.string("kind").orEmpty(), args.string("value").orEmpty().trim())
            Tools.BACK -> MapResult.Success(AgentAction.Navigate(NavKey.Back))
            Tools.DONE -> MapResult.Success(AgentAction.Done(args.bool("ok") ?: true, args.string("msg").orEmpty()))
            else -> MapResult.Failure("Unknown tool ${call.name}.")
        }
    }

    private fun intent(kind: String, value: String): MapResult {
        if (value.isEmpty()) return MapResult.Failure("intent needs a value.")
        val spec = when (kind) {
            "open_app" -> IntentSpec.OpenApp(value)
            "alarm" -> parseAlarm(value) ?: return MapResult.Failure("alarm value must be \"HH:MM label\".")
            "timer" -> parseDuration(value)?.let { IntentSpec.Timer(it) }
                ?: return MapResult.Failure("timer value must be seconds between 1 and 86400.")
            "url" -> parseUrl(value) ?: return MapResult.Failure("url must be an https link.")
            "dial" -> parsePhone(value) ?: return MapResult.Failure("dial value must be a phone number.")
            "web_search" -> IntentSpec.WebSearch(value)
            "add_contact" -> parseContact(value) ?: return MapResult.Failure("add_contact value must be \"Name | phone\".")
            else -> return MapResult.Failure("Unknown intent kind $kind.")
        }
        return MapResult.Success(AgentAction.LaunchIntent(spec))
    }

    companion object {
        private val ALARM = Regex("""^(\d{1,2})(?:\s*[:h.]\s*(\d{2})?)?\s*(am|pm)?\s*(.*)$""", RegexOption.IGNORE_CASE)
        private const val UNITS =
            "h|hr|hrs|hora|horas|hour|hours|m|min|mins|minuto|minutos|minute|minutes|s|seg|sec|secs|segundo|segundos|second|seconds"
        private val DURATION_PART = Regex("""(\d+)\s*($UNITS)?""")
        private val DURATION_FULL = Regex("""^(?:\d+\s*(?:$UNITS)?\s*)+$""")
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
