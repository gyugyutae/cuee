package com.cuee.domain.demo

enum class DemoStep {
    SELECT_DEPARTURE_FIELD,
    INPUT_DEPARTURE,
    SELECT_DEPARTURE_RESULT,
    SELECT_ARRIVAL_FIELD,
    INPUT_ARRIVAL,
    SELECT_ARRIVAL_RESULT,
    SELECT_DATE_FIELD,
    SELECT_TOMORROW,
    SELECT_TIME,
    CONFIRM_DATE,
    SELECT_PASSENGER_FIELD,
    ADULT_PLUS_1,
    CHILD_PLUS_1,
    CONFIRM_PASSENGER,
    SEARCH_TRAINS,
    SCAN_VISIBLE_RESULTS,
    APPLY_NEXT_SEARCH_POLICY,
    SUGGEST_TRAIN,
    FOLLOW_USER_SELECTION,
    PAYMENT_ENTRY,
    DONE
}

fun DemoStep.next(): DemoStep {
    val values = DemoStep.entries
    return values.getOrElse(ordinal + 1) { DemoStep.DONE }
}

/** 각 단계에서 사용자에게 읽어 줄 기본 안내 문구. 화면 분석 결과에 별도 메시지가 없을 때 쓰인다. */
fun DemoStep.simpleInstruction(): String {
    return when (this) {
        DemoStep.SELECT_DEPARTURE_FIELD -> "1단계. 출발역을 눌러 주세요."
        DemoStep.INPUT_DEPARTURE -> "검색창을 눌러 주세요. 진주를 입력해 드릴게요."
        DemoStep.SELECT_DEPARTURE_RESULT -> "검색 결과에서 진주역을 눌러 주세요."
        DemoStep.SELECT_ARRIVAL_FIELD -> "도착역을 눌러 주세요."
        DemoStep.INPUT_ARRIVAL -> "검색창을 눌러 주세요. 서울을 입력해 드릴게요."
        DemoStep.SELECT_ARRIVAL_RESULT -> "검색 결과에서 서울역을 눌러 주세요."
        DemoStep.SELECT_DATE_FIELD -> "가는 날을 눌러 주세요."
        DemoStep.SELECT_TOMORROW -> "달력에서 내일을 눌러 주세요."
        DemoStep.SELECT_TIME -> "출발 시간을 오전 6시 이후로 맞춰 주세요."
        DemoStep.CONFIRM_DATE -> "날짜와 시간을 확인한 뒤 확인을 눌러 주세요."
        DemoStep.SELECT_PASSENGER_FIELD -> "인원을 눌러 주세요."
        DemoStep.ADULT_PLUS_1 -> "어른을 한 명 더해 2명으로 맞춰 주세요."
        DemoStep.CHILD_PLUS_1 -> "어린이를 한 명으로 맞춰 주세요."
        DemoStep.CONFIRM_PASSENGER -> "인원을 확인한 뒤 확인을 눌러 주세요."
        DemoStep.SEARCH_TRAINS -> "열차 조회를 눌러 주세요."
        DemoStep.SCAN_VISIBLE_RESULTS,
        DemoStep.APPLY_NEXT_SEARCH_POLICY -> "조건에 맞는 열차를 찾고 있어요. 잠시 기다려 주세요."
        DemoStep.SUGGEST_TRAIN -> "강조된 열차를 확인하고 직접 선택해 주세요."
        DemoStep.FOLLOW_USER_SELECTION -> "예매 정보가 맞는지 확인하고 다음 버튼을 직접 눌러 주세요."
        DemoStep.PAYMENT_ENTRY -> "결제 전 정보는 직접 확인해 주세요. 큐 안내는 여기서 멈춥니다."
        DemoStep.DONE -> "안내를 마쳤어요."
    }
}
