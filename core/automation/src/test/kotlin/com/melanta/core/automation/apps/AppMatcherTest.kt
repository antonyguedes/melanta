package com.melanta.core.automation.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppMatcherTest {

    private val apps = listOf(
        LaunchableApp("WhatsApp", "com.whatsapp", "Main"),
        LaunchableApp("Configurações", "com.android.settings", "Settings"),
        LaunchableApp("Câmera", "com.google.android.GoogleCamera", "Camera"),
        LaunchableApp("Relógio", "com.google.android.deskclock", "Clock"),
        LaunchableApp("Chrome", "com.android.chrome", "Main"),
        LaunchableApp("YouTube Music", "com.google.android.apps.youtube.music", "Main"),
        LaunchableApp("YouTube", "com.google.android.youtube", "Main"),
        LaunchableApp("Contatos", "com.google.android.contacts", "People"),
    )

    private fun match(q: String) = AppMatcher.bestMatch(q, apps)?.label

    @Test
    fun `nome exato, sem acento e em caixa diferente`() {
        assertEquals("WhatsApp", match("whatsapp"))
        assertEquals("Configurações", match("configuracoes"))
        assertEquals("Câmera", match("CAMERA"))
        assertEquals("YouTube", match("YouTube"))
    }

    @Test
    fun `sinonimos em ingles e erros de digitacao`() {
        assertEquals("Configurações", match("Settings"))
        assertEquals("Relógio", match("clock"))
        assertEquals("WhatsApp", match("Whatsap"))
        assertEquals("YouTube Music", match("youtube music"))
        assertEquals("Chrome", match("browser"))
    }

    @Test
    fun `nome do pacote tambem funciona`() {
        assertEquals("WhatsApp", match("com.whatsapp"))
        assertNull(match("com.google.android.calendar"))
        // Pacote de outro fabricante: casa pelo último segmento.
        assertEquals("Contatos", match("com.android.contacts"))
        assertEquals("Contatos", match("contacts"))
    }

    @Test
    fun `app inexistente nao casa com qualquer coisa`() {
        assertNull(match("Spotify"))
        assertNull(match(""))
    }
}
