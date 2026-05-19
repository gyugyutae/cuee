package com.cuee.domain.scoring

import com.cuee.domain.command.KorailCommand

interface TargetScorer {
    fun score(snapshot: ScreenSnapshot, command: KorailCommand): List<TargetCandidate>
}
