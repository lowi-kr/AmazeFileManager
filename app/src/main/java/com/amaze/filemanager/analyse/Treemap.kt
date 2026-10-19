/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.analyse

/** One block to place in a treemap. [weight] is usually a size in bytes. */
class TreemapItem(
    val label: String,
    val weight: Double,
    val tag: Any? = null,
    val sub: String = "",
)

/** A placed block, in the same coordinate system as the area passed to [Treemap.layout]. */
class TreemapRect(
    val item: TreemapItem,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

/** Squarified treemap layout (Bruls, Huizing and van Wijk): keeps blocks close to square. */
object Treemap {
    fun layout(
        items: List<TreemapItem>,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ): List<TreemapRect> {
        val sorted = items.filter { it.weight > 0 }.sortedByDescending { it.weight }
        if (sorted.isEmpty() || w <= 0f || h <= 0f) return emptyList()
        val total = sorted.sumOf { it.weight }
        val scale = (w.toDouble() * h.toDouble()) / total
        val areas = DoubleArray(sorted.size) { sorted[it].weight * scale }

        val out = ArrayList<TreemapRect>(sorted.size)
        var rx = x.toDouble()
        var ry = y.toDouble()
        var rw = w.toDouble()
        var rh = h.toDouble()
        var start = 0
        while (start < sorted.size) {
            val side = minOf(rw, rh)
            var end = start + 1
            var rowSum = areas[start]
            var rowMin = areas[start]
            var rowMax = areas[start]
            while (end < sorted.size) {
                val a = areas[end]
                val newSum = rowSum + a
                val newMin = minOf(rowMin, a)
                val newMax = maxOf(rowMax, a)
                if (worst(newSum, newMin, newMax, side) <= worst(rowSum, rowMin, rowMax, side)) {
                    rowSum = newSum
                    rowMin = newMin
                    rowMax = newMax
                    end++
                } else {
                    break
                }
            }
            // lay the row along the shorter side
            if (rw >= rh) {
                val colW = if (rh > 0) rowSum / rh else 0.0
                var cy = ry
                for (i in start until end) {
                    val bh = if (colW > 0) areas[i] / colW else 0.0
                    out.add(TreemapRect(sorted[i], rx.toFloat(), cy.toFloat(), colW.toFloat(), bh.toFloat()))
                    cy += bh
                }
                rx += colW
                rw -= colW
            } else {
                val rowH = if (rw > 0) rowSum / rw else 0.0
                var cx = rx
                for (i in start until end) {
                    val bw = if (rowH > 0) areas[i] / rowH else 0.0
                    out.add(TreemapRect(sorted[i], cx.toFloat(), ry.toFloat(), bw.toFloat(), rowH.toFloat()))
                    cx += bw
                }
                ry += rowH
                rh -= rowH
            }
            start = end
        }
        return out
    }

    private fun worst(
        sum: Double,
        min: Double,
        max: Double,
        side: Double,
    ): Double {
        val s2 = side * side
        return maxOf(s2 * max / (sum * sum), (sum * sum) / (s2 * min))
    }
}
