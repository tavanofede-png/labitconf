package org.nieto.assistant.presence

import kotlin.math.roundToInt

/** Coordinates inside the safe screen area, independent of Android for deterministic tests. */
internal object BubbleDock {
    enum class Edge { LEFT, TOP, RIGHT, BOTTOM }
    data class Point(val x: Int, val y: Int)
    data class Pin(val edge: Edge, val fraction: Float)
    fun position(pin: Pin, width: Int, height: Int): Point {
        val w = width.coerceAtLeast(0); val h = height.coerceAtLeast(0)
        val f = pin.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f
        return when (pin.edge) {
            Edge.LEFT -> Point(0, (h * f).roundToInt())
            Edge.RIGHT -> Point(w, (h * f).roundToInt())
            Edge.TOP -> Point((w * f).roundToInt(), 0)
            Edge.BOTTOM -> Point((w * f).roundToInt(), h)
        }
    }
    fun nearest(x: Int, y: Int, width: Int, height: Int): Pin {
        val w = width.coerceAtLeast(0); val h = height.coerceAtLeast(0)
        val cx = x.coerceIn(0, w); val cy = y.coerceIn(0, h)
        val edge = listOf(Edge.LEFT to cx, Edge.RIGHT to w - cx, Edge.TOP to cy, Edge.BOTTOM to h - cy).minBy { it.second }.first
        return Pin(edge, if (edge == Edge.LEFT || edge == Edge.RIGHT) { if (h == 0) 0f else cy.toFloat() / h } else { if (w == 0) 0f else cx.toFloat() / w })
    }
}
