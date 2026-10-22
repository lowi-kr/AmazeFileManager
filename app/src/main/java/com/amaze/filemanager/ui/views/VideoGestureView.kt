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
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Transparent overlay that turns touch gestures on a video into player actions: vertical swipe on
 * the right half changes volume, on the left half changes screen brightness, a horizontal drag
 * seeks, and a double tap seeks 10 seconds forward or back depending on which side was tapped.
 *
 * Reports gestures through [listener]; draws nothing itself, the caller shows its own feedback.
 *
 * This view sits on top of the player (it has to, to catch swipes anywhere on the video), so any
 * touch that lands on [controlsView] while that view is visible is left alone here and allowed to
 * fall through to the real control underneath, instead of being swallowed as a gesture.
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

    /**
     * The player's control bar (e.g. the exo_controller layout). While this view is visible,
     * touches that land on its on-screen bounds are not treated as gestures, so its buttons and
     * seek bar keep working normally instead of just dismissing the controller.
     */
    var controlsView: View? = null

    private var mode = MODE_NONE
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var lastSeekDelta = 0f

    private val hitRect = Rect()

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
                    // Use our own recorded ACTION_DOWN point rather than the detector's e1: e1 is
                    // a MotionEvent owned by the framework's event pool and can be recycled/mutated
                    // between callbacks, which was causing the drag's reference point to drift
                    // mid-gesture (seen as the feedback occasionally flipping sign).
                    if (mode == MODE_NONE) {
                        mode = if (abs(e2.x - downX) > abs(e2.y - downY)) MODE_SEEK else MODE_VERTICAL
                        startX = downX
                        startY = downY
                    }
                    when (mode) {
                        MODE_SEEK -> {
                            lastSeekDelta = (e2.x - startX) / width
                            listener?.onHorizontalSeek(lastSeekDelta, false)
                        }
                        MODE_VERTICAL -> listener?.onVerticalSwipe(startX > width / 2f, (startY - e2.y) / height, false)
                    }
                    return true
                }
            },
        )

    private fun touchHitsControls(event: MotionEvent): Boolean {
        val controls = controlsView ?: return false
        if (controls.visibility != View.VISIBLE) return false
        controls.getGlobalVisibleRect(hitRect)
        return hitRect.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
            if (touchHitsControls(event)) {
                // Don't claim this touch stream at all: returning false here lets the sibling
                // view underneath (the player's controller) receive the down event and handle
                // its own button/seek-bar taps normally.
                return false
            }
        }
        val handled = detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            when (mode) {
                // Previously this always sent a delta of 0f on release, which told the listener
                // to seek back to the position the drag started from -- i.e. the whole scrub
                // visually tracked the finger but was undone the moment it was released. Send the
                // last real delta instead so the seek actually lands where it was dragged to.
                MODE_SEEK -> listener?.onHorizontalSeek(lastSeekDelta, true)
                MODE_VERTICAL -> listener?.onVerticalSwipe(startX > width / 2f, 0f, true)
            }
            mode = MODE_NONE
            lastSeekDelta = 0f
        }
        return handled || mode != MODE_NONE
    }

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_SEEK = 1
        private const val MODE_VERTICAL = 2
    }
}
