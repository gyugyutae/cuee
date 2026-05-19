package com.cuee.overlay

import android.content.Context
import android.view.View
import android.view.WindowManager
import com.cuee.domain.scoring.Bounds

class CandidateHighlighter(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private val borderViews = mutableListOf<View>()

    fun show(bounds: List<Bounds>) {
        hide()
        val screen = windowManager.screenBounds().toBounds()
        val thickness = context.dp(BORDER_THICKNESS_DP).coerceAtLeast(1)
        bounds
            .filter { it.isValid() }
            .take(MAX_HIGHLIGHTS)
            .forEach { drawBorder(it, screen, thickness) }
    }

    fun hide() {
        borderViews.forEach { runCatching { windowManager.removeView(it) } }
        borderViews.clear()
    }

    private fun drawBorder(bounds: Bounds, screen: Bounds, thickness: Int) {
        addStrip(
            Bounds(bounds.left, bounds.top - thickness, bounds.right, bounds.top).clampedTo(screen)
        )
        addStrip(
            Bounds(bounds.left, bounds.bottom, bounds.right, bounds.bottom + thickness).clampedTo(screen)
        )
        addStrip(
            Bounds(bounds.left - thickness, bounds.top - thickness, bounds.left, bounds.bottom + thickness).clampedTo(screen)
        )
        addStrip(
            Bounds(bounds.right, bounds.top - thickness, bounds.right + thickness, bounds.bottom + thickness).clampedTo(screen)
        )
    }

    private fun addStrip(bounds: Bounds) {
        if (!bounds.isValid()) return
        val view = View(context).apply {
            setBackgroundColor(CUE_GREEN)
            alpha = 0.95f
        }
        borderViews += view
        windowManager.addView(
            view,
            overlayParams(
                width = bounds.width,
                height = bounds.height,
                x = bounds.left,
                y = bounds.top,
                touchable = false
            )
        )
    }

    private fun Bounds.clampedTo(screen: Bounds): Bounds {
        return Bounds(
            left = left.coerceIn(screen.left, screen.right),
            top = top.coerceIn(screen.top, screen.bottom),
            right = right.coerceIn(screen.left, screen.right),
            bottom = bottom.coerceIn(screen.top, screen.bottom)
        )
    }

    private companion object {
        const val BORDER_THICKNESS_DP = 2
        const val MAX_HIGHLIGHTS = 3
    }
}
