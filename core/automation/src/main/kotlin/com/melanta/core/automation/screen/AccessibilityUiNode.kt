package com.melanta.core.automation.screen

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** [UiNode] sobre um nó real. Os campos são lidos uma vez: cada leitura no nó é uma chamada IPC. */
class AccessibilityUiNode(val info: AccessibilityNodeInfo) : UiNode {
    override val className: String? = info.className?.toString()
    override val text: String? = info.text?.toString()
    override val contentDescription: String? = info.contentDescription?.toString()
    override val hintText: String? = info.hintText?.toString()
    override val viewId: String? = info.viewIdResourceName
    override val roleDescription: String? = AccessibilityNodeInfoCompat.wrap(info).roleDescription?.toString()
    override val bounds: Bounds = Rect().also(info::getBoundsInScreen).let { Bounds(it.left, it.top, it.right, it.bottom) }
    override val isVisibleToUser: Boolean = info.isVisibleToUser
    override val isClickable: Boolean = info.isClickable
    override val isLongClickable: Boolean = info.isLongClickable
    override val isEditable: Boolean = info.isEditable
    override val isCheckable: Boolean = info.isCheckable
    override val isChecked: Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            info.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            info.isChecked
        }
    override val isEnabled: Boolean = info.isEnabled
    override val isFocused: Boolean = info.isFocused
    override val isScrollable: Boolean = info.isScrollable
    override val childCount: Int = info.childCount

    override fun child(index: Int): UiNode? = info.getChild(index)?.let(::AccessibilityUiNode)
}
