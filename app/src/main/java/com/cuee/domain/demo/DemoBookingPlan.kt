package com.cuee.domain.demo

data class DemoBookingPlan(
    val departureStation: String = "진주",
    val arrivalStation: String = "서울",
    val searchPolicies: List<SearchPolicy> = defaultJinjuSeoulPolicies(),
    val passengers: PassengerPlan = PassengerPlan(adults = 2, children = 1)
)

data class PassengerPlan(
    val adults: Int,
    val children: Int = 0,
    val infants: Int = 0,
    val seniors: Int = 0,
    val severeDisabled: Int = 0,
    val mildDisabled: Int = 0
)

data class SearchPolicy(
    val dateOffsetDays: Long,
    val earliestDepartureHour: Int,
    val label: String
)

fun defaultJinjuSeoulPolicies(): List<SearchPolicy> {
    return listOf(
        SearchPolicy(dateOffsetDays = 1, earliestDepartureHour = 6, label = "내일 06시 이후"),
        SearchPolicy(dateOffsetDays = 1, earliestDepartureHour = 0, label = "내일 시간 전체"),
        SearchPolicy(dateOffsetDays = 2, earliestDepartureHour = 6, label = "다음날 06시 이후"),
        SearchPolicy(dateOffsetDays = 2, earliestDepartureHour = 0, label = "다음날 시간 전체")
    )
}
