package com.cuee.overlay

import com.cuee.domain.scoring.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutCalculatorTest {
    @Test
    fun padsCandidateAndClampsToScreen() {
        val calculator = OverlayLayoutCalculator(candidatePaddingPx = 6)
        val layout = calculator.calculate(
            screen = Bounds(0, 0, 100, 100),
            candidates = listOf(Bounds(0, 0, 10, 10))
        )

        assertEquals(listOf(Bounds(0, 0, 16, 16)), layout.holes)
    }

    @Test
    fun maskRectanglesNeverIntersectVisibleHole() {
        val calculator = OverlayLayoutCalculator(candidatePaddingPx = 0)
        val hole = Bounds(40, 40, 60, 60)
        val layout = calculator.calculate(
            screen = Bounds(0, 0, 100, 100),
            candidates = listOf(hole)
        )

        assertTrue(layout.maskRects.isNotEmpty())
        assertTrue(layout.maskRects.none { it.intersects(hole) })
        assertFalse(layout.maskRects.contains(hole))
    }

    @Test
    fun overlappingPaddedHolesAreMerged() {
        val calculator = OverlayLayoutCalculator(candidatePaddingPx = 4)
        val layout = calculator.calculate(
            screen = Bounds(0, 0, 100, 100),
            candidates = listOf(
                Bounds(20, 20, 40, 40),
                Bounds(38, 20, 58, 40)
            )
        )

        assertEquals(listOf(Bounds(16, 16, 62, 44)), layout.holes)
    }

    @Test
    fun emptyCandidatesMaskWholeScreen() {
        val calculator = OverlayLayoutCalculator(candidatePaddingPx = 0)
        val screen = Bounds(0, 0, 100, 100)

        val layout = calculator.calculate(screen = screen, candidates = emptyList())

        assertEquals(emptyList<Bounds>(), layout.holes)
        assertEquals(listOf(screen), layout.maskRects)
    }
}
