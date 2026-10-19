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

package com.amaze.filemanager.analyse

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Walks a folder tree once and collects everything the analyser screens need. */
class StorageScanner(private val nowMs: Long = System.currentTimeMillis()) {
    fun interface ProgressListener {
        fun onProgress(
            files: Int,
            bytes: Long,
            dir: String,
        )
    }

    private class Work(val node: DirNode, val dir: File, val inHidden: Boolean)

    /** Returns null if [cancel] was set before the scan finished. */
    fun scan(
        root: File,
        cancel: AtomicBoolean,
        listener: ProgressListener? = null,
    ): ScanResult? {
        val rootNode = DirNode(root.name.ifEmpty { root.path }, root.path, null)
        val order = ArrayList<DirNode>()
        val stack = ArrayList<Work>()
        stack.add(Work(rootNode, root, false))

        val cats = FileCategory.values()
        val catBytes = LongArray(cats.size)
        val catCounts = IntArray(cats.size)
        val largestHeap = PriorityQueue<FileEntry>(MAX_LARGEST + 1, compareBy { it.size })
        val junk = ArrayList<FileEntry>()
        val emptyDirs = ArrayList<FileEntry>()
        val apks = ArrayList<FileEntry>()
        val oldDownloads = ArrayList<FileEntry>()
        val screenshots = ArrayList<FileEntry>()
        val recordings = ArrayList<FileEntry>()
        val hidden = ArrayList<FileEntry>()
        val hiddenDirs = ArrayList<DirNode>()
        val junkDirs = ArrayList<DirNode>()
        val sizeMap = HashMap<Long, MutableList<FileEntry>>()

        var fileCount = 0
        var dirCount = 0
        var totalBytes = 0L
        var lastReport = 0L
        val oldCutoff = nowMs - OLD_DOWNLOAD_MS

        while (stack.isNotEmpty()) {
            if (cancel.get()) return null
            val work = stack.removeAt(stack.size - 1)
            order.add(work.node)
            dirCount++
            val kids = work.dir.listFiles() ?: continue
            if (kids.isEmpty() && work.node.parent != null) {
                emptyDirs.add(FileEntry(work.dir.path, 0, work.dir.lastModified(), true))
            }
            for (kid in kids) {
                val name = kid.name
                if (kid.isDirectory) {
                    if (isLinkOrBroken(kid)) continue
                    val node = DirNode(name, kid.path, work.node)
                    work.node.children.add(node)
                    val isHidden = name.startsWith(".")
                    if (isHidden && !work.inHidden) hiddenDirs.add(node)
                    if (name == ".thumbnails") junkDirs.add(node)
                    stack.add(Work(node, kid, work.inHidden || isHidden))
                    continue
                }
                val size = kid.length()
                val modified = kid.lastModified()
                val entry = FileEntry(kid.path, size, modified)
                val ext = FileCategory.extensionOf(name)
                val cat = FileCategory.forExtension(ext)
                catBytes[cat.ordinal] += size
                catCounts[cat.ordinal]++
                fileCount++
                totalBytes += size
                work.node.filesSize += size
                work.node.fileCount++

                largestHeap.add(entry)
                if (largestHeap.size > MAX_LARGEST) largestHeap.poll()
                if (size >= MIN_DUPLICATE_SIZE) sizeMap.getOrPut(size) { ArrayList() }.add(entry)

                if (ext in JUNK_EXTENSIONS || name.lowercase() in JUNK_NAMES) junk.add(entry)
                if (ext == "apk" || ext == "xapk" || ext == "apks") apks.add(entry)
                if (name.startsWith(".") && !work.inHidden) hidden.add(entry)
                val lowerPath = kid.path.lowercase()
                if (modified in 1 until oldCutoff &&
                    (lowerPath.contains("/download/") || lowerPath.contains("/downloads/"))
                ) {
                    oldDownloads.add(entry)
                }
                if (lowerPath.contains("/screenshots/") || lowerPath.contains("/screenshot/")) {
                    screenshots.add(entry)
                }
                if (cat == FileCategory.AUDIO &&
                    (
                        lowerPath.contains("/recordings/") || lowerPath.contains("/recorder/") ||
                            lowerPath.contains("/voice recorder/") || lowerPath.contains("/call/")
                    )
                ) {
                    recordings.add(entry)
                }
            }
            val now = System.currentTimeMillis()
            if (listener != null && now - lastReport > 150) {
                lastReport = now
                listener.onProgress(fileCount, totalBytes, work.dir.path)
            }
        }

        // children are always listed after their parent, so walking backwards sums bottom-up
        for (i in order.indices.reversed()) {
            val n = order[i]
            n.size += n.filesSize
            n.parent?.let { it.size += n.size }
        }
        for (n in hiddenDirs) hidden.add(FileEntry(n.path, n.size, File(n.path).lastModified(), true))
        for (n in junkDirs) junk.add(FileEntry(n.path, n.size, File(n.path).lastModified(), true))

        val categoryBytes = LinkedHashMap<FileCategory, Long>()
        val categoryCounts = LinkedHashMap<FileCategory, Int>()
        for (c in cats) {
            categoryBytes[c] = catBytes[c.ordinal]
            categoryCounts[c] = catCounts[c.ordinal]
        }
        val duplicatesBySize = HashMap<Long, List<FileEntry>>()
        for ((size, list) in sizeMap) if (list.size >= 2) duplicatesBySize[size] = list

        return ScanResult(
            root = rootNode,
            totalBytes = totalBytes,
            fileCount = fileCount,
            dirCount = dirCount,
            categoryBytes = categoryBytes,
            categoryCounts = categoryCounts,
            largest = largestHeap.sortedByDescending { it.size },
            junk = junk.sortedByDescending { it.size },
            emptyDirs = emptyDirs.sortedBy { it.path },
            apks = apks.sortedByDescending { it.size },
            oldDownloads = oldDownloads.sortedByDescending { it.size },
            screenshots = screenshots.sortedByDescending { it.size },
            recordings = recordings.sortedByDescending { it.size },
            hidden = hidden.sortedByDescending { it.size },
            sizeGroups = duplicatesBySize,
        )
    }

    /**
     * Finds files with identical content among the same-size candidates from [scan]. Cheap partial
     * hash first, full hash only for files that still collide. Returns null if cancelled.
     */
    fun findDuplicates(
        result: ScanResult,
        cancel: AtomicBoolean,
        progress: ((done: Int, total: Int) -> Unit)? = null,
    ): List<DuplicateGroup>? {
        val total = result.sizeGroups.values.sumOf { it.size }
        var done = 0
        val groups = ArrayList<DuplicateGroup>()
        for (sameSize in result.sizeGroups.values) {
            val byQuick = HashMap<String, MutableList<FileEntry>>()
            for (f in sameSize) {
                if (cancel.get()) return null
                quickHash(File(f.path))?.let { byQuick.getOrPut(it) { ArrayList() }.add(f) }
                done++
                progress?.invoke(done, total)
            }
            for (quickMatches in byQuick.values) {
                if (quickMatches.size < 2) continue
                val byFull = HashMap<String, MutableList<FileEntry>>()
                for (f in quickMatches) {
                    if (cancel.get()) return null
                    fullHash(File(f.path), cancel)?.let { byFull.getOrPut(it) { ArrayList() }.add(f) }
                }
                for (same in byFull.values) {
                    if (same.size >= 2) groups.add(DuplicateGroup(same[0].size, same.sortedBy { it.modified }))
                }
            }
        }
        groups.sortByDescending { it.wasted }
        return groups
    }

    private fun isLinkOrBroken(dir: File): Boolean =
        try {
            dir.canonicalPath != dir.absolutePath
        } catch (e: IOException) {
            true
        }

    private fun quickHash(file: File): String? =
        try {
            RandomAccessFile(file, "r").use { raf ->
                val md = MessageDigest.getInstance("SHA-1")
                val len = raf.length()
                val buf = ByteArray(QUICK_HASH_BYTES)
                var n = raf.read(buf)
                if (n > 0) md.update(buf, 0, n)
                if (len > QUICK_HASH_BYTES) {
                    raf.seek(maxOf(QUICK_HASH_BYTES.toLong(), len - QUICK_HASH_BYTES))
                    n = raf.read(buf)
                    if (n > 0) md.update(buf, 0, n)
                }
                md.update(len.toString().toByteArray())
                toHex(md.digest())
            }
        } catch (e: IOException) {
            null
        }

    private fun fullHash(
        file: File,
        cancel: AtomicBoolean,
    ): String? =
        try {
            file.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-1")
                val buf = ByteArray(FULL_HASH_BUFFER)
                while (true) {
                    if (cancel.get()) return null
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
                toHex(md.digest())
            }
        } catch (e: IOException) {
            null
        }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return sb.toString()
    }

    companion object {
        const val MAX_LARGEST = 100
        const val MIN_DUPLICATE_SIZE = 16L * 1024
        private const val OLD_DOWNLOAD_MS = 90L * 24 * 3600 * 1000
        private const val QUICK_HASH_BYTES = 64 * 1024
        private const val FULL_HASH_BUFFER = 256 * 1024
        private const val HEX = "0123456789abcdef"
        private val JUNK_EXTENSIONS =
            setOf("tmp", "temp", "log", "bak", "old", "cache", "crdownload", "part", "dmp")
        private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store")
    }
}
