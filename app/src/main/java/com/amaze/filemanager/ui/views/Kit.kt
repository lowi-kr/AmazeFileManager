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

package com.amaze.filemanager.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for building the dark, framework-only screens of the built-in utilities. */
class Kit(private val ctx: Context) {
    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun column(paddingDp: Int): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
        return c
    }

    fun label(
        text: String,
        sp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView {
        val t = TextView(ctx)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    fun roundedBg(
        color: Int,
        radiusDp: Int,
    ): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    fun card(): LinearLayout {
        val c = column(16)
        c.background = roundedBg(CARD, 12)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(12)
        c.layoutParams = lp
        return c
    }

    fun button(
        text: String,
        color: Int = ACCENT,
        onClick: () -> Unit,
    ): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(if (color == ACCENT) Color.BLACK else Color.WHITE)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33000000), roundedBg(color, 10), null)
        b.setOnClickListener { onClick() }
        return b
    }

    fun smallButton(
        text: String,
        onClick: () -> Unit,
    ): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.textSize = 13f
        b.setTextColor(ACCENT)
        b.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), roundedBg(0xFF2A2A2A.toInt(), 8), null)
        b.setPadding(dp(14), 0, dp(14), 0)
        b.minHeight = 0
        b.minimumHeight = dp(36)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(WRAP, WRAP)
        lp.rightMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    /** The top bar with a back arrow and a title. */
    fun topBar(
        title: String,
        onBack: () -> Unit,
    ): LinearLayout {
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(CARD)
        bar.setPadding(dp(4), dp(10), dp(16), dp(10))
        val back = TextView(ctx)
        back.text = "<"
        back.textSize = 22f
        back.setTextColor(TXT)
        back.setPadding(dp(16), dp(2), dp(16), dp(2))
        back.setOnClickListener { onBack() }
        val t = label(title, 20f, TXT, bold = true)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        bar.addView(back)
        bar.addView(t)
        return bar
    }

    companion object {
        const val BG = 0xFF121212.toInt()
        const val CARD = 0xFF1E1E1E.toInt()
        const val TXT = 0xFFEEEEEE.toInt()
        const val MUTED = 0xFF9E9E9E.toInt()
        const val ACCENT = 0xFF4DB6AC.toInt()
        const val DANGER = 0xFFE05A55.toInt()
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
