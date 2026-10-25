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
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * A vertical [RecyclerView] that supports pinch to zoom, double tap to zoom and panning while
 * zoomed.
 *
 * The whole list is drawn scaled from its top left corner. Horizontal panning is a canvas
 * translation, vertical panning scrolls the list itself. Zoom is never below 1.
 */
class ZoomRecyclerView(context: Context) : RecyclerView(context) {
    /** Current zoom factor, 1 means not zoomed. */
    var zoom = 1f
        private set

    /** Called when a zoom gesture ends, with the final zoom factor. */
    var onZoomSettled: ((Float) -> Unit)? = null

    private var offsetX = 0f

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    cancelListTouch()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    applyZoom(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    onZoomSettled?.invoke(zoom)
                }
            },
        )

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (zoom <= 1f) return false
                    offsetX = (offsetX - distanceX).coerceIn(width - width * zoom, 0f)
                    scrollBy(0, (distanceY / zoom).roundToInt())
                    invalidate()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (zoom <= 1f) return false
                    fling(0, (-velocityY / zoom).roundToInt())
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val target = if (zoom > 1.01f) MIN_ZOOM else DOUBLE_TAP_ZOOM
                    applyZoom(target, e.x, e.y)
                    onZoomSettled?.invoke(zoom)
                    return true
                }
            },
        )

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) return true
        // While zoomed the gesture detector pans, otherwise the list scrolls normally
        return if (zoom > 1f) true else super.onTouchEvent(e)
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(offsetX, 0f)
        canvas.scale(zoom, zoom)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    /** Stops any scroll the list had started, once a pinch takes over the gesture. */
    private fun cancelListTouch() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    /** Zooms to target keeping the content under (focusX, focusY) in place. */
    private fun applyZoom(
        target: Float,
        focusX: Float,
        focusY: Float,
    ) {
        val newZoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val oldZoom = zoom
        if (newZoom == oldZoom) return

        val contentX = (focusX - offsetX) / oldZoom
        val contentY = focusY / oldZoom
        zoom = newZoom
        offsetX = (focusX - contentX * newZoom).coerceIn(width - width * newZoom, 0f)
        scrollBy(0, (contentY - focusY / newZoom).roundToInt())
        invalidate()
    }

    companion object {
        private const val MIN_ZOOM = 1f
        // Higher ceiling so small text / detailed diagrams stay readable
        private const val MAX_ZOOM = 8f
        private const val DOUBLE_TAP_ZOOM = 3f
    }
}
