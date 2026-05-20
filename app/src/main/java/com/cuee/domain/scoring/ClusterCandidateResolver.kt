package com.cuee.domain.scoring

class ClusterCandidateResolver(
    private val minimumScore: Int = MINIMUM_SCORE,
    private val maxCandidates: Int = MAX_CANDIDATES
) : CandidateResolver {
    override fun resolve(candidates: List<TargetCandidate>): List<TargetCandidate> {
        val eligible = candidates
            .filter { it.score >= minimumScore && it.bounds.isValid() }
            .sortedWith(compareByDescending<TargetCandidate> { it.score }.thenBy { it.nodeId })

        val visited = BooleanArray(eligible.size)
        val resolved = mutableListOf<TargetCandidate>()

        for (index in eligible.indices) {
            if (visited[index]) continue
            val cluster = mutableListOf<TargetCandidate>()
            val queue = ArrayDeque<Int>()
            queue.add(index)
            visited[index] = true

            while (queue.isNotEmpty()) {
                val currentIndex = queue.removeFirst()
                val current = eligible[currentIndex]
                cluster.add(current)
                for (nextIndex in eligible.indices) {
                    if (visited[nextIndex]) continue
                    if (current.bounds.overlapRatio(eligible[nextIndex].bounds) > 0.0) {
                        visited[nextIndex] = true
                        queue.add(nextIndex)
                    }
                }
            }

            resolved += cluster.maxWith(
                compareBy<TargetCandidate> { it.score }
                    .thenBy { it.bounds.area }
                    .thenByDescending { it.nodeId }
            )
        }

        val ranked = resolved
            .sortedWith(compareByDescending<TargetCandidate> { it.score }.thenBy { it.nodeId })

        val best = ranked.firstOrNull() ?: return emptyList()
        val closeCandidates = ranked.filter { best.score - it.score <= SCORE_TIE_MARGIN }
        return if (closeCandidates.size == 1) {
            listOf(best)
        } else {
            closeCandidates.take(maxCandidates)
        }
    }

    private companion object {
        const val MINIMUM_SCORE = 65
        const val MAX_CANDIDATES = 3
        const val SCORE_TIE_MARGIN = 15
    }
}
