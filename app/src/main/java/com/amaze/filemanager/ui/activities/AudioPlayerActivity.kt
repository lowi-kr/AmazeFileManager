/*
 * This file is part of Amaze File Manager.
 * Licensed under the GNU General Public License v3 or later.
 */
package com.amaze.filemanager.ui.activities

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.amaze.filemanager.R
import com.google.common.util.concurrent.ListenableFuture
import org.slf4j.LoggerFactory

/** Media3 controller UI for audio playback managed by a background MediaSessionService. */
class AudioPlayerActivity : AppCompatActivity() {
    private val log = LoggerFactory.getLogger(AudioPlayerActivity::class.java)
    private var playerView: PlayerView? = null
    private var audioUri: Uri? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

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
    }

    override fun onStart() {
        super.onStart()
        connectController()
    }

    override fun onStop() {
        playerView?.player = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onStop()
    }

    private fun connectController() {
        val uri = audioUri ?: return
        val token = SessionToken(this, ComponentName(this, AudioPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val controller = future.get()
                playerView?.player = controller
                controller.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        log.error("Audio playback failed for {}", uri, error)
                        Toast.makeText(
                            this@AudioPlayerActivity,
                            R.string.audio_open_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                })
                val currentUri = controller.currentMediaItem?.localConfiguration?.uri
                if (currentUri != uri) {
                    controller.setMediaItem(MediaItem.fromUri(uri))
                    controller.prepare()
                    controller.play()
                } else if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                    controller.play()
                }
            } catch (error: Exception) {
                log.error("Could not connect to audio playback service for {}", uri, error)
                Toast.makeText(
                    this@AudioPlayerActivity,
                    R.string.audio_open_failed,
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }
}
