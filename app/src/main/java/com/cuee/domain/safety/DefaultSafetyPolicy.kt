package com.cuee.domain.safety

import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot

class DefaultSafetyPolicy(
    private val korailPackageName: String = KORAIL_PACKAGE_NAME
) : SafetyPolicy {
    override fun evaluate(snapshot: ScreenSnapshot): SafetyDecision {
        if (snapshot.packageName != korailPackageName) {
            return SafetyDecision(
                allowed = false,
                reason = StopReason.NOT_KORAIL_APP,
                matchedPolicy = null
            )
        }

        val screenText = snapshot.nodes.joinToString(separator = " ") { it.searchableText() }
            .normalizeForSafety()

        sensitivePolicies.firstOrNull { policy ->
            policy.keywords.any { keyword -> screenText.contains(keyword.normalizeForSafety()) }
        }?.let { policy ->
            return SafetyDecision(
                allowed = false,
                reason = policy.reason,
                matchedPolicy = policy.id
            )
        }

        return SafetyDecision(allowed = true, reason = null, matchedPolicy = null)
    }

    private fun ScreenNode.searchableText(): String {
        return listOfNotNull(text, contentDescription, parentHint).joinToString(separator = " ")
    }

    private fun String.normalizeForSafety(): String {
        return lowercase().filter { it.isLetterOrDigit() }
    }

    private data class SensitivePolicy(
        val id: SafetyPolicyId,
        val reason: StopReason,
        val keywords: Set<String>
    )

    private companion object {
        const val KORAIL_PACKAGE_NAME = "com.korail.talk"

        val sensitivePolicies = listOf(
            SensitivePolicy(
                id = SafetyPolicyId.LOGIN,
                reason = StopReason.SENSITIVE_LOGIN,
                keywords = setOf("로그인", "아이디", "비밀번호", "password", "간편로그인")
            ),
            SensitivePolicy(
                id = SafetyPolicyId.PERSONAL_INFO,
                reason = StopReason.SENSITIVE_PERSONAL_INFO,
                keywords = setOf("개인정보", "주민등록", "생년월일", "휴대폰번호", "전화번호", "이메일")
            ),
            SensitivePolicy(
                id = SafetyPolicyId.AUTH_CODE,
                reason = StopReason.SENSITIVE_AUTH_CODE,
                keywords = setOf("인증번호", "본인인증", "휴대폰인증", "보안문자", "otp")
            ),
            SensitivePolicy(
                id = SafetyPolicyId.PAYMENT,
                reason = StopReason.SENSITIVE_PAYMENT,
                keywords = setOf("결제", "카드번호", "신용카드", "계좌번호", "간편결제")
            ),
            SensitivePolicy(
                id = SafetyPolicyId.RESERVATION_CONFIRMATION,
                reason = StopReason.SENSITIVE_CONFIRMATION,
                keywords = setOf("예매확정", "구매확정", "예약확정", "최종확인")
            )
        )
    }
}
