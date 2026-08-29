package com.cuee.domain.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class DemoSessionTest {
    @Test
    fun advancesThroughDemoSteps() {
        val session = DemoSession(clock = { 1L })

        assertEquals(DemoStep.SELECT_DEPARTURE_FIELD, session.step)
        session.advance()
        assertEquals(DemoStep.INPUT_DEPARTURE, session.step)
        session.advance()
        assertEquals(DemoStep.SELECT_DEPARTURE_RESULT, session.step)
    }

    @Test
    fun stopsAtDone() {
        val session = DemoSession(clock = { 1L })

        session.stop()

        assertEquals(DemoStep.DONE, session.step)
    }

    @Test
    fun backFromStationSearchReturnsToDepartureField() {
        val session = DemoSession(initialStep = DemoStep.SELECT_DEPARTURE_RESULT, clock = { 1L })

        session.goBackToPreviousUserStep()

        assertEquals(DemoStep.SELECT_DEPARTURE_FIELD, session.step)
    }

    @Test
    fun backFromTimeReturnsToDateField() {
        val session = DemoSession(initialStep = DemoStep.SELECT_TIME, clock = { 1L })

        session.goBackToPreviousUserStep()

        assertEquals(DemoStep.SELECT_DATE_FIELD, session.step)
    }
}
