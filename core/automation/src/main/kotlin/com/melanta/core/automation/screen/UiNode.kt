package com.melanta.core.automation.screen

/** Retângulo em pixels de tela. Próprio para que o parser rode em testes na JVM. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    operator fun contains(other: Bounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

/** Visão mínima de um nó de acessibilidade, implementada sobre `AccessibilityNodeInfo` no aparelho. */
interface UiNode {
    val className: String?
    val text: String?
    val contentDescription: String?
    val hintText: String?
    val viewId: String?
    val roleDescription: String?
    val bounds: Bounds
    val isVisibleToUser: Boolean
    val isClickable: Boolean
    val isLongClickable: Boolean
    val isEditable: Boolean
    val isCheckable: Boolean
    val isChecked: Boolean
    val isEnabled: Boolean
    val isFocused: Boolean
    val isScrollable: Boolean
    val childCount: Int

    fun child(index: Int): UiNode?
}

enum class WindowKind { Application, System, InputMethod, Overlay, Other }

data class UiWindow(
    val kind: WindowKind,
    val layer: Int,
    val packageName: String?,
    val title: String?,
    val bounds: Bounds,
    val root: UiNode?,
)
