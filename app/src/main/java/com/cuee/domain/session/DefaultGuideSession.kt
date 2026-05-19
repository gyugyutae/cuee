package com.cuee.domain.session

import com.cuee.domain.command.KorailCommand
import com.cuee.domain.safety.DefaultSafetyPolicy
import com.cuee.domain.safety.SafetyPolicy
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.CandidateResolver
import com.cuee.domain.scoring.ClusterCandidateResolver
import com.cuee.domain.scoring.KorailTargetScorer
import com.cuee.domain.scoring.ScreenSnapshot
import com.cuee.domain.scoring.TargetCandidate
import com.cuee.domain.scoring.TargetScorer

class DefaultGuideSession(
    private val safetyPolicy: SafetyPolicy = DefaultSafetyPolicy(),
    private val targetScorer: TargetScorer = KorailTargetScorer(),
    private val candidateResolver: CandidateResolver = ClusterCandidateResolver(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxSteps: Int = MAX_STEPS
) : GuideSession {
    override var state: GuideState = GuideState.IDLE
        private set

    override var stepCount: Int = 0
        private set

    private var command: KorailCommand? = null
    private var startedAt: Long = 0L
    private var lastStepAt: Long = 0L

    override fun begin(command: KorailCommand) {
        this.command = command
        stepCount = 0
        startedAt = clock()
        lastStepAt = startedAt
        state = GuideState.THINKING
    }

    override fun next(snapshot: ScreenSnapshot): GuideStepResult {
        val activeCommand = command ?: return fail(StopReason.NO_TARGET, SpokenPrompt.TRY_AGAIN)

        if (stepCount >= maxSteps) {
            return fail(StopReason.MAX_STEPS_REACHED, SpokenPrompt.CHECK_DIRECTLY)
        }

        val safetyDecision = safetyPolicy.evaluate(snapshot)
        if (!safetyDecision.allowed) {
            return stopForSafety(safetyDecision.reason ?: StopReason.NO_TARGET)
        }

        val scored = targetScorer.score(snapshot, activeCommand)
        val resolved = candidateResolver.resolve(scored)
        if (resolved.isEmpty()) {
            val reason = if (scored.isEmpty()) StopReason.NO_TARGET else StopReason.LOW_CONFIDENCE
            return fail(reason, SpokenPrompt.TRY_AGAIN)
        }
        if (resolved.hasAnyOverlap()) {
            return fail(StopReason.OVERLAPPING_CANDIDATES, SpokenPrompt.TRY_AGAIN)
        }

        stepCount += 1
        lastStepAt = clock()
        state = GuideState.GUIDING
        return GuideStepResult(
            state = state,
            instruction = OverlayInstruction(
                visibleHoles = resolved.map { it.bounds },
                highlightedBounds = resolved.map { it.bounds },
                spokenPrompt = SpokenPrompt.PLEASE_TAP
            ),
            stopReason = null,
            stepCount = stepCount,
            candidates = resolved
        )
    }

    override fun stop(reason: StopReason) {
        state = if (reason == StopReason.USER_CANCELLED) GuideState.IDLE else GuideState.FAILED
        command = null
    }

    private fun stopForSafety(reason: StopReason): GuideStepResult {
        state = when (reason) {
            StopReason.SENSITIVE_LOGIN,
            StopReason.SENSITIVE_PERSONAL_INFO,
            StopReason.SENSITIVE_PAYMENT,
            StopReason.SENSITIVE_AUTH_CODE,
            StopReason.SENSITIVE_CONFIRMATION -> GuideState.SENSITIVE_PAUSE
            else -> GuideState.FAILED
        }
        return GuideStepResult(
            state = state,
            instruction = OverlayInstruction(
                visibleHoles = emptyList(),
                highlightedBounds = emptyList(),
                spokenPrompt = SpokenPrompt.CHECK_DIRECTLY
            ),
            stopReason = reason,
            stepCount = stepCount
        )
    }

    private fun fail(reason: StopReason, prompt: SpokenPrompt): GuideStepResult {
        state = GuideState.FAILED
        return GuideStepResult(
            state = state,
            instruction = OverlayInstruction(
                visibleHoles = emptyList(),
                highlightedBounds = emptyList(),
                spokenPrompt = prompt
            ),
            stopReason = reason,
            stepCount = stepCount
        )
    }

    private fun List<TargetCandidate>.hasAnyOverlap(): Boolean {
        for (leftIndex in indices) {
            for (rightIndex in leftIndex + 1 until size) {
                if (this[leftIndex].bounds.intersects(this[rightIndex].bounds)) return true
            }
        }
        return false
    }

    private companion object {
        const val MAX_STEPS = 3
    }
}
