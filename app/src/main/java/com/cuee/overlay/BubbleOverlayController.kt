package com.cuee.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.cuee.data.BubbleEdge
import kotlin.math.abs

class BubbleOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onTap: () -> Unit,
    private val onDismissed: () -> Unit,
    private val onPositionSaved: (BubbleEdge, Float) -> Unit
) {
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var dismissView: TextView? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    fun show(side: BubbleEdge = BubbleEdge.RIGHT, yRatio: Float = 0.55f) {
        bubble?.let { existing ->
            params?.let { existingParams ->
                if (runCatching { windowManager.updateViewLayout(existing, existingParams) }.isSuccess) return
            }
            bubble = null
            params = null
        }
        val screen = windowManager.screenBounds()
        val size = context.dp(60)
        val x = if (side == BubbleEdge.LEFT) 0 else screen.width() - size
        val y = ((screen.height() - size) * yRatio).toInt().coerceIn(context.dp(48), screen.height() - size - context.dp(48))
        val view = TextView(context).apply {
            text = "큐"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(CUE_GREEN)
            elevation = context.dp(8).toFloat()
        }
        val lp = overlayParams(size, size, x, y)
        view.setOnTouchListener { _, event -> handleTouch(event) }
        bubble = view
        params = lp
        windowManager.addView(view, lp)
    }

    fun hide() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        params = null
        hideDismissTarget()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val lp = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = lp.x
                downY = lp.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > context.dp(4) || abs(dy) > context.dp(4))) {
                    dragging = true
                    showDismissTarget()
                }
                if (dragging) {
                    val screen = windowManager.screenBounds()
                    lp.x = (downX + dx).coerceIn(0, screen.width() - context.dp(60))
                    lp.y = (downY + dy).coerceIn(context.dp(40), screen.height() - context.dp(120))
                    bubble?.let { windowManager.updateViewLayout(it, lp) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    onTap()
                } else {
                    if (isInDismissArea(event.rawX, event.rawY)) {
                        hide()
                        onDismissed()
                    } else {
                        snapToSide()
                    }
                }
                hideDismissTarget()
                return true
            }
        }
        return false
    }

    private fun snapToSide() {
        val lp = params ?: return
        val screen = windowManager.screenBounds()
        val size = context.dp(60)
        val side = if (lp.x + size / 2 < screen.width() / 2) BubbleEdge.LEFT else BubbleEdge.RIGHT
        lp.x = if (side == BubbleEdge.LEFT) 0 else screen.width() - size
        bubble?.animate()?.x(lp.x.toFloat())?.setDuration(160)?.start()
        bubble?.let { windowManager.updateViewLayout(it, lp) }
        val yRatio = lp.y.toFloat() / (screen.height() - size).coerceAtLeast(1)
        onPositionSaved(side, yRatio)
    }

    private fun showDismissTarget() {
        if (dismissView != null) return
        val screen = windowManager.screenBounds()
        val size = context.dp(72)
        val view = TextView(context).apply {
            text = "X"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = circle(0xCC222222.toInt())
        }
        val lp = overlayParams(
            size,
            size,
            x = (screen.width() - size) / 2,
            y = screen.height() - size - context.dp(36)
        )
        dismissView = view
        windowManager.addView(view, lp)
    }

    private fun hideDismissTarget() {
        dismissView?.let { runCatching { windowManager.removeView(it) } }
        dismissView = null
    }

    private fun isInDismissArea(rawX: Float, rawY: Float): Boolean {
        val screen = windowManager.screenBounds()
        val centerX = screen.width() / 2f
        val centerY = screen.height() - context.dp(72).toFloat()
        return abs(rawX - centerX) < context.dp(80) && abs(rawY - centerY) < context.dp(80)
    }
}
