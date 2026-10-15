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

package com.amaze.filemanager.ui.activities

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal built-in PDF viewer based on the platform [PdfRenderer].
 *
 * Pages are fitted to the screen width and scroll vertically. Password protected PDFs, zooming and
 * text search are not supported.
 */
class PdfViewerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(PdfViewerActivity::class.java)

    // PdfRenderer is not thread safe and allows only one open page at a time, so all access to
    // renderer goes through renderLock
    private val renderLock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pagesView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null || !openRenderer(uri)) {
            finish()
            return
        }
        val pageCount = renderer?.pageCount ?: 0

        title = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.pdf_viewer)

        val layoutManager = LinearLayoutManager(this)
        val recycler =
            RecyclerView(this).apply {
                setBackgroundColor(Color.DKGRAY)
                this.layoutManager = layoutManager
            }
        pagesView = recycler
        recycler.adapter = PageAdapter(recycler, pageCount)
        setContentView(recycler)

        savedInstanceState?.getInt(KEY_PAGE, 0)?.let {
            if (it in 1 until pageCount) layoutManager.scrollToPosition(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val layoutManager = pagesView?.layoutManager as? LinearLayoutManager
        outState.putInt(
            KEY_PAGE,
            (layoutManager?.findFirstVisibleItemPosition() ?: 0).coerceAtLeast(0),
        )
    }

    override fun onDestroy() {
        renderExecutor.shutdownNow()
        synchronized(renderLock) {
            try {
                renderer?.close()
            } catch (e: Exception) {
                log.warn("failed to close PdfRenderer", e)
            }
            renderer = null
            try {
                descriptor?.close()
            } catch (e: Exception) {
                log.warn("failed to close file descriptor", e)
            }
            descriptor = null
        }
        super.onDestroy()
    }

    private fun openRenderer(uri: Uri): Boolean {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd == null) {
                Toast.makeText(this, R.string.pdf_open_failed, Toast.LENGTH_LONG).show()
                false
            } else {
                renderer = PdfRenderer(pfd)
                descriptor = pfd
                true
            }
        } catch (e: SecurityException) {
            // PdfRenderer throws SecurityException for password protected documents
            log.warn("cannot open password protected PDF", e)
            closeQuietly(pfd)
            Toast.makeText(this, R.string.pdf_password_protected, Toast.LENGTH_LONG).show()
            false
        } catch (e: Exception) {
            log.error("failed to open PDF", e)
            closeQuietly(pfd)
            Toast.makeText(this, R.string.pdf_open_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (e: Exception) {
            log.warn("failed to close file descriptor", e)
        }
    }

    /** Renders one page scaled to targetWidth. Runs on the render thread. */
    private fun renderPage(
        index: Int,
        targetWidth: Int,
    ): Bitmap? =
        synchronized(renderLock) {
            val pdfRenderer = renderer ?: return null
            try {
                val page = pdfRenderer.openPage(index)
                try {
                    val height =
                        (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer does not paint a background, transparent pages need white
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            } catch (e: OutOfMemoryError) {
                log.error("out of memory rendering PDF page {}", index, e)
                null
            } catch (e: Exception) {
                log.error("failed to render PDF page {}", index, e)
                null
            }
        }

    private class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    private inner class PageAdapter(
        private val recycler: RecyclerView,
        private val pageCount: Int,
    ) : RecyclerView.Adapter<PageHolder>() {
        private val density = resources.displayMetrics.density
        private val screenWidth = resources.displayMetrics.widthPixels

        // Height reserved for a page while it is still rendering, roughly A4 portrait
        private val placeholderHeight = (screenWidth * PLACEHOLDER_ASPECT).toInt()

        override fun getItemCount(): Int = pageCount

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): PageHolder {
            val image =
                ImageView(parent.context).apply {
                    layoutParams =
                        RecyclerView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = (PAGE_GAP_DP * density).toInt() }
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.WHITE)
                }
            return PageHolder(image)
        }

        override fun onBindViewHolder(
            holder: PageHolder,
            position: Int,
        ) {
            val image = holder.image
            image.setImageDrawable(null)
            image.minimumHeight = placeholderHeight
            image.tag = position
            val targetWidth = if (recycler.width > 0) recycler.width else screenWidth
            try {
                renderExecutor.execute {
                    val bitmap = renderPage(position, targetWidth) ?: return@execute
                    runOnUiThread {
                        if (!isDestroyed && image.tag == position) {
                            image.setImageBitmap(bitmap)
                            image.minimumHeight = 0
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            } catch (e: RejectedExecutionException) {
                log.debug("render executor already shut down", e)
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.image.setImageDrawable(null)
            holder.image.tag = null
        }
    }

    companion object {
        private const val KEY_PAGE = "pdf_page"
        private const val PAGE_GAP_DP = 8
        private const val PLACEHOLDER_ASPECT = 1.4f
    }
}
