package com.melanta.feature.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import androidx.core.graphics.toColorInt
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.melanta.core.automation.service.AccessibilityBridge
import com.melanta.feature.agent.AgentController
import com.melanta.feature.agent.R
import com.melanta.feature.agent.state.AgentState
import javax.inject.Inject
import kotlin.math.abs

/**
 * Kill switch flutuante sobre qualquer app: botão PARAR arrastável e, quando o agente pede
 * confirmação, um painel com Permitir/Negar. Usa `TYPE_ACCESSIBILITY_OVERLAY` da janela do
 * serviço de acessibilidade, então não exige a permissão de sobreposição.
 *
 * Todos os métodos devem ser chamados na main thread.
 */
class AgentOverlay @Inject constructor(
    private val bridge: AccessibilityBridge,
    private val controller: AgentController,
) {
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var confirmPanel: LinearLayout? = null
    private var question: TextView? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (root != null) return
        val service = bridge.service.value ?: return
        val wm = service.getSystemService(WindowManager::class.java)
        val view = build(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(service, 12)
            y = dp(service, 96)
        }
        wm.addView(view, lp)
        root = view
        windowManager = wm
        params = lp
    }

    fun hide() {
        root?.let { view -> runCatching { windowManager?.removeView(view) } }
        root = null
        status = null
        confirmPanel = null
        question = null
        windowManager = null
        params = null
    }

    fun render(state: AgentState) {
        val context = root?.context ?: return
        status?.text = when (state) {
            is AgentState.LoadingModel -> context.getString(R.string.overlay_loading)
            is AgentState.Running -> context.getString(R.string.overlay_step, state.step + 1)
            is AgentState.AwaitingConfirmation -> context.getString(R.string.overlay_confirm)
            else -> ""
        }
        val awaiting = state as? AgentState.AwaitingConfirmation
        confirmPanel?.visibility = if (awaiting != null) View.VISIBLE else View.GONE
        question?.text = awaiting?.description
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun build(context: Context): LinearLayout {
        val radius = dp(context, 20).toFloat()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(PLUM, PLUM_DEEP)).apply {
                cornerRadius = radius
                setStroke(dp(context, 1), OUTLINE)
            }
            elevation = dp(context, 6).toFloat()
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val statusView = TextView(context).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, dp(context, 10), 0)
            text = context.getString(R.string.overlay_loading)
        }
        row.addView(statusView)
        row.addView(pill(context, context.getString(R.string.overlay_stop), DANGER) { controller.stop() })
        column.addView(row)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(context, 8), 0, 0)
        }
        val questionView = TextView(context).apply {
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxWidth = dp(context, 260)
        }
        panel.addView(questionView)
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 8), 0, 0)
        }
        buttons.addView(pill(context, context.getString(R.string.overlay_allow), SURFACE_HIGH) { controller.confirm(true) })
        buttons.addView(View(context), LinearLayout.LayoutParams(dp(context, 8), 1))
        buttons.addView(pill(context, context.getString(R.string.overlay_deny), DANGER) { controller.confirm(false) })
        panel.addView(buttons)
        column.addView(panel)

        // Arrastar pela área do status, para tirar o botão de cima do que o agente precisa tocar.
        statusView.setOnTouchListener(DragListener())

        status = statusView
        confirmPanel = panel
        question = questionView
        return column
    }

    private fun pill(context: Context, label: String, color: Int, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(TEXT)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 16).toFloat()
            setColor(color)
        }
        setOnClickListener { onClick() }
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val lp = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) + abs(dy) < 4) return true
                    // Gravity END: x cresce para a esquerda.
                    lp.x = startX - dx
                    lp.y = startY + dy
                    root?.let { windowManager?.updateViewLayout(it, lp) }
                }
            }
            return true
        }
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private companion object {
        // Paleta da marca (ver :core:designsystem MelantaColors).
        val PLUM = "#4A2E4B".toColorInt()
        val PLUM_DEEP = "#2E1430".toColorInt()
        val SURFACE_HIGH = "#342E38".toColorInt()
        val OUTLINE = "#5E4A5F".toColorInt()
        val TEXT = "#F4E6F4".toColorInt()
        val DANGER = "#8C1D18".toColorInt()
    }
}
