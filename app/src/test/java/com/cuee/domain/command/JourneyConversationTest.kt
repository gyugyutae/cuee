package com.cuee.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JourneyConversationTest {
    private fun conversation() = JourneyConversation { LocalDate.of(2026, 10, 3) }

    @Test fun missingConditionsAreAskedAndNeverDefaulted() {
        val c = conversation()
        assertNull(c.accept("부산에서 서울"))
        assertEquals("부산", c.departure)
        assertEquals("서울", c.arrival)
        assertNull(c.request())
        assertTrue(c.question.contains("언제"))
        c.accept("내일")
        assertTrue(c.question.contains("몇 시"))
        c.accept("오후 3시")
        assertTrue(c.question.contains("몇 명"))
        c.accept("어른 두 명 어린이 한 명")
        val request = c.request()!!
        assertEquals(15, request.earliestHour)
        assertEquals(2, request.passengers.adults)
        assertEquals(1, request.passengers.children)
    }

    @Test fun singleUtteranceAndDifferentRoutes() {
        listOf("진주" to "서울", "대전" to "부산", "구로" to "영등포").forEach { (from, to) ->
            val c = conversation()
            assertNull(c.accept("내일 ${from}에서 ${to} 오후 6시 어른 3명"))
            assertEquals(from, c.request()!!.departure)
            assertEquals(to, c.request()!!.arrival)
            assertEquals(18, c.request()!!.earliestHour)
        }
    }

    @Test fun correctionsPreserveOtherConditionsAndRejectInvalidInput() {
        val c = conversation()
        c.accept("부산에서 대전 내일 오전 12시 어른 2명")
        assertEquals(0, c.hour)
        c.accept("어린이 1명")
        assertEquals(2, c.passengers!!.adults)
        assertEquals(1, c.passengers!!.children)
        assertNotNull(c.accept("2월 30일"))
        assertNotNull(c.accept("어른 10명"))
        assertNotNull(c.accept("오후 25시"))
        assertNotNull(c.accept("왕복"))
        assertNotNull(c.accept("부산에서 부산"))
        assertEquals("대전", c.arrival)
    }

    @Test fun individualStationsAndAllPassengerTypes() {
        val c = conversation()
        c.accept("서울역"); c.accept("부산역"); c.accept("10월 15일")
        c.accept("시간 상관없어"); c.accept("어른 1명 어린이 2명 유아 1명 경로 1명")
        assertEquals("서울", c.request()!!.departure)
        assertEquals(1, c.request()!!.passengers.seniors)
    }

    @Test fun spokenHoursAreSupportedAndMinutesAreNotSilentlyIgnored() {
        val c = conversation()
        assertNull(c.accept("오후 세 시"))
        assertEquals(15, c.hour)
        assertNotNull(c.accept("오후 3시 30분"))
        assertEquals(15, c.hour)
    }

    @Test fun particlesAreNotStoredAsPartOfStationNames() {
        val c = conversation()
        assertTrue(c.accept("부산에서 서울로 내일 오후 3시 어른 2명")!!.contains("역 이름인지 조사인지"))
        assertNull(c.request())
        assertNull(c.departure) // rejected utterances are transactional
        assertNull(c.accept("부산에서 서울역 내일 오후 3시 어른 2명"))
        assertEquals("서울", c.arrival)
        assertEquals("부산", c.departure)
        val labeledAmbiguousDestination = conversation()
        assertTrue(labeledAmbiguousDestination.accept("부산 출발, 서울로 도착 내일 오후 3시 어른 2명")!!.contains("역 이름인지 조사인지"))
        assertNull(labeledAmbiguousDestination.request())
    }

    @Test fun commonDestinationParticlesAreNotStoredAsPartOfStationNames() {
        listOf(
            "부산역에서 대전역까지 내일 오전 9시 어른 2명" to ("부산" to "대전"),
            "대구에서 광주송정역으로 2026-10-04 오전 9시 어른 1명" to ("대구" to "광주송정")
        ).forEach { (utterance, route) ->
            val c = conversation()
            assertNull(utterance, c.accept(utterance))
            assertEquals(route.first, c.departure)
            assertEquals(route.second, c.arrival)
        }
    }

    @Test fun labeledStationsAndSpokenPassengerCountsWorkWithoutCounterWords() {
        val c = conversation()
        assertNull(c.accept("내일 부산역 출발, 서울역 도착, 오전 아홉 시, 어른 두 분 어린이 하나"))
        assertEquals("부산", c.departure)
        assertEquals("서울", c.arrival)
        assertEquals(LocalDate.of(2026, 10, 4), c.date)
        assertEquals(9, c.hour)
        assertEquals(2, c.passengers!!.adults)
        assertEquals(1, c.passengers!!.children)
    }

    @Test fun unrecognizedConditionsBlockOtherwiseCompleteRequests() {
        val ordinaryKorean = conversation()
        assertNull(ordinaryKorean.accept("부산역에서 대전역까지 가는 기차표를 예약해 주세요. 내일 오전 9시 이후 어른 두 명."))
        assertEquals("부산", ordinaryKorean.departure)
        assertEquals("대전", ordinaryKorean.arrival)

        val unknownPreference = conversation()
        assertTrue(unknownPreference.accept("부산에서 대전까지 내일 오전 9시 어른 2명 가장 저렴한 열차")!!.contains("해석하지 못한 조건"))
        assertNull(unknownPreference.request())
        assertNull(unknownPreference.departure) // an unknown condition cannot be silently discarded
    }

    @Test fun malformedPassengerCountsAndUnsupportedConditionsCannotBeIgnored() {
        val c = conversation()
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 어른 2명 어린이 -1명")!!.contains("반영하지 않았어요"))
        assertNull(c.request())
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 어른 2명 어린이 999999999999999999명")!!.contains("승객 수"))
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 성인 2명 장애인 1명")!!.contains("제외하지 않고"))
        assertNull(c.accept("부산에서 대전 내일 오후 3시 어른 2명 어린이 1명"))
        assertNull(c.request())
        assertTrue(c.accept("이전 입력 취소")!!.contains("취소"))
        assertNotNull(c.request()) // cancelling the rejected extra condition permits an explicit review of prior supported inputs
        c.accept("부산에서 대전 내일 오후 3시 어른 2명 어린이 1명")
        assertEquals(3, c.request()!!.passengers.adults + c.request()!!.passengers.children)
    }

    @Test fun ambiguousHoursDatesAndPassengerTypesRequireCorrection() {
        val c = conversation()
        assertTrue(c.accept("부산에서 대전 3시 어른 2명")!!.contains("오전인지 오후인지"))
        assertNull(c.request())
        assertTrue(c.accept("부산에서 대전 다음주 월요일 아침 어른 2명")!!.contains("반영하지 않았어요"))
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 30분 어른 2명")!!.contains("시간 단위"))
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 3명")!!.contains("반영하지 않았어요"))
        c.accept("이전 입력 취소")
        assertNull(c.accept("부산에서 대전 다음 주 월요일 오후 3시 어른 2명"))
        assertEquals(LocalDate.of(2026, 10, 5), c.date)
    }

    @Test fun isoDatesAndManualSeatPreferencesAreRetainedForConfirmation() {
        val c = conversation()
        assertNull(c.accept("부산에서 대전 2026-10-04 오후 3시 어른 2명 창가 순방향"))
        assertEquals(setOf("창가", "순방향"), c.request()!!.selectionPreferences)
        assertTrue(c.request()!!.summary.contains("직접 선택"))
    }

    @Test fun colloquialPeriodIsNormalizedAndUnrecognizedPassengerTypesBlockSearch() {
        val c = conversation()
        assertNull(c.accept("부산에서 대전 내일 저녁 7시 성인 2명"))
        assertEquals(19, c.request()!!.earliestHour)
        val unknownPassenger = conversation()
        assertTrue(unknownPassenger.accept("부산에서 대전 내일 오후 3시 어른 2명 초등학생 1명")!!.contains("반영하지 않았어요"))
        assertNull(unknownPassenger.request())
        val reorderedCount = conversation()
        assertTrue(reorderedCount.accept("부산에서 대전 내일 오후 3시 3명 성인")!!.contains("반영하지 않았어요"))
        val unfamiliarType = conversation()
        assertTrue(unfamiliarType.accept("부산에서 대전 내일 오후 3시 어른 2명 소아 1명")!!.contains("반영하지 않았어요"))
        val halfHour = conversation()
        assertTrue(halfHour.accept("부산에서 대전 내일 오후 3시 반 어른 2명")!!.contains("시간 단위"))
    }

    @Test fun dottedSlashDatesAndUnappliedSeatArrangementsAreExplicit() {
        for (date in listOf("2026.10.04", "2026/10/04")) {
            val c = conversation()
            assertNull(c.accept("부산에서 대전 $date 오후 3시 어른 2명"))
            assertEquals(LocalDate.of(2026, 10, 4), c.date)
        }
        val c = conversation()
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 어른 2명 아이와 나란히")!!.contains("좌석 배치 요청"))
        assertNull(c.request())
    }

    @Test fun contradictoryTimeAndSeatPreferencesMustBeResolved() {
        val c = conversation()
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 시간 상관없어 어른 2명")!!.contains("둘 중 하나"))
        assertNull(c.request())
        c.accept("이전 입력 취소")
        assertTrue(c.accept("부산에서 대전 내일 오후 3시 어른 2명 창가 통로")!!.contains("서로 다른 좌석"))
        assertNull(c.request())
        c.accept("이전 입력 취소")
        assertNull(c.accept("부산에서 대전 내일 오후 3시 어른 2명 창가"))
        assertNull(c.accept("통로"))
        assertEquals(setOf("통로"), c.request()!!.selectionPreferences)
    }

    @Test fun approximateOrMultipleTripInputsAreNotSilentlyAccepted() {
        val c = conversation()
        assertTrue(c.accept("부산에서 서울 다음에 대전에서 광명 내일 오후 3시 어른 2명")!!.contains("한 구간"))
        assertTrue(c.accept("부산에서 대전 내일 오후 3시쯤 어른 2명")!!.contains("대략적인 시각"))
        assertNull(c.accept("부산에서 대전 내일 아무 때나 어른 2명"))
        assertEquals(0, c.hour)
        val fuzzy = conversation()
        assertTrue(fuzzy.accept("부산에서 대전 내일 오후 3시 어른 약 2명")!!.contains("확정된 인원"))
    }

    @Test fun unsupportedTransferAndMixedSeatConditionsAreNotSilentlyDropped() {
        val transfer = conversation()
        assertTrue(transfer.accept("부산에서 대전을 거쳐 서울 내일 오후 3시 어른 2명")!!.contains("지원하지 않는 조건"))
        assertNull(transfer.request())
        assertNull(transfer.departure) // unsupported multi-stop request is transactional

        val seat = conversation()
        assertTrue(seat.accept("부산에서 대전 내일 오후 3시 어른 2명 창가 콘센트")!!.contains("좌석 조건을 모두 반영"))
        assertNull(seat.request()) // the supported window preference cannot mask the unsupported outlet request
        assertNull(seat.departure)
        seat.accept("이전 입력 취소")
        assertNull(seat.accept("부산에서 대전 내일 오후 3시 어른 2명 창가"))
        assertEquals(setOf("창가"), seat.request()!!.selectionPreferences)
    }

    @Test fun colloquialRoundTripsTransfersAndDiscountProductNamesAreNotDropped() {
        val unsupportedRequests = listOf(
            "부산에서 대전 갔다가 부산으로 돌아오는 표 내일 오후 3시 어른 2명",
            "부산에서 대전 갔다와서 부산으로 돌아오는 표 내일 오후 3시 어른 2명",
            "부산에서 대전 갈아타고 서울 내일 오후 3시 어른 2명",
            "부산에서 대전 내일 오후 3시 어른 2명 N카드",
            "부산에서 대전 내일 오후 3시 어른 2명 힘내라 청춘",
            "부산에서 대전 내일 오후 3시 어른 2명 청소년 드림",
            "부산에서 대전 내일 오후 3시 어른 2명 맘편한 코레일",
            "부산에서 대전 내일 오후 3시 어른 2명 다자녀 행복",
            "부산에서 대전 내일 오후 3시 어른 2명 내일로 패스",
            "부산에서 대전 내일 오후 3시 어른 2명 내일로 좌석 지정권",
            "부산에서 대전 내일 오후 3시 어른 2명 정기권",
            "부산에서 대전 내일 오후 3시 어른 2명 30% 할인"
        )
        unsupportedRequests.forEach { utterance ->
            val c = conversation()
            assertTrue(utterance, c.accept(utterance)!!.contains("지원하지 않는 조건"))
            assertNull(c.request())
            assertNull(c.departure) // no supported part of an unsupported request is applied
        }

        val oneWayReturnLeg = conversation()
        assertNull(oneWayReturnLeg.accept("서울에서 부산으로 돌아오는 표 내일 오후 3시 어른 2명"))
        assertEquals("서울", oneWayReturnLeg.departure)
        assertEquals("부산", oneWayReturnLeg.arrival)
    }

    @Test fun structuredDraftRestoresWithoutRetainingTheSpokenInput() {
        val c = conversation()
        c.accept("부산에서 대전 내일 오후 3시 어른 2명 창가")
        val restored = JourneyConversation.restore(c.snapshot()) { LocalDate.of(2026, 10, 3) }
        assertEquals(c.request(), restored.request())
        assertEquals(setOf("창가"), restored.selectionPreferences)
    }
}
