package com.cuee.overlay

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.cuee.domain.scoring.Bounds

const val CUE_GREEN = 0xFF2F9E44.toInt()

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
