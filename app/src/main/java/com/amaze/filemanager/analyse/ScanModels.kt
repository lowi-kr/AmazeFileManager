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

/** A file or folder found by the scan. [size] is in bytes. */
class FileEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val isDir: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** A folder in the scanned tree, used for the visual map. [size] includes everything below it. */
class DirNode(val name: String, val path: String, val parent: DirNode?) {
    var size: Long = 0
    var filesSize: Long = 0
    var fileCount: Int = 0
    val children = ArrayList<DirNode>()
}

/** Files with identical content. */
class DuplicateGroup(val size: Long, val files: List<FileEntry>) {
    /** Space freed by keeping one copy. */
    val wasted: Long get() = size * (files.size - 1)
}

class ScanResult(
    val root: DirNode,
    val totalBytes: Long,
    val fileCount: Int,
    val dirCount: Int,
    val categoryBytes: Map<FileCategory, Long>,
    val categoryCounts: Map<FileCategory, Int>,
    val largest: List<FileEntry>,
    val junk: List<FileEntry>,
    val emptyDirs: List<FileEntry>,
    val apks: List<FileEntry>,
    val oldDownloads: List<FileEntry>,
    val screenshots: List<FileEntry>,
    val recordings: List<FileEntry>,
    val hidden: List<FileEntry>,
    /** Files grouped by size, only groups with two or more files. Input for duplicate detection. */
    val sizeGroups: Map<Long, List<FileEntry>>,
)
