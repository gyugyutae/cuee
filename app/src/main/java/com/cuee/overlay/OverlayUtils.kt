package com.cuee.overlay

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.cuee.domain.scoring.Bounds

// 굿닥 파랑 팔레트(out/ 캔버스 seed-goodoc 토큰). 브랜드 색은 버튼·큐 버튼, 누를 곳 테두리는 초록.
const val CUE_BRAND = 0xFF0462D1.toInt()
const val CUE_HIGHLIGHT = 0xFF1A8347.toInt()
const val CUE_SURFACE = 0xFFFEFEFE.toInt()
const val CUE_TEXT = 0xFF1A2128.toInt()
const val CUE_TEXT_SUB = 0xFF485360.toInt()
const val CUE_TINT = 0xFFE5EEF9.toInt()
const val CUE_LINE = 0xFFE5E9ED.toInt()
const val CUE_DANGER = 0xFFD61E24.toInt()

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun rounded(color: Int, radiusPx: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = radiusPx.toFloat()
}

fun overlayParams(
    width: Int,
    height: Int,
    x: Int = 0,
    y: Int = 0,
    gravity: Int = Gravity.TOP or Gravity.START,
    touchable: Boolean = true
): WindowManager.LayoutParams {
    val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    return WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        android.graphics.PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        this.x = x
        this.y = y
    }
}

fun WindowManager.screenBounds(): Rect {
    return if (Build.VERSION.SDK_INT >= 30) {
        currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        android.util.DisplayMetrics().also { defaultDisplay.getRealMetrics(it) }.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
    }
}

fun Rect.toBounds(): Bounds = Bounds(left, top, right, bottom)
