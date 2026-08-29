package com.cuee.domain.demo

import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

class DemoTargetPlannerTest {
    private val planner = DemoTargetPlanner(today = { LocalDate.of(2026, 5, 24) })

    @Test
    fun highlightsTomorrowDate() {
        val session = DemoSession(initialStep = DemoStep.SELECT_TOMORROW, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "day-24", text = "24", top = 100),
                node(id = "day-25", text = "25", top = 100, left = 80)
            ),
            session
        )

        assertEquals("date", result.target?.label)
        assertEquals("day-25", result.target?.nodeId)
    }

    @Test
    fun highlightsSixOClockWhenVisible() {
        val session = DemoSession(initialStep = DemoStep.SELECT_TIME, clock = { 1L })

        val result = planner.plan(snapshot(node(id = "time", text = "06시")), session)

        assertEquals("06:00 time", result.target?.label)
        assertEquals("time", result.target?.nodeId)
    }

    @Test
    fun findsAdultPlusNearAdultLabel() {
        val session = DemoSession(initialStep = DemoStep.ADULT_PLUS_1, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "adult", text = "어른", top = 100, left = 10),
                node(id = "adult-plus", text = "+", top = 105, left = 300, clickable = true)
            ),
            session
        )

        assertEquals("어른 plus", result.target?.label)
        assertEquals("adult-plus", result.target?.nodeId)
    }

    @Test
    fun findsChildPlusNearChildLabel() {
        val session = DemoSession(initialStep = DemoStep.CHILD_PLUS_1, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "child", text = "어린이", top = 100, left = 10),
                node(id = "child-plus", text = "+", top = 105, left = 300, clickable = true)
            ),
            session
        )

        assertEquals("어린이 plus", result.target?.label)
        assertEquals("child-plus", result.target?.nodeId)
    }

    @Test
    fun passengerPlanDefaultsToTwoAdultsAndOneChild() {
        val plan = DemoBookingPlan()

        assertEquals(2, plan.passengers.adults)
        assertEquals(1, plan.passengers.children)
    }

    @Test
    fun resumesToPassengerFieldAfterDateIsConfirmedOnHome() {
        val session = DemoSession(initialStep = DemoStep.SELECT_TOMORROW, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "com.korail.talk:id/v_departure_station", text = "진주"),
                node(id = "com.korail.talk:id/v_arrival_station", text = "서울"),
                node(id = "com.korail.talk:id/tv_value_going_date", text = "2026년 05월 25일 (월) 00:00"),
                node(id = "com.korail.talk:id/tv_value_passenger", text = "어른 1명"),
                node(id = "search", text = "열차조회"),
                node(id = "passenger", text = "어른 1명", clickable = true)
            ),
            session
        )

        assertEquals(DemoStep.SELECT_PASSENGER_FIELD, session.step)
        assertEquals("passenger", result.target?.label)
    }

    @Test
    fun skipsStationSelectionWhenRouteIsAlreadyApplied() {
        val session = DemoSession(initialStep = DemoStep.SELECT_DEPARTURE_FIELD, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "com.korail.talk:id/v_departure_station", text = "진주", clickable = true),
                node(id = "com.korail.talk:id/v_arrival_station", text = "서울", clickable = true),
                node(id = "search", text = "열차조회"),
                node(id = "com.korail.talk:id/rl_going_date", text = "가는날", clickable = true)
            ),
            session
        )

        assertEquals(DemoStep.SELECT_DATE_FIELD, session.step)
        assertEquals("date field", result.target?.label)
    }

    @Test
    fun recognizesKorailPlusComposeStationSearchFromDescendantSemantics() {
        val session = DemoSession(initialStep = DemoStep.SELECT_DEPARTURE_FIELD, clock = { 1L })
        val editTextId = "root/0/0/0/0/1"

        val result = planner.plan(
            snapshot(
                node(
                    id = editTextId,
                    text = null,
                    className = "android.widget.EditText",
                    editable = true,
                    clickable = true
                ),
                node(
                    id = "$editTextId/0",
                    text = null,
                    contentDescription = "역 이름 또는 초성 입력"
                )
            ),
            session
        )

        assertEquals(DemoStep.INPUT_DEPARTURE, session.step)
        assertEquals("search field", result.target?.label)
        assertEquals(editTextId, result.target?.nodeId)
    }

    @Test
    fun usesNaturalKoreanParticleForSeoulSearchInstruction() {
        val session = DemoSession(initialStep = DemoStep.INPUT_ARRIVAL, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(
                    id = "search",
                    text = null,
                    className = "android.widget.EditText",
                    contentDescription = "역 이름 또는 초성 입력",
                    editable = true,
                    clickable = true
                )
            ),
            session
        )

        assertEquals("검색창에 서울을 입력해 주세요.", result.message)
    }

    @Test
    fun promotesKorailPlusTextLabelToClickableParent() {
        val session = DemoSession(initialStep = DemoStep.SELECT_DEPARTURE_FIELD, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "root/departure", text = null, clickable = true),
                node(id = "root/departure/label", text = "출발역"),
                node(id = "root/arrival/label", text = "도착역"),
                node(id = "root/search/label", text = "열차 조회")
            ),
            session
        )

        assertEquals("root/departure", result.target?.nodeId)
    }

    @Test
    fun stopsSafelyWhenCurrentScreenDoesNotMatchBookingHome() {
        val session = DemoSession(initialStep = DemoStep.SELECT_DEPARTURE_FIELD, clock = { 1L })

        val result = planner.plan(
            snapshot(node(id = "root/random", text = "출발역 이벤트 안내", clickable = true)),
            session
        )

        assertEquals(null, result.target)
        assertEquals(DemoTargetPlanner.MSG_OFF_FLOW, result.message)
    }

    @Test
    fun findsPaymentWithSafetyMessage() {
        val session = DemoSession(initialStep = DemoStep.PAYMENT_ENTRY, clock = { 1L })

        val result = planner.plan(snapshot(node(id = "payment", text = "결제하기", clickable = true)), session)

        assertEquals(DemoTargetPlanner.MSG_PAYMENT, result.message)
        assertEquals(true, result.doneAfterRender)
        assertNotNull(result.target)
    }

    @Test
    fun recoversDateStepFromVisiblePickerAndSelectedDate() {
        val session = DemoSession(initialStep = DemoStep.SELECT_TOMORROW, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(
                    id = "com.korail.talk:id/date_cell_tomorrow",
                    text = "25일 · 내일",
                    contentDescription = "5월 25일 내일 선택됨",
                    clickable = true
                ),
                node(id = "com.korail.talk:id/hourTxt06", text = "06시", clickable = true)
            ),
            session
        )

        assertEquals(DemoStep.SELECT_TIME, session.step)
        assertEquals("06:00 time", result.target?.label)
    }

    @Test
    fun recoversPassengerStepFromCurrentCounts() {
        val session = DemoSession(initialStep = DemoStep.ADULT_PLUS_1, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "adult", text = "어른", top = 100, left = 10),
                node(id = "com.korail.talk:id/adult_count", text = "2", top = 100, left = 200),
                node(id = "com.korail.talk:id/adult_plus", text = "+", top = 100, left = 300, clickable = true),
                node(id = "child", text = "어린이", top = 200, left = 10),
                node(id = "com.korail.talk:id/child_count", text = "0", top = 200, left = 200),
                node(id = "com.korail.talk:id/child_plus", text = "+", top = 200, left = 300, clickable = true)
            ),
            session
        )

        assertEquals(DemoStep.CHILD_PLUS_1, session.step)
        assertEquals("어린이 plus", result.target?.label)
    }

    @Test
    fun recoversFollowUpStepFromTrainDetail() {
        val session = DemoSession(initialStep = DemoStep.SCAN_VISIBLE_RESULTS, clock = { 1L })

        val result = planner.plan(
            snapshot(node(id = "com.korail.talk:id/booking_button", text = "예매", clickable = true)),
            session
        )

        assertEquals(DemoStep.FOLLOW_USER_SELECTION, session.step)
        assertEquals("예매", result.target?.label)
    }

    @Test
    fun suggestsVisibleNonClickableSeatTextFromKorailResults() {
        val session = DemoSession(initialStep = DemoStep.SUGGEST_TRAIN, clock = { 1L })

        val result = planner.plan(
            snapshot(
                node(id = "train", text = "KTX-산천", top = 100, left = 10),
                node(id = "departure", text = "08:56\n진주", top = 100, left = 160),
                node(id = "arrival", text = "12:25\n서울", top = 100, left = 320),
                node(id = "com.korail.talk:id/firstTextView", text = "입석+좌석", top = 105, left = 650, clickable = false)
            ),
            session
        )

        assertEquals("KTX seat", result.target?.label)
        assertEquals("com.korail.talk:id/firstTextView", result.target?.nodeId)
    }

    private fun snapshot(vararg nodes: ScreenNode): ScreenSnapshot {
        return ScreenSnapshot("com.korail.talk", nodes.toList(), 1L)
    }

    private fun node(
        id: String,
        text: String?,
        top: Int = 0,
        left: Int = 0,
        clickable: Boolean = false,
        contentDescription: String? = null,
        className: String = "android.widget.TextView",
        editable: Boolean = false
    ): ScreenNode {
        return ScreenNode(
            id = id,
            text = text,
            contentDescription = contentDescription,
            className = className,
            packageName = "com.korail.talk",
            bounds = Bounds(left, top, left + 120, top + 60),
            clickable = clickable,
            enabled = true,
            visible = true,
            scrollable = false,
            editable = editable,
            depth = 0,
            parentHint = null
        )
    }
}
