package com.cuee.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cuee.overlay.CUE_DANGER
import com.cuee.overlay.CUE_BRAND
import com.cuee.overlay.CUE_LINE
import com.cuee.overlay.CUE_SURFACE
import com.cuee.overlay.CUE_TEXT
import com.cuee.overlay.CUE_TEXT_SUB
import com.cuee.overlay.CUE_TINT
import com.cuee.overlay.dp
import com.cuee.overlay.rounded

/** 화면 공용 스타일: 회색 바탕 + 흰 블록, 큰 제목, 56dp 버튼 (out/ 캔버스의 굿닥 파랑 팔레트). */
const val CUE_BG = 0xFFF1F3F6.toInt()

enum class ButtonKind { PRIMARY, SECONDARY, QUIET, DANGER }

fun Activity.cuePage(content: LinearLayout.() -> Unit): ScrollView {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(40), 0, dp(32))
        content()
    }
    return ScrollView(this).apply {
        setBackgroundColor(CUE_BG)
        isFillViewport = true
        addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** 큰 머리글: 화면의 핵심 한 문장. */
fun LinearLayout.hero(textValue: String) {
    addView(cueText(textValue, 28f, CUE_TEXT, bold = true).apply { setLineSpacing(0f, 1.3f) }, matchWrap().apply {
        marginStart = context.dp(24)
        marginEnd = context.dp(24)
        bottomMargin = context.dp(20)
    })
}

/** 흰 블록 하나 = 한 가지 일. */
fun LinearLayout.block(content: LinearLayout.() -> Unit) {
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(24), context.dp(24), context.dp(24), context.dp(16))
        background = rounded(CUE_SURFACE, context.dp(6))
        content()
    }, matchWrap().apply {
        marginStart = context.dp(16)
        marginEnd = context.dp(16)
        bottomMargin = context.dp(12)
    })
}

fun LinearLayout.section(textValue: String) {
    addView(cueText(textValue, 20f, CUE_TEXT, bold = true), matchWrap().apply {
        topMargin = context.dp(8)
        bottomMargin = context.dp(6)
    })
}

fun LinearLayout.body(textValue: String) {
    addView(cueText(textValue, 18f, CUE_TEXT), matchWrap().apply { bottomMargin = context.dp(12) })
}

/** 부가 정보 한 줄(안심 문구·예시). */
fun LinearLayout.note(textValue: String) {
    addView(cueText(textValue, 16f, CUE_TEXT_SUB), matchWrap().apply { bottomMargin = context.dp(12) })
}

fun LinearLayout.cueButton(textValue: String, kind: ButtonKind = ButtonKind.SECONDARY, onClick: () -> Unit) {
    val radius = context.dp(6)
    val fill = when (kind) {
        ButtonKind.PRIMARY -> CUE_BRAND
        ButtonKind.SECONDARY -> CUE_TINT
        ButtonKind.QUIET, ButtonKind.DANGER -> CUE_SURFACE
    }
    val label = when (kind) {
        ButtonKind.PRIMARY -> CUE_SURFACE
        ButtonKind.SECONDARY -> CUE_BRAND
        ButtonKind.QUIET -> CUE_TEXT_SUB
        ButtonKind.DANGER -> CUE_DANGER
    }
    addView(Button(context).apply {
        text = textValue
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isAllCaps = false
        minHeight = context.dp(56)
        stateListAnimator = null
        setTextColor(label)
        background = RippleDrawable(ColorStateList.valueOf(CUE_LINE), rounded(fill, radius), rounded(fill, radius))
        setOnClickListener { onClick() }
    }, matchWrap().apply { bottomMargin = context.dp(8) })
}

fun LinearLayout.cueInput(hintValue: String): EditText = EditText(context).apply {
    hint = hintValue
    textSize = 18f
    setTextColor(CUE_TEXT)
    setHintTextColor(CUE_TEXT_SUB)
    isSaveEnabled = false
    minHeight = context.dp(56)
    setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
    background = GradientDrawable().apply {
        setColor(CUE_SURFACE)
        cornerRadius = context.dp(6).toFloat()
        setStroke(context.dp(1), CUE_TEXT_SUB)
    }
}.also { addView(it, matchWrap().apply { bottomMargin = context.dp(12) }) }

private fun LinearLayout.cueText(textValue: String, size: Float, color: Int, bold: Boolean = false) =
    TextView(context).apply {
        text = textValue
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(color)
        setLineSpacing(0f, 1.3f)
    }

private fun matchWrap() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT
)
