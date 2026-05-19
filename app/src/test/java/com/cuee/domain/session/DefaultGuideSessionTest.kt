package com.cuee.domain.session

import com.cuee.domain.command.KorailCommand
import com.cuee.domain.safety.StopReason
import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGuideSessionTest {
    @Test
    fun guidesResolvedCandidatesAndStopsAfterThreeSteps() {
        val session = DefaultGuideSession(clock = { 1L })
        session.begin(KorailCommand.FIND_RESERVATION_START)

        repeat(3) { index ->
            val result = session.next(snapshot(nodeId = "reservation-$index", text = "승차권 예매"))
            assertTrue(result.isGuiding)
            assertEquals(index + 1, result.stepCount)
            assertEquals(1, result.candidates.size)
            assertNotNull(result.instruction)
        }

        val stopped = session.next(snapshot(nodeId = "reservation-4", text = "승차권 예매"))
        assertEquals(GuideState.FAILED, stopped.state)
        assertEquals(StopReason.MAX_STEPS_REACHED, stopped.stopReason)
        assertEquals(3, stopped.stepCount)
    }

    @Test
    fun stopsForSensitiveScreen() {
        val session = DefaultGuideSession(clock = { 1L })
        session.begin(KorailCommand.SHOW_MY_TICKET)

        val result = session.next(snapshot(nodeId = "login", text = "로그인"))

        assertEquals(GuideState.SENSITIVE_PAUSE, result.state)
        assertEquals(StopReason.SENSITIVE_LOGIN, result.stopReason)
        assertEquals(SpokenPrompt.CHECK_DIRECTLY, result.instruction?.spokenPrompt)
    }

    @Test
    fun failsSafelyWhenNextRunsBeforeBegin() {
        val session = DefaultGuideSession(clock = { 1L })

        val result = session.next(snapshot(nodeId = "reservation", text = "승차권 예매"))

        assertEquals(GuideState.FAILED, result.state)
        assertEquals(StopReason.NO_TARGET, result.stopReason)
        assertEquals(0, result.stepCount)
        assertEquals(SpokenPrompt.TRY_AGAIN, result.instruction?.spokenPrompt)
    }

    @Test
    fun userCancelReturnsSessionToIdle() {
        val session = DefaultGuideSession(clock = { 1L })
        session.begin(KorailCommand.FIND_RESERVATION_START)
        assertEquals(GuideState.THINKING, session.state)

        session.stop(StopReason.USER_CANCELLED)

        assertEquals(GuideState.IDLE, session.state)
        assertEquals(0, session.stepCount)
    }

    private fun snapshot(nodeId: String, text: String): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = "com.korail.talk",
            nodes = listOf(
                ScreenNode(
                    id = nodeId,
                    text = text,
                    contentDescription = null,
                    className = "android.widget.Button",
                    packageName = "com.korail.talk",
                    bounds = Bounds(0, 0, 180, 64),
                    clickable = true,
                    enabled = true,
                    visible = true,
                    scrollable = false,
                    editable = false,
                    depth = 0,
                    parentHint = null
                )
            ),
            capturedAt = 1L
        )
    }
}
