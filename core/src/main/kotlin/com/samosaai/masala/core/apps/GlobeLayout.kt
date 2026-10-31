package com.samosaai.masala.core.apps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The globe drawer's packing (plan UI refresh U3, globe board): the most-used apps as circles in a
 * round globe, sized by how often each is opened and placed biggest first, each at the first free spot
 * on a spiral out from the centre. Pure and deterministic: the same counts always give the same layout,
 * so apps only move when their rank changes. Units are the design's: a globe of radius [GLOBE_RADIUS]
 * centred at ([CENTER], [CENTER]); the caller scales to its width.
 */
object GlobeLayout {
    const val CENTER = 185.0
    const val GLOBE_RADIUS = 178.0
    const val GAP = 5.0

    /** Diameters before the fill factor: 30 for an unused app up to 88 for the most used. */
    private const val MIN_DIAMETER = 30.0
    private const val SPREAD = 58.0

    /** Share of the globe's area the circles fill together, whatever the usage spread. */
    const val FILL = 0.47

    data class Circle(val x: Double, val y: Double, val r: Double)

    /**
     * Diameters for [counts]: a square-root scale (so a heavy favourite doesn't dwarf the rest), then one
     * factor so the circles fill about [FILL] of the globe; never scaled up past the raw size.
     */
    fun diameters(counts: List<Int>): List<Double> {
        if (counts.isEmpty()) return emptyList()
        val max = maxOf(counts.max(), 1)
        val raw = counts.map { MIN_DIAMETER + SPREAD * sqrt(it.coerceAtLeast(0).toDouble() / max) }
        val area = raw.sumOf { it * it } * PI / 4
        val k = min(1.0, sqrt(FILL * PI * GLOBE_RADIUS * GLOBE_RADIUS / area))
        return raw.map { it * k }
    }

    /** Places circles of [radii], biggest first (ties keep their order); returns one per input, in order. */
    fun pack(radii: List<Double>): List<Circle> {
        val order = radii.indices.sortedByDescending { radii[it] }
        val pos = arrayOfNulls<Circle>(radii.size)
        val placed = ArrayList<Circle>(radii.size)
        for (i in order) {
            var r = radii[i]
            var spot: Pair<Double, Double>? = null
            // A circle that fits nowhere is shrunk (up to three times) before it gives up.
            var tries = 0
            while (tries < 4 && spot == null) {
                var th = 0.0
                while (true) {
                    val rr = 1.3 * th
                    if (rr + r > GLOBE_RADIUS) break
                    val x = CENTER + rr * cos(th)
                    val y = CENTER + rr * sin(th)
                    val free = placed.none { p ->
                        val dx = x - p.x
                        val dy = y - p.y
                        val m = r + p.r + GAP
                        dx * dx + dy * dy < m * m
                    }
                    if (free) {
                        spot = x to y
                        break
                    }
                    th += 3 / maxOf(rr, 6.0)
                }
                if (spot == null) r *= 0.85
                tries++
            }
            val (x, y) = spot ?: (CENTER to CENTER + GLOBE_RADIUS - r)
            val c = Circle(x, y, r)
            pos[i] = c
            placed += c
        }
        return pos.map { it!! }
    }

    /** [diameters] then [pack]: the globe for these launch counts. */
    fun layout(counts: List<Int>): List<Circle> = pack(diameters(counts).map { it / 2 })
}
