package com.cuee.overlay

import com.cuee.domain.scoring.Bounds

class OverlayLayoutCalculator(
    private val candidatePaddingPx: Int = DEFAULT_CANDIDATE_PADDING_PX
) {
    fun calculate(screen: Bounds, candidates: List<Bounds>): OverlayLayout {
        if (!screen.isValid()) return OverlayLayout(holes = emptyList(), maskRects = emptyList())

        val holes = mergeOverlapping(
            candidates
                .asSequence()
                .filter { it.isValid() }
                .map { it.padded(candidatePaddingPx).clampedTo(screen) }
                .filter { it.isValid() }
                .toList()
        )

        return OverlayLayout(
            holes = holes,
            maskRects = maskRectangles(screen, holes)
        )
    }

    fun overlaps(left: Bounds, right: Bounds): Boolean = left.intersects(right)

    fun padded(bounds: Bounds, screen: Bounds): Bounds {
        return bounds.padded(candidatePaddingPx).clampedTo(screen)
    }

    fun maskRectangles(screen: Bounds, holes: List<Bounds>): List<Bounds> {
        if (!screen.isValid()) return emptyList()
        if (holes.isEmpty()) return listOf(screen)

        val clippedHoles = holes
            .map { it.clampedTo(screen) }
            .filter { it.isValid() }
        if (clippedHoles.isEmpty()) return listOf(screen)

        val xs = mutableSetOf(screen.left, screen.right)
        val ys = mutableSetOf(screen.top, screen.bottom)
        clippedHoles.forEach { hole ->
            xs += hole.left
            xs += hole.right
            ys += hole.top
            ys += hole.bottom
        }

        val sortedX = xs.sorted()
        val sortedY = ys.sorted()
        val result = mutableListOf<Bounds>()
        for (yIndex in 0 until sortedY.lastIndex) {
            for (xIndex in 0 until sortedX.lastIndex) {
                val rect = Bounds(
                    left = sortedX[xIndex],
                    top = sortedY[yIndex],
                    right = sortedX[xIndex + 1],
                    bottom = sortedY[yIndex + 1]
                )
                if (rect.isValid() && clippedHoles.none { rect.intersects(it) }) {
                    result += rect
                }
            }
        }
        return result.sortedWith(compareBy<Bounds> { it.top }.thenBy { it.left })
    }

    private fun mergeOverlapping(bounds: List<Bounds>): List<Bounds> {
        val merged = mutableListOf<Bounds>()
        bounds.forEach { boundsToAdd ->
            var current = boundsToAdd
            var index = 0
            while (index < merged.size) {
                if (current.intersects(merged[index])) {
                    current = current.union(merged.removeAt(index))
                } else {
                    index += 1
                }
            }
            merged += current
        }
        return merged.sortedWith(compareBy<Bounds> { it.top }.thenBy { it.left })
    }

    private fun mergeAdjacent(bounds: List<Bounds>): List<Bounds> {
        var result = bounds
        var changed: Boolean
        do {
            changed = false
            val next = mutableListOf<Bounds>()
            val consumed = BooleanArray(result.size)
            for (index in result.indices) {
                if (consumed[index]) continue
                var current = result[index]
                consumed[index] = true
                for (candidateIndex in index + 1 until result.size) {
                    if (!consumed[candidateIndex] && current.canMerge(result[candidateIndex])) {
                        current = current.union(result[candidateIndex])
                        consumed[candidateIndex] = true
                        changed = true
                    }
                }
                next += current
            }
            result = next
        } while (changed)
        return result.sortedWith(compareBy<Bounds> { it.top }.thenBy { it.left })
    }

    private fun Bounds.padded(padding: Int): Bounds {
        return Bounds(
            left = left - padding,
            top = top - padding,
            right = right + padding,
            bottom = bottom + padding
        )
    }

    private fun Bounds.clampedTo(screen: Bounds): Bounds {
        return Bounds(
            left = left.coerceIn(screen.left, screen.right),
            top = top.coerceIn(screen.top, screen.bottom),
            right = right.coerceIn(screen.left, screen.right),
            bottom = bottom.coerceIn(screen.top, screen.bottom)
        )
    }

    private fun Bounds.union(other: Bounds): Bounds {
        return Bounds(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom)
        )
    }

    private fun Bounds.canMerge(other: Bounds): Boolean {
        val sameHeightTouching = top == other.top && bottom == other.bottom && (right == other.left || left == other.right)
        val sameWidthTouching = left == other.left && right == other.right && (bottom == other.top || top == other.bottom)
        return sameHeightTouching || sameWidthTouching
    }

    private companion object {
        const val DEFAULT_CANDIDATE_PADDING_PX = 6
    }
}

data class OverlayLayout(
    val holes: List<Bounds>,
    val maskRects: List<Bounds>
)
