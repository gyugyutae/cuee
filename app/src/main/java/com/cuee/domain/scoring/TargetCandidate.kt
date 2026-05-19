package com.cuee.domain.scoring

import com.cuee.domain.command.KorailCommand

data class TargetCandidate(
    val nodeId: String,
    val command: KorailCommand,
    val targetType: TargetType,
    val bounds: Bounds,
    val score: Int,
    val evidence: CandidateEvidence
)

enum class TargetType {
    MY_TICKET_ENTRY,
    RESERVATION_ENTRY,
    NEXT_STEP_ENTRY
}

data class CandidateEvidence(
    val textMatched: Boolean,
    val contentDescriptionMatched: Boolean,
    val clickable: Boolean,
    val classHintMatched: Boolean,
    val positionHintMatched: Boolean
)
