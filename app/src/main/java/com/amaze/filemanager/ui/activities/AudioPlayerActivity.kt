/*
 * This file is part of Amaze File Manager.
 * Licensed under the GNU General Public License v3 or later.
 */
package com.amaze.filemanager.ui.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.amaze.filemanager.R
import org.slf4j.LoggerFactory

/** Built-in audio playback using the project's existing AndroidX Media3 stack. */
class AudioPlayerActivity : AppCompatActivity() {
    private val log = LoggerFactory.getLogger(AudioPlayerActivity::class.java)
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var audioUri: Uri? = null
    private var resumePosition = 0L
    private var resumePlayWhenReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioUri = intent?.data
        if (audioUri == null) {
            Toast.makeText(this, R.string.error_unsupported_or_null_uri, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        supportActionBar?.hide()
        setContentView(R.layout.activity_audio_player)
        playerView = findViewById(R.id.audio_view)
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
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        player?.let {
            resumePosition = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
        }
        outState.putLong(KEY_POSITION, resumePosition)
        outState.putBoolean(KEY_PLAY_WHEN_READY, resumePlayWhenReady)
        super.onSaveInstanceState(outState)
    }

    private fun initializePlayer() {
        val uri = audioUri ?: return
        val view = playerView ?: return
        if (player != null) return
        val mediaPlayer = ExoPlayer.Builder(this).build()
        mediaPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                log.error("Audio playback failed for {}", uri, error)
                Toast.makeText(this@AudioPlayerActivity, R.string.audio_open_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        })
        view.player = mediaPlayer
        mediaPlayer.setMediaItem(MediaItem.fromUri(uri))
        mediaPlayer.playWhenReady = resumePlayWhenReady
        mediaPlayer.seekTo(resumePosition)
        mediaPlayer.prepare()
        player = mediaPlayer
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

    companion object {
        private const val KEY_POSITION = "audio_position"
        private const val KEY_PLAY_WHEN_READY = "audio_play_when_ready"
    }
}
