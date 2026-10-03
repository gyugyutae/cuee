package com.cuee.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundPackageResolverTest {
    @Test
    fun `background system event does not replace active Korail package`() {
        assertEquals(
            KORAIL,
            resolveForegroundPackage(
                currentPackageName = KORAIL,
                eventPackageName = "com.android.systemui",
                activeWindowPackageName = KORAIL,
                servicePackageName = CUEE,
                targetPackageName = KORAIL,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        )
    }

    @Test
    fun `active app replaces stale Korail package`() {
        assertEquals(
            "com.example.other",
            resolveForegroundPackage(
                currentPackageName = KORAIL,
                eventPackageName = "com.example.other",
                activeWindowPackageName = "com.example.other",
                servicePackageName = CUEE,
                targetPackageName = KORAIL,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
        )
    }

    private companion object {
        const val CUEE = "com.cuee"
        const val KORAIL = "com.korail.talk"
    }

    @Test
    fun `own application screen replaces Korail but overlay does not`() {
        assertEquals(CUEE, resolveForegroundPackage(KORAIL, CUEE, CUEE, CUEE, KORAIL,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, focusedApplicationPackageName = CUEE))
        assertEquals(KORAIL, resolveForegroundPackage(KORAIL, CUEE, CUEE, CUEE, KORAIL,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, focusedApplicationPackageName = KORAIL))
    }
}
