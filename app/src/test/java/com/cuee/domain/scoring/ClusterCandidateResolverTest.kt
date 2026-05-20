package com.cuee.domain.scoring

import com.cuee.domain.command.KorailCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterCandidateResolverTest {
    private val resolver = ClusterCandidateResolver()

    @Test
    fun filtersBelowWeakThresholdAndLimitsCloseCandidatesToThree() {
        val resolved = resolver.resolve(
            listOf(
                candidate("a", Bounds(0, 0, 100, 60), 95),
                candidate("b", Bounds(120, 0, 220, 60), 90),
                candidate("c", Bounds(240, 0, 340, 60), 85),
                candidate("d", Bounds(360, 0, 460, 60), 70),
                candidate("low", Bounds(480, 0, 580, 60), 64)
            )
        )

        assertEquals(listOf("a", "b", "c"), resolved.map { it.nodeId })
    }

    @Test
    fun clustersOverlappingCandidatesAndKeepsHighestScore() {
        val resolved = resolver.resolve(
            listOf(
                candidate("lower", Bounds(0, 0, 100, 100), 80),
                candidate("higher", Bounds(10, 10, 110, 110), 90),
                candidate("separate", Bounds(200, 0, 300, 100), 70)
            )
        )

        assertEquals(1, resolved.size)
        assertTrue(resolved.any { it.nodeId == "higher" })
        assertTrue(resolved.none { it.nodeId == "lower" })
    }

    private fun candidate(id: String, bounds: Bounds, score: Int): TargetCandidate {
        return TargetCandidate(
            nodeId = id,
            command = KorailCommand.FIND_RESERVATION_START,
            targetType = TargetType.RESERVATION_ENTRY,
            bounds = bounds,
            score = score,
            evidence = CandidateEvidence(
                textMatched = true,
                contentDescriptionMatched = false,
                clickable = true,
                classHintMatched = true,
                positionHintMatched = true
            )
        )
    }
}
