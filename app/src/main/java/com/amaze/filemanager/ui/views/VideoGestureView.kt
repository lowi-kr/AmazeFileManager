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
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Transparent overlay that turns touch gestures on a video into player actions: vertical swipe on
 * the right half changes volume, on the left half changes screen brightness, a horizontal drag
 * seeks, and a double tap seeks 10 seconds forward or back depending on which side was tapped.
 *
 * Reports gestures through [listener]; draws nothing itself, the caller shows its own feedback.
 */
class VideoGestureView(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Listener {
        /** A vertical swipe. [deltaFraction] is the drag distance divided by the view height, sign up = positive. */
        fun onVerticalSwipe(
            rightSide: Boolean,
            deltaFraction: Float,
            finished: Boolean,
        )

        /** A horizontal drag to seek. [deltaFraction] is the drag distance divided by the view width. */
        fun onHorizontalSeek(
            deltaFraction: Float,
            finished: Boolean,
        )

        fun onDoubleTap(rightSide: Boolean)

        fun onSingleTap()
    }

    var listener: Listener? = null
    var gesturesEnabled: Boolean = true

    private var mode = MODE_NONE
    private var startX = 0f
    private var startY = 0f

    private val detector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    listener?.onSingleTap()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    listener?.onDoubleTap(e.x > width / 2f)
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (!gesturesEnabled || width == 0 || height == 0) return false
                    val start = e1 ?: return false
                    if (mode == MODE_NONE) {
                        mode =
                            if (abs(e2.x - start.x) > abs(e2.y - start.y)) MODE_SEEK else MODE_VERTICAL
                        startX = start.x
                        startY = start.y
                    }
                    when (mode) {
                        MODE_SEEK -> listener?.onHorizontalSeek((e2.x - startX) / width, false)
                        MODE_VERTICAL -> listener?.onVerticalSwipe(startX > width / 2f, (startY - e2.y) / height, false)
                    }
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            when (mode) {
                MODE_SEEK -> listener?.onHorizontalSeek(0f, true)
                MODE_VERTICAL -> listener?.onVerticalSwipe(startX > width / 2f, 0f, true)
            }
            mode = MODE_NONE
        }
        return handled || mode != MODE_NONE
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_SEEK = 1
        private const val MODE_VERTICAL = 2
    }
}
