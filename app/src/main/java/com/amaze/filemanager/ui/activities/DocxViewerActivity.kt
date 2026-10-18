/*
 * Copyright (C) 2014-2026 Amaze File Manager contributors.
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui.activities

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.amaze.filemanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.zwobble.mammoth.DocumentConverter

/**
 * Built-in DOCX viewer using Mammoth to convert Word documents to HTML.
 *
 * Conversion runs off the main thread. The WebView cannot access local files or execute
 * JavaScript because the converted document is untrusted input.
 */
class DocxViewerActivity : AppCompatActivity() {
    private val log = LoggerFactory.getLogger(DocxViewerActivity::class.java)
    private val activityScope = CoroutineScope(Job() + Dispatchers.Main)
    private var webView: WebView? = null
    private var progressBar: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri: Uri = intent?.data ?: run {
            finish()
            return
        }
        title = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.docx_viewer)

        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView = web
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        progressBar = progress
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                web,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                progress,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        setContentView(root)
        loadDocument(uri)
    }

    private fun loadDocument(uri: Uri) {
        activityScope.launch {
            val html = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        DocumentConverter().convertToHtml(input).value
                    } ?: throw IllegalStateException("Unable to open the selected document")
                } catch (e: Exception) {
                    log.error("Failed to convert DOCX document", e)
                    null
                }
            }

            progressBar?.visibility = View.GONE
            if (html == null) {
                Toast.makeText(
                    this@DocxViewerActivity,
                    R.string.docx_open_failed,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }

            webView?.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
