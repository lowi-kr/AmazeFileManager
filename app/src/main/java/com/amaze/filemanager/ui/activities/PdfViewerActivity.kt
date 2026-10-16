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
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amaze.filemanager.R
import com.amaze.filemanager.ui.views.ZoomRecyclerView
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Minimal built-in PDF viewer based on the platform [PdfRenderer].
 *
 * Pages are fitted to the screen width and scroll vertically. Pinch or double tap to zoom; pages
 * are re-rendered at 2x resolution once zoomed in so text stays readable. Password protected PDFs
 * and text search are not supported.
 */
class PdfViewerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(PdfViewerActivity::class.java)

    // PdfRenderer is not thread safe and allows only one open page at a time, so all access to
    // renderer goes through renderLock
    private val renderLock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pagesView: ZoomRecyclerView? = null

    // Rendered pages, keyed by page index and render scale. Sized by bytes, not entries.
    private val pageCache =
        object : LruCache<String, Bitmap>(
            (Runtime.getRuntime().maxMemory() / CACHE_FRACTION).coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
        ) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.byteCount
        }

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
            ZoomRecyclerView(this).apply {
                setBackgroundColor(Color.DKGRAY)
                this.layoutManager = layoutManager
                // Rendered bitmaps can be big, do not keep detached page views around
                setItemViewCacheSize(0)
            }
        pagesView = recycler
        val adapter = PageAdapter(recycler, pageCount)
        recycler.adapter = adapter
        recycler.onZoomSettled = { zoom ->
            adapter.updateRenderScale(if (zoom > SHARP_RENDER_ZOOM) 2 else 1)
        }
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
        pageCache.evictAll()
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

    private fun cacheKey(
        index: Int,
        scale: Int,
    ): String = "$index@$scale"

    /**
     * Renders one page at baseWidth * scale pixels wide and caches it. Runs on the render thread.
     */
    private fun renderPage(
        index: Int,
        scale: Int,
        baseWidth: Int,
    ): Bitmap? =
        synchronized(renderLock) {
            val pdfRenderer = renderer ?: return null
            val targetWidth = (baseWidth * scale).coerceAtMost(MAX_BITMAP_WIDTH)
            try {
                val page = pdfRenderer.openPage(index)
                try {
                    val height =
                        (targetWidth.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer does not paint a background, transparent pages need white
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageCache.put(cacheKey(index, scale), bitmap)
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

        // 1 = fit to width, 2 = double resolution, used while zoomed in. UI thread only.
        private var renderScale = 1

        fun updateRenderScale(newScale: Int) {
            if (newScale == renderScale) return
            renderScale = newScale
            notifyDataSetChanged()
        }

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
            image.tag = position

            val exact = pageCache.get(cacheKey(position, renderScale))
            if (exact != null) {
                image.setImageBitmap(exact)
                image.minimumHeight = 0
                return
            }

            // Show the other resolution of this page, if we have it, until the new one is ready
            val interim = pageCache.get(cacheKey(position, if (renderScale == 1) 2 else 1))
            if (interim != null) {
                image.setImageBitmap(interim)
                image.minimumHeight = 0
            } else {
                image.setImageDrawable(null)
                image.minimumHeight = placeholderHeight
            }

            val scale = renderScale
            val baseWidth = if (recycler.width > 0) recycler.width else screenWidth
            try {
                renderExecutor.execute {
                    val bitmap = renderPage(position, scale, baseWidth) ?: return@execute
                    runOnUiThread {
                        if (!isDestroyed && image.tag == position && renderScale == scale) {
                            image.setImageBitmap(bitmap)
                            image.minimumHeight = 0
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

        // The page cache may use 1/6 of the app heap
        private const val CACHE_FRACTION = 6

        // Zoom above which pages are re-rendered at 2x
        private const val SHARP_RENDER_ZOOM = 1.4f

        // Upper bound on a rendered page width, to keep bitmaps from blowing the heap
        private const val MAX_BITMAP_WIDTH = 3000
    }
}
