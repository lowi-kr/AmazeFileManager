/*
 * Copyright (C) 2014-2026 Amaze File Manager contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.amaze.filemanager.ui.activities

import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.amaze.filemanager.R

/**
 * In-app image viewer with pinch-to-zoom, double-tap zoom, and panning.
 */
class ImageViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri = intent?.data ?: run {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val imageView = ZoomableImageView(this)
        imageView.contentDescription = getString(R.string.image_viewer)
        imageView.setBackgroundColor(android.graphics.Color.BLACK)
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.setImageURI(uri)

        if (imageView.drawable == null) {
            Toast.makeText(this, R.string.unsupported_operation, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                addView(
                    imageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private class ZoomableImageView(context: android.content.Context) : AppCompatImageView(context) {
    private val imageMatrixValues = FloatArray(9)
    private val matrixRect = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var currentScale = 1f
    private val baseMatrix = Matrix()
    private val touchMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val nextScale = (currentScale * detector.scaleFactor).coerceIn(1f, 5f)
                val factor = nextScale / currentScale
                currentScale = nextScale
                touchMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        },
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetScale = if (currentScale > 1.05f) 1f else 2.5f
                val factor = targetScale / currentScale
                currentScale = targetScale
                touchMatrix.postScale(factor, factor, e.x, e.y)
                applyMatrix()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImageToView()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { fitImageToView() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale > 1f) {
                    touchMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    applyMatrix()
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun fitImageToView() {
        val image = drawable ?: return
        if (width <= 0 || height <= 0 || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) {
            return
        }
        val source = RectF(
            0f,
            0f,
            image.intrinsicWidth.toFloat(),
            image.intrinsicHeight.toFloat(),
        )
        val destination = RectF(0f, 0f, width.toFloat(), height.toFloat())
        baseMatrix.setRectToRect(source, destination, Matrix.ScaleToFit.CENTER)
        touchMatrix.set(baseMatrix)
        currentScale = 1f
        applyMatrix()
    }

    private fun applyMatrix() {
        touchMatrix.getValues(imageMatrixValues)
        touchMatrix.getValues(imageMatrixValues)
        val image = drawable ?: return
        matrixRect.set(
            0f,
            0f,
            image.intrinsicWidth.toFloat(),
            image.intrinsicHeight.toFloat(),
        )
        touchMatrix.mapRect(matrixRect)
        val dx = when {
            matrixRect.width() <= width -> width / 2f - matrixRect.centerX()
            matrixRect.left > 0f -> -matrixRect.left
            matrixRect.right < width -> width - matrixRect.right
            else -> 0f
        }
        val dy = when {
            matrixRect.height() <= height -> height / 2f - matrixRect.centerY()
            matrixRect.top > 0f -> -matrixRect.top
            matrixRect.bottom < height -> height - matrixRect.bottom
            else -> 0f
        }
        touchMatrix.postTranslate(dx, dy)
        imageMatrix = touchMatrix
        invalidate()
    }
}
