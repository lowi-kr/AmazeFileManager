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
import android.text.InputType
import android.util.LruCache
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
 * Built-in PDF viewer based on the platform [PdfRenderer].
 *
 * Pages are fitted to the screen width and scroll vertically. Pinch or double tap to zoom; pages
 * are re-rendered at higher resolution once zoomed in so text stays readable. Text search (via
 * [PdfTextSearch], backed by PdfBox-Android) is available from the toolbar. Password protected
 * PDFs are not supported.
 */
class PdfViewerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(PdfViewerActivity::class.java)

    private val renderLock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pagesView: ZoomRecyclerView? = null
    private var pageIndicator: TextView? = null
    private var totalPages = 0

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
    private val highlightedPages = HashSet<Int>()

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
        totalPages = renderer?.pageCount ?: 0

        setContentView(R.layout.activity_pdf_viewer)
        val toolbar = findViewById<Toolbar>(R.id.pdf_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        title = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.pdf_viewer)
        originalTitle = title
        searchEngine = PdfTextSearch(this, uri)

        pageIndicator = findViewById(R.id.pdf_page_indicator)
        updatePageIndicator(0)

        val layoutManager = LinearLayoutManager(this)
        val recycler =
            ZoomRecyclerView(this).apply {
                setBackgroundColor(Color.DKGRAY)
                this.layoutManager = layoutManager
                setItemViewCacheSize(0)
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        pagesView = recycler
        findViewById<FrameLayout>(R.id.pdf_pages_container).addView(recycler, 0)
        val adapter = PageAdapter(recycler, totalPages)
        recycler.adapter = adapter
        recycler.onZoomSettled = { zoom ->
            val scale =
                when {
                    zoom >= 4f -> 4
                    zoom >= 2f -> 3
                    zoom > SHARP_RENDER_ZOOM -> 2
                    else -> 1
                }
            adapter.updateRenderScale(scale)
        }
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    val first = layoutManager.findFirstVisibleItemPosition()
                    if (first >= 0) updatePageIndicator(first)
                }
            },
        )

        savedInstanceState?.getInt(KEY_PAGE, 0)?.let {
            if (it in 0 until totalPages) layoutManager.scrollToPosition(it)
        }
    }

    private fun updatePageIndicator(zeroBasedPage: Int) {
        val tv = pageIndicator ?: return
        if (totalPages <= 0) {
            tv.visibility = android.view.View.GONE
            return
        }
        tv.visibility = android.view.View.VISIBLE
        tv.text = getString(R.string.pdf_page_of, zeroBasedPage + 1, totalPages)
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
            R.id.action_goto_page -> {
                showGoToPageDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun showGoToPageDialog() {
        if (totalPages <= 0) return
        val input =
            EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = getString(R.string.pdf_goto_page_hint, totalPages)
                setPadding(48, 32, 48, 32)
            }
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_goto_page)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n < 1 || n > totalPages) {
                    Toast.makeText(this, R.string.pdf_goto_page_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    (pagesView?.layoutManager as? LinearLayoutManager)
                        ?.scrollToPositionWithOffset(n - 1, 0)
                    updatePageIndicator(n - 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        clearAllHighlights()
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
                engine.search(
                    query,
                    onBatch = { batch ->
                        found.addAll(batch)
                        val snapshot = found.toList()
                        runOnUiThread {
                            if (isDestroyed || query != lastQuery) return@runOnUiThread
                            val previousActive = activeHighlightMatch
                            currentMatches = snapshot
                            if (currentMatchIndex == -1 && snapshot.isNotEmpty()) {
                                currentMatchIndex = 0
                                jumpToCurrentMatch()
                            } else if (previousActive != null) {
                                val idx = snapshot.indexOf(previousActive)
                                currentMatchIndex =
                                    if (idx >= 0) {
                                        idx
                                    } else {
                                        currentMatchIndex.coerceIn(0, snapshot.lastIndex)
                                    }
                                refreshHighlightOverlays()
                            }
                            prevMatchItem?.isVisible = snapshot.isNotEmpty()
                            nextMatchItem?.isVisible = snapshot.isNotEmpty()
                            updateMatchCountTitle()
                        }
                    },
                    onFinished = {
                        runOnUiThread {
                            if (isDestroyed || query != lastQuery) return@runOnUiThread
                            if (currentMatches.isEmpty()) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.pdf_search_no_results, query),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
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
        activeHighlightMatch = match
        refreshHighlightOverlays()
        (pagesView?.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(match.pageIndex, 0)
        updatePageIndicator(match.pageIndex)
    }

    private fun refreshHighlightOverlays() {
        val adapter = pagesView?.adapter as? PageAdapter ?: return
        val oldPages = HashSet(highlightedPages)
        highlightedPages.clear()
        for (m in currentMatches) highlightedPages.add(m.pageIndex)
        activeHighlightMatch?.let { highlightedPages.add(it.pageIndex) }
        val dirty = oldPages.union(highlightedPages)
        for (page in dirty) {
            if (page in 0 until totalPages) adapter.notifyItemChanged(page)
        }
    }

    private fun clearAllHighlights() {
        val adapter = pagesView?.adapter as? PageAdapter
        val old = HashSet(highlightedPages)
        highlightedPages.clear()
        activeHighlightMatch = null
        for (page in old) {
            if (page in 0 until totalPages) adapter?.notifyItemChanged(page)
        }
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
        private val matchPaint =
            Paint().apply {
                color = MATCH_HIGHLIGHT_COLOR
                style = Paint.Style.FILL
            }
        private val activePaint =
            Paint().apply {
                color = ACTIVE_HIGHLIGHT_COLOR
                style = Paint.Style.FILL
            }

        private val placeholderHeight = (screenWidth * PLACEHOLDER_ASPECT).toInt()
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
                image.setImageBitmap(withHighlights(exact, position))
                image.minimumHeight = 0
                return
            }

            val interim =
                sequenceOf(4, 3, 2, 1)
                    .filter { it != renderScale }
                    .mapNotNull { pageCache.get(cacheKey(position, it)) }
                    .firstOrNull()
            if (interim != null) {
                image.setImageBitmap(withHighlights(interim, position))
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
                            image.setImageBitmap(withHighlights(bitmap, position))
                            image.minimumHeight = 0
                        }
                    }
                }
            } catch (e: RejectedExecutionException) {
                log.debug("render executor already shut down", e)
            }
        }

        private fun withHighlights(
            bitmap: Bitmap,
            position: Int,
        ): Bitmap {
            if (position !in highlightedPages) return bitmap
            val engine = searchEngine ?: return bitmap
            val pageMatches = currentMatches.filter { it.pageIndex == position }
            if (pageMatches.isEmpty()) return bitmap

            val highlighted = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(highlighted)
            val active = activeHighlightMatch
            for (match in pageMatches) {
                if (match == active) continue
                val rects = engine.highlightRectsIfCached(match, bitmap.width, bitmap.height)
                for (rect in rects) canvas.drawRect(rect, matchPaint)
            }
            if (active != null && active.pageIndex == position) {
                val rects = engine.highlightRectsIfCached(active, bitmap.width, bitmap.height)
                for (rect in rects) canvas.drawRect(rect, activePaint)
            }
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
        private const val CACHE_FRACTION = 6
        private const val SHARP_RENDER_ZOOM = 1.25f
        private const val MAX_BITMAP_WIDTH = 4096
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val MATCH_HIGHLIGHT_COLOR = 0x55FFEB3B.toInt()
        private const val ACTIVE_HIGHLIGHT_COLOR = 0xAAFFC107.toInt()
    }
}
