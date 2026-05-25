package com.cuee.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.WindowManager
import com.cuee.domain.scoring.Bounds
import com.cuee.domain.session.OverlayInstruction

class MaskOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit,
    private val layoutCalculator: OverlayLayoutCalculator = OverlayLayoutCalculator(
        candidatePaddingPx = context.dp(CANDIDATE_PADDING_DP)
    )
) {
    private val maskViews = mutableListOf<View>()
    private var closeView: View? = null
    private var lastMaskRects: List<Bounds> = emptyList()
    private var lastCloseBounds: Bounds? = null

    fun show(instruction: OverlayInstruction) {
        show(instruction.visibleHoles)
    }

    fun show(visibleHoles: List<Bounds>) {
        val screen = windowManager.screenBounds().toBounds()
        val layout = layoutCalculator.calculate(screen, visibleHoles)
        if (layout.maskRects == lastMaskRects && maskViews.isNotEmpty()) {
            showClose(screen, layout.holes)
            return
        }

        maskViews.forEach { runCatching { windowManager.removeView(it) } }
        maskViews.clear()
        lastMaskRects = layout.maskRects
        layout.maskRects
            .filter { it.isValid() }
            .forEach { rect ->
                val view = View(context).apply { setBackgroundColor(Color.WHITE) }
                maskViews += view
                windowManager.addView(
                    view,
                    overlayParams(
                        width = rect.width,
                        height = rect.height,
                        x = rect.left,
                        y = rect.top,
                        touchable = false
                    )
                )
            }

        showClose(screen, layout.holes)
    }

    fun hide() {
        maskViews.forEach { runCatching { windowManager.removeView(it) } }
        maskViews.clear()
        lastMaskRects = emptyList()
        closeView?.let { runCatching { windowManager.removeView(it) } }
        closeView = null
        lastCloseBounds = null
    }

    private fun showClose(screen: Bounds, holes: List<Bounds>) {
        val size = context.dp(CLOSE_SIZE_DP)
        val margin = context.dp(CLOSE_MARGIN_DP)
        val candidates = listOf(
            Bounds(margin, margin, margin + size, margin + size),
            Bounds(screen.right - margin - size, margin, screen.right - margin, margin + size),
            Bounds(margin, screen.bottom - margin - size, margin + size, screen.bottom - margin),
            Bounds(screen.right - margin - size, screen.bottom - margin - size, screen.right - margin, screen.bottom - margin)
        )

        val closeBounds = candidates.firstOrNull { candidate ->
            candidate.isValid() && candidate.within(screen) && holes.none { it.intersects(candidate) }
        } ?: return
        if (closeBounds == lastCloseBounds && closeView != null) return

        closeView?.let { runCatching { windowManager.removeView(it) } }
        val view = CloseButtonView(context).apply {
            setOnClickListener { onClose() }
            elevation = context.dp(12).toFloat()
        }
        closeView = view
        lastCloseBounds = closeBounds
        windowManager.addView(
            view,
            overlayParams(
                width = closeBounds.width,
                height = closeBounds.height,
                x = closeBounds.left,
                y = closeBounds.top,
                touchable = true
            )
        )
    }

    private fun Bounds.within(screen: Bounds): Boolean {
        return left >= screen.left && top >= screen.top && right <= screen.right && bottom <= screen.bottom
    }

    private class CloseButtonView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeWidth = context.dp(2).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = minOf(width, height) / 2f
            canvas.drawCircle(width / 2f, height / 2f, radius, fillPaint)
            val inset = width * 0.34f
            canvas.drawLine(inset, inset, width - inset, height - inset, strokePaint)
            canvas.drawLine(width - inset, inset, inset, height - inset, strokePaint)
        }
    }

    private companion object {
        const val CANDIDATE_PADDING_DP = 72
        const val CLOSE_SIZE_DP = 48
        const val CLOSE_MARGIN_DP = 16
    }
}
