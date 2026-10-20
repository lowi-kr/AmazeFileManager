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

package com.amaze.filemanager.ui.views.drawer

import android.app.Activity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.amaze.filemanager.R
import com.amaze.filemanager.ui.activities.StorageAnalyserActivity
import com.amaze.filemanager.ui.activities.WifiTransferActivity

/**
 * The built-in utilities (formerly the separate Amaze File Utilities app) that get an entry in the
 * navigation drawer. To add a utility, add its screen here: the drawer picks it up by itself.
 */
object EmbeddedUtilities {
    class Entry(
        @StringRes val title: Int,
        @DrawableRes val icon: Int,
        val activity: Class<out Activity>,
    )

    @JvmStatic
    fun drawerEntries(): List<Entry> =
        listOf(
            Entry(R.string.wifip2p, R.drawable.ic_round_connect_without_contact_24, WifiTransferActivity::class.java),
            Entry(R.string.analyse_storage, R.drawable.ic_round_analytics_24, StorageAnalyserActivity::class.java),
        )
}
