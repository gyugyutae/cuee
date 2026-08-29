package com.cuee.domain.demo

import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import java.time.LocalDate

class DemoTargetPlanner(
    private val today: () -> LocalDate = { LocalDate.now() }
) {
    fun plan(snapshot: ScreenSnapshot, session: DemoSession): DemoGuideResult {
        reconcileStep(snapshot, session)
        return when (session.step) {
            DemoStep.SELECT_DEPARTURE_FIELD -> findDepartureField(snapshot)
            DemoStep.INPUT_DEPARTURE -> findSearchField(snapshot, session.plan.departureStation)
            DemoStep.SELECT_DEPARTURE_RESULT -> findStationResult(snapshot, session.plan.departureStation)
            DemoStep.SELECT_ARRIVAL_FIELD -> findArrivalField(snapshot)
            DemoStep.INPUT_ARRIVAL -> findSearchField(snapshot, session.plan.arrivalStation)
            DemoStep.SELECT_ARRIVAL_RESULT -> findStationResult(snapshot, session.plan.arrivalStation)
            DemoStep.SELECT_DATE_FIELD -> (
                findByIdOrLabel(snapshot, listOf("rl_going_date"), listOf("가는날", "가는 날"), "date field")
                    ?.focusTopBand()
                    ?: offFlow()
                ).copy(statusText = session.activePolicy.label)
            DemoStep.SELECT_TOMORROW -> findDate(snapshot, session.activePolicy.dateOffsetDays)
            DemoStep.SELECT_TIME -> findTime(snapshot, session.activePolicy.earliestDepartureHour)
            DemoStep.CONFIRM_DATE -> findConfirm(snapshot)
            DemoStep.SELECT_PASSENGER_FIELD -> findPassengerField(snapshot)
            DemoStep.ADULT_PLUS_1 -> findPlusNearLabel(snapshot, "어른")
                ?: DemoGuideResult(null, MSG_PASSENGER_FALLBACK)
            DemoStep.CHILD_PLUS_1 -> findPlusNearLabel(snapshot, "어린이")
                ?: DemoGuideResult(null, MSG_PASSENGER_FALLBACK)
            DemoStep.CONFIRM_PASSENGER -> findConfirm(snapshot)
            DemoStep.SEARCH_TRAINS -> (
                findByText(snapshot, "열차조회", "train search")
                    ?: findByText(snapshot, "조회", "train search")
                    ?: offFlow()
                ).copy(statusText = "조회")
            DemoStep.SCAN_VISIBLE_RESULTS,
            DemoStep.SUGGEST_TRAIN -> TrainResultSelector(session.activePolicy.earliestDepartureHour).select(snapshot)
                ?: DemoGuideResult(null, MSG_NO_TRAIN, statusText = "대안 탐색")
            DemoStep.APPLY_NEXT_SEARCH_POLICY -> DemoGuideResult(null, statusText = session.activePolicy.label, advanceOnRender = true)
            DemoStep.FOLLOW_USER_SELECTION -> findFollowUpCta(snapshot)
            DemoStep.PAYMENT_ENTRY -> findPayment(snapshot)
            DemoStep.DONE -> DemoGuideResult(null, doneAfterRender = true)
        }
    }

    private fun reconcileStep(snapshot: ScreenSnapshot, session: DemoSession) {
        if (session.step == DemoStep.DONE) return

        if (reconcileResultsToFollowUp(snapshot, session)) return
        if (session.step == DemoStep.FOLLOW_USER_SELECTION || session.step == DemoStep.PAYMENT_ENTRY) return

        if (isDatePicker(snapshot)) {
            reconcileDatePicker(snapshot, session)
            return
        }

        if (isPassengerPicker(snapshot)) {
            reconcilePassengerPicker(snapshot, session)
            return
        }

        reconcileHomeAndSearch(snapshot, session)
    }

    /** 열차 결과에서 예매 버튼이 보이면 사용자 선택 단계로 넘긴다. 전이했으면 true. */
    private fun reconcileResultsToFollowUp(snapshot: ScreenSnapshot, session: DemoSession): Boolean {
        val bookingVisible = snapshot.nodes.any {
            it.visible && it.id.contains("booking_button", ignoreCase = true)
        }
        if (session.step in DemoStep.SCAN_VISIBLE_RESULTS..DemoStep.SUGGEST_TRAIN && bookingVisible) {
            session.setStep(DemoStep.FOLLOW_USER_SELECTION)
            return true
        }
        return false
    }

    private fun reconcileDatePicker(snapshot: ScreenSnapshot, session: DemoSession) {
        when (session.step) {
            DemoStep.SELECT_DATE_FIELD -> session.setStep(DemoStep.SELECT_TOMORROW)
            DemoStep.SELECT_TOMORROW -> if (isTomorrowSelected(snapshot)) session.setStep(DemoStep.SELECT_TIME)
            DemoStep.SELECT_TIME -> if (isPolicyHourSelected(snapshot, session)) session.setStep(DemoStep.CONFIRM_DATE)
            else -> Unit
        }
    }

    private fun reconcilePassengerPicker(snapshot: ScreenSnapshot, session: DemoSession) {
        when (session.step) {
            DemoStep.SELECT_PASSENGER_FIELD -> session.setStep(DemoStep.ADULT_PLUS_1)
            DemoStep.ADULT_PLUS_1 -> if (passengerCount(snapshot, "adult_count") >= session.plan.passengers.adults) {
                session.setStep(DemoStep.CHILD_PLUS_1)
            }
            DemoStep.CHILD_PLUS_1 -> if (passengerCount(snapshot, "child_count") >= session.plan.passengers.children) {
                session.setStep(DemoStep.CONFIRM_PASSENGER)
            }
            else -> Unit
        }
    }

    private fun reconcileHomeAndSearch(snapshot: ScreenSnapshot, session: DemoSession) {
        val stationSearchOpen = hasStationSearchField(snapshot)
        val home = isBookingHome(snapshot)
        val departureApplied = stationApplied(snapshot, session.plan.departureStation)
        val arrivalApplied = stationApplied(snapshot, session.plan.arrivalStation)

        if (home && departureApplied && arrivalApplied && session.step <= DemoStep.SELECT_ARRIVAL_RESULT) {
            session.setStep(DemoStep.SELECT_DATE_FIELD)
            return
        }
        if (home && departureApplied && session.step <= DemoStep.SELECT_DEPARTURE_RESULT) {
            session.setStep(DemoStep.SELECT_ARRIVAL_FIELD)
            return
        }

        if (stationSearchOpen) {
            reconcileStationSearch(snapshot, session)
            return
        }

        if (home && departureApplied && session.step == DemoStep.SELECT_DEPARTURE_RESULT) {
            session.setStep(DemoStep.SELECT_ARRIVAL_FIELD)
            return
        }
        if (home && departureApplied && arrivalApplied && session.step == DemoStep.SELECT_ARRIVAL_RESULT) {
            session.setStep(DemoStep.SELECT_DATE_FIELD)
            return
        }
        if (
            home &&
            departureApplied &&
            arrivalApplied &&
            selectedDateMatchesPolicy(snapshot, session) &&
            session.step in DemoStep.SELECT_DATE_FIELD..DemoStep.CONFIRM_DATE
        ) {
            session.setStep(DemoStep.SELECT_PASSENGER_FIELD)
            return
        }
        if (home && passengerSummaryMatches(snapshot) && session.step == DemoStep.CONFIRM_PASSENGER) {
            session.setStep(DemoStep.SEARCH_TRAINS)
            return
        }
        if (isTrainResults(snapshot) && session.step < DemoStep.SCAN_VISIBLE_RESULTS) {
            session.setStep(DemoStep.SCAN_VISIBLE_RESULTS)
        }
    }

    private fun reconcileStationSearch(snapshot: ScreenSnapshot, session: DemoSession) {
        val searchText = stationSearchText(snapshot).normalize()
        when {
            session.step == DemoStep.SELECT_DEPARTURE_FIELD -> session.setStep(DemoStep.INPUT_DEPARTURE)
            session.step == DemoStep.SELECT_ARRIVAL_FIELD -> session.setStep(DemoStep.INPUT_ARRIVAL)
            session.step == DemoStep.INPUT_DEPARTURE && searchText.contains(session.plan.departureStation.normalize()) -> session.setStep(DemoStep.SELECT_DEPARTURE_RESULT)
            session.step == DemoStep.INPUT_ARRIVAL && searchText.contains(session.plan.arrivalStation.normalize()) -> session.setStep(DemoStep.SELECT_ARRIVAL_RESULT)
        }
    }

    private fun findDepartureField(snapshot: ScreenSnapshot): DemoGuideResult {
        if (hasStationSearchField(snapshot)) {
            return DemoGuideResult(null, advanceOnRender = true)
        }
        if (!hasBookingHomeMarkers(snapshot)) return offFlow()
        return findByIdOrLabel(snapshot, listOf("v_departure_station", "tv_departure_station"), listOf("출발"), "departure")
            ?.copy(statusText = "진주 -> 서울")
            ?: offFlow()
    }

    private fun findArrivalField(snapshot: ScreenSnapshot): DemoGuideResult {
        if (hasStationSearchField(snapshot)) {
            return DemoGuideResult(null, advanceOnRender = true)
        }
        if (!hasBookingHomeMarkers(snapshot)) return offFlow()
        return findByIdOrLabel(snapshot, listOf("v_arrival_station", "tv_arrival_station"), listOf("도착"), "arrival")
            ?.copy(statusText = "진주 -> 서울")
            ?: offFlow()
    }

    private fun findPassengerField(snapshot: ScreenSnapshot): DemoGuideResult {
        return findByIdOrLabel(snapshot, listOf("passenger", "person", "people", "adult"), listOf("인원", "어른"), "passenger")
            ?.copy(statusText = "어른 2명, 어린이 1명")
            ?: offFlow()
    }

    private fun findSearchField(snapshot: ScreenSnapshot, station: String): DemoGuideResult {
        val target = stationSearchNodes(snapshot)
            .sortedByDescending { if (it.editable) 2 else 1 }
            .firstOrNull()
            ?.toTarget("search field")

        return if (target == null) {
            DemoGuideResult(null, "검색창에 ${station}${station.objectParticle()} 입력해 주세요.")
        } else {
            DemoGuideResult(target = target, message = "검색창에 ${station}${station.objectParticle()} 입력해 주세요.")
        }
    }

    private fun hasStationSearchField(snapshot: ScreenSnapshot): Boolean {
        return stationSearchNodes(snapshot).isNotEmpty()
    }

    private fun stationSearchNodes(snapshot: ScreenSnapshot): List<ScreenNode> {
        val visibleNodes = snapshot.nodes.filter { it.enabled && it.visible && it.bounds.isValid() }
        return visibleNodes.filter { candidate ->
            val isInput = candidate.editable || candidate.className.orEmpty().contains("EditText", ignoreCase = true)
            if (!isInput) return@filter false

            val ownSemantics = candidate.searchable().normalize()
            val descendantSemantics = visibleNodes
                .asSequence()
                .filter { it.id.startsWith("${candidate.id}/") }
                .joinToString(" ") { it.searchable() }
                .normalize()
            listOf("역이름", "초성입력", "역검색").any { keyword ->
                ownSemantics.contains(keyword.normalize()) || descendantSemantics.contains(keyword.normalize())
            }
        }
    }

    private fun isBookingHome(snapshot: ScreenSnapshot): Boolean {
        return !hasStationSearchField(snapshot) && hasBookingHomeMarkers(snapshot)
    }

    private fun hasBookingHomeMarkers(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { it.visible && it.searchable().normalize().contains("열차조회".normalize()) } &&
            snapshot.nodes.any {
                it.visible && (it.id.contains("v_departure_station", ignoreCase = true) || it.searchable().normalize().contains("출발역"))
            } &&
            snapshot.nodes.any {
                it.visible && (it.id.contains("v_arrival_station", ignoreCase = true) || it.searchable().normalize().contains("도착역"))
            }
    }

    private fun isTrainResults(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { it.visible && it.searchable().normalize().contains("열차조회".normalize()) } &&
            snapshot.nodes.any { node ->
                node.visible &&
                    (node.id.contains("trainList", ignoreCase = true) ||
                        node.id.contains("reserveButton", ignoreCase = true) ||
                        node.id.contains("firstTextView", ignoreCase = true) ||
                        node.id.contains("secondTextView", ignoreCase = true))
            }
    }

    private fun isDatePicker(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { it.visible && it.id.contains("date_cell", ignoreCase = true) } &&
            snapshot.nodes.any { it.visible && it.id.contains("hourTxt", ignoreCase = true) }
    }

    private fun isTomorrowSelected(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { node ->
            node.visible &&
                node.id.contains("date_cell_tomorrow", ignoreCase = true) &&
                node.searchable().contains("선택됨")
        }
    }

    private fun isPolicyHourSelected(snapshot: ScreenSnapshot, session: DemoSession): Boolean {
        val hour = session.activePolicy.earliestDepartureHour.toString().padStart(2, '0')
        return snapshot.nodes.any { node ->
            node.visible &&
                node.id.contains("hourTxt$hour", ignoreCase = true) &&
                node.searchable().contains("선택됨")
        }
    }

    private fun isPassengerPicker(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { it.visible && it.id.contains("adult_plus", ignoreCase = true) } &&
            snapshot.nodes.any { it.visible && it.id.contains("child_plus", ignoreCase = true) }
    }

    private fun passengerCount(snapshot: ScreenSnapshot, idHint: String): Int {
        return snapshot.nodes
            .firstOrNull { it.visible && it.id.contains(idHint, ignoreCase = true) }
            ?.text
            ?.filter(Char::isDigit)
            ?.toIntOrNull()
            ?: 0
    }

    private fun stationApplied(snapshot: ScreenSnapshot, station: String): Boolean {
        return snapshot.nodes.any { node ->
            node.visible &&
                node.bounds.isValid() &&
                !node.editable &&
                node.searchable().normalize().contains(station.normalize())
        }
    }

    private fun stationSearchText(snapshot: ScreenSnapshot): String {
        return stationSearchNodes(snapshot)
            .firstOrNull()
            ?.text
            .orEmpty()
    }

    private fun selectedDateMatchesPolicy(snapshot: ScreenSnapshot, session: DemoSession): Boolean {
        val date = today().plusDays(session.activePolicy.dateOffsetDays)
        val month = date.monthValue.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        val target = "${date.year}년 ${month}월 ${day}일"
        return snapshot.nodes.any { node ->
            node.visible &&
                (node.id.contains("tv_value_going_date", ignoreCase = true) || node.searchable().contains("가는날")) &&
                node.searchable().contains(target)
        }
    }

    private fun passengerSummaryMatches(snapshot: ScreenSnapshot): Boolean {
        return snapshot.nodes.any { node ->
            node.visible &&
                node.id.contains("tv_value_passenger", ignoreCase = true) &&
                node.searchable().contains("어른 2") &&
                node.searchable().contains("어린이 1")
        }
    }

    private fun findStationResult(snapshot: ScreenSnapshot, station: String): DemoGuideResult {
        val onBookingHome = snapshot.nodes.any { it.visible && it.searchable().normalize().contains("열차조회".normalize()) }
        val stationApplied = snapshot.nodes.any { node ->
            node.visible &&
                node.bounds.isValid() &&
                node.searchable().normalize().contains(station.normalize())
        }
        if (!hasStationSearchField(snapshot) && onBookingHome && stationApplied) {
            return DemoGuideResult(null, advanceOnRender = true)
        }

        val stationResult = snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter { node ->
                val raw = node.searchable()
                val normalized = raw.normalize()
                !raw.contains("-") &&
                    (normalized == station.normalize() || normalized.contains(station.normalize()))
            }
            .filterNot { it.editable }
            .sortedWith(compareByDescending<ScreenNode> { it.clickable }.thenBy { it.bounds.top })
            .firstOrNull()
            ?.let { DemoGuideResult(it.toTarget("$station result"), statusText = "진주 -> 서울") }
        if (stationResult != null) return stationResult

        return offFlow()
    }

    private fun findDate(snapshot: ScreenSnapshot, offsetDays: Long): DemoGuideResult {
        val date = today().plusDays(offsetDays)
        val day = date.dayOfMonth.toString()
        return snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter {
                it.text.orEmpty().normalize() == day ||
                    it.contentDescription.orEmpty().normalize() == day ||
                    it.searchable().normalize().contains("${date.monthValue}월${day}일".normalize())
            }
            .sortedBy { it.bounds.top * 10_000 + it.bounds.left }
            .firstOrNull()
            ?.let { DemoGuideResult(it.toTarget("date"), statusText = if (offsetDays == 1L) "내일" else "다음날") }
            ?: offFlow()
    }

    private fun findTime(snapshot: ScreenSnapshot, hour: Int): DemoGuideResult {
        val padded = hour.toString().padStart(2, '0')
        return snapshot.nodes
            .filter { it.enabled && it.bounds.isValid() }
            .filter { it.id.contains("hourTxt", ignoreCase = true) || it.searchable().normalize().contains("시") }
            .filter {
                val text = it.searchable().normalize()
                val ownText = it.text.orEmpty().normalize()
                ownText == hour.toString() ||
                    ownText == padded ||
                    text.contains("${hour}시") ||
                    text.contains("${padded}시") ||
                    text.contains("${padded}00")
            }
            .sortedBy { it.bounds.top * 10_000 + it.bounds.left }
            .firstOrNull()
            ?.let { DemoGuideResult(it.toTarget("${padded}:00 time"), statusText = "${padded}시 이후") }
            ?: DemoGuideResult(null, MSG_TIME_FALLBACK)
    }

    private fun findPlusNearLabel(snapshot: ScreenSnapshot, label: String): DemoGuideResult? {
        val labelNode = snapshot.nodes
            .filter { it.visible && it.searchable().contains(label) }
            .minByOrNull { it.bounds.top }
            ?: return null

        val rowTop = labelNode.bounds.top - ROW_TOLERANCE
        val rowBottom = labelNode.bounds.bottom + ROW_TOLERANCE
        return snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter { it.bounds.top <= rowBottom && it.bounds.bottom >= rowTop }
            .filter { it.bounds.left > labelNode.bounds.left }
            .filter {
                val text = it.searchable().normalize()
                text == "+" || text.contains("증가") || text.contains("더하기") || it.id.contains("plus", ignoreCase = true)
            }
            .maxByOrNull { it.bounds.left }
            ?.let { DemoGuideResult(it.toTarget("$label plus"), statusText = if (label == "어른") "어른 2명" else "어린이 1명") }
    }

    private fun findConfirm(snapshot: ScreenSnapshot): DemoGuideResult {
        return findByText(snapshot, "확인", "confirm") ?: offFlow()
    }

    private fun findPayment(snapshot: ScreenSnapshot): DemoGuideResult {
        return findByText(snapshot, "결제하기", "payment", timeoutMs = DemoGuideResult.PAYMENT_TIMEOUT_MS)
            ?.copy(message = MSG_PAYMENT, doneAfterRender = true)
            ?: findByText(snapshot, "결제", "payment", timeoutMs = DemoGuideResult.PAYMENT_TIMEOUT_MS)
                ?.copy(message = MSG_PAYMENT, doneAfterRender = true)
            ?: offFlow()
    }

    private fun findFollowUpCta(snapshot: ScreenSnapshot): DemoGuideResult {
        val ctas = listOf("결제하기", "결제/발권", "결제발권", "발권", "예매", "좌석선택", "확인")
        for (text in ctas) {
            val result = findByText(snapshot, text, text)
            if (result != null) {
                return when (text) {
                    "결제하기" -> result.copy(message = MSG_PAYMENT, statusText = "결제 전 확인", doneAfterRender = true, timeoutMs = DemoGuideResult.PAYMENT_TIMEOUT_MS)
                    "결제/발권", "결제발권", "발권" -> result.copy(message = MSG_ISSUE_PAYMENT, statusText = "결제 직전", timeoutMs = DemoGuideResult.PAYMENT_TIMEOUT_MS)
                    else -> result.copy(statusText = "직접 선택")
                }
            }
        }
        return offFlow()
    }

    private fun findByText(
        snapshot: ScreenSnapshot,
        text: String,
        label: String,
        timeoutMs: Long = DemoGuideResult.DEFAULT_TIMEOUT_MS
    ): DemoGuideResult? {
        return snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter { it.searchable().normalize().contains(text.normalize()) }
            .sortedWith(compareByDescending<ScreenNode> { it.clickable }.thenBy { it.bounds.top })
            .firstOrNull()
            ?.let { anchor -> DemoGuideResult(anchor.actionableNode(snapshot).toTarget(label), timeoutMs = timeoutMs) }
    }

    private fun findByIdOrLabel(
        snapshot: ScreenSnapshot,
        idHints: List<String>,
        labels: List<String>,
        label: String
    ): DemoGuideResult? {
        val byId = snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter { node -> idHints.any { node.id.contains(it, ignoreCase = true) } }
            .sortedWith(compareByDescending<ScreenNode> { it.clickable }.thenByDescending { it.bounds.area })
            .firstOrNull()
        if (byId != null) return DemoGuideResult(byId.actionableNode(snapshot).toTarget(label))

        return snapshot.nodes
            .filter { it.enabled && it.visible && it.bounds.isValid() }
            .filter { node -> labels.any { node.searchable().normalize().contains(it.normalize()) } }
            .sortedWith(compareByDescending<ScreenNode> { it.clickable }.thenBy { it.bounds.top })
            .firstOrNull()
            ?.let { anchor -> DemoGuideResult(anchor.actionableNode(snapshot).toTarget(label)) }
    }

    private fun offFlow(): DemoGuideResult = DemoGuideResult(null, MSG_OFF_FLOW)

    private fun ScreenNode.toTarget(label: String): DemoTarget = DemoTarget(id, bounds, label)

    private fun ScreenNode.actionableNode(snapshot: ScreenSnapshot): ScreenNode {
        if (clickable) return this
        val anchor = this
        return snapshot.nodes
            .asSequence()
            .filter { candidate ->
                candidate.enabled && candidate.visible && candidate.clickable && candidate.bounds.isValid() &&
                    (anchor.id.startsWith("${candidate.id}/") ||
                        candidate.bounds.intersectionArea(anchor.bounds) == anchor.bounds.area)
            }
            .sortedWith(compareByDescending<ScreenNode> { it.depth }.thenBy { it.bounds.area })
            .firstOrNull()
            ?: this
    }

    private fun DemoGuideResult.focusTopBand(): DemoGuideResult {
        val currentTarget = target ?: return this
        val bounds = currentTarget.bounds
        return copy(
            target = currentTarget.copy(
                bounds = Bounds(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = (bounds.top + DATE_FIELD_TAP_BAND_HEIGHT).coerceAtMost(bounds.bottom)
                )
            )
        )
    }

    private fun ScreenNode.searchable(): String {
        return listOfNotNull(text, contentDescription, parentHint, id).joinToString(" ")
    }

    private fun String.normalize(): String = lowercase().filter { it.isLetterOrDigit() || it == '+' }

    companion object {
        const val MSG_TIME_FALLBACK = "시간을 맞춘 뒤 확인을 눌러주세요."
        const val MSG_PASSENGER_FALLBACK = "어른 2명, 어린이 1명으로 맞춘 뒤 확인을 눌러주세요."
        const val MSG_NO_TRAIN = "현재 조건에서는 바로 예매 가능한 좌석이 없어요. 시간을 더 넓히거나 조건을 바꾸면 이어서 찾을 수 있어요."
        const val MSG_OFF_FLOW = "현재 코레일+ 화면은 아직 지원하지 않아요. 잘못된 곳을 안내하지 않도록 여기서 멈출게요. 코레일+ 홈으로 돌아간 뒤 다시 시작해 주세요."
        const val MSG_PAYMENT = "결제하기 버튼이에요. 결제는 직접 확인해 주세요."
        const val MSG_ISSUE_PAYMENT = "결제/발권 버튼이에요. 누르면 결제 직전 확인 단계로 이동해요."
        private const val ROW_TOLERANCE = 72
        private const val DATE_FIELD_TAP_BAND_HEIGHT = 72
    }
}

private fun String.objectParticle(): String {
    val last = lastOrNull() ?: return "를"
    return if (last in '\uAC00'..'\uD7A3' && (last.code - '\uAC00'.code) % 28 != 0) "을" else "를"
}
