package com.cuee.domain.safety

data class SafetyDecision(
    val allowed: Boolean,
    val reason: StopReason?,
    val matchedPolicy: SafetyPolicyId?
)

enum class SafetyPolicyId {
    LOGIN,
    PERSONAL_INFO,
    PAYMENT,
    AUTH_CODE,
    RESERVATION_CONFIRMATION
}
