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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
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
 * are re-rendered at higher resolution once zoomed in so text stays readable. Text search (via
 * [PdfTextSearch], backed by PdfBox-Android) is available from the toolbar; the renderer itself
 * is untouched for display. Password protected PDFs are not supported.
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

    // Text search. searchEngine does its parsing on searchExecutor; results come back on the
    // main thread via runOnUiThread.
    private var searchEngine: PdfTextSearch? = null
    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var lastQuery = ""
    private var currentMatches: List<PdfSearchMatch> = emptyList()
    private var currentMatchIndex = -1
    private var activeHighlightMatch: PdfSearchMatch? = null
    private var prevMatchItem: MenuItem? = null
    private var nextMatchItem: MenuItem? = null
    private var originalTitle: CharSequence = ""

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

        // Theme is NoActionBar (appCompatBlack). Host a Toolbar so the options menu
        // (search / prev / next) is actually visible.
        setContentView(R.layout.activity_pdf_viewer)
        val toolbar = findViewById<Toolbar>(R.id.pdf_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        title = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.pdf_viewer)
        originalTitle = title
        searchEngine = PdfTextSearch(this, uri)

        val layoutManager = LinearLayoutManager(this)
        val recycler =
            ZoomRecyclerView(this).apply {
                setBackgroundColor(Color.DKGRAY)
                this.layoutManager = layoutManager
                // Rendered bitmaps can be big, do not keep detached page views around
                setItemViewCacheSize(0)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        pagesView = recycler
        findViewById<FrameLayout>(R.id.pdf_pages_container).addView(recycler)
        val adapter = PageAdapter(recycler, pageCount)
        recycler.adapter = adapter
        recycler.onZoomSettled = { zoom ->
            // Scale render resolution with zoom so text stays sharp at high magnification
            val scale =
                when {
                    zoom >= 4f -> 4
                    zoom >= 2f -> 3
                    zoom > SHARP_RENDER_ZOOM -> 2
                    else -> 1
                }
            adapter.updateRenderScale(scale)
        }

        savedInstanceState?.getInt(KEY_PAGE, 0)?.let {
            if (it in 1 until pageCount) layoutManager.scrollToPosition(it)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.pdf_search)
        searchView.maxWidth = Integer.MAX_VALUE
        prevMatchItem = menu.findItem(R.id.action_search_previous)
        nextMatchItem = menu.findItem(R.id.action_search_next)
        prevMatchItem?.isVisible = false
        nextMatchItem?.isVisible = false
        searchView.setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    // Jump to next match on keyboard search; if none yet, start a search
                    if (currentMatches.isEmpty()) {
                        scheduleSearch(query.orEmpty())
                    } else {
                        jumpToMatch(1)
                    }
                    searchView.clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    scheduleSearch(newText.orEmpty())
                    return true
                }
            },
        )
        searchItem.setOnActionExpandListener(
            object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    scheduleSearch("")
                    return true
                }
            },
        )
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_search_previous -> {
                jumpToMatch(-1)
                true
            }
            R.id.action_search_next -> {
                jumpToMatch(1)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun scheduleSearch(query: String) {
        lastQuery = query
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val r = Runnable { runSearch(query) }
        pendingSearch = r
        searchHandler.postDelayed(r, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearch(query: String) {
        searchEngine?.cancelSearch()
        clearHighlight()
        currentMatches = emptyList()
        currentMatchIndex = -1
        prevMatchItem?.isVisible = false
        nextMatchItem?.isVisible = false
        updateMatchCountTitle()
        if (query.isBlank()) return
        val engine = searchEngine ?: return
        try {
            searchExecutor.execute {
                val found = ArrayList<PdfSearchMatch>()
                engine.search(query) { batch ->
                    found.addAll(batch)
                    val snapshot = found.toList()
                    runOnUiThread {
                        if (isDestroyed || query != lastQuery) return@runOnUiThread
                        currentMatches = snapshot
                        if (currentMatchIndex == -1) {
                            currentMatchIndex = 0
                            jumpToCurrentMatch()
                        }
                        prevMatchItem?.isVisible = true
                        nextMatchItem?.isVisible = true
                        updateMatchCountTitle()
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            log.debug("search executor already shut down", e)
        }
    }

    private fun jumpToMatch(delta: Int) {
        if (currentMatches.isEmpty()) return
        currentMatchIndex =
            ((currentMatchIndex + delta) % currentMatches.size + currentMatches.size) % currentMatches.size
        jumpToCurrentMatch()
        updateMatchCountTitle()
    }

    private fun jumpToCurrentMatch() {
        val match = currentMatches.getOrNull(currentMatchIndex) ?: return
        val previous = activeHighlightMatch
        activeHighlightMatch = match
        val adapter = pagesView?.adapter as? PageAdapter
        if (previous != null && previous.pageIndex != match.pageIndex) {
            adapter?.notifyItemChanged(previous.pageIndex)
        }
        adapter?.notifyItemChanged(match.pageIndex)
        (pagesView?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(match.pageIndex, 0)
    }

    private fun clearHighlight() {
        val previous = activeHighlightMatch ?: return
        activeHighlightMatch = null
        (pagesView?.adapter as? PageAdapter)?.notifyItemChanged(previous.pageIndex)
    }

    private fun updateMatchCountTitle() {
        title =
            if (currentMatches.isEmpty()) {
                originalTitle
            } else {
                "${currentMatchIndex + 1}/${currentMatches.size}  $originalTitle"
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
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val engine = searchEngine
        try {
            searchExecutor.execute { engine?.close() }
        } catch (e: RejectedExecutionException) {
            log.debug("search executor already shut down", e)
        }
        searchExecutor.shutdown()
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
        private val highlightPaint =
            Paint().apply {
                color = HIGHLIGHT_COLOR
                style = Paint.Style.FILL
            }

        // Height reserved for a page while it is still rendering, roughly A4 portrait
        private val placeholderHeight = (screenWidth * PLACEHOLDER_ASPECT).toInt()

        // Render scale relative to screen width. UI thread only.
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
                image.setImageBitmap(withHighlightIfActive(exact, position))
                image.minimumHeight = 0
                return
            }

            // Show any already-cached resolution of this page until the target scale is ready
            val interim =
                sequenceOf(4, 3, 2, 1)
                    .filter { it != renderScale }
                    .mapNotNull { pageCache.get(cacheKey(position, it)) }
                    .firstOrNull()
            if (interim != null) {
                image.setImageBitmap(withHighlightIfActive(interim, position))
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
                            image.setImageBitmap(withHighlightIfActive(bitmap, position))
                            image.minimumHeight = 0
                        }
                    }
                }
            } catch (e: RejectedExecutionException) {
                log.debug("render executor already shut down", e)
            }
        }

        /**
         * If [position] is the page holding the current search match, returns a highlighted copy
         * of [bitmap]; otherwise returns [bitmap] unchanged. The page's text was already
         * extracted during the search scan, so this is a cheap cache lookup, safe on the UI
         * thread.
         */
        private fun withHighlightIfActive(
            bitmap: Bitmap,
            position: Int,
        ): Bitmap {
            val match = activeHighlightMatch ?: return bitmap
            if (match.pageIndex != position) return bitmap
            val engine = searchEngine ?: return bitmap
            val rects = engine.highlightRectsIfCached(match, bitmap.width, bitmap.height)
            if (rects.isEmpty()) return bitmap
            val highlighted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(highlighted)
            for (rect in rects) canvas.drawRect(rect, highlightPaint)
            return highlighted
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

        // Zoom above which pages are re-rendered at higher resolution
        private const val SHARP_RENDER_ZOOM = 1.25f

        // Upper bound on a rendered page width, to keep bitmaps from blowing the heap
        private const val MAX_BITMAP_WIDTH = 4096

        private const val SEARCH_DEBOUNCE_MS = 350L

        // translucent amber highlight for search matches
        private const val HIGHLIGHT_COLOR = 0x80FFEB3B.toInt()
    }
}
