package com.cuee.domain.command

class DefaultKorailCommandParser : KorailCommandParser {
    override fun parse(utterance: String): KorailCommand? {
        val normalized = utterance.normalizeForKorailCommand()
        if (normalized.isBlank()) return null

        return when {
            demoJinjuToSeoulPhrases.any { normalized.contains(it) } -> KorailCommand.DEMO_JINJU_TO_SEOUL
            parseGuidedRoute(utterance) != null -> KorailCommand.GUIDED_ROUTE_SEARCH
            showMyTicketPhrases.any { normalized.contains(it) } -> KorailCommand.SHOW_MY_TICKET
            normalized.isDepartureStationRequest() -> KorailCommand.SELECT_DEPARTURE_STATION
            findReservationStartPhrases.any { normalized.contains(it) } -> {
                KorailCommand.FIND_RESERVATION_START
            }
            else -> null
        }
    }

    private fun String.normalizeForKorailCommand(): String {
        return lowercase()
            .filter { it.isLetterOrDigit() }
    }

    private fun String.isDepartureStationRequest(): Boolean {
        return departureStationPrefixes.any { prefix ->
            startsWith(prefix) && removePrefix(prefix).endsWith("역")
        }
    }

    private companion object {
        val demoJinjuToSeoulPhrases = setOf(
            "진주에서서울가는표예매해줘",
            "진주에서서울가는표예약해줘",
            "진주에서서울역가는표예매해줘",
            "진주에서서울가는ktx예매해줘",
            "진주서울표예매"
        )

        val showMyTicketPhrases = setOf(
            "예매한표보여줘",
            "예매표보여줘",
            "예매표확인",
            "내표보여줘",
            "승차권보여줘",
            "승차권확인",
            "나의티켓",
            "마이티켓",
            "표확인"
        )

        val findReservationStartPhrases = setOf(
            "승차권예매",
            "승차권예매찾기",
            "열차예매",
            "열차예약",
            "열차조회",
            "기차표예매",
            "기차표예약",
            "예매시작"
        )

        val departureStationPrefixes = setOf("출발역", "출발약", "순발력")
    }
}

data class GuidedRoute(val departureStation: String, val arrivalStation: String)

fun parseGuidedRoute(utterance: String): GuidedRoute? {
    val normalized = utterance.lowercase().filter { it.isLetterOrDigit() }
    val match = ROUTE_PATTERN.find(normalized) ?: return null
    val departure = match.groupValues[1].removeSuffix("역")
    val arrival = match.groupValues[2].removeSuffix("역")
    if (departure.isBlank() || arrival.isBlank() || departure == arrival) return null
    return GuidedRoute(departure, arrival)
}

private val ROUTE_PATTERN = Regex("^([가-힣a-z0-9]{1,12}?)(?:역)?에서([가-힣a-z0-9]{1,12}?)(?:역)?(?:으로|로)?(?:가는|행)(?:열차|기차|표|승차권)")
