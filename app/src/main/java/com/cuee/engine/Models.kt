package com.cuee.engine

import android.graphics.Rect

data class TargetCandidate(
    val nodeId: String,
    val bounds: Rect,
    val score: Float = 0f
)
