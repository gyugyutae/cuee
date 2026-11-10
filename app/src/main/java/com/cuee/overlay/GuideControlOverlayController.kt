package com.cuee.overlay

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.cuee.domain.scoring.Bounds

class GuideControlOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onRepeat: () -> Unit,
    private val onBack: () -> Unit,
    private val onStop: () -> Unit,
    private val onEdit: (() -> Unit)? = null
) {
    private var panel: LinearLayout? = null
    private var lastMessage: String? = null
    private var lastTarget: Bounds? = null
    private var lastPreferTop = false

    fun show(message: String, target: Bounds? = null, preferTop: Boolean = false) {
        if (message == lastMessage && target == lastTarget && lastPreferTop == preferTop && panel != null) return
        hide()
        lastMessage = message
        lastTarget = target
        lastPreferTop = preferTop

        val screen = windowManager.screenBounds()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            background = rounded(CUE_SURFACE, context.dp(8))
            elevation = context.dp(12).toFloat()
            contentDescription = "큐 안내 조작"
        }
        container.addView(TextView(context).apply {
            text = message
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CUE_TEXT)
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
        onEdit?.let { actions.addView(actionButton("조건 수정", it), actionParams()) }
        container.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })

        val width = screen.width() - context.dp(24)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val bottomY = (screen.height() - container.measuredHeight - context.dp(32)).coerceAtLeast(context.dp(12))
        val panelY = if (preferTop || (target != null && target.bottom > bottomY - context.dp(12))) context.dp(48) else bottomY
        panel = container
        windowManager.addView(
            container,
            overlayParams(
                width = width,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                x = context.dp(12),
                y = panelY,
                touchable = true
            )
        )
    }

    fun hide() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        lastMessage = null
        lastTarget = null
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        minHeight = context.dp(52)
        stateListAnimator = null
        // 종료는 위험 색 글자, 나머지는 옅은 브랜드 바탕 — 채운 브랜드 버튼은 화면의 주 행동에만.
        setTextColor(if (label == "종료") CUE_DANGER else CUE_BRAND)
        background = rounded(if (label == "종료") DANGER_TINT else CUE_TINT, context.dp(6))
        setOnClickListener { action() }
    }

    private companion object {
        const val DANGER_TINT = 0xFFFCEFF0.toInt()
    }

    private fun actionParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = context.dp(4)
        marginEnd = context.dp(4)
    }
}
