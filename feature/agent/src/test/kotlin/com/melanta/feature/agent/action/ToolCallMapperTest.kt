package com.melanta.feature.agent.action

import com.melanta.core.ai.tool.ParseResult
import com.melanta.core.ai.tool.ToolCallParser
import com.melanta.core.automation.action.AgentAction
import com.melanta.core.automation.action.Direction
import com.melanta.core.automation.action.IntentSpec
import com.melanta.core.automation.action.NavKey
import com.melanta.core.automation.action.Target
import com.melanta.feature.agent.action.ToolCallMapper.Companion.parseAlarm
import com.melanta.feature.agent.action.ToolCallMapper.Companion.parseDuration
import com.melanta.feature.agent.action.ToolCallMapper.Companion.durationFromTask
import com.melanta.feature.agent.action.ToolCallMapper.Companion.parseEvent
import com.melanta.core.automation.action.SettingsPage
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallMapperTest {

    private val parser = ToolCallParser()
    private val mapper = ToolCallMapper()

    private fun map(json: String): MapResult {
        val call = (parser.parse(json) as ParseResult.Success).call
        return mapper.map(call)
    }

    private fun action(json: String) = (map(json) as MapResult.Success).action

    @Test
    fun `ferramentas basicas viram acoes tipadas`() {
        assertEquals(AgentAction.Click(Target.ById(3)), action("""{"tool":"click","args":{"id":3}}"""))
        assertEquals(
            AgentAction.Type(Target.ById(2), "chego em 10 min", submit = true),
            action("""{"tool":"type","args":{"id":2,"text":"chego em 10 min","enter":true}}"""),
        )
        assertEquals(AgentAction.Scroll(Direction.Up, null), action("""{"tool":"scroll","args":{"dir":"up"}}"""))
        assertEquals(AgentAction.Navigate(NavKey.Back), action("""{"tool":"back","args":{}}"""))
        assertEquals(AgentAction.Done(false, "Não consegui"), action("""{"tool":"done","args":{"ok":false,"msg":"Não consegui"}}"""))
    }

    @Test
    fun `intents com valores semanticos`() {
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Alarm(7, 30, "Gym")),
            action("""{"tool":"intent","args":{"kind":"alarm","value":"07:30 Gym"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Timer(300)),
            action("""{"tool":"intent","args":{"kind":"timer","value":"300"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.OpenApp("WhatsApp")),
            action("""{"tool":"open_app","args":{"name":"WhatsApp"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Url("https://g1.globo.com")),
            action("""{"tool":"intent","args":{"kind":"url","value":"g1.globo.com"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Dial("+5511999998888")),
            action("""{"tool":"intent","args":{"kind":"dial","value":"+55 (11) 99999-8888"}}"""),
        )
    }

    @Test
    fun `novo contato com nome e telefone em varios formatos`() {
        val expected = AgentAction.LaunchIntent(IntentSpec.AddContact("Jose", "+5524987778990"))
        listOf(
            """{"tool":"intent","args":{"kind":"add_contact","value":"Jose | +55 24 987778990"}}""",
            """{"tool":"intent","args":{"kind":"add_contact","value":"Jose, +55 (24) 98777-8990"}}""",
            """{"tool":"intent","args":{"kind":"add_contact","name":"Jose","phone":"+55 24 987778990"}}""",
            """{"tool":"create_contact","args":{"name":"Jose","number":"+55 24 98777 8990"}}""",
        ).forEach { json -> assertEquals(json, expected, action(json)) }
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.AddContact("Maria Silva", null)),
            action("""{"tool":"intent","args":{"kind":"add_contact","value":"Maria Silva"}}"""),
        )
        assertTrue(map("""{"tool":"intent","args":{"kind":"add_contact","value":"+55 24 987778990"}}""") is MapResult.Failure)
    }

    @Test
    fun `valores invalidos geram dica para o re-prompt`() {
        listOf(
            """{"tool":"intent","args":{"kind":"alarm","value":"amanhã cedo"}}""",
            """{"tool":"intent","args":{"kind":"alarm","value":"25:00"}}""",
            """{"tool":"intent","args":{"kind":"timer","value":"0"}}""",
            """{"tool":"intent","args":{"kind":"url","value":"http://site.com"}}""",
            """{"tool":"intent","args":{"kind":"url","value":"javascript:alert(1)"}}""",
        ).forEach { json -> assertTrue(json, map(json) is MapResult.Failure) }
    }

    @Test
    fun `discar aceita numero ou nome do contato`() {
        assertEquals(AgentAction.LaunchIntent(IntentSpec.Dial("Mãe")), action("""{"tool":"intent","args":{"kind":"dial","value":"Mãe"}}"""))
    }

    @Test
    fun `configuracoes por sinonimo em portugues e ingles`() {
        mapOf(
            "wifi" to SettingsPage.Wifi, "Wi-Fi" to SettingsPage.Wifi, "bluetooth" to SettingsPage.Bluetooth,
            "brilho da tela" to SettingsPage.Display, "dark mode" to SettingsPage.Display, "modo avião" to SettingsPage.Airplane,
            "bateria" to SettingsPage.Battery, "localização" to SettingsPage.Location, "volume" to SettingsPage.Sound,
        ).forEach { (value, page) ->
            assertEquals(value, AgentAction.LaunchIntent(IntentSpec.Settings(page)), action("""{"tool":"intent","args":{"kind":"settings","value":"$value"}}"""))
        }
        assertTrue(map("""{"tool":"intent","args":{"kind":"settings","value":"segurança"}}""") is MapResult.Failure)
    }

    @Test
    fun `mensagens com destinatario e texto`() {
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Sms("Ana", "chego em 10 min")),
            action("""{"tool":"intent","args":{"kind":"sms","value":"Ana | chego em 10 min"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.WhatsApp("+55 24 98777-8990", "oi | tudo bem?")),
            action("""{"tool":"intent","args":{"kind":"whatsapp","value":"+55 24 98777-8990 | oi | tudo bem?"}}"""),
        )
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.WhatsApp("Ana", "oi")),
            action("""{"tool":"send_whatsapp","args":{"to":"Ana","message":"oi"}}"""),
        )
        assertTrue(map("""{"tool":"intent","args":{"kind":"sms","value":"Ana"}}""") is MapResult.Failure)
        assertEquals(
            AgentAction.LaunchIntent(IntentSpec.Email("ana@ex.com", "Reunião", "Confirmado")),
            action("""{"tool":"intent","args":{"kind":"email","value":"ana@ex.com | Reunião | Confirmado"}}"""),
        )
        assertTrue(map("""{"tool":"intent","args":{"kind":"email","value":"Ana | oi"}}""") is MapResult.Failure)
    }

    @Test
    fun `eventos com data absoluta ou relativa`() {
        val now = LocalDateTime.of(2026, 10, 26, 13, 5)
        assertEquals(IntentSpec.Event("Dentista", LocalDateTime.of(2026, 10, 27, 15, 0)), parseEvent("Dentista | 2026-10-27 15:00", now))
        assertEquals(IntentSpec.Event("Dentista", LocalDateTime.of(2026, 10, 27, 15, 0)), parseEvent("Dentista | amanhã 15h", now))
        assertEquals(IntentSpec.Event("Café", LocalDateTime.of(2026, 10, 26, 9, 30)), parseEvent("Café | 09:30", now))
        assertEquals(IntentSpec.Event("Prova", LocalDateTime.of(2026, 11, 3, 8, 0)), parseEvent("Prova | 03/11/2026 08:00", now))
        assertEquals(IntentSpec.Event("Dentista", LocalDateTime.of(2026, 10, 27, 15, 0)), parseEvent("Marca dentista amanhã às 15h", now))
        assertEquals(IntentSpec.Event("Dentista", LocalDateTime.of(2026, 10, 27, 15, 0)), parseEvent("Marca dentista 2026-10-27 15:00", now))
        assertEquals(IntentSpec.Event("Reunião com Ana", LocalDateTime.of(2026, 10, 26, 16, 30)), parseEvent("Reunião com Ana hoje 16:30", now))
        assertNull(parseEvent("Dentista", now))
        assertNull(parseEvent("Marca dentista amanhã", now))
        assertNull(parseEvent("Dentista | semana que vem", now))
    }

    @Test
    fun `navegacao`() {
        assertEquals(AgentAction.LaunchIntent(IntentSpec.Navigate("Av. Paulista")), action("""{"tool":"navigate","args":{"value":"Av. Paulista"}}"""))
    }

    @Test
    fun `formatos de horario de alarme`() {
        assertEquals(IntentSpec.Alarm(7, 0, null), parseAlarm("7h"))
        assertEquals(IntentSpec.Alarm(7, 0, null), parseAlarm("7"))
        assertEquals(IntentSpec.Alarm(19, 30, "Remédio"), parseAlarm("19h30 Remédio"))
        assertEquals(IntentSpec.Alarm(19, 0, null), parseAlarm("7:00 pm"))
        assertEquals(IntentSpec.Alarm(0, 15, null), parseAlarm("12:15 am"))
        assertNull(parseAlarm("7:75"))
    }

    @Test
    fun `formatos de duracao de timer`() {
        assertEquals(300, parseDuration("300"))
        assertEquals(300, parseDuration("5 min"))
        assertEquals(300, parseDuration("5m"))
        assertEquals(5400, parseDuration("1h30m"))
        assertEquals(90, parseDuration("90s"))
        assertEquals(90, parseDuration("01:30"))
        assertEquals(3600, parseDuration("1:00:00"))
        assertEquals(600, parseDuration("10 minutos"))
        assertEquals(5400, parseDuration("1 hora e 30 minutos"))
        assertNull(parseDuration("0"))
        assertNull(parseDuration("2 dias"))
        assertNull(parseDuration("100000"))
    }

    @Test
    fun `timer so com numero usa a unidade escrita no pedido`() {
        assertEquals(300, durationFromTask("5", "Coloca um timer de 5 minutos"))
        assertEquals(5400, durationFromTask("1", "timer de 1 hora e 30 minutos"))
        assertEquals(90, durationFromTask("90", "timer de 90 segundos"))
        assertNull(durationFromTask("300", "Coloca um timer de 5 minutos"))
        assertNull(durationFromTask("5 min", "Coloca um timer de 5 minutos"))
        val call = (parser.parse("""{"tool":"intent","args":{"kind":"timer","value":"5"}}""") as ParseResult.Success).call
        assertEquals(MapResult.Success(AgentAction.LaunchIntent(IntentSpec.Timer(300))), mapper.map(call, "timer de 5 minutos"))
    }
}
