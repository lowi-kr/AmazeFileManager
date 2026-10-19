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

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.amaze.filemanager.analyse.DirNode
import com.amaze.filemanager.analyse.DuplicateGroup
import com.amaze.filemanager.analyse.FileCategory
import com.amaze.filemanager.analyse.FileEntry
import com.amaze.filemanager.analyse.ScanResult
import com.amaze.filemanager.analyse.StorageScanner
import com.amaze.filemanager.analyse.TreemapItem
import com.amaze.filemanager.ui.views.TreemapView
import java.io.File
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Built-in storage analyser: a type breakdown, a visual folder map and clean-up lists (largest
 * files, duplicates, junk, empty folders, old downloads, screenshots, recordings, APKs, hidden).
 *
 * Built from plain framework views so it has no layout files or library dependencies.
 */
class StorageAnalyserActivity : Activity() {
    private val scanner = StorageScanner()
    private val scanCancel = AtomicBoolean(false)
    private var dupCancel = AtomicBoolean(false)
    private var result: ScanResult? = null
    private var duplicates: List<DuplicateGroup>? = null
    private val removed = HashSet<String>()
    private var scanning = false

    private lateinit var titleView: TextView
    private lateinit var contentHost: FrameLayout

    private class Screen(val title: String, val view: View, val onLeave: (() -> Unit)? = null)

    private val screens = ArrayList<Screen>()

    private var resultsBox: LinearLayout? = null
    private var statusView: TextView? = null
    private var scanButton: Button? = null
    private var spinner: ProgressBar? = null

    private val storageRoot: File get() = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        // keeps the bar clear of the status bar when the app is drawn edge to edge
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(CARD)
        bar.setPadding(dp(4), dp(10), dp(16), dp(10))
        val back = TextView(this)
        back.text = "<"
        back.textSize = 22f
        back.setTextColor(TXT)
        back.setPadding(dp(16), dp(2), dp(16), dp(2))
        back.setOnClickListener { onBackPressed() }
        titleView = TextView(this)
        titleView.textSize = 20f
        titleView.setTextColor(TXT)
        titleView.typeface = Typeface.DEFAULT_BOLD
        titleView.maxLines = 1
        titleView.ellipsize = TextUtils.TruncateAt.END
        bar.addView(back)
        bar.addView(titleView)
        contentHost = FrameLayout(this)
        root.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(contentHost, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
        showStart()
    }

    override fun onResume() {
        super.onResume()
        if (screens.size == 1 && screens[0].title == TITLE_ACCESS && hasAccess()) showOverview()
    }

    override fun onDestroy() {
        scanCancel.set(true)
        dupCancel.set(true)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pop()) super.onBackPressed()
    }

    // ---------------------------------------------------------------- navigation

    private fun setRoot(
        title: String,
        view: View,
    ) {
        contentHost.removeAllViews()
        screens.clear()
        push(title, view)
    }

    private fun push(
        title: String,
        view: View,
        onLeave: (() -> Unit)? = null,
    ) {
        screens.lastOrNull()?.view?.visibility = View.GONE
        screens.add(Screen(title, view, onLeave))
        contentHost.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        titleView.text = title
    }

    private fun pop(): Boolean {
        if (screens.size <= 1) return false
        val s = screens.removeAt(screens.size - 1)
        s.onLeave?.invoke()
        contentHost.removeView(s.view)
        val top = screens.last()
        top.view.visibility = View.VISIBLE
        titleView.text = top.title
        return true
    }

    // ---------------------------------------------------------------- access

    private fun hasAccess(): Boolean =
        when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= 23 ->
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            else -> true
        }

    private fun showStart() {
        if (hasAccess()) showOverview() else showAccessScreen()
    }

    private fun showAccessScreen() {
        val col = column(24)
        col.gravity = Gravity.CENTER
        col.addView(label("Storage access needed", 20f, TXT, bold = true))
        col.addView(
            label(
                "To measure your storage the analyser must be allowed to read all files on this device.",
                15f,
                MUTED,
            ).apply { setPadding(0, dp(12), 0, dp(20)) },
        )
        col.addView(
            button("Allow access") {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else if (Build.VERSION.SDK_INT >= 23) {
                    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
                }
            },
        )
        setRoot(TITLE_ACCESS, col)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE && hasAccess()) showOverview()
    }

    // ---------------------------------------------------------------- overview

    private fun showOverview() {
        val scroll = ScrollView(this)
        val col = column(16)
        scroll.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))

        col.addView(deviceCard())

        val actions = column(0)
        val btn = button(if (result == null) "Scan storage" else "Scan again") { onScanClicked() }
        scanButton = btn
        actions.addView(btn)
        val spin = ProgressBar(this)
        spin.isIndeterminate = true
        spin.visibility = View.GONE
        spinner = spin
        actions.addView(spin, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(10) })
        val status = label("", 13f, MUTED)
        status.setPadding(0, dp(8), 0, 0)
        statusView = status
        actions.addView(status)
        col.addView(actions)

        val box = column(0)
        box.setPadding(0, dp(16), 0, 0)
        resultsBox = box
        col.addView(box)

        setRoot("Analyse storage", scroll)
        if (result != null) renderResults()
    }

    private fun deviceCard(): View {
        val card = card()
        val stat = StatFs(storageRoot.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        val used = total - free
        card.addView(label("Internal storage", 16f, TXT, bold = true))
        card.addView(label("${size(used)} used of ${size(total)}  -  ${size(free)} free", 14f, MUTED))
        val bar = SegmentBar(this)
        val frac = if (total > 0) used.toFloat() / total else 0f
        bar.segments = listOf(frac to ACCENT)
        card.addView(bar, LinearLayout.LayoutParams(MATCH, dp(12)).apply { topMargin = dp(12) })
        return card
    }

    private fun onScanClicked() {
        if (scanning) {
            scanCancel.set(true)
            statusView?.text = "Cancelling..."
            return
        }
        scanning = true
        scanCancel.set(false)
        scanButton?.text = "Cancel scan"
        spinner?.visibility = View.VISIBLE
        statusView?.text = "Starting..."
        Thread {
            val r =
                scanner.scan(storageRoot, scanCancel) { files, bytes, dir ->
                    runOnUiThread { statusView?.text = "$files files, ${size(bytes)}\n$dir" }
                }
            runOnUiThread {
                scanning = false
                spinner?.visibility = View.GONE
                if (r == null) {
                    scanButton?.text = if (result == null) "Scan storage" else "Scan again"
                    statusView?.text = "Scan cancelled"
                } else {
                    result = r
                    duplicates = null
                    removed.clear()
                    scanButton?.text = "Scan again"
                    statusView?.text = "Scanned ${r.fileCount} files in ${r.dirCount} folders"
                    renderResults()
                }
            }
        }.start()
    }

    private fun renderResults() {
        val r = result ?: return
        val box = resultsBox ?: return
        box.removeAllViews()

        val types = card()
        types.addView(label("Storage by type", 16f, TXT, bold = true))
        val shown = FileCategory.values().filter { (r.categoryBytes[it] ?: 0L) > 0 }
        val segBar = SegmentBar(this)
        segBar.segments =
            shown.map { c ->
                (if (r.totalBytes > 0) (r.categoryBytes[c] ?: 0L).toFloat() / r.totalBytes else 0f) to c.color
            }
        types.addView(segBar, LinearLayout.LayoutParams(MATCH, dp(16)).apply { topMargin = dp(12); bottomMargin = dp(8) })
        for (c in shown.sortedByDescending { r.categoryBytes[it] ?: 0L }) {
            types.addView(legendRow(c, r.categoryCounts[c] ?: 0, r.categoryBytes[c] ?: 0L, r.totalBytes))
        }
        box.addView(types)

        box.addView(
            clickRow("Visual map", "See which folders use the most space", "") { openTreemap(r.root) },
        )

        box.addView(label("Clean up", 14f, MUTED, bold = true).apply { setPadding(dp(4), dp(8), 0, dp(6)) })
        val dups = duplicates
        if (dups == null) {
            box.addView(clickRow("Duplicate files", "Tap to search (reads file contents)", "") { openDuplicates() })
        } else {
            val live = liveGroups(dups)
            box.addView(
                clickRow("Duplicate files", "${live.size} groups", size(live.sumOf { it.wasted })) { openDuplicates() },
            )
        }
        addListRow(box, "Largest files", r.largest)
        addListRow(box, "Junk files", r.junk)
        addListRow(box, "Empty folders", r.emptyDirs)
        addListRow(box, "Old downloads", r.oldDownloads, "Over 90 days old")
        addListRow(box, "Screenshots", r.screenshots)
        addListRow(box, "Voice recordings", r.recordings)
        addListRow(box, "Install files (APK)", r.apks)
        addListRow(box, "Hidden files and folders", r.hidden)
        if (removed.isNotEmpty()) {
            box.addView(label("Deleted items are hidden. Scan again to refresh the chart.", 12f, MUTED).apply { setPadding(dp(4), dp(10), 0, 0) })
        }
    }

    private fun addListRow(
        box: LinearLayout,
        title: String,
        all: List<FileEntry>,
        hint: String = "",
    ) {
        val live = all.filter { it.path !in removed }
        val sub = if (hint.isEmpty()) "${live.size} items" else "${live.size} items  -  $hint"
        box.addView(clickRow(title, sub, size(live.sumOf { it.size })) { openFileList(title, all) })
    }

    private fun liveGroups(groups: List<DuplicateGroup>): List<DuplicateGroup> =
        groups.filter { g -> g.files.count { it.path !in removed } >= 2 }

    // ---------------------------------------------------------------- treemap

    private fun openTreemap(node: DirNode) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val path = label(node.path, 12f, MUTED)
        path.setPadding(dp(16), dp(8), dp(16), dp(6))
        path.maxLines = 1
        path.ellipsize = TextUtils.TruncateAt.START
        col.addView(path)

        val tv = TreemapView(this)
        tv.items = treemapItems(node)
        tv.onBlockClick = { item ->
            val tag = item.tag
            if (tag is DirNode && (tag.children.isNotEmpty() || tag.filesSize > 0)) {
                openTreemap(tag)
            } else {
                toast("${item.label}: ${item.sub}")
            }
        }
        col.addView(tv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bottom = LinearLayout(this)
        bottom.setPadding(dp(12), dp(8), dp(12), dp(8))
        bottom.setBackgroundColor(CARD)
        bottom.addView(
            button("Show in file manager") { openInFileManager(File(node.path), null) },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        col.addView(bottom)
        push(node.name.ifEmpty { "Storage" }, col)
    }

    private fun treemapItems(node: DirNode): List<TreemapItem> {
        val items = ArrayList<TreemapItem>()
        val kids = node.children.filter { it.size > 0 }.sortedByDescending { it.size }
        for (k in kids.take(MAX_BLOCKS)) {
            items.add(TreemapItem(k.name, k.size.toDouble(), k, size(k.size)))
        }
        val rest = kids.drop(MAX_BLOCKS)
        if (rest.isNotEmpty()) {
            val s = rest.sumOf { it.size }
            items.add(TreemapItem("${rest.size} smaller folders", s.toDouble(), null, size(s)))
        }
        if (node.filesSize > 0) {
            items.add(TreemapItem("Files in this folder", node.filesSize.toDouble(), null, "${node.fileCount} files  -  ${size(node.filesSize)}"))
        }
        return items
    }

    // ---------------------------------------------------------------- duplicates

    private fun openDuplicates() {
        val r = result ?: return
        val cached = duplicates
        if (cached != null) {
            openDuplicateList(cached)
            return
        }
        val host = FrameLayout(this)
        val col = column(24)
        col.gravity = Gravity.CENTER
        val msg = label("Comparing files...", 16f, TXT)
        msg.gravity = Gravity.CENTER
        col.addView(msg)
        col.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(40), dp(40)).apply { topMargin = dp(16) })
        host.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        val cancel = AtomicBoolean(false)
        dupCancel = cancel
        push("Duplicate files", host) { cancel.set(true) }
        Thread {
            val found =
                scanner.findDuplicates(r, cancel) { done, total ->
                    runOnUiThread { msg.text = "Comparing files...\n$done of $total" }
                }
            runOnUiThread {
                if (found != null && !isDestroyed) {
                    duplicates = found
                    if (screens.lastOrNull()?.view === host) {
                        host.removeAllViews()
                        host.addView(duplicateListView(found), FrameLayout.LayoutParams(MATCH, MATCH))
                    }
                    renderResults()
                }
            }
        }.start()
    }

    private fun openDuplicateList(groups: List<DuplicateGroup>) {
        push("Duplicate files", duplicateListView(groups))
    }

    private fun duplicateListView(groups: List<DuplicateGroup>): View = ListController(null, groups).build()

    private fun openFileList(
        title: String,
        entries: List<FileEntry>,
    ) {
        push(title, ListController(entries, null).build())
    }

    // ---------------------------------------------------------------- list screens

    private sealed class Row {
        class Header(val group: DuplicateGroup, val live: List<FileEntry>) : Row()

        class Item(val entry: FileEntry) : Row()
    }

    private class RowViews(
        val check: CheckBox,
        val name: TextView,
        val info: TextView,
        val size: TextView,
        val open: TextView,
    )

    /** A scrollable list with checkboxes, select helpers and a delete button. */
    private inner class ListController(
        private val flat: List<FileEntry>?,
        private val groups: List<DuplicateGroup>?,
    ) {
        private val selected = HashSet<String>()
        private var rows: List<Row> = emptyList()
        private lateinit var summary: TextView
        private lateinit var deleteButton: Button
        private val adapter = RowAdapter()

        fun build(): View {
            val col = LinearLayout(this@StorageAnalyserActivity)
            col.orientation = LinearLayout.VERTICAL
            summary = label("", 13f, MUTED)
            summary.setPadding(dp(16), dp(10), dp(16), dp(4))
            col.addView(summary)

            val tools = LinearLayout(this@StorageAnalyserActivity)
            tools.setPadding(dp(12), 0, dp(12), dp(6))
            val selectLabel = if (groups != null) "Keep oldest, select rest" else "Select all"
            tools.addView(smallButton(selectLabel) { selectDefault() })
            tools.addView(smallButton("Clear") { selected.clear(); refresh() })
            col.addView(tools)

            val list = ListView(this@StorageAnalyserActivity)
            list.divider = null
            list.adapter = adapter
            list.setOnItemClickListener { _, _, position, _ ->
                val row = rows[position]
                if (row is Row.Item) {
                    val p = row.entry.path
                    if (!selected.remove(p)) selected.add(p)
                    refresh()
                }
            }
            col.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

            val bottom = LinearLayout(this@StorageAnalyserActivity)
            bottom.setBackgroundColor(CARD)
            bottom.setPadding(dp(12), dp(8), dp(12), dp(8))
            deleteButton = button("Delete selected", DANGER) { confirmDelete() }
            bottom.addView(deleteButton, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(bottom)
            refresh()
            return col
        }

        private fun rebuild() {
            val out = ArrayList<Row>()
            if (flat != null) {
                for (e in flat) if (e.path !in removed) out.add(Row.Item(e))
            } else if (groups != null) {
                for (g in groups) {
                    val live = g.files.filter { it.path !in removed }
                    if (live.size >= 2) {
                        out.add(Row.Header(g, live))
                        for (f in live) out.add(Row.Item(f))
                    }
                }
            }
            rows = out
            val paths = out.filterIsInstance<Row.Item>().map { it.entry.path }.toHashSet()
            selected.retainAll(paths)
        }

        private fun refresh() {
            rebuild()
            adapter.notifyDataSetChanged()
            val items = rows.filterIsInstance<Row.Item>()
            if (groups != null) {
                val live = rows.filterIsInstance<Row.Header>()
                summary.text = "${live.size} groups  -  ${size(live.sumOf { it.group.size * (it.live.size - 1) })} can be freed"
            } else {
                summary.text = "${items.size} items  -  ${size(items.sumOf { it.entry.size })}"
            }
            val chosen = items.filter { it.entry.path in selected }
            deleteButton.isEnabled = chosen.isNotEmpty()
            deleteButton.alpha = if (chosen.isEmpty()) 0.4f else 1f
            deleteButton.text =
                if (chosen.isEmpty()) "Delete selected" else "Delete ${chosen.size} selected  (${size(chosen.sumOf { it.entry.size })})"
        }

        private fun selectDefault() {
            selected.clear()
            if (groups != null) {
                for (row in rows) if (row is Row.Header) row.live.drop(1).forEach { selected.add(it.path) }
            } else {
                for (row in rows) if (row is Row.Item) selected.add(row.entry.path)
            }
            refresh()
        }

        private fun confirmDelete() {
            val chosen = rows.filterIsInstance<Row.Item>().map { it.entry }.filter { it.path in selected }
            if (chosen.isEmpty()) return
            if (groups != null) {
                // never let a delete wipe out every copy of a file
                for (row in rows) {
                    if (row is Row.Header && row.live.all { it.path in selected }) {
                        toast("Keep at least one copy in each group")
                        return
                    }
                }
            }
            AlertDialog.Builder(this@StorageAnalyserActivity)
                .setTitle("Delete ${chosen.size} items?")
                .setMessage("${size(chosen.sumOf { it.size })} will be permanently deleted. This cannot be undone.")
                .setPositiveButton("Delete") { _, _ -> deleteInBackground(chosen) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun deleteInBackground(entries: List<FileEntry>) {
            deleteButton.isEnabled = false
            Thread {
                var freed = 0L
                var failed = 0
                val done = ArrayList<String>()
                for (e in entries) {
                    val f = File(e.path)
                    val ok = if (e.isDir) f.deleteRecursively() else f.delete()
                    if (ok || !f.exists()) {
                        freed += e.size
                        done.add(e.path)
                    } else {
                        failed++
                    }
                }
                runOnUiThread {
                    removed.addAll(done)
                    selected.removeAll(done.toSet())
                    if (done.isNotEmpty()) {
                        MediaScannerConnection.scanFile(this@StorageAnalyserActivity, done.toTypedArray(), null, null)
                    }
                    toast("Freed ${size(freed)}" + if (failed > 0) ", $failed could not be deleted" else "")
                    refresh()
                    renderResults()
                }
            }.start()
        }

        private inner class RowAdapter : BaseAdapter() {
            override fun getCount() = rows.size

            override fun getItem(position: Int): Any = rows[position]

            override fun getItemId(position: Int) = position.toLong()

            override fun getViewTypeCount() = 2

            override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

            override fun areAllItemsEnabled() = false

            override fun isEnabled(position: Int) = rows[position] is Row.Item

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View {
                val row = rows[position]
                if (row is Row.Header) {
                    val tv = (convertView as? TextView) ?: label("", 13f, ACCENT, bold = true)
                    tv.setPadding(dp(16), dp(14), dp(16), dp(4))
                    val first = row.live.first()
                    tv.text = "${first.name}  -  ${row.live.size} copies  -  ${size(row.group.size)} each"
                    return tv
                }
                val entry = (row as Row.Item).entry
                val view = convertView ?: newRow()
                val v = view.tag as RowViews
                v.check.isChecked = entry.path in selected
                v.name.text = entry.name
                val parentDir = entry.path.substringBeforeLast('/', "")
                val date = if (entry.modified > 0) DateFormat.getDateFormat(this@StorageAnalyserActivity).format(Date(entry.modified)) else ""
                v.info.text = if (date.isEmpty()) parentDir else "$date  -  $parentDir"
                v.size.text = size(entry.size)
                v.open.setOnClickListener { locate(entry) }
                return view
            }

            private fun newRow(): View {
                val ctx = this@StorageAnalyserActivity
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(12), dp(8), dp(4), dp(8))
                val check = CheckBox(ctx)
                check.isClickable = false
                check.isFocusable = false
                val texts = LinearLayout(ctx)
                texts.orientation = LinearLayout.VERTICAL
                val name = label("", 15f, TXT)
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.MIDDLE
                val info = label("", 11f, MUTED)
                info.maxLines = 1
                info.ellipsize = TextUtils.TruncateAt.START
                texts.addView(name)
                texts.addView(info)
                val sizeView = label("", 13f, TXT)
                sizeView.setPadding(dp(8), 0, dp(4), 0)
                val open = label("Open", 13f, ACCENT, bold = true)
                open.setPadding(dp(10), dp(10), dp(10), dp(10))
                row.addView(check)
                row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
                row.addView(sizeView)
                row.addView(open)
                row.tag = RowViews(check, name, info, sizeView, open)
                return row
            }
        }
    }

    // ---------------------------------------------------------------- file manager hand-off

    private fun locate(entry: FileEntry) {
        val f = File(entry.path)
        val parent = f.parentFile ?: return
        openInFileManager(parent, f.name)
    }

    /** Opens the main file manager screen on [dir], optionally highlighting [select]. */
    private fun openInFileManager(
        dir: File,
        select: String?,
    ) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setClass(this, MainActivity::class.java)
            intent.setDataAndType(FileProvider.getUriForFile(this, packageName, dir), "resource/folder")
            if (select != null) intent.putExtra("com.amaze.fileutilities.AFM_LOCATE_FILE_NAME", select)
            startActivity(intent)
        } catch (e: Exception) {
            toast("Could not open this location")
        }
    }

    // ---------------------------------------------------------------- small UI helpers

    private fun size(bytes: Long): String = Formatter.formatFileSize(this, bytes)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun column(paddingDp: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        return c
    }

    private fun label(
        text: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun roundedBg(
        color: Int,
        radiusDp: Int,
    ): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun card(): LinearLayout {
        val c = column(16)
        c.background = roundedBg(CARD, 12)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    private fun button(
        text: String,
        color: Int = ACCENT,
        onClick: () -> Unit,
    ): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(if (color == ACCENT) Color.BLACK else Color.WHITE)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), roundedBg(color, 10), null)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun smallButton(
        text: String,
        onClick: () -> Unit,
    ): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(ACCENT)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), roundedBg(CARD, 8), null)
        b.setPadding(dp(14), 0, dp(14), 0)
        b.minHeight = 0
        b.minimumHeight = dp(36)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun clickRow(
        title: String,
        sub: String,
        right: String,
        onClick: () -> Unit,
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(14), dp(16), dp(14))
        row.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), roundedBg(CARD, 12), null)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(title, 16f, TXT, bold = true))
        texts.addView(label(sub, 12f, MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (right.isNotEmpty()) row.addView(label(right, 14f, TXT))
        row.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    private fun legendRow(
        cat: FileCategory,
        count: Int,
        bytes: Long,
        total: Long,
    ): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(5), 0, dp(5))
        val dot = View(this)
        dot.background = roundedBg(cat.color, 4)
        row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
        val pct = if (total > 0) (bytes * 100 / total).toInt() else 0
        val name = label("${cat.label}  ($count)", 14f, TXT)
        row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(label("${size(bytes)}  -  $pct%", 13f, MUTED))
        return row
    }

    /** A horizontal bar made of coloured segments. Each segment is (fraction of width, colour). */
    private class SegmentBar(context: Context) : View(context) {
        var segments: List<Pair<Float, Int>> = emptyList()
            set(value) {
                field = value
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2C.toInt() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, r, r, track)
            val save = canvas.save()
            val clip = android.graphics.Path()
            clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            canvas.clipPath(clip)
            var x = 0f
            for ((frac, color) in segments) {
                val w = frac.coerceIn(0f, 1f) * width
                if (w <= 0f) continue
                paint.color = color
                canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
                x += w
            }
            canvas.restoreToCount(save)
        }
    }

    companion object {
        private const val BG = 0xFF121212.toInt()
        private const val CARD = 0xFF1E1E1E.toInt()
        private const val TXT = 0xFFEEEEEE.toInt()
        private const val MUTED = 0xFF9E9E9E.toInt()
        private const val ACCENT = 0xFF4DB6AC.toInt()
        private const val DANGER = 0xFFE05A55.toInt()
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val MAX_BLOCKS = 40
        private const val REQUEST_STORAGE = 4711
        private const val TITLE_ACCESS = "Allow access"
    }
}
