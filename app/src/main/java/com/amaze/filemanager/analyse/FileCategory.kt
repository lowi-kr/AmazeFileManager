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

/** Broad file kinds used by the storage analyser, decided from the file extension. */
enum class FileCategory(val label: String, val color: Int, private val extensions: Set<String>) {
    IMAGES(
        "Images",
        0xFF42A5F5.toInt(),
        setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "raw", "dng",
            "tif", "tiff", "ico",
        ),
    ),
    VIDEOS(
        "Videos",
        0xFFAB47BC.toInt(),
        setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "mpg", "mpeg", "ts"),
    ),
    AUDIO(
        "Audio",
        0xFFEF5350.toInt(),
        setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "amr", "mid", "midi"),
    ),
    DOCUMENTS(
        "Documents",
        0xFFFFCA28.toInt(),
        setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
            "csv", "epub", "mobi", "xps", "cbz", "fb2", "md", "html", "htm", "xml", "json",
        ),
    ),
    ARCHIVES(
        "Archives",
        0xFF8D6E63.toInt(),
        setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso"),
    ),
    APPS("Apps", 0xFF66BB6A.toInt(), setOf("apk", "xapk", "apks", "aab", "obb")),
    OTHER("Other", 0xFF78909C.toInt(), emptySet()),
    ;

    companion object {
        private val byExtension: Map<String, FileCategory> =
            values().flatMap { cat -> cat.extensions.map { it to cat } }.toMap()

        /** Returns the lower case extension of a file name, or an empty string. */
        fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
        }

        fun forExtension(ext: String): FileCategory = byExtension[ext] ?: OTHER
    }
}
