package com.melanta.core.automation.screen

import javax.inject.Inject

/**
 * Converte as janelas visíveis em uma lista compacta de elementos para o prompt.
 *
 * - Lê de cima para baixo (diálogos antes do app embaixo) e para na primeira janela de app em tela cheia.
 * - Ignora o próprio app, o teclado e barras de sistema pequenas (status e navegação).
 * - Funde o texto de filhos não clicáveis no ancestral clicável (uma linha por item de lista).
 * - Interruptor sem rótulo herda o texto da mesma linha ("Wi-Fi") e sempre mostra o estado.
 * - Deduplica e corta em [MAX_ELEMENTS], descartando primeiro o que tem menor prioridade.
 */
class ScreenParser @Inject constructor() {

    fun parse(windows: List<UiWindow>, ownPackage: String, screen: Bounds): ScreenSnapshot {
        val selected = selectWindows(windows, ownPackage, screen)
        val protectedWindow = protectedWindowTitle(windows, screen)
        val main = selected.firstOrNull { it.kind == WindowKind.Application } ?: selected.firstOrNull()
            ?: return ScreenSnapshot.empty(screen).copy(protectedWindow = protectedWindow)

        val drafts = selected.flatMap { window -> window.root?.let(::collect).orEmpty() }
        val cleaned = dedupe(dropEmptyContainers(labelUnnamedToggles(drafts)))
        val kept = cap(cleaned)
        val elements = kept.mapIndexed { index, d ->
            UiElement(index + 1, d.role, d.label(), d.flags, d.bounds, d.viewId, d.priority, d.node)
        }
        return ScreenSnapshot(
            packageName = main.packageName.orEmpty(),
            title = main.title?.let(::clean)?.takeIf { it.isNotEmpty() },
            elements = elements,
            screenBounds = screen,
            droppedElements = cleaned.size - kept.size,
            protectedWindow = protectedWindow,
        )
    }

    /** Janela de app sem árvore legível acima da primeira janela de app em tela cheia que dá para ler. */
    private fun protectedWindowTitle(windows: List<UiWindow>, screen: Bounds): String? {
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.kind != WindowKind.Application) continue
            if (window.root == null) return window.title?.let(::clean)?.takeIf { it.isNotEmpty() } ?: UNTITLED_PROTECTED
            if (screen.area > 0 && window.bounds.area.toDouble() / screen.area >= FULL_SCREEN_COVERAGE) return null
        }
        return null
    }

    private fun selectWindows(windows: List<UiWindow>, ownPackage: String, screen: Bounds): List<UiWindow> {
        val result = mutableListOf<UiWindow>()
        for (window in windows.sortedByDescending { it.layer }) {
            if (window.root == null || window.packageName == ownPackage) continue
            val coverage = if (screen.area == 0L) 0.0 else window.bounds.area.toDouble() / screen.area
            when (window.kind) {
                WindowKind.Application -> {
                    result += window
                    if (coverage >= FULL_SCREEN_COVERAGE) break
                }
                // Painel de notificações e diálogos de sistema sim; barra de status e navegação não.
                WindowKind.System -> if (coverage >= SYSTEM_WINDOW_MIN_COVERAGE) result += window
                WindowKind.InputMethod, WindowKind.Overlay, WindowKind.Other -> Unit
            }
        }
        return result
    }

    /** DFS iterativa com limite de profundidade. */
    private fun collect(root: UiNode): List<Draft> {
        val drafts = mutableListOf<Draft>()
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, depth = 0, owner = null))
        while (stack.isNotEmpty()) {
            val (node, depth, owner) = stack.removeLast()
            if (!node.isVisibleToUser || depth > MAX_DEPTH) continue

            val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
                node.isCheckable || node.isScrollable
            val ownLabel = labelOf(node)
            var childOwner = owner

            if (actionable && !node.bounds.isEmpty) {
                val role = roleOf(node)
                val draft = Draft(node, role, flagsOf(node, role), priorityOf(node))
                ownLabel?.let(draft::append)
                drafts += draft
                // Itens de uma lista rolável não são rótulo da lista; os de um botão/linha são.
                childOwner = if (node.isScrollable && !node.isClickable) null else draft
            } else if (ownLabel != null) {
                if (owner != null) {
                    owner.append(ownLabel)
                } else if (!node.bounds.isEmpty) {
                    val role = if (node.className.orEmpty().contains("Image")) "img" else "txt"
                    drafts += Draft(node, role, emptyList(), if (role == "img") 1 else 2).also { it.append(ownLabel) }
                }
            }

            for (i in node.childCount - 1 downTo 0) {
                node.child(i)?.let { stack.addLast(Frame(it, depth + 1, childOwner)) }
            }
        }
        return drafts
    }

    /**
     * Interruptores de painéis e listas de Configurações costumam vir sem texto, com o rótulo num item
     * vizinho da mesma linha. Sem nome, o modelo não sabe o que o interruptor controla.
     */
    private fun labelUnnamedToggles(drafts: List<Draft>): List<Draft> {
        for (toggle in drafts) {
            if (toggle.role !in TOGGLE_ROLES || toggle.hasLabel()) continue
            val b = toggle.bounds
            drafts.asSequence()
                .filter { it !== toggle && it.hasLabel() && it.role !in TOGGLE_ROLES && it.role != "list" }
                .filter { b.centerY in it.bounds.top..it.bounds.bottom && it.bounds.height <= b.height * ROW_HEIGHT_FACTOR }
                .minByOrNull { other ->
                    when {
                        b in other.bounds -> 0
                        other.bounds.right <= b.left -> b.left - other.bounds.right
                        else -> (other.bounds.left - b.right).coerceAtLeast(0)
                    }
                }
                ?.let { toggle.append(it.label()) }
        }
        return drafts
    }

    /** Contêineres clicáveis sem rótulo que só envolvem outros elementos não ajudam o modelo. */
    private fun dropEmptyContainers(drafts: List<Draft>): List<Draft> = drafts.filter { d ->
        if (d.hasLabel() || d.role == "edit" || d.role == "list") return@filter true
        if (d.viewId != null) {
            d.append(d.viewId.substringAfter(":id/").substringAfter('/').replace('_', ' '))
            return@filter true
        }
        drafts.none { other -> other !== d && other.bounds in d.bounds }
    }

    private fun dedupe(drafts: List<Draft>): List<Draft> {
        val seen = HashSet<Triple<String, String, Bounds>>()
        return drafts.filter { seen.add(Triple(it.role, it.label(), it.bounds)) }
    }

    private fun cap(drafts: List<Draft>): List<Draft> {
        if (drafts.size <= MAX_ELEMENTS) return drafts
        val keep = drafts.indices
            .sortedWith(compareByDescending<Int> { drafts[it].priority }.thenBy { it })
            .take(MAX_ELEMENTS)
            .toSortedSet()
        return keep.map { drafts[it] }
    }

    private fun labelOf(node: UiNode): String? {
        val raw = if (node.isEditable) {
            node.text?.takeIf { it.isNotBlank() } ?: node.hintText ?: node.contentDescription
        } else {
            node.text?.takeIf { it.isNotBlank() } ?: node.contentDescription ?: node.hintText
        }
        return raw?.let(::clean)?.takeIf { it.isNotEmpty() }
    }

    private fun roleOf(node: UiNode): String {
        val cls = node.className.orEmpty()
        val role = node.roleDescription.orEmpty().lowercase()
        return when {
            node.isEditable -> "edit"
            cls.endsWith("Switch") || cls.endsWith("SwitchCompat") || cls.endsWith("ToggleButton") ||
                role.contains("switch") -> "sw"
            node.isCheckable -> "chk"
            role == "tab" || cls.contains("Tab") -> "tab"
            node.isScrollable && !node.isClickable -> "list"
            cls.endsWith("Button") || role.contains("button") || cls.contains("Image") || cls.endsWith("TextView") -> "btn"
            else -> "item"
        }
    }

    private fun flagsOf(node: UiNode, role: String): List<String> = buildList {
        if (node.isClickable || node.isLongClickable) add("click")
        if (node.isFocused) add("focused")
        if (node.isCheckable && node.isChecked) add("checked")
        // Em abas, a ausência de "checked" basta; em interruptores o estado desligado precisa ser explícito.
        else if (role in TOGGLE_ROLES) add("unchecked")
        if (!node.isEnabled) add("off")
        if (node.isScrollable) add("scroll")
    }

    private fun priorityOf(node: UiNode): Int = when {
        !node.isEnabled -> 0
        node.isEditable && node.isFocused -> 6
        node.isEditable -> 5
        node.isClickable || node.isCheckable || node.isLongClickable -> 4
        node.isScrollable -> 3
        else -> 2
    }

    private data class Frame(val node: UiNode, val depth: Int, val owner: Draft?)

    private class Draft(val node: UiNode, val role: String, val flags: List<String>, val priority: Int) {
        val bounds: Bounds = node.bounds
        val viewId: String? = node.viewId
        private val parts = mutableListOf<String>()
        private var length = 0

        fun append(text: String) {
            if (length >= MAX_LABEL || text in parts) return
            parts += text
            length += text.length + 1
        }

        fun hasLabel() = parts.isNotEmpty()

        fun label(): String = clean(parts.joinToString(" "))
    }

    companion object {
        const val MAX_ELEMENTS = 60
        const val MAX_LABEL = 40
        private val TOGGLE_ROLES = setOf("sw", "chk")
        private const val UNTITLED_PROTECTED = "janela protegida do sistema"
        private const val ROW_HEIGHT_FACTOR = 4
        private const val MAX_DEPTH = 40
        private const val FULL_SCREEN_COVERAGE = 0.9
        private const val SYSTEM_WINDOW_MIN_COVERAGE = 0.5

        /** Uma linha, sem aspas duplas (delimitam o rótulo) e no máximo [MAX_LABEL] caracteres. */
        internal fun clean(raw: String): String {
            val oneLine = raw.replace('"', '\'').replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= MAX_LABEL) oneLine else oneLine.take(MAX_LABEL - 1).trimEnd() + "…"
        }
    }
}
