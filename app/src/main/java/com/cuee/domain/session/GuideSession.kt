package com.cuee.domain.session

import com.cuee.domain.command.KorailCommand
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.ScreenSnapshot

interface GuideSession {
    val state: GuideState
    val stepCount: Int

    fun begin(command: KorailCommand)
    fun next(snapshot: ScreenSnapshot): GuideStepResult
    fun stop(reason: StopReason)
}
