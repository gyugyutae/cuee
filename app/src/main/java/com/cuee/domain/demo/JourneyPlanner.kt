package com.cuee.domain.demo

import com.cuee.domain.command.JourneyRequest
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import java.time.LocalDate

/** Snapshot-driven transitions: a touch event alone never proves a condition was applied. */
class JourneyPlanner(val request: JourneyRequest, private val today: () -> LocalDate = { LocalDate.now() }) {
    enum class ResultsGate { NOT_RESULTS, SEARCH_NOT_CONFIRMED, NO_TRAINS, ROUTE_MISMATCH, DATE_MISMATCH, TIME_MISMATCH, PASSENGER_MISMATCH, EARLIER_DEPARTURE, VERIFIED }

    val routeSession = DemoSession(DemoBookingPlan(request.departure, request.arrival,
        passengers = request.passengers, useCurrentTravelOptions = true))
    private val routePlanner = DemoTargetPlanner(today)
    private var resultsEntered = false
    private var searchReady = false
    private var passengerPickerVerified = false
    var resultsGate = ResultsGate.NOT_RESULTS
        private set

    fun plan(snapshot: ScreenSnapshot): DemoGuideResult {
        resultsGate = ResultsGate.NOT_RESULTS
        val nodes = snapshot.nodes.filter { it.visible && it.enabled && it.bounds.isValid() }
        fun find(vararg terms: String): ScreenNode? = terms.firstNotNullOfOrNull { term ->
            nodes.filter { !it.words().contains("바꾸기") && it.words().contains(term) }
                .minWithOrNull(compareBy<ScreenNode> { if (it.clickable) 0 else 1 }.thenBy { it.bounds.area })
        }
        fun target(node: ScreenNode?, message: String) = DemoGuideResult(node?.let { DemoTarget(it.id, it.bounds, message) }, message)
        if (nodes.any { node ->
                listOf("네트워크 오류", "네트워크 연결", "조회 실패", "조회에 실패", "조회할 수 없", "일시적 오류", "일시 오류", "오류가 발생", "다시 시도", "재시도")
                    .any(node.words()::contains)
            }) {
            return DemoGuideResult(null, "코레일+ 오류 안내를 확인해 주세요. 다시 시도는 직접 눌러 주세요. 계속 실패하면 승차권 조회 홈으로 돌아가 조건을 다시 확인해 주세요.")
        }
        if (nodes.any { it.words().contains("선택된 옵션") }) {
            return target(find("선택 완료"), "직통·인접역 미포함인지 확인하고 선택 완료를 눌러 주세요.").copy(preferTopPanel = true)
        }
        val seatScreen = nodes.any { it.words().contains("좌석 선택") || it.words().contains("좌석선택") } &&
            nodes.any { it.words().contains("호차") }
        if (seatScreen) {
            if (nodes.any { listOf("선택 가능한 좌석이 없", "좌석이 없습니다", "좌석 매진").any(it.words()::contains) })
                return DemoGuideResult(null, "선택 가능한 좌석이 없어요. 이전 단계로 돌아가 화면에 표시된 다른 예약 가능한 열차를 직접 고르거나 조건을 바꿔 다시 조회해 주세요.", preferTopPanel = true)
            val preference = request.selectionPreferences.takeIf { it.isNotEmpty() }?.joinToString() ?: ""
            return DemoGuideResult(null, "원하는 호차와 좌석을 직접 선택하세요. ${request.passengerSummary}의 좌석을 확인하세요. $preference").copy(preferTopPanel = true)
        }
        val trainResults = nodes.any { it.id.contains("trainList", true) || it.id.contains("reserveButton", true) || it.id.contains("secondTextView", true) } ||
            (nodes.any { it.words().contains("일반실") || it.words().contains("특실") } &&
                nodes.count { Regex("[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(it.words()) } >= 2)
        if (trainResults) {
            if (!searchReady) {
                resultsGate = ResultsGate.SEARCH_NOT_CONFIRMED
                return DemoGuideResult(null, "먼저 승차권 조회 홈으로 돌아가 요청 조건을 맞춰 주세요. 기존 검색 결과는 요청과 다를 수 있어요.")
            }
            if (nodes.any { listOf("조회된 열차가 없", "검색 결과가 없", "운행하는 열차가 없", "열차가 없습니다").any(it.words()::contains) }) {
                resultsGate = ResultsGate.NO_TRAINS
                return DemoGuideResult(null, "조건에 맞는 열차가 없어요. 코레일+에서 조건 변경 항목을 열고 실제 표시되는 다른 시간·날짜·역을 확인해 주세요.")
            }
            val unavailableVisible = nodes.any { it.words().contains("매진") || it.words().contains("예약대기") }
            val bookableCandidateVisible = TrainResultSelector(request.earliestHour).select(snapshot) != null
            if (unavailableVisible && !bookableCandidateVisible) {
                resultsGate = ResultsGate.NO_TRAINS
                return DemoGuideResult(null, "매진·예약대기 열차는 좌석을 선택할 수 없어요. 화면에 표시된 예약 가능한 열차가 있으면 직접 선택하고, 없으면 날짜나 시간을 바꿔 다시 조회해 주세요.")
            }
            val visible = nodes.joinToString(" ") { it.words() }.lowercase().filter { it.isLetterOrDigit() }
            val departurePosition = visible.indexOf(request.departure.filter(Char::isLetterOrDigit).lowercase())
            val arrivalPosition = visible.indexOf(request.arrival.filter(Char::isLetterOrDigit).lowercase())
            val routeMatches = departurePosition >= 0 && arrivalPosition > departurePosition
            val datePattern = Regex("${request.date.year}\\s*(?:년|[./-])\\s*0?${request.date.monthValue}\\s*(?:월|[./-])\\s*0?${request.date.dayOfMonth}\\s*일?")
            val dateMatches = datePattern.containsMatchIn(nodes.joinToString(" ") { it.words() })
            val hour = request.earliestHour
            val timeSummary = nodes.filter { node ->
                listOf("이후", "부터", "시간 전체", "시간전체", "조회 시간").any(node.words()::contains)
            }
            val timeMatches = if (timeSummary.isEmpty()) searchReady else timeSummary.any { node ->
                val words = node.words().lowercase()
                val isTimeSummary = listOf("시간", "이후", "부터", "가는날", "출발").any(words::contains)
                isTimeSummary && if (hour == 0) {
                    listOf("전체시간", "하루종일", "00시이후", "0000시이후").any(words.filter(Char::isLetterOrDigit)::contains)
                } else {
                    Regex("(?<![0-9])0?${hour}(?:시\\s*(?:이후|부터)|:00)(?![0-9])").containsMatchIn(words)
                }
            }
            val passengers = request.passengers.adults + request.passengers.children + request.passengers.infants + request.passengers.seniors
            val peopleSummary = nodes.filter { node -> node.words().contains("인원") || node.words().contains("승객") }
            val peopleMatches = peopleSummary.isEmpty() && searchReady || peopleSummary.any { node ->
                val words = node.words()
                (words.contains("인원") || words.contains("승객")) && Regex("(?<![0-9])${passengers}\\s*명(?![0-9])").containsMatchIn(words)
            }
            if (!routeMatches || !dateMatches || !timeMatches || !peopleMatches) {
                resultsGate = when {
                    !routeMatches -> ResultsGate.ROUTE_MISMATCH
                    !dateMatches -> ResultsGate.DATE_MISMATCH
                    !timeMatches -> ResultsGate.TIME_MISMATCH
                    else -> ResultsGate.PASSENGER_MISMATCH
                }
                return DemoGuideResult(null, "조회 결과의 출발역·도착역·날짜·시간·인원을 요청 내용과 대조해 주세요. 하나라도 다르거나 화면에서 확인되지 않으면 열차를 고르지 말고 조건을 다시 설정해 주세요.")
            }
            val resultTimePattern = Regex("(?<![0-9])((?:[01]?\\d|2[0-3])):([0-5]\\d)(?![0-9])")
            val departureTimeNodes = nodes.filter { node ->
                node.hasUnambiguousDepartureTimeSemantics() && resultTimePattern.containsMatchIn(node.words())
            }
            val resultHours = departureTimeNodes.flatMap { node ->
                resultTimePattern.findAll(node.words()).map { it.groupValues[1].toInt() }
            }
            if (resultHours.any { it < request.earliestHour }) {
                resultsGate = ResultsGate.EARLIER_DEPARTURE
                return DemoGuideResult(null, "요청한 ${request.earliestHour}시보다 이른 열차가 보여요. 조건을 다시 확인하고 조회해 주세요.")
            }
            resultsEntered = true
            resultsGate = ResultsGate.VERIFIED
            // Do not select a preferred train or mask other train/seat choices.
            val timeNeedsManualCheck = request.earliestHour > 0 && timeSummary.isEmpty() && departureTimeNodes.isEmpty()
            val timeReminder = if (timeNeedsManualCheck) "요청한 ${request.earliestHour}시 이후 출발인지 시각을 직접 확인한 뒤, " else ""
            val availabilityReminder = if (unavailableVisible) "매진·예약대기 항목은 제외하고, " else ""
            return DemoGuideResult(null, "${timeReminder}${availabilityReminder}원하는 열차와 일반실·특실을 직접 선택하세요. 매진이면 화면에 표시된 다른 열차를 확인하세요.")
        }
        if (resultsEntered && !nodes.any { it.words().contains("열차조회") || it.words().contains("열차 조회") }) {
            return DemoGuideResult(null, "화면에서 원하는 선택지를 직접 선택하세요. 조건을 바꾸려면 조건 수정을 누르세요.")
        }
        val datePicker = nodes.any { Regex("가는\\s*날\\s*선택").containsMatchIn(it.words()) || it.id.contains("date_cell", true) }
        if (datePicker) {
            val desired = request.date
            val visibleMonth = nodes.mapNotNull { Regex("^(\\d{4})년\\s*(\\d{1,2})월$").find(it.text.orEmpty()) }.firstOrNull()
                ?.let { LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), 1) }
            if (visibleMonth != null && visibleMonth != desired.withDayOfMonth(1)) {
                val direction = if (visibleMonth < desired.withDayOfMonth(1)) "다음달" else "이전달"
                return target(find(direction), "${desired.year}년 ${desired.monthValue}월로 달력을 이동해 주세요.")
            }
            val datePattern = Regex("(?<![0-9])0?${desired.monthValue}월\\s*0?${desired.dayOfMonth}일(?![0-9])")
            val dateNodes = nodes.filter { datePattern.containsMatchIn(it.words()) }
            val selectedDate = dateNodes.any { it.selected || it.checked || it.words().contains("선택됨") || it.text.orEmpty().contains("${desired.year}년") }
            if (!selectedDate) {
                val exact = dateNodes.filter { it.contentDescription.orEmpty().contains("월") }.singleOrNull()
                // ponytail: unlabeled day numbers are ambiguous across months; request manual calendar navigation.
                return target(exact, "${desired.monthValue}월 ${desired.dayOfMonth}일을 선택해 주세요. 보이지 않으면 달력을 이동해 주세요.")
            }
            val hourNodes = nodes.filter { Regex("0?${request.earliestHour}(?:시|:00)").matches(it.text.orEmpty().trim()) }
            val selectedHour = hourNodes.any { it.selected || it.checked || it.words().contains("선택됨") } ||
                nodes.any {
                    it.text == "${request.earliestHour.toString().padStart(2, '0')}:00" ||
                        Regex("(?<![0-9])0?${request.earliestHour}시\\s*이후").containsMatchIn(it.words())
                }
            if (!selectedHour) return target(hourNodes.firstOrNull(), "${request.earliestHour}시 이후로 맞춰 주세요. 시간이 보이지 않으면 시간 목록을 직접 밀어 주세요.").copy(preferTopPanel = true)
            return target(find("선택 완료", "확인"), "날짜와 시간을 확인하고 선택 완료를 눌러 주세요.").copy(preferTopPanel = true)
        }
        val labels = listOf("어른", "어린이", "유아", "노인", "중증장애인", "경증장애인")
        val picker = nodes.any { it.contentDescription?.contains("인원증가") == true || it.id.contains("adult_plus", true) }
        if (picker) {
            passengerPickerVerified = false
            val desired = listOf(request.passengers.adults, request.passengers.children, request.passengers.infants, request.passengers.seniors, 0, 0)
            labels.forEachIndexed { i, label ->
                val idPrefix = listOf("adult", "child", "infant", "senior", "severe", "mild")[i]
                val increase = nodes.firstOrNull { it.contentDescription == "$label 인원증가" || (i == 3 && it.contentDescription == "경로 인원증가") || it.id.contains(idPrefix + "_plus", true) }
                if (increase == null && desired[i] == 0) return@forEachIndexed
                val count = nodes.firstOrNull { it.id.contains(idPrefix + "_count", true) }?.text?.toIntOrNull()
                    ?: increase?.let { plus -> nodes.filter { it.text?.toIntOrNull() != null && it.bounds.right <= plus.bounds.left && it.bounds.bottom >= plus.bounds.top && it.bounds.top <= plus.bounds.bottom }.maxByOrNull { it.bounds.left }?.text?.toIntOrNull() }
                if (count == null) return DemoGuideResult(null, "$label ${desired[i]}명인지 직접 확인해 주세요.\n${request.summary}")
                if (count != desired[i]) {
                    val action = if (count < desired[i]) increase else nodes.firstOrNull { it.contentDescription == "$label 인원감소" || (i == 3 && it.contentDescription == "경로 인원감소") || it.id.contains(idPrefix + "_minus", true) }
                    return target(action, "$label ${desired[i]}명으로 맞춰 주세요. 현재 ${count}명이에요.")
                }
            }
            passengerPickerVerified = true
            return target(find("선택 완료", "확인"), "인원이 맞아요. 선택 완료를 눌러 주세요.")
        }
        val home = nodes.any { it.words().contains("열차조회") || it.words().contains("열차 조회") }
        if (!home) {
            if (nodes.any { it.editable }) return routePlanner.plan(snapshot, routeSession)
            return DemoGuideResult(null, "코레일+ 승차권 조회 홈을 열어 주세요. 요청 조건은 유지돼요.")
        }
        resultsEntered = false
        searchReady = false
        val departureField = find("v_departure_station", "출발역선택", "출발역")
        val arrivalField = find("v_arrival_station", "도착역선택", "도착역")
        fun stationMatches(field: ScreenNode?, station: String, label: String): Boolean = field != null && nodes.any {
            (it.id == field.id || field.bounds.intersectionArea(it.bounds) == it.bounds.area) &&
                listOfNotNull(it.text, it.contentDescription).any { value ->
                    val normalized = value.trim().replace(Regex("\\s+"), " ")
                    normalized.removeSuffix("역") == station ||
                        Regex("^(?:${label}역|${label})\\s*${Regex.escape(station)}(?:역)?(?:\\s*선택(?:됨)?)?$").matches(normalized)
                }
        }
        if (!stationMatches(departureField, request.departure, "출발")) {
            routeSession.setStep(DemoStep.SELECT_DEPARTURE_FIELD)
            return target(departureField, "출발역을 눌러 ${request.departure}역을 선택해 주세요.")
        }
        if (!stationMatches(arrivalField, request.arrival, "도착")) {
            routeSession.setStep(DemoStep.SELECT_ARRIVAL_FIELD)
            return target(arrivalField, "도착역을 눌러 ${request.arrival}역을 선택해 주세요.")
        }
        val dateNode = find("rl_going_date", "가는날", "가는 날")
        val dateText = nodes.filter { dateNode != null && dateNode.bounds.intersectionArea(it.bounds) > 0 }.joinToString(" ") { it.words() }
        val desiredDate = request.date
        val dateMatches = Regex("${desiredDate.year}년\\s*0?${desiredDate.monthValue}월\\s*0?${desiredDate.dayOfMonth}일(?![0-9])").containsMatchIn(dateText)
        val hourMatches = Regex("(?<![0-9])0?${request.earliestHour}(?:시|:00)").containsMatchIn(dateText)
        if (!dateMatches || !hourMatches) return target(dateNode, "가는 날을 눌러 ${request.date}, ${request.earliestHour}시 이후로 맞춰 주세요.")
        val passengerNode = find("passenger", "인원", "어른")
        val passengerText = nodes.filter { passengerNode != null && passengerNode.bounds.intersectionArea(it.bounds) > 0 }.joinToString(" ") { it.words() }
        val wanted = listOf(request.passengers.adults, request.passengers.children, request.passengers.infants, request.passengers.seniors, 0, 0)
        val detailedCountsMatch = labels.indices.all { i ->
            val labelPattern = if (i == 3) "(?:노인|경로)" else labels[i]
            val count = Regex("$labelPattern\\s*([0-9]+)").find(passengerText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            count == wanted[i]
        }
        val hasBreakdown = labels.any(passengerText::contains) || passengerText.contains("경로")
        val homeTotal = Regex("([0-9]+)명").findAll(passengerText).map { it.groupValues[1].toInt() }.distinct().singleOrNull()
        val countsMatch = if (hasBreakdown) detailedCountsMatch else passengerPickerVerified && homeTotal == wanted.sum()
        if (!countsMatch) return target(passengerNode, "인원을 눌러 ${request.passengerSummary}으로 맞춰 주세요.")
        searchReady = true
        return target(find("열차조회", "열차 조회"), "편도·직통 조건인지 확인하고 조회를 눌러 주세요.\n${request.summary}")
    }

    private fun ScreenNode.words(): String = listOfNotNull(text, contentDescription, id).joinToString(" ")

    private fun ScreenNode.hasUnambiguousDepartureTimeSemantics(): Boolean {
        val semantics = listOfNotNull(text, contentDescription, id, parentHint).joinToString(" ").lowercase()
        val departure = listOf("출발", "depart", "departure", "starttime", "start_time", "time_start")
            .any(semantics::contains)
        val arrival = listOf("도착", "arrival", "arrive", "endtime", "end_time", "time_end")
            .any(semantics::contains)
        return departure && !arrival
    }
}
