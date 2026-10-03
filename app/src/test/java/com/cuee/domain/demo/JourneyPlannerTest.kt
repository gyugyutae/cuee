package com.cuee.domain.demo

import com.cuee.domain.command.JourneyRequest
import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class JourneyPlannerTest {
    private val request = JourneyRequest("부산", "대전", LocalDate.of(2026, 10, 4), 15, PassengerPlan(2, 1))
    private fun node(id: String, description: String? = null, text: String? = null, top: Int = 100, left: Int = 0) = ScreenNode(
        id, text, description, "android.view.View", "com.korail.talk", Bounds(left, top, left + 100, top + 50),
        false, true, true, false, false, 1, null)
    private fun snapshot(vararg nodes: ScreenNode) = ScreenSnapshot("com.korail.talk", nodes.toList(), 0)
    private fun home(date: String = "가는날, 2026년 10월 4일 (일) 15:00, 버튼", people: String = "인원, 어른 2명, 어린이 1명, 버튼") = snapshot(
        node("departure", "출발역 부산 선택됨"), node("arrival", "도착역 대전 선택됨", top = 200),
        node("date", date, top = 300), node("people", people, top = 400), node("search", text = "열차 조회", top = 500))
    private fun verifySearchHome(planner: JourneyPlanner) {
        assertEquals("search", planner.plan(home()).target?.nodeId)
    }

    @Test fun homeConditionsMustMatchBeforeSearch() {
        val planner = JourneyPlanner(request)
        assertEquals("date", planner.plan(home(date = "가는날, 2026년 10월 3일 15:00")).target!!.nodeId)
        assertEquals("people", planner.plan(home(people = "인원, 어른 1명, 버튼")).target!!.nodeId)
        assertEquals("search", planner.plan(home()).target!!.nodeId)
    }

    @Test fun reversedRouteDoesNotCountAsApplied() {
        val planner = JourneyPlanner(request)
        val result = planner.plan(snapshot(node("departure", "출발역 대전 선택됨"),
            node("arrival", "도착역 부산 선택됨", top = 200), node("search", text = "열차 조회", top = 500)))
        assertEquals("departure", result.target!!.nodeId)
    }

    @Test fun roleLabeledStationValuesCountEvenWithoutSelectedSuffix() {
        val result = JourneyPlanner(request).plan(snapshot(
            node("departure", "출발역 부산"), node("arrival", "도착역 대전", top = 200),
            node("date", "가는날, 2026년 10월 4일, 15:00", top = 300),
            node("people", "인원, 어른 2명, 어린이 1명", top = 400),
            node("search", text = "열차 조회", top = 500)))
        assertEquals("search", result.target!!.nodeId)
    }

    @Test fun pickerUsesSelectedSummaryWhenComposeDoesNotExposeSelectedFlags() {
        val planner = JourneyPlanner(request)
        val result = planner.plan(snapshot(node("title", text = "가는날 선택"),
            node("day", "10월 4일 (일)", top = 200), node("summary", text = "2026년 10월 4일 (일)", top = 300),
            node("hour", text = "15:00", top = 400), node("confirm", text = "선택 완료", top = 500)))
        assertEquals("confirm", result.target!!.nodeId)
    }

    @Test fun decreasingPassengerCountsIsSupportedAndTapAloneDoesNotAdvance() {
        val planner = JourneyPlanner(request)
        val screen = snapshot(node("plus", "어른 인원증가", top = 200, left = 300),
            node("minus", "어른 인원감소", top = 200, left = 100), node("count", text = "3", top = 200, left = 200))
        assertEquals("minus", planner.plan(screen).target!!.nodeId)
        assertEquals("minus", planner.plan(screen).target!!.nodeId)
    }

    @Test fun selectedStationResultMustNotMatchAnotherStationWithSamePrefix() {
        val route = DemoSession(DemoBookingPlan("서울", "부산", useCurrentTravelOptions = true), DemoStep.SELECT_DEPARTURE_RESULT)
        val result = DemoTargetPlanner().plan(snapshot(node("wrong", "서울숲"), node("right", "서울 선택", top = 200)), route)
        assertEquals("right", result.target!!.nodeId)
    }

    @Test fun composeHourSummaryAndFooterRemainAvailable() {
        val result = JourneyPlanner(request).plan(snapshot(node("title", text = "가는날 선택"),
            node("date", text = "2026년 10월 4일 (일)", top = 300),
            node("time", text = "15시 이후", top = 400), node("confirm", text = "선택 완료", top = 500)))
        assertEquals("confirm", result.target!!.nodeId)
        assertTrue(result.preferTopPanel)
    }

    @Test fun existingResultsAreNotMistakenForConfirmedSearch() {
        val result = JourneyPlanner(request).plan(snapshot(node("trainList", text = "열차조회")))
        assertNull(result.target)
        assertTrue(result.message!!.contains("기존 검색 결과"))
    }

    @Test fun verifiedHomeAllowsResultsWhenKorailOmitsTimeAndPassengerSummaries() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("trainList", text = "열차조회 일반실 15:04")))
        assertTrue(result.message!!.contains("원하는 열차"))
    }

    @Test fun resultTrainBeforeRequestedHourBlocksGuidance() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("departure_time", text = "14:55"),
            node("trainList", text = "열차조회 일반실")))
        assertTrue(result.message!!.contains("보다 이른 열차"))
    }

    @Test fun arrivalAfterMidnightIsNotComparedToTheRequestedDepartureHour() {
        val lateRequest = request.copy(earliestHour = 22)
        val planner = JourneyPlanner(lateRequest)
        assertEquals("search", planner.plan(home(date = "가는날, 2026년 10월 4일 (일) 22:00, 버튼")).target!!.nodeId)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("departure_time", text = "22:10"),
            node("arrival_time", text = "01:52"), node("trainList", text = "열차조회 일반실")))
        assertTrue(result.message!!.contains("원하는 열차"))
        assertTrue(!result.message!!.contains("보다 이른 열차"))
    }

    @Test fun ambiguousResultTimesRequireManualDepartureTimeCheck() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("trainList", text = "열차조회 일반실 14:55 → 18:20")))
        assertTrue(result.message!!.contains("출발인지 시각을 직접 확인"))
        assertTrue(result.message!!.contains("원하는 열차"))
    }

    @Test fun combinedDepartureAndArrivalTextDoesNotTreatArrivalAsAnEarlyDeparture() {
        val lateRequest = request.copy(earliestHour = 22)
        val planner = JourneyPlanner(lateRequest)
        assertEquals("search", planner.plan(home(date = "가는날, 2026년 10월 4일 (일) 22:00, 버튼")).target!!.nodeId)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"),
            node("journey_time", text = "출발 22:10 부산 도착 01:52 대전"),
            node("trainList", text = "열차조회 일반실")))
        assertTrue(result.message!!.contains("출발인지 시각을 직접 확인"))
        assertTrue(!result.message!!.contains("보다 이른 열차"))
    }

    @Test fun displayedResultsMustMatchTheRequestBeforeTrainChoiceGuidance() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val wrongRoute = planner.plan(snapshot(node("route", text = "서울 출발 부산 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"),
            node("people", text = "인원 3명"), node("trainList", text = "열차조회 일반실 15:04")))
        assertNull(wrongRoute.target)
        assertTrue(wrongRoute.message!!.contains("조건을 다시 설정"))
        assertEquals(JourneyPlanner.ResultsGate.ROUTE_MISMATCH, planner.resultsGate)
        val matches = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"),
            node("people", text = "인원 3명"), node("trainList", text = "열차조회 일반실 15:04")))
        assertTrue(matches.message!!.contains("원하는 열차"))
        assertEquals(JourneyPlanner.ResultsGate.VERIFIED, planner.resultsGate)
    }

    @Test fun reversedDisplayedRouteDoesNotCountAsVerified() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "대전 도착 부산 출발"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"),
            node("people", text = "인원 3명"), node("trainList", text = "열차조회 일반실 15:04")))
        assertTrue(result.message!!.contains("하나라도 다르거나"))
    }

    @Test fun verifiedPassengerPlanIsRetainedWhenResultsOmitPassengerSummary() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"),
            node("trainList", text = "열차조회 일반실 15:04, 3명 예약 가능")))
        assertTrue(result.message!!.contains("원하는 열차"))
    }

    @Test fun explicitResultTimeMismatchBlocksTrainGuidance() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "14시 이후"),
            node("people", text = "인원 3명"), node("trainList", text = "열차조회 일반실 15:04")))
        assertTrue(result.message!!.contains("하나라도 다르거나"))
    }

    @Test fun explicitResultPassengerMismatchBlocksTrainGuidance() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"),
            node("people", text = "인원 2명"), node("trainList", text = "열차조회 일반실 15:04")))
        assertTrue(result.message!!.contains("하나라도 다르거나"))
    }

    @Test fun routeDateTimeAndPassengerPlanAreNotTiedToOneTrip() {
        val cases = listOf(
            JourneyRequest("진주", "서울", LocalDate.of(2026, 11, 8), 6, PassengerPlan(1)),
            JourneyRequest("대전", "부산", LocalDate.of(2026, 12, 17), 18, PassengerPlan(2, 1, 1))
        )
        cases.forEach { trip ->
            val planner = JourneyPlanner(trip)
            val passengerSummary = buildString {
                append("인원, 어른 ${trip.passengers.adults}명")
                if (trip.passengers.children > 0) append(", 어린이 ${trip.passengers.children}명")
                if (trip.passengers.infants > 0) append(", 유아 ${trip.passengers.infants}명")
            }
            val home = snapshot(node("departure", "출발역 ${trip.departure} 선택됨"),
                node("arrival", "도착역 ${trip.arrival} 선택됨", top = 200),
                node("date", text = "가는날, ${trip.date.year}년 ${trip.date.monthValue}월 ${trip.date.dayOfMonth}일 ${trip.earliestHour}:00", top = 300),
                node("people", text = passengerSummary, top = 400), node("search", text = "열차 조회", top = 500))
            assertEquals("search", planner.plan(home).target!!.nodeId)

            val results = snapshot(node("route", text = "${trip.departure} 출발 ${trip.arrival} 도착"),
                node("date", text = "${trip.date.year}년 ${trip.date.monthValue}월 ${trip.date.dayOfMonth}일"),
                node("time", text = "${trip.earliestHour}시 이후"),
                node("people", text = "인원 ${trip.passengers.adults + trip.passengers.children + trip.passengers.infants + trip.passengers.seniors}명"),
                node("trainList", text = "열차조회 일반실 18:04"))
            assertTrue(planner.plan(results).message!!.contains("원하는 열차"))
        }
    }

    @Test fun emptyResultsNeverSuggestChoosingAnAbsentTrain() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 대전"), node("date", text = "2026년 10월 4일"),
            node("time", text = "15시 이후"), node("people", text = "인원 3명"),
            node("empty", text = "조회된 열차가 없습니다"), node("trainList")))
        assertTrue(result.message!!.contains("열차가 없어요"))
    }

    @Test fun filterSheetIsNotMistakenForSeatSelection() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        planner.plan(snapshot(node("trainList")))
        val filter = planner.plan(snapshot(node("options", text = "선택된 옵션"), node("done", text = "선택 완료")))
        assertEquals("done", filter.target!!.nodeId)
        assertTrue(filter.preferTopPanel)
        assertTrue(!filter.message!!.contains("호차"))
        val unknown = planner.plan(snapshot(node("other", text = "다른 화면")))
        assertTrue(!unknown.message!!.contains("호차"))
        val seats = planner.plan(snapshot(node("coach", text = "1호차"), node("title", text = "좌석 선택")))
        assertTrue(seats.message!!.contains("좌석"))
    }

    @Test fun seatGuidanceCanResumeWithoutSelectingOrBooking() {
        val result = JourneyPlanner(request).plan(snapshot(node("title", text = "좌석 선택"), node("coach", text = "1호차")))
        assertNull(result.target)
        assertTrue(result.preferTopPanel)
        assertTrue(result.message!!.contains("어린이 1명"))
    }

    @Test fun soldOutResultsNeverOfferThatTrainAsAChoice() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"), node("people", text = "인원 3명"),
            node("trainList", text = "열차조회 일반실 15:04 매진 예약대기")))
        assertTrue(result.message!!.contains("예약 가능한 열차"))
        assertTrue(!result.message!!.contains("원하는 열차와 일반실"))
    }

    @Test fun soldOutTrainDoesNotBlockOtherVisibleBookableTrains() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("route", text = "부산 출발 대전 도착"),
            node("date", text = "2026년 10월 4일"), node("time", text = "15시 이후"), node("people", text = "인원 3명"),
            node("trainList", text = "열차조회 일반실 15:04 매진 예약대기"),
            node("reserveButton206", text = "KTX 일반실 예매", top = 300).copy(clickable = true)))
        assertTrue(result.message!!.contains("매진·예약대기 항목은 제외"))
        assertTrue(result.message!!.contains("원하는 열차와 일반실"))
        assertEquals(JourneyPlanner.ResultsGate.VERIFIED, planner.resultsGate)
    }

    @Test fun transientKorailErrorDoesNotHighlightUnderlyingSearchControls() {
        val planner = JourneyPlanner(request)
        verifySearchHome(planner)
        val result = planner.plan(snapshot(node("search", text = "열차 조회"),
            node("error", text = "일시적 오류가 발생했습니다"), node("retry", text = "다시 시도")))
        assertNull(result.target)
        assertTrue(result.message!!.contains("다시 시도는 직접"))
    }

    @Test fun unavailableSeatsOfferManualRecoveryInsteadOfSeatSelection() {
        val result = JourneyPlanner(request).plan(snapshot(node("title", text = "좌석 선택"), node("coach", text = "1호차"),
            node("empty", text = "선택 가능한 좌석이 없습니다")))
        assertNull(result.target)
        assertTrue(result.message!!.contains("이전 단계로 돌아가"))
        assertTrue(result.preferTopPanel)
    }

    @Test fun onlyStationSearchFieldsMayBeFilled() {
        val unrelated = node("input", text = "검색").copy(editable = true, className = "android.widget.EditText")
        assertEquals(false, DemoTargetPlanner().hasStationSearchField(snapshot(unrelated)))
        val station = unrelated.copy(id = "root/input", contentDescription = "역 이름 또는 초성 입력")
        assertTrue(DemoTargetPlanner().hasStationSearchField(snapshot(station)))
    }

    @Test fun homeTotalRequiresPreviouslyVerifiedPassengerBreakdown() {
        val planner = JourneyPlanner(request)
        assertEquals("people", planner.plan(home(people = "인원, 3명, 버튼")).target!!.nodeId)
        val labels = listOf("어른", "어린이", "유아", "노인", "중증장애인", "경증장애인")
        val counts = listOf(2, 1, 0, 0, 0, 0)
        val rows = labels.flatMapIndexed { i, label -> listOf(
            node("plus-$i", "$label 인원증가", top = 200 + i * 100, left = 300),
            node("count-$i", text = counts[i].toString(), top = 200 + i * 100, left = 200)) }
        val screen = ScreenSnapshot("com.korail.talk", rows + node("confirm", text = "선택 완료", top = 1000), 0)
        assertEquals("confirm", planner.plan(screen).target!!.nodeId)
        assertEquals("search", planner.plan(home(people = "인원, 3명, 버튼")).target!!.nodeId)
        assertEquals("people", planner.plan(home(people = "인원, 4명, 버튼")).target!!.nodeId)
    }
}
