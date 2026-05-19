package com.cuee.domain.scoring

interface CandidateResolver {
    fun resolve(candidates: List<TargetCandidate>): List<TargetCandidate>
}
