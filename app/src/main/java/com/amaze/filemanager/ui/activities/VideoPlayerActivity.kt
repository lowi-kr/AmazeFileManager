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

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.amaze.filemanager.R
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Minimal built-in video player based on Media3 ExoPlayer.
 *
 * Receives an ACTION_VIEW intent with a video/* mime type and a content:// or file:// uri.
 */
class VideoPlayerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(VideoPlayerActivity::class.java)

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var videoUri: Uri? = null
    private var resumePosition = 0L
    private var resumePlayWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent?.data
        if (videoUri == null) {
            finish()
            return
        }

        supportActionBar?.hide()
        val view =
            PlayerView(this).apply {
                setBackgroundColor(Color.BLACK)
            }
        playerView = view
        setContentView(view)
        hideSystemBars()

        savedInstanceState?.let {
            resumePosition = it.getLong(KEY_POSITION, 0L)
            resumePlayWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
        }
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
        outState.putLong(KEY_POSITION, resumePosition)
        outState.putBoolean(KEY_PLAY_WHEN_READY, resumePlayWhenReady)
    }

    private fun initializePlayer() {
        val uri = videoUri ?: return
        val view = playerView ?: return
        if (player != null) return

        val exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    log.error("Video playback failed for {}", uri, error)
                    Toast.makeText(
                        this@VideoPlayerActivity,
                        R.string.video_open_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            },
        )
        view.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.playWhenReady = resumePlayWhenReady
        exoPlayer.seekTo(resumePosition)
        exoPlayer.prepare()
        player = exoPlayer
    }

    private fun releasePlayer() {
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
            it.release()
        }
        player = null
        playerView?.player = null
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val KEY_POSITION = "video_position"
        private const val KEY_PLAY_WHEN_READY = "video_play_when_ready"
    }
}
