package com.cuee.domain.command

class DefaultKorailCommandParser : KorailCommandParser {
    override fun parse(utterance: String): KorailCommand? {
        val normalized = utterance.normalizeForKorailCommand()
        if (normalized.isBlank()) return null

        return when {
            demoJinjuToSeoulPhrases.any { normalized.contains(it) } -> KorailCommand.DEMO_JINJU_TO_SEOUL
            showMyTicketPhrases.any { normalized.contains(it) } -> KorailCommand.SHOW_MY_TICKET
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
    }
}
