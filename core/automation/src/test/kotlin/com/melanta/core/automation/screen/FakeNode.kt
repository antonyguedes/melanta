package com.melanta.core.automation.screen

/** Nó de teste. Por padrão visível, habilitado e não interativo. */
data class FakeNode(
    override val className: String? = "android.view.View",
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val hintText: String? = null,
    override val viewId: String? = null,
    override val roleDescription: String? = null,
    override val bounds: Bounds = Bounds(0, 0, 100, 100),
    override val isVisibleToUser: Boolean = true,
    override val isClickable: Boolean = false,
    override val isLongClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isCheckable: Boolean = false,
    override val isChecked: Boolean = false,
    override val isEnabled: Boolean = true,
    override val isFocused: Boolean = false,
    override val isScrollable: Boolean = false,
    val children: List<FakeNode> = emptyList(),
) : UiNode {
    override val childCount: Int get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
}

fun row(y: Int, vararg texts: String) = FakeNode(
    className = "android.widget.LinearLayout",
    isClickable = true,
    bounds = Bounds(0, y, 1080, y + 150),
    children = texts.mapIndexed { i, t ->
        FakeNode(className = "android.widget.TextView", text = t, bounds = Bounds(40, y + 10 + i * 60, 1000, y + 60 + i * 60))
    },
)
