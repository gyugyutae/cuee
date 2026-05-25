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

    fun stop() {
        step = DemoStep.DONE
        updatedAt = clock()
    }
}
