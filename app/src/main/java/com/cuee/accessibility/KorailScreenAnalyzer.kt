package com.cuee.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.cuee.domain.command.KorailCommand
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.ScreenSnapshot
import com.cuee.domain.session.DefaultGuideSession
import com.cuee.domain.session.GuideSession
import com.cuee.domain.session.GuideState
import com.cuee.domain.session.GuideStepResult

class KorailScreenAnalyzer(
    private val snapshotMapper: AccessibilitySnapshotMapper = DefaultAccessibilitySnapshotMapper(),
    private val guideSession: GuideSession = DefaultGuideSession()
) {
    val state: GuideState get() = guideSession.state
    val stepCount: Int get() = guideSession.stepCount

    fun begin(command: KorailCommand) {
        guideSession.begin(command)
    }

    fun analyze(root: AccessibilityNodeInfo?): GuideStepResult {
        return guideSession.next(snapshot(root))
    }

    fun snapshot(root: AccessibilityNodeInfo?): ScreenSnapshot {
        return snapshotMapper.map(root)
    }

    fun stop(reason: StopReason) {
        guideSession.stop(reason)
    }
}
