package com.cuee.domain.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoStepInstructionTest {

    @Test
    fun everyStepHasNonBlankInstruction() {
        // 모든 단계는 사용자에게 읽어 줄 안내 문구를 반드시 가져야 한다.
        DemoStep.entries.forEach { step ->
            assertTrue("빈 안내 문구: $step", step.simpleInstruction().isNotBlank())
        }
    }

    @Test
    fun paymentStepAnnouncesSafetyStop() {
        // 결제 진입 단계는 반드시 "직접 확인 + 안내 멈춤"을 알려야 한다 (안전 원칙).
        val message = DemoStep.PAYMENT_ENTRY.simpleInstruction()
        assertTrue(message.contains("직접"))
        assertTrue(message.contains("멈"))
    }

    @Test
    fun firstStepGuidesDepartureField() {
        assertEquals("1단계. 출발역을 눌러 주세요.", DemoStep.SELECT_DEPARTURE_FIELD.simpleInstruction())
    }
}
