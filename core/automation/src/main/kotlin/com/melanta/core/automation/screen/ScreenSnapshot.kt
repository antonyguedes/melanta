package com.melanta.core.automation.screen

/**
 * Um elemento da tela na gramática da seção 3.3: `[idx] <role> "<label≤40>" <flags>`.
 * [node] permite ao executor agir sobre o nó original (ele é reconsultado antes de agir).
 */
data class UiElement(
    val id: Int,
    val role: String,
    val label: String,
    val flags: List<String>,
    val bounds: Bounds,
    val viewId: String?,
    val priority: Int,
    val node: UiNode?,
) {
    fun line(screen: Bounds? = null): String = buildString {
        append('[').append(id).append("] ").append(role).append(" \"").append(label).append('"')
        flags.forEach { append(' ').append(it) }
        // Coordenadas opcionais numa grade normalizada 0–999.
        if (screen != null && !screen.isEmpty) {
            val x = ((bounds.centerX - screen.left) * 999L / screen.width).coerceIn(0, 999)
            val y = ((bounds.centerY - screen.top) * 999L / screen.height).coerceIn(0, 999)
            append(" @").append(x).append(',').append(y)
        }
    }
}

data class ScreenSnapshot(
    val packageName: String,
    val title: String?,
    val elements: List<UiElement>,
    val screenBounds: Bounds,
    /** Elementos descartados pelo limite de [ScreenParser.MAX_ELEMENTS]. */
    val droppedElements: Int = 0,
    /**
     * Título de uma janela no topo que o serviço não consegue ler (ex.: pedido de permissão do sistema,
     * marcado como sensível). Só o usuário pode respondê-la.
     */
    val protectedWindow: String? = null,
) {
    /** Muda quando a tela muda; usado na detecção de loop (mesma ação + mesma tela). */
    val hash: Int = (packageName + "\n" + protectedWindow + "\n" + elements.joinToString("\n") { it.line() }).hashCode()

    fun byId(id: Int): UiElement? = elements.firstOrNull { it.id == id }

    fun lines(includeCoords: Boolean = false): List<String> =
        elements.map { it.line(if (includeCoords) screenBounds else null) }

    companion object {
        fun empty(screen: Bounds) = ScreenSnapshot(packageName = "", title = null, elements = emptyList(), screenBounds = screen)
    }
}
