package com.cuee.domain.scoring

data class ScreenSnapshot(
    val packageName: String,
    val nodes: List<ScreenNode>,
    val capturedAt: Long
)
