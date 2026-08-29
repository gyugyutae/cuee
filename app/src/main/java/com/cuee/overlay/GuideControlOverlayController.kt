package com.cuee.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class GuideControlOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onRepeat: () -> Unit,
    private val onBack: () -> Unit,
    private val onStop: () -> Unit
) {
    private var panel: LinearLayout? = null
    private var lastMessage: String? = null

    fun show(message: String) {
        if (message == lastMessage && panel != null) return
        hide()
        lastMessage = message

        val screen = windowManager.screenBounds()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            background = rounded(Color.WHITE, context.dp(16))
            elevation = context.dp(12).toFloat()
            contentDescription = "큐 안내 조작"
        }
        container.addView(TextView(context).apply {
            text = message
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(actionButton("다시 듣기", onRepeat), actionParams())
        actions.addView(actionButton("이전", onBack), actionParams())
        actions.addView(actionButton("종료", onStop), actionParams())
        container.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })

        panel = container
        windowManager.addView(
            container,
            overlayParams(
                width = screen.width() - context.dp(24),
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                x = context.dp(12),
                y = (screen.height() - context.dp(180)).coerceAtLeast(context.dp(12)),
                touchable = true
            )
        )
    }

    fun hide() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        lastMessage = null
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        minHeight = context.dp(52)
        setTextColor(Color.WHITE)
        setBackgroundColor(if (label == "종료") 0xFF7F1D1D.toInt() else CUE_GREEN)
        setOnClickListener { action() }
    }

    private fun actionParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = context.dp(4)
        marginEnd = context.dp(4)
    }
}
