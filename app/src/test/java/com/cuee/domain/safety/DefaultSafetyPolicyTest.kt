package com.cuee.domain.safety

import com.cuee.domain.scoring.Bounds
import com.cuee.domain.scoring.ScreenNode
import com.cuee.domain.scoring.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSafetyPolicyTest {
    private val policy = DefaultSafetyPolicy()

    @Test
    fun stopsOutsideKorailTalk() {
        val decision = policy.evaluate(snapshot(packageName = "com.other.app"))

        assertFalse(decision.allowed)
        assertEquals(StopReason.NOT_KORAIL_APP, decision.reason)
        assertNull(decision.matchedPolicy)
    }

    @Test
    fun stopsSensitiveScreens() {
        val cases = listOf(
            "로그인" to StopReason.SENSITIVE_LOGIN,
            "개인정보 입력" to StopReason.SENSITIVE_PERSONAL_INFO,
            "인증번호" to StopReason.SENSITIVE_AUTH_CODE,
            "결제하기" to StopReason.SENSITIVE_PAYMENT,
            "예매 확정" to StopReason.SENSITIVE_CONFIRMATION,
            "구매 확정" to StopReason.SENSITIVE_CONFIRMATION
        )

        cases.forEach { (text, reason) ->
            val decision = policy.evaluate(snapshot(nodes = listOf(node(text = text))))
            assertFalse(text, decision.allowed)
            assertEquals(text, reason, decision.reason)
        }
    }

    @Test
    fun allowsNormalKorailScreen() {
        val decision = policy.evaluate(snapshot(nodes = listOf(node(text = "승차권 예매"))))

        assertTrue(decision.allowed)
        assertNull(decision.reason)
    }

    private fun snapshot(
        packageName: String = "com.korail.talk",
        nodes: List<ScreenNode> = emptyList()
    ): ScreenSnapshot {
        return ScreenSnapshot(packageName = packageName, nodes = nodes, capturedAt = 1L)
    }

    private fun node(text: String): ScreenNode {
        return ScreenNode(
            id = text,
            text = text,
            contentDescription = null,
            className = "android.widget.TextView",
            packageName = "com.korail.talk",
            bounds = Bounds(0, 0, 100, 48),
            clickable = false,
            enabled = true,
            visible = true,
            scrollable = false,
            editable = false,
            depth = 0,
            parentHint = null
        )
    }
}
