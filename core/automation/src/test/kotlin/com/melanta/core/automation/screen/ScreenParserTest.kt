package com.melanta.core.automation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenParserTest {

    private val parser = ScreenParser()
    private val screen = Bounds(0, 0, 1080, 2400)

    private fun app(root: FakeNode, pkg: String = "com.whatsapp", title: String? = "Conversas", layer: Int = 1) =
        UiWindow(WindowKind.Application, layer, pkg, title, screen, root)

    private fun lines(vararg windows: UiWindow) = parser.parse(windows.toList(), "com.melanta", screen).lines()

    @Test
    fun `lista do whatsapp vira o formato da secao 3_3`() {
        val root = FakeNode(
            bounds = screen,
            children = listOf(
                FakeNode(className = "android.widget.ImageButton", contentDescription = "Nova conversa", isClickable = true, bounds = Bounds(900, 2200, 1040, 2340)),
                FakeNode(className = "android.widget.EditText", hintText = "Pesquisar...", isEditable = true, isFocused = true, isClickable = true, bounds = Bounds(0, 200, 1080, 320)),
                FakeNode(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    isScrollable = true,
                    bounds = Bounds(0, 340, 1080, 2100),
                    children = listOf(row(340, "Ana", "Onde você está?"), row(500, "Grupo Família")),
                ),
            ),
        )

        assertEquals(
            listOf(
                """[1] btn "Nova conversa" click""",
                """[2] edit "Pesquisar..." click focused""",
                """[3] list "" scroll""",
                """[4] item "Ana Onde você está?" click""",
                """[5] item "Grupo Família" click""",
            ),
            lines(app(root)),
        )
    }

    @Test
    fun `invisiveis, proprio app, teclado e barras pequenas ficam de fora`() {
        val content = FakeNode(
            bounds = screen,
            children = listOf(
                FakeNode(text = "visível", bounds = Bounds(0, 0, 500, 100)),
                FakeNode(text = "escondido", isVisibleToUser = false),
            ),
        )
        val statusBar = UiWindow(WindowKind.System, 10, "com.android.systemui", null, Bounds(0, 0, 1080, 80), FakeNode(text = "12:00"))
        val keyboard = UiWindow(WindowKind.InputMethod, 9, "com.google.android.inputmethod", null, Bounds(0, 1500, 1080, 2400), FakeNode(text = "q"))
        val own = UiWindow(WindowKind.Application, 8, "com.melanta", null, Bounds(0, 0, 200, 200), FakeNode(text = "PARAR", isClickable = true))

        assertEquals(listOf("""[1] txt "visível""""), lines(statusBar, keyboard, own, app(content)))
    }

    @Test
    fun `dialogo aparece antes do app e o app em tela cheia encerra a leitura`() {
        val dialog = UiWindow(
            WindowKind.Application, 5, "com.google.android.permissioncontroller", "Permitir?", Bounds(100, 900, 980, 1500),
            FakeNode(
                bounds = Bounds(100, 900, 980, 1500),
                children = listOf(FakeNode(className = "android.widget.Button", text = "Permitir", isClickable = true, bounds = Bounds(150, 1300, 900, 1400))),
            ),
        )
        val camera = app(FakeNode(bounds = screen, children = listOf(FakeNode(text = "Câmera", bounds = Bounds(0, 0, 300, 100)))), pkg = "com.camera", layer = 2)
        val behind = app(FakeNode(bounds = screen, children = listOf(FakeNode(text = "launcher"))), pkg = "launcher", layer = 1)

        val snapshot = parser.parse(listOf(behind, camera, dialog), "com.melanta", screen)
        assertEquals(listOf("""[1] btn "Permitir" click""", """[2] txt "Câmera""""), snapshot.lines())
        assertEquals("com.google.android.permissioncontroller", snapshot.packageName)
    }

    @Test
    fun `papeis e flags`() {
        val root = FakeNode(
            bounds = screen,
            children = listOf(
                FakeNode(className = "android.widget.Switch", text = "Wi-Fi", isCheckable = true, isChecked = true, isClickable = true, bounds = Bounds(0, 0, 1080, 100)),
                FakeNode(className = "android.widget.CheckBox", text = "Lembrar", isCheckable = true, isClickable = true, bounds = Bounds(0, 100, 1080, 200)),
                FakeNode(className = "android.widget.Button", text = "Enviar", isClickable = true, isEnabled = false, bounds = Bounds(0, 200, 1080, 300)),
                FakeNode(className = "android.view.View", roleDescription = "Tab", text = "Início", isClickable = true, bounds = Bounds(0, 300, 1080, 400)),
                FakeNode(className = "android.widget.ImageView", contentDescription = "Foto de perfil", bounds = Bounds(0, 400, 100, 500)),
            ),
        )
        assertEquals(
            listOf(
                """[1] sw "Wi-Fi" click checked""",
                """[2] chk "Lembrar" click""",
                """[3] btn "Enviar" click off""",
                """[4] tab "Início" click""",
                """[5] img "Foto de perfil"""",
            ),
            lines(app(root)),
        )
    }

    @Test
    fun `rotulos longos, aspas e quebras de linha sao normalizados`() {
        val root = FakeNode(
            bounds = screen,
            children = listOf(FakeNode(text = "Uma mensagem \"muito\"\ncomprida que passa de quarenta caracteres", bounds = Bounds(0, 0, 1080, 100))),
        )
        val line = lines(app(root)).single()
        assertEquals("""[1] txt "Uma mensagem 'muito' comprida que passa…"""", line)
    }

    @Test
    fun `botao sem rotulo usa o id da view e conteiner vazio some`() {
        val root = FakeNode(
            bounds = screen,
            children = listOf(
                FakeNode(className = "android.widget.ImageButton", viewId = "com.whatsapp:id/send_button", isClickable = true, bounds = Bounds(900, 2200, 1040, 2340)),
                FakeNode(
                    className = "android.widget.FrameLayout",
                    isClickable = true,
                    bounds = Bounds(0, 0, 1080, 400),
                    children = listOf(FakeNode(className = "android.widget.Button", text = "OK", isClickable = true, bounds = Bounds(10, 10, 200, 100))),
                ),
            ),
        )
        assertEquals(listOf("""[1] btn "send button" click""", """[2] btn "OK" click"""), lines(app(root)))
    }

    @Test
    fun `corta em 60 elementos mantendo os de maior prioridade e a ordem`() {
        val texts = (1..80).map { FakeNode(text = "texto $it", bounds = Bounds(0, it * 10, 500, it * 10 + 9)) }
        val button = FakeNode(className = "android.widget.Button", text = "Enviar", isClickable = true, bounds = Bounds(0, 2000, 500, 2100))
        val snapshot = parser.parse(listOf(app(FakeNode(bounds = screen, children = texts + button))), "com.melanta", screen)

        assertEquals(ScreenParser.MAX_ELEMENTS, snapshot.elements.size)
        assertEquals(21, snapshot.droppedElements)
        assertTrue(snapshot.elements.any { it.label == "Enviar" })
        assertEquals((1..ScreenParser.MAX_ELEMENTS).toList(), snapshot.elements.map { it.id })
        assertFalse(snapshot.elements.any { it.label == "texto 80" })
    }

    @Test
    fun `hash muda quando a tela muda e coordenadas sao normalizadas`() {
        val a = parser.parse(listOf(app(FakeNode(bounds = screen, children = listOf(FakeNode(text = "A", bounds = Bounds(0, 0, 1080, 2400)))))), "x", screen)
        val b = parser.parse(listOf(app(FakeNode(bounds = screen, children = listOf(FakeNode(text = "B", bounds = Bounds(0, 0, 1080, 2400)))))), "x", screen)
        assertTrue(a.hash != b.hash)
        assertEquals("""[1] txt "A" @499,499""", a.lines(includeCoords = true).single())
    }
}
