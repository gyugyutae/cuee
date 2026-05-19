package com.cuee.domain.command

class DefaultKorailCommandParser : KorailCommandParser {
    override fun parse(utterance: String): KorailCommand? {
        val normalized = utterance.normalizeForKorailCommand()
        if (normalized.isBlank()) return null

        return when {
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
        val showMyTicketPhrases = setOf(
            "예매한표보여줘",
            "내표보여줘",
            "표확인"
        )

        val findReservationStartPhrases = setOf(
            "승차권예매찾기",
            "기차표예매",
            "예매시작"
        )
    }
}
