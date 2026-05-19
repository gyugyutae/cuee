package com.cuee.domain.scoring

import com.cuee.domain.command.KorailCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KorailTargetScorerTest {
    private val scorer = KorailTargetScorer()

    @Test
    fun scoresExactClickableTargetAsStrongCandidate() {
        val candidates = scorer.score(
            snapshot(
                node(
                    id = "reservation",
                    text = "승차권 예매",
                    clickable = true,
                    className = "android.widget.Button"
                )
            ),
            KorailCommand.FIND_RESERVATION_START
        )

        assertEquals(1, candidates.size)
        assertEquals("reservation", candidates.first().nodeId)
        assertEquals(TargetType.RESERVATION_ENTRY, candidates.first().targetType)
        assertTrue(candidates.first().score >= 80)
        assertTrue(candidates.first().evidence.textMatched)
        assertTrue(candidates.first().evidence.clickable)
    }

    @Test
    fun excludesSensitiveCandidateText() {
        val candidates = scorer.score(
            snapshot(
                node(
                    id = "payment",
                    text = "결제하기",
                    clickable = true,
                    className = "android.widget.Button"
                )
            ),
            KorailCommand.FIND_RESERVATION_START
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun penalizesLongGeneralText() {
        val candidates = scorer.score(
            snapshot(
                node(
                    id = "long",
                    text = "아래 안내 내용을 끝까지 확인한 뒤 화면 중앙의 승차권 예매 메뉴를 선택하세요",
                    contentDescription = null,
                    clickable = false,
                    className = "android.widget.TextView"
                )
            ),
            KorailCommand.FIND_RESERVATION_START
        )

        assertFalse(candidates.isEmpty())
        assertTrue(candidates.first().score < 65)
    }

    private fun snapshot(vararg nodes: ScreenNode): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = "com.korail.talk",
            nodes = nodes.toList(),
            capturedAt = 1L
        )
    }

    private fun node(
        id: String,
        text: String?,
        contentDescription: String? = null,
        clickable: Boolean,
        className: String?,
        bounds: Bounds = Bounds(0, 0, 180, 64)
    ): ScreenNode {
        return ScreenNode(
            id = id,
            text = text,
            contentDescription = contentDescription,
            className = className,
            packageName = "com.korail.talk",
            bounds = bounds,
            clickable = clickable,
            enabled = true,
            visible = true,
            scrollable = false,
            editable = false,
            depth = 0,
            parentHint = null
        )
    }
}
