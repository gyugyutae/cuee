package com.cuee.domain.demo

class DemoSession(
    val plan: DemoBookingPlan = DemoBookingPlan(),
    initialStep: DemoStep = DemoStep.SELECT_DEPARTURE_FIELD,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    var step: DemoStep = initialStep
        private set

    var startedAt: Long = clock()
        private set

    var updatedAt: Long = startedAt
        private set

    var retryCount: Int = 0
        private set

    var activePolicyIndex: Int = 0
        private set

    val activePolicy: SearchPolicy
        get() = plan.searchPolicies.getOrElse(activePolicyIndex) { plan.searchPolicies.last() }

    fun hasNextPolicy(): Boolean = activePolicyIndex + 1 < plan.searchPolicies.size

    fun advancePolicy(): Boolean {
        if (!hasNextPolicy()) return false
        activePolicyIndex += 1
        retryCount = 0
        updatedAt = clock()
        return true
    }

    fun advance() {
        step = step.next()
        retryCount = 0
        updatedAt = clock()
    }

    fun setStep(value: DemoStep) {
        step = value
        retryCount = 0
        updatedAt = clock()
    }

    fun markRetry() {
        retryCount += 1
        updatedAt = clock()
    }

    fun goBackToPreviousUserStep() {
        step = when (step) {
            DemoStep.INPUT_DEPARTURE,
            DemoStep.SELECT_DEPARTURE_RESULT -> DemoStep.SELECT_DEPARTURE_FIELD
            DemoStep.INPUT_ARRIVAL,
            DemoStep.SELECT_ARRIVAL_RESULT -> DemoStep.SELECT_ARRIVAL_FIELD
            DemoStep.SELECT_TOMORROW,
            DemoStep.SELECT_TIME,
            DemoStep.CONFIRM_DATE -> DemoStep.SELECT_DATE_FIELD
            DemoStep.ADULT_PLUS_1,
            DemoStep.CHILD_PLUS_1,
            DemoStep.CONFIRM_PASSENGER -> DemoStep.SELECT_PASSENGER_FIELD
            DemoStep.SCAN_VISIBLE_RESULTS,
            DemoStep.APPLY_NEXT_SEARCH_POLICY,
            DemoStep.SUGGEST_TRAIN -> DemoStep.SEARCH_TRAINS
            DemoStep.FOLLOW_USER_SELECTION,
            DemoStep.PAYMENT_ENTRY -> DemoStep.SUGGEST_TRAIN
            DemoStep.SELECT_ARRIVAL_FIELD -> DemoStep.SELECT_DEPARTURE_FIELD
            DemoStep.SELECT_DATE_FIELD -> DemoStep.SELECT_ARRIVAL_FIELD
            DemoStep.SELECT_PASSENGER_FIELD -> DemoStep.SELECT_DATE_FIELD
            DemoStep.SEARCH_TRAINS -> DemoStep.SELECT_PASSENGER_FIELD
            DemoStep.SELECT_DEPARTURE_FIELD,
            DemoStep.DONE -> DemoStep.SELECT_DEPARTURE_FIELD
        }
        retryCount = 0
        updatedAt = clock()
    }

    fun stop() {
        step = DemoStep.DONE
        updatedAt = clock()
    }
}
