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

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import androidx.annotation.WorkerThread
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.Writer
import java.util.concurrent.atomic.AtomicInteger

/** One occurrence of a search query: page and character range within that page's extracted text. */
data class PdfSearchMatch(
    val pageIndex: Int,
    val charStart: Int,
    val charEnd: Int,
)

private class PageTextData(
    val text: String,
    // one entry per character of [text] (best-effort; ligatures/separators may share a position)
    val positions: Array<TextPosition?>,
    val pageWidthPt: Float,
    val pageHeightPt: Float,
)

/**
 * Extracts text from a PDF one page at a time (so opening a huge PDF for search doesn't block on
 * parsing it in full), and finds case-insensitive matches across pages.
 *
 * [search] and [highlightRects] do real parsing work and must be called off the main thread.
 * [cancelSearch] and [close] are safe to call from any thread.
 */
class PdfTextSearch(
    context: Context,
    private val uri: Uri,
) {
    private val log = LoggerFactory.getLogger(PdfTextSearch::class.java)
    private val appContext = context.applicationContext
    private var document: PDDocument? = null
    private var pageCache: Array<PageTextData?> = emptyArray()
    private val generation = AtomicInteger(0)

    @Volatile
    var pageCount = 0
        private set

    @WorkerThread
    @Throws(IOException::class)
    private fun ensureOpen(): PDDocument {
        document?.let { return it }
        PDFBoxResourceLoader.init(appContext)
        val doc =
            appContext.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                ?: throw IOException("cannot open $uri")
        document = doc
        pageCount = doc.numberOfPages
        pageCache = arrayOfNulls(pageCount)
        return doc
    }

    @WorkerThread
    private fun extractPage(
        doc: PDDocument,
        index: Int,
    ): PageTextData {
        pageCache[index]?.let { return it }
        val page = doc.getPage(index)
        val textBuilder = StringBuilder()
        val positions = ArrayList<TextPosition?>()
        var data =
            PageTextData(
                text = "",
                positions = emptyArray(),
                pageWidthPt = page.mediaBox.width,
                pageHeightPt = page.mediaBox.height,
            )
        try {
            val stripper =
                object : PDFTextStripper() {
                    override fun writeString(
                        text: String,
                        textPositions: MutableList<TextPosition>,
                    ) {
                        for (i in text.indices) {
                            textBuilder.append(text[i])
                            val pos = textPositions.getOrNull(i.coerceAtMost(textPositions.size - 1))
                            positions.add(pos)
                        }
                    }

                    override fun writeWordSeparator() {
                        textBuilder.append(' ')
                        positions.add(positions.lastOrNull())
                    }

                    override fun writeLineSeparator() {
                        textBuilder.append('\n')
                        positions.add(positions.lastOrNull())
                    }
                }
            stripper.sortByPosition = true
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            val discard =
                object : Writer() {
                    override fun write(
                        cbuf: CharArray,
                        off: Int,
                        len: Int,
                    ) {}

                    override fun flush() {}

                    override fun close() {}
                }
            stripper.writeText(doc, discard)
            data = PageTextData(textBuilder.toString(), positions.toTypedArray(), data.pageWidthPt, data.pageHeightPt)
        } catch (e: Exception) {
            log.warn("failed to extract text from PDF page {}", index, e)
        }
        pageCache[index] = data
        return data
    }

    /**
     * Scans every page for [query] (case-insensitive), calling [onBatch] on the calling thread
     * with newly found matches as each page finishes, in page order. A new call to [search]
     * invalidates any call still in progress, whose further [onBatch] calls become no-ops.
     */
    @WorkerThread
    fun search(
        query: String,
        onBatch: (List<PdfSearchMatch>) -> Unit,
    ) {
        val myGeneration = generation.incrementAndGet()
        if (query.isEmpty()) return
        val doc =
            try {
                ensureOpen()
            } catch (e: Exception) {
                log.warn("could not open PDF for search", e)
                return
            }
        val needle = query.lowercase()
        for (page in 0 until pageCount) {
            if (generation.get() != myGeneration) return
            val data =
                try {
                    extractPage(doc, page)
                } catch (e: Exception) {
                    log.warn("failed to extract page {} for search", page, e)
                    continue
                }
            if (generation.get() != myGeneration) return
            val haystack = data.text.lowercase()
            val matches = ArrayList<PdfSearchMatch>()
            var from = 0
            while (true) {
                val at = haystack.indexOf(needle, from)
                if (at < 0) break
                matches.add(PdfSearchMatch(page, at, at + needle.length))
                from = at + needle.length
            }
            if (matches.isNotEmpty()) onBatch(matches)
        }
    }

    /** Cancels any [search] call in progress; its remaining [onBatch] calls will stop firing. */
    fun cancelSearch() {
        generation.incrementAndGet()
    }

    /**
     * Highlight rectangles for [match], in pixel space for a page rendered at
     * [renderedWidthPx] by [renderedHeightPx]. Adjacent characters on the same line are merged
     * into one rectangle per line. May parse the page if it is not already cached, so this is
     * only safe off the main thread; from the main thread use [highlightRectsIfCached] instead,
     * which every real caller wants anyway since a match only exists for an already-scanned page.
     */
    @WorkerThread
    fun highlightRects(
        match: PdfSearchMatch,
        renderedWidthPx: Int,
        renderedHeightPx: Int,
    ): List<RectF> {
        val doc = document ?: return emptyList()
        val data =
            try {
                extractPage(doc, match.pageIndex)
            } catch (e: Exception) {
                return emptyList()
            }
        return rectsFor(match, data, renderedWidthPx, renderedHeightPx)
    }

    /**
     * Same as [highlightRects], but never parses: if [match]'s page has not already been
     * extracted (normally true the moment a match for it exists, since [search] just scanned it),
     * this returns an empty list instead of blocking. Safe to call from the main thread.
     */
    fun highlightRectsIfCached(
        match: PdfSearchMatch,
        renderedWidthPx: Int,
        renderedHeightPx: Int,
    ): List<RectF> {
        val data = pageCache.getOrNull(match.pageIndex) ?: return emptyList()
        return rectsFor(match, data, renderedWidthPx, renderedHeightPx)
    }

    private fun rectsFor(
        match: PdfSearchMatch,
        data: PageTextData,
        renderedWidthPx: Int,
        renderedHeightPx: Int,
    ): List<RectF> {
        if (data.pageWidthPt <= 0 || data.pageHeightPt <= 0) return emptyList()
        val scaleX = renderedWidthPx / data.pageWidthPt
        val scaleY = renderedHeightPx / data.pageHeightPt
        val rects = ArrayList<RectF>()
        var current: RectF? = null
        val end = match.charEnd.coerceAtMost(data.positions.size)
        for (i in match.charStart until end) {
            val pos = data.positions.getOrNull(i) ?: continue
            val left = pos.x * scaleX
            val top = pos.y * scaleY
            val right = left + pos.width * scaleX
            val bottom = top + pos.height * scaleY
            val c = current
            if (c != null && top < c.bottom && bottom > c.top) {
                c.left = minOf(c.left, left)
                c.top = minOf(c.top, top)
                c.right = maxOf(c.right, right)
                c.bottom = maxOf(c.bottom, bottom)
            } else {
                c?.let { rects.add(it) }
                current = RectF(left, top, right, bottom)
            }
        }
        current?.let { rects.add(it) }
        return rects
    }

    @WorkerThread
    fun close() {
        generation.incrementAndGet()
        try {
            document?.close()
        } catch (e: Exception) {
            log.warn("failed to close PDF text document", e)
        }
        document = null
        pageCache = emptyArray()
    }
}
