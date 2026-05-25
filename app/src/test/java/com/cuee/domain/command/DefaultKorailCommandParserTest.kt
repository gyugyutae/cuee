package com.cuee.domain.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultKorailCommandParserTest {
    private val parser = DefaultKorailCommandParser()

    @Test
    fun parsesOnlySupportedCommands() {
        assertEquals(KorailCommand.SHOW_MY_TICKET, parser.parse("예매한 표 보여줘"))
        assertEquals(KorailCommand.SHOW_MY_TICKET, parser.parse("예매표 확인"))
        assertEquals(KorailCommand.SHOW_MY_TICKET, parser.parse("내 표 보여줘"))
        assertEquals(KorailCommand.SHOW_MY_TICKET, parser.parse("나의 티켓"))
        assertEquals(KorailCommand.SHOW_MY_TICKET, parser.parse("표 확인"))

        assertEquals(KorailCommand.FIND_RESERVATION_START, parser.parse("승차권 예매 찾기"))
        assertEquals(KorailCommand.FIND_RESERVATION_START, parser.parse("열차 예매"))
        assertEquals(KorailCommand.FIND_RESERVATION_START, parser.parse("열차 조회"))
        assertEquals(KorailCommand.FIND_RESERVATION_START, parser.parse("기차표 예매"))
        assertEquals(KorailCommand.FIND_RESERVATION_START, parser.parse("예매 시작"))

        assertEquals(KorailCommand.DEMO_JINJU_TO_SEOUL, parser.parse("진주에서 서울 가는 표 예매해줘"))
        assertEquals(KorailCommand.DEMO_JINJU_TO_SEOUL, parser.parse("진주에서 서울역 가는 표 예매해줘"))
    }

    @Test
    fun rejectsUnsupportedCommand() {
        assertNull(parser.parse("결제해줘"))
        assertNull(parser.parse("예약 확정 눌러줘"))
    }
}
