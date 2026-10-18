/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam <emmanuelbendavid@gmail.com> and Contributors.
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
 */

package com.amaze.filemanager.ui.activities

import android.content.Intent
import android.database.Cursor
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.amaze.filemanager.R
import java.text.DecimalFormat

/**
 * In-app image viewer with pinch-to-zoom, double-tap zoom, image details, and sharing.
 */
class ImageViewerActivity : AppCompatActivity() {
    private var imageUri: Uri? = null
    private var imageView: ZoomableImageView? = null
    private var overlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri = intent?.data ?: run {
            finish()
            return
        }
        imageUri = uri

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val viewer = ZoomableImageView(this).apply {
            contentDescription = getString(R.string.image_viewer)
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType = ImageView.ScaleType.MATRIX
            setImageURI(uri)
        }
        if (viewer.drawable == null) {
            Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        imageView = viewer

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(
                viewer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0x99000000.toInt())
        }
        val title = TextView(this).apply {
            text = displayName(uri)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        val detailsButton = toolbarButton("ⓘ", "Image details")
        detailsButton.setOnClickListener { showImageDetails(uri) }
        val shareButton = toolbarButton("↗", "Share image")
        shareButton.setOnClickListener { shareImage(uri) }
        toolbar.addView(title)
        toolbar.addView(detailsButton)
        toolbar.addView(shareButton)
        root.addView(
            toolbar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP,
            ),
        )
        overlay = toolbar
        setContentView(root)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun toolbarButton(label: String, description: String): TextView =
        TextView(this).apply {
            text = label
            contentDescription = description
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }

    private fun showImageDetails(uri: Uri) {
        val name = displayName(uri)
        val mimeType = contentResolver.getType(uri) ?: "Unknown"
        var size: Long? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) size = cursor.getLong(index)
            }
        } catch (_: Exception) {
            // Some content providers do not expose file size.
        } finally {
            cursor?.close()
        }

        var width = 0
        var height = 0
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                width = options.outWidth
                height = options.outHeight
            }
        } catch (_: Exception) {
            // Metadata may be unavailable for remote or unsupported content providers.
        }

        val sizeText = size?.let { formatBytes(it) } ?: "Unavailable"
        val dimensions = if (width > 0 && height > 0) "$width × $height pixels" else "Unavailable"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.image_viewer))
            .setMessage(
                "Name: $name\n\nType: $mimeType\n\nDimensions: $dimensions\n\nSize: $sizeText\n\nURI: $uri",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareImage(uri: Uri) {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = contentResolver.getType(uri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = android.content.ClipData.newUri(contentResolver, displayName(uri), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
        } catch (error: Exception) {
            Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_LONG).show()
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        } catch (_: Exception) {
            // Fall back to the URI path below.
        } finally {
            cursor?.close()
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.image_viewer)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024.0
            unit++
        } while (value >= 1024.0 && unit < units.lastIndex)
        return "${DecimalFormat("0.##").format(value)} ${units[unit]}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

private class ZoomableImageView(context: android.content.Context) : AppCompatImageView(context) {
    private val matrixRect = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var currentScale = 1f
    private val baseMatrix = Matrix()
    private val touchMatrix = Matrix()
    private val scaleDetector =
        ScaleGestureDetector(
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
    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val targetScale = if (currentScale > 1.05f) 1f else 2.5f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    if (targetScale == 1f) fitImageToView()
                    else {
                        touchMatrix.postScale(factor, factor, e.x, e.y)
                        applyMatrix()
                    }
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
        val source = RectF(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
        val destination = RectF(0f, 0f, width.toFloat(), height.toFloat())
        baseMatrix.setRectToRect(source, destination, Matrix.ScaleToFit.CENTER)
        touchMatrix.set(baseMatrix)
        currentScale = 1f
        applyMatrix()
    }

    private fun applyMatrix() {
        val image = drawable ?: return
        matrixRect.set(0f, 0f, image.intrinsicWidth.toFloat(), image.intrinsicHeight.toFloat())
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
