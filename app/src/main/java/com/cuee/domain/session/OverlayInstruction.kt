package com.cuee.domain.session

import com.cuee.domain.scoring.Bounds

data class OverlayInstruction(
    val visibleHoles: List<Bounds>,
    val highlightedBounds: List<Bounds>,
    val timeoutMs: Long = 12_000L,
    val spokenPrompt: SpokenPrompt
)

enum class SpokenPrompt {
    PLEASE_TAP,
    TRY_AGAIN,
    CHECK_DIRECTLY
}
