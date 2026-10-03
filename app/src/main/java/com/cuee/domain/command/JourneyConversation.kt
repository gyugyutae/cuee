package com.cuee.domain.command

import com.cuee.domain.demo.PassengerPlan
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

data class JourneyRequest(
    val departure: String,
    val arrival: String,
    val date: LocalDate,
    val earliestHour: Int,
    val passengers: PassengerPlan,
    val selectionPreferences: Set<String> = emptySet()
) {
    val passengerSummary: String get() = listOf("어른" to passengers.adults, "어린이" to passengers.children,
        "유아" to passengers.infants, "경로" to passengers.seniors).filter { it.second > 0 }
        .joinToString(", ") { "${it.first} ${it.second}명" }
    val summary: String get() = "$departure → $arrival\n$date, ${earliestHour}시 이후\n" +
        passengerSummary + if (selectionPreferences.isEmpty()) "" else "\n희망: ${selectionPreferences.joinToString(", ")} (직접 선택)"
}

data class JourneyConversationState(
    val departure: String?, val arrival: String?, val date: LocalDate?, val hour: Int?,
    val passengers: PassengerPlan?, val selectionPreferences: Set<String>, val unresolved: Set<String>
)

/** Raw utterances live only in the caller's input; retain structured conditions only. */
class JourneyConversation(private val today: () -> LocalDate = { LocalDate.now() }) {
    var departure: String? = null
        private set
    var arrival: String? = null
        private set
    var date: LocalDate? = null
        private set
    var hour: Int? = null
        private set
    var passengers: PassengerPlan? = null
        private set
    var selectionPreferences: Set<String> = emptySet()
        private set
    private val unresolved = mutableSetOf<String>()

    val question: String get() = when {
        unresolved.isNotEmpty() -> "확인되지 않은 조건을 다시 알려 주세요: ${unresolved.joinToString(", ")}. 이전 입력을 취소할 수도 있어요."
        departure == null -> "어느 역에서 출발하나요?"
        arrival == null -> "어느 역으로 가나요?"
        date == null -> "언제 가나요? 예: 내일, 10월 15일"
        hour == null -> "몇 시 이후에 출발하나요? 예: 오후 3시, 시간 상관없어"
        passengers == null -> "누가 몇 명 타나요? 예: 어른 2명 어린이 1명. 유아·경로도 함께 말해 주세요."
        else -> "아래 조건이 맞나요? 확인 후 안내를 시작해 주세요."
    }
    val hasUnresolvedInput: Boolean get() = unresolved.isNotEmpty()

    fun request(): JourneyRequest? {
        if (unresolved.isNotEmpty()) return null
        return JourneyRequest(departure ?: return null, arrival ?: return null,
            date ?: return null, hour ?: return null, passengers ?: return null, selectionPreferences)
    }

    private fun reject(field: String, message: String): String {
        unresolved += field
        return "이번 입력은 반영하지 않았어요. $message"
    }

    fun snapshot() = JourneyConversationState(departure, arrival, date, hour, passengers, selectionPreferences, unresolved.toSet())

    companion object {
        fun restore(state: JourneyConversationState, today: () -> LocalDate = { LocalDate.now() }) = JourneyConversation(today).apply {
            departure = state.departure
            arrival = state.arrival
            date = state.date
            hour = state.hour
            passengers = state.passengers
            selectionPreferences = state.selectionPreferences
            unresolved += state.unresolved
        }
    }

    fun accept(raw: String): String? {
        val hours = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6,
            "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10, "열한" to 11, "열두" to 12)
        val text = raw.trim().replace(Regex("\\s+"), " ")
            .replace(Regex("(열두|열한|열|아홉|여덟|일곱|여섯|다섯|네|세|두|한)\\s*시")) { "${hours.getValue(it.groupValues[1])}시" }
        if (text.isBlank()) return "말하거나 글자로 입력해 주세요."
        if (text == "이전 입력 취소") { unresolved.clear(); return "반영되지 않은 이전 입력을 취소했어요. 남아 있는 조건을 다시 확인해 주세요." }
        val compact = text.filter(Char::isLetterOrDigit).lowercase()
        val unsupportedTripOrProduct = listOf(
            "왕복", "갔다가", "갔다오", "갔다와", "갔다온", "갔다왔다", "다녀오", "다녀와",
            "환승", "갈아타", "경유", "거쳐",
            "할인", "우대", "특가", "n카드", "ncard", "청소년드림", "힘내라청춘", "다자녀행복", "맘편한",
            "정기권", "내일로패스", "내일로좌석지정", "장애인", "휠체어", "반려", "자전거"
        )
        if (unsupportedTripOrProduct.any(compact::contains))
            return reject("지원 범위", "지금은 편도·직통 일반 승차권만 안내해요. 왕복·환승·경유·할인·별도 이용권 등 지원하지 않는 조건을 제외하지 않고 입력을 보류했어요. 코레일+에서 직접 진행하거나 이전 입력을 취소해 주세요.")
        if (listOf("나란히", "옆자리", "붙여", "맨 앞", "맨 뒤", "앞쪽", "뒷쪽").any(text::contains))
            return reject("좌석", "좌석 배치 요청은 조건에서 빠지지 않도록 보류했어요. 열차 화면에서 좌석을 직접 고르거나 이 입력을 취소해 주세요.")
        if (listOf("복도", "콘센트", "조용한 자리").any(text::contains))
            return reject("지원 범위", "요청한 좌석 조건을 모두 반영할 수 없어 입력을 보류했어요. 지원되는 조건으로 다시 말하거나 코레일+에서 직접 골라 주세요.")
        val route = Regex("([가-힣A-Za-z]+?)(?:역)?에서\\s*([가-힣A-Za-z]+?)(?=\\s|(?:역)?(?:까지|행|가는)|역(?:으로|로)|$)").find(text)
        if (Regex("[가-힣A-Za-z]+에서").findAll(text).count() > 1)
            return reject("역", "출발역·도착역이 여러 쌍으로 들렸어요. 한 번에 한 구간만 알려 주세요.")
        val from = Regex("출발(?:역)?\\s*[:은는]?\\s*([가-힣A-Za-z]+)").find(text)
        val to = Regex("도착(?:역)?\\s*[:은는]?\\s*([가-힣A-Za-z]+)").find(text)
        val departureBefore = Regex("(?:^|[\\s,，、])([가-힣A-Za-z]{1,12})(?:역)?\\s*출발(?:해서|하고)?(?=$|[\\s,，、])").find(text)
        val arrivalBefore = Regex("(?:^|[\\s,，、])([가-힣A-Za-z]{1,12})(?:역)?\\s*도착(?:하는)?(?=$|[\\s,，、])").find(text)
        val ambiguousDestination = route?.groupValues?.get(2)?.takeIf { it.endsWith("로") && !it.endsWith("으로") }
            ?: to?.groupValues?.get(1)?.takeIf { it.endsWith("로") && !it.endsWith("역") }
            ?: arrivalBefore?.groupValues?.get(1)?.takeIf { it.endsWith("로") && !it.endsWith("역") }
            ?: text.takeIf { route == null && from == null && to == null && departureBefore == null && arrivalBefore == null && it != "통로" && Regex("[가-힣A-Za-z]+로").matches(it) }
        if (ambiguousDestination != null)
            return reject("도착역", "‘$ambiguousDestination’가 역 이름인지 조사인지 불분명해요. 도착역 서울역처럼 역 이름을 다시 말해 주세요.")
        val numericDatePattern = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")
        val numericDate = numericDatePattern.find(text)?.value?.split('-', '.', '/')
        val dateValue = when {
            text.contains("모레") -> today().plusDays(2)
            text.contains("내일") -> today().plusDays(1)
            text.contains("오늘") -> today()
            numericDate != null -> runCatching {
                LocalDate.of(numericDate[0].toInt(), numericDate[1].toInt(), numericDate[2].toInt())
            }.getOrNull() ?: return reject("날짜", "존재하는 날짜를 입력해 주세요.")
            Regex("(?:이번|다음)\\s*주\\s*[월화수목금토일]요일").containsMatchIn(text) -> {
                val match = Regex("(이번|다음)\\s*주\\s*([월화수목금토일])요일").find(text)!!
                val monday = today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                monday.plusWeeks(if (match.groupValues[1] == "다음") 1 else 0)
                    .plusDays("월화수목금토일".indexOf(match.groupValues[2]).toLong())
            }
            else -> Regex("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일").find(text)?.let {
                runCatching { LocalDate.of(it.groupValues[1].toIntOrNull() ?: today().year,
                    it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
                    ?: return reject("날짜", "존재하는 날짜를 입력해 주세요.")
            }
        }
        if (dateValue != null && dateValue < today()) return reject("날짜", "지난 날짜는 검색할 수 없어요. 날짜를 다시 알려 주세요.")
        if (dateValue == null && Regex("다음\\s*주|이번\\s*주|다음\\s*달|이번\\s*달|주말|요일|\\d{1,2}\\s*월|\\d{2,4}년|날짜|\\d{4}[./-]").containsMatchIn(text))
            return reject("날짜", "날짜를 정확히 알려 주세요. 예: 다음 주 월요일, 2026-10-15")
        val time = Regex("(?<![0-9])(오전|오후|새벽|아침|저녁|밤)?\\s*(\\d+)(?:시|:([0-9]{2}))").find(text)
        if (Regex("[0-9]+\\s*분|[0-9]+\\s*시\\s*반").containsMatchIn(text)) return reject("시간", "현재 코레일+ 조회는 시간 단위로 안내해요. 예: 오후 3시 이후")
        if (time != null && Regex("쯤|즈음|정도|내외|경").containsMatchIn(text.substring(time.range.last + 1)))
            return reject("시간", "대략적인 시각은 조회 조건으로 삼을 수 없어요. 가장 이른 시각을 알려 주세요.")
        val anyTimePhrases = listOf(
            "상관없어요", "상관없어", "상관없", "시간 전체", "시간전체", "언제든지", "언제든", "언제라도",
            "아무 때나", "아무때나", "아무 때", "아무때", "시간 관계없어요", "시간 관계없어", "시간 관계없"
        )
        val anyTime = anyTimePhrases.any(text::contains)
        if (anyTime && time != null) return reject("시간", "시간 전체와 특정 시각을 함께 말씀하셨어요. 둘 중 하나를 알려 주세요.")
        val timeValue = if (anyTime) 0 else time?.let {
            var value = it.groupValues[2].toIntOrNull() ?: return reject("시간", "시간은 0시부터 23시 사이로 알려 주세요.")
            val period = when (it.groupValues[1]) {
                "새벽", "아침" -> "오전"
                "저녁", "밤" -> "오후"
                else -> it.groupValues[1]
            }
            if ((period.isNotBlank() && value !in 1..12) || value !in 0..23) return reject("시간", "시간은 0시부터 23시 사이로 알려 주세요.")
            if (it.groupValues[3].isNotBlank() && it.groupValues[3] != "00") return reject("시간", "현재는 시간 단위로 검색해요. 예: 오후 3시 이후")
            if (it.groupValues[1] == "밤" && value == 12)
                return reject("날짜", "밤 12시는 날짜가 달라질 수 있어요. 코레일+ 기준 날짜와 함께 다시 알려 주세요.")
            if (period.isBlank() && it.groupValues[3].isBlank() && value in 1..12)
                return reject("시간", "오전인지 오후인지 알려 주세요. 예: 오후 3시 또는 15:00")
            if (period == "오후" && value < 12) value += 12
            if (period == "오전" && value == 12) value = 0
            value
        }
        if (timeValue == null && Regex("낮|아침|저녁|밤|새벽|오전|오후|[0-9]+\\s*시").containsMatchIn(text))
            return reject("시간", "조회 시작 시각을 정확히 알려 주세요. 예: 오전 8시, 오후 6시")
        if (Regex("[-+][0-9]+\\s*명|[0-9]+[.,][0-9]+\\s*명").containsMatchIn(text))
            return reject("승객", "승객 수는 0~9 사이 정수로 알려 주세요.")
        if (Regex("(?:약|대략)\\s*[0-9]+\\s*(?:명|인)|[0-9]+\\s*(?:명|인)\\s*(?:쯤|정도|가량)").containsMatchIn(text))
            return reject("승객", "승객 수는 예상 인원이 아닌 확정된 인원으로 알려 주세요.")
        val counts = mutableMapOf<String, Int>()
        val passengerPattern = Regex("(어른|성인|어린이|아이|유아|경로|노인)\\s*([0-9]+|한|하나|두|둘|세|셋|네|넷|다섯|여섯|일곱|여덟|아홉)\\s*(?:명|분)?")
        passengerPattern.findAll(text).forEach {
            val count = it.groupValues[2].toIntOrNull() ?: mapOf("한" to 1, "하나" to 1, "두" to 2, "둘" to 2,
                "세" to 3, "셋" to 3, "네" to 4, "넷" to 4, "다섯" to 5, "여섯" to 6,
                "일곱" to 7, "여덟" to 8, "아홉" to 9)[it.groupValues[2]]
                ?: return reject("승객", "승객 수는 0~9 사이 정수로 알려 주세요.")
            if (count !in 0..9) return reject("승객", "승객 수는 0~9 사이 정수로 알려 주세요.")
            val label = when (it.groupValues[1]) { "성인" -> "어른"; "아이" -> "어린이"; "노인" -> "경로"; else -> it.groupValues[1] }
            if (label in counts) return reject("승객", "$label 인원이 여러 번 나왔어요. 최종 인원을 한 번만 알려 주세요.")
            counts[label] = count
        }
        val remainingPassengers = text.replace(passengerPattern, "")
        if (Regex("[가-힣A-Za-z]+\\s*[0-9]+\\s*(명|인|사람|장|매|좌석)|[0-9]+\\s*(명|인|사람|장|매|좌석)|(한|하나|두|둘|세|셋|네|넷|다섯|여섯|일곱|여덟|아홉)\\s*(명|인|사람|장|매|좌석)|어른|성인|어린이|아이|유아|경로|노인|소아|청소년|초등학생|탑승자|동행인").containsMatchIn(remainingPassengers))
            return reject("승객", "모든 승객의 유형과 인원을 함께 알려 주세요. 예: 어른 2명 어린이 1명")
        val passengerValue = if (counts.isEmpty()) null else PassengerPlan(counts["어른"] ?: passengers?.adults ?: 0,
            counts["어린이"] ?: passengers?.children ?: 0, counts["유아"] ?: passengers?.infants ?: 0, counts["경로"] ?: passengers?.seniors ?: 0)
        if (passengerValue != null && (passengerValue.adults + passengerValue.children + passengerValue.infants + passengerValue.seniors) !in 1..9) return reject("승객", "승객은 총 1~9명으로 알려 주세요. 실제 선택 가능 인원은 코레일+ 화면에서 확인해요.")
        val preferences = listOf("창가", "통로", "순방향", "역방향", "일반실", "특실", "KTX", "ITX", "무궁화")
            .filter { text.contains(it, ignoreCase = true) }.toSet()
        val seatConflicts = listOf(setOf("창가", "통로"), setOf("순방향", "역방향"), setOf("일반실", "특실"))
        if (seatConflicts.any { group -> group.all(preferences::contains) })
            return reject("좌석", "서로 다른 좌석 조건이 함께 있어요. 창가·통로, 순방향·역방향, 일반실·특실 중 하나씩 골라 주세요.")
        var newDeparture = departure
        var newArrival = arrival
        var stationOnlyInput = false
        if (route != null) {
            newDeparture = route.groupValues[1].removeSuffix("역")
            newArrival = route.groupValues[2].removeSuffix("으로").removeSuffix("역")
        } else if (from != null || to != null || departureBefore != null || arrivalBefore != null) {
            newDeparture = (from?.groupValues?.get(1) ?: departureBefore?.groupValues?.get(1))?.removeSuffix("역") ?: newDeparture
            newArrival = (to?.groupValues?.get(1) ?: arrivalBefore?.groupValues?.get(1))?.removeSuffix("역") ?: newArrival
        } else if (dateValue == null && timeValue == null && passengerValue == null && preferences.isEmpty() && Regex("[가-힣A-Za-z]{1,12}").matches(text)) {
            if (departure == null) newDeparture = text.removeSuffix("역")
            else if (arrival == null) newArrival = text.removeSuffix("역")
            else return reject("입력", "수정할 항목을 함께 알려 주세요. 예: 출발역 부산")
            stationOnlyInput = true
        } else if (dateValue == null && timeValue == null && passengerValue == null && preferences.isEmpty()) return reject("입력", "조건을 이해하지 못했어요. ${question}")
        if (newDeparture != null && newDeparture == newArrival) return reject("역", "출발역과 도착역은 다르게 알려 주세요.")

        val consumed = BooleanArray(text.length)
        fun consume(start: Int, endInclusive: Int) {
            for (index in start..endInclusive) if (index in consumed.indices) consumed[index] = true
        }
        fun consume(match: MatchResult?) { match?.range?.let { consume(it.first, it.last) } }
        fun consume(pattern: Regex) { pattern.findAll(text).forEach { consume(it) } }

        if (stationOnlyInput) consume(0, text.lastIndex)
        consume(route)
        consume(from)
        consume(to)
        consume(departureBefore)
        consume(arrivalBefore)
        if (dateValue != null) {
            listOf(
                Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}"),
                Regex("오늘|내일|모레"),
                Regex("(?:이번|다음)\\s*주\\s*[월화수목금토일]요일"),
                Regex("(?:(?:\\d{4})년\\s*)?\\d{1,2}월\\s*\\d{1,2}일")
            ).forEach(::consume)
        }
        consume(time)
        passengerPattern.findAll(text).forEach(::consume)
        preferences.forEach { preference -> consume(Regex(Regex.escape(preference), RegexOption.IGNORE_CASE)) }
        if (anyTime) anyTimePhrases
            .forEach { phrase ->
                var index = text.indexOf(phrase, ignoreCase = true)
                while (index >= 0) {
                    consume(index, index + phrase.lastIndex)
                    index = text.indexOf(phrase, index + phrase.length, ignoreCase = true)
                }
            }
        // ponytail: this bounded filler vocabulary fails closed on unfamiliar wording; add phrases with a regression test.
        listOf(
            "역으로", "역까지", "역행", "에서", "까지", "으로", "로", "행", "가는", "돌아오는", "돌아온", "편도", "직통",
            "출발역", "도착역", "출발", "도착", "시간", "날짜", "인원", "승객", "열차", "기차", "승차권", "표",
            "예매", "예약", "조회", "검색", "찾아줘", "찾아", "주세요", "해 주세요", "해주세요", "해줘",
            "부탁드립니다", "부탁드려요", "부탁해요", "부탁해", "부탁", "해서", "하는", "하고 싶어요", "하고싶어요", "하고 싶어", "하고싶어",
            "가고 싶어요", "가고싶어요", "가고 싶어", "가고싶어", "원해요", "원해", "원하는", "싶어요", "싶어",
            "자리", "좌석", "호차", "일반", "좀", "를", "을", "은", "는", "이", "가", "에", "와", "과", "도", "만",
            "이후", "부터", "이후에", "부터는", "타고", "타는", "탈"
        ).sortedByDescending(String::length).forEach { phrase ->
            var index = text.indexOf(phrase, ignoreCase = true)
            while (index >= 0) {
                consume(index, index + phrase.lastIndex)
                index = text.indexOf(phrase, index + phrase.length, ignoreCase = true)
            }
        }
        if (text.indices.any { index -> !consumed[index] && text[index].isLetterOrDigit() })
            return reject("입력", "해석하지 못한 조건을 빠뜨리지 않도록 이번 입력 전체를 보류했어요. 조건을 나누어 다시 알려 주세요.")

        departure = newDeparture
        arrival = newArrival
        if (route != null || from != null || to != null) unresolved.remove("역")
        if (newArrival != null) unresolved.remove("도착역")
        dateValue?.let { date = it; unresolved.remove("날짜") }
        timeValue?.let { hour = it; unresolved.remove("시간") }
        passengerValue?.let { passengers = it; unresolved.remove("승객") }
        if (preferences.isNotEmpty()) {
            selectionPreferences = selectionPreferences.filterNot { old -> seatConflicts.any { old in it && it.any(preferences::contains) } }.toSet() + preferences
            unresolved.remove("좌석")
        }
        return null
    }
}
