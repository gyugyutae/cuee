package com.cuee.domain.demo

import com.cuee.domain.scoring.Bounds

data class DemoGuideResult(
    val target: DemoTarget?,
    val message: String? = null,
    val statusText: String? = null,
    val advanceOnRender: Boolean = false,
    val autoTap: Boolean = false,
    val doneAfterRender: Boolean = false,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    val isGuiding: Boolean get() = target != null

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        const val PAYMENT_TIMEOUT_MS = 8_000L
    }
}

data class DemoTarget(
    val nodeId: String,
    val bounds: Bounds,
    val label: String
)
