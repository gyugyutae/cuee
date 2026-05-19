package com.cuee.domain.scoring

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height

    fun isValid(): Boolean = right > left && bottom > top

    fun intersects(other: Bounds): Boolean {
        return left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top
    }

    fun intersectionArea(other: Bounds): Int {
        val intersectionLeft = maxOf(left, other.left)
        val intersectionTop = maxOf(top, other.top)
        val intersectionRight = minOf(right, other.right)
        val intersectionBottom = minOf(bottom, other.bottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0
        }
        return (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
    }

    fun overlapRatio(other: Bounds): Double {
        val smallerArea = minOf(area, other.area)
        if (smallerArea <= 0) return 0.0
        return intersectionArea(other).toDouble() / smallerArea.toDouble()
    }
}
