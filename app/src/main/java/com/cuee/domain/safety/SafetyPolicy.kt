package com.cuee.domain.safety

import com.cuee.domain.scoring.ScreenSnapshot

interface SafetyPolicy {
    fun evaluate(snapshot: ScreenSnapshot): SafetyDecision
}
