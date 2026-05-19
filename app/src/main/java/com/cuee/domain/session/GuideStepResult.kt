package com.cuee.domain.session

import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.TargetCandidate

data class GuideStepResult(
    val state: GuideState,
    val instruction: OverlayInstruction?,
    val stopReason: StopReason?,
    val stepCount: Int,
    val candidates: List<TargetCandidate> = emptyList()
) {
    val isGuiding: Boolean get() = state == GuideState.GUIDING && instruction != null
}
