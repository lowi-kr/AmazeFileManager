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

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
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
 * Built-in Media3 player with bottom-positioned transport controls and an orientation toggle.
 *
 * Receives an ACTION_VIEW intent for a video file, with a content or file URI.
 */
class VideoPlayerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(VideoPlayerActivity::class.java)

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var videoUri: Uri? = null
    private var resumePosition = 0L
    private var resumePlayWhenReady = true
    private var forcedOrientation: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent?.data
        if (videoUri == null) {
            finish()
            return
        }

        supportActionBar?.hide()
        setContentView(R.layout.activity_video_player)
        val view = findViewById<PlayerView>(R.id.video_view)
        playerView = view
        findViewById<ImageButton>(R.id.rotate_screen).setOnClickListener {
            toggleOrientation()
        }
        hideSystemBars()

        savedInstanceState?.let {
            resumePosition = it.getLong(KEY_POSITION, 0L)
            resumePlayWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
            forcedOrientation =
                it.getInt(KEY_FORCED_ORIENTATION, NO_FORCED_ORIENTATION)
                    .takeUnless { orientation -> orientation == NO_FORCED_ORIENTATION }
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
        outState.putInt(KEY_FORCED_ORIENTATION, forcedOrientation ?: NO_FORCED_ORIENTATION)
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

    private fun toggleOrientation() {
        val current = resources.configuration.orientation
        val target =
            if (current == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        forcedOrientation = target
        requestedOrientation = target
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
        private const val KEY_FORCED_ORIENTATION = "video_forced_orientation"
        private const val NO_FORCED_ORIENTATION = -100
    }
}