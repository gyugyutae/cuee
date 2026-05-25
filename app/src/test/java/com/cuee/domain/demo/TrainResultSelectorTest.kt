package com.cuee.domain.demo

import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainResultSelectorTest {
    private val selector = TrainResultSelector(earliestHour = 9)

    @Test
    fun skipsUnavailableKtxAndSelectsNextAvailableStandardSeat() {
        val result = selector.select(
            snapshot(
                row(100, "KTX", "09:10", "일반실", "매진", "예매"),
                row(300, "KTX", "10:00", "일반실", "", "예매")
            )
        )

        assertEquals("reserve-300", result?.target?.nodeId)
    }

    @Test
    fun rejectsBeforeNineOClockCandidate() {
        val result = selector.select(
            snapshot(
                row(100, "KTX", "08:40", "일반실", "", "예매")
            )
        )

        assertNull(result)
    }

    @Test
    fun excludesReservationLinks() {
        val result = selector.select(
            snapshot(
                row(100, "SRT", "10:00", "일반실", "예약링크", "예약링크")
            )
        )

        assertNull(result)
    }

    @Test
    fun prefersSeoulKtxPremiumOverSuseoSrtStandard() {
        val result = selector.select(
            snapshot(
                row(100, "SRT", "10:00", "일반실", "", "예매", arrival = "수서"),
                row(300, "KTX", "12:00", "특실", "", "예매", arrival = "서울")
            )
        )

        assertEquals("reserve-300", result?.target?.nodeId)
    }

    private fun row(
        top: Int,
        train: String,
        time: String,
        seat: String,
        availability: String,
        button: String,
        arrival: String = "서울"
    ): List<ScreenNode> {
        return listOf(
            node("train-$top", train, top, 0),
            node("time-$top", time, top, 120),
            node("arrival-$top", arrival, top, 180),
            node("seat-$top", seat, top, 240),
            node("availability-$top", availability, top, 360),
            node("reserve-$top", listOf(seat, availability, button).joinToString(" "), top, 480, clickable = true)
        )
    }

    private fun snapshot(vararg rows: List<ScreenNode>): ScreenSnapshot {
        return ScreenSnapshot("com.korail.talk", rows.flatMap { it }, 1L)
    }

    private fun node(
        id: String,
        text: String,
        top: Int,
        left: Int,
        clickable: Boolean = false
    ): ScreenNode {
        return ScreenNode(
            id = id,
            text = text.takeIf { it.isNotBlank() },
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = "com.korail.talk",
            bounds = Bounds(left, top, left + 100, top + 50),
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
