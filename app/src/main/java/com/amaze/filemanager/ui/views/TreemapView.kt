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

package com.amaze.filemanager.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import com.amaze.filemanager.analyse.Treemap
import com.amaze.filemanager.analyse.TreemapItem
import com.amaze.filemanager.analyse.TreemapRect

/** Draws a squarified treemap of [items]. Tapping a block calls [onBlockClick]. */
class TreemapView(context: Context) : View(context) {
    var items: List<TreemapItem> = emptyList()
        set(value) {
            field = value
            relayout()
        }

    var onBlockClick: ((TreemapItem) -> Unit)? = null

    private var rects: List<TreemapRect> = emptyList()
    private val density = resources.displayMetrics.density
    private val gap = 1.5f * density
    private val pad = 6f * density
    private val box = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.DEFAULT_BOLD
        }
    private val subPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xDDFFFFFF.toInt()
            textSize = 11f * density
        }
    private var downX = 0f
    private var downY = 0f

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        rects =
            if (width > 0 && height > 0) {
                Treemap.layout(items, 0f, 0f, width.toFloat(), height.toFloat())
            } else {
                emptyList()
            }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val minW = 54f * density
        val minH = 30f * density
        for ((i, r) in rects.withIndex()) {
            box.set(r.x + gap, r.y + gap, r.x + r.w - gap, r.y + r.h - gap)
            if (box.width() <= 1f || box.height() <= 1f) continue
            fill.color = PALETTE[i % PALETTE.size]
            canvas.drawRect(box, fill)
            if (box.width() < minW || box.height() < minH) continue
            canvas.save()
            canvas.clipRect(box)
            val maxW = box.width() - 2 * pad
            val name = TextUtils.ellipsize(r.item.label, namePaint, maxW, TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, box.left + pad, box.top + pad + namePaint.textSize, namePaint)
            if (r.item.sub.isNotEmpty() && box.height() > minH * 1.8f) {
                val sub = TextUtils.ellipsize(r.item.sub, subPaint, maxW, TextUtils.TruncateAt.END)
                val y = box.top + pad + namePaint.textSize + subPaint.textSize + 4f * density
                canvas.drawText(sub, 0, sub.length, box.left + pad, y, subPaint)
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slop = 12f * density
                if (Math.abs(event.x - downX) < slop && Math.abs(event.y - downY) < slop) {
                    val hit = rects.firstOrNull { event.x >= it.x && event.x < it.x + it.w && event.y >= it.y && event.y < it.y + it.h }
                    if (hit != null) {
                        performClick()
                        onBlockClick?.invoke(hit.item)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private val PALETTE =
            intArrayOf(
                0xFF3F51B5.toInt(), 0xFF00897B.toInt(), 0xFF8E24AA.toInt(), 0xFFC62828.toInt(),
                0xFFEF6C00.toInt(), 0xFF2E7D32.toInt(), 0xFF00838F.toInt(), 0xFF6D4C41.toInt(),
                0xFF5E35B1.toInt(), 0xFFAD1457.toInt(),
            )
    }
}
