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

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.amaze.filemanager.R
import com.amaze.filemanager.ui.views.VideoGestureView
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Built-in Media3 video player: transport controls, swipe gestures for seek/volume/brightness,
 * playback speed, aspect ratio cycling, screen lock, picture-in-picture and subtitle tracks
 * (embedded, or loaded from an external .srt/.vtt file).
 *
 * Receives an ACTION_VIEW intent for a video file, with a content or file URI.
 */
@UnstableApi
class VideoPlayerActivity : AppCompatActivity() {
    private val log: Logger = LoggerFactory.getLogger(VideoPlayerActivity::class.java)

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var playerView: PlayerView? = null
    private var gestureView: VideoGestureView? = null
    private var feedbackText: TextView? = null
    private var lockButton: ImageButton? = null
    private var floatingUnlockButton: ImageButton? = null
    private var backButton: TextView? = null
    private var lockOverlayHideRunnable: Runnable? = null
    private var controlsRoot: ViewGroup? = null
    private var videoUri: Uri? = null
    private var externalSubtitle: Uri? = null
    private var resumePosition = 0L
    private var resumePlayWhenReady = true
    private var forcedOrientation: Int? = null
    private var speed = 1f
    private var resizeModeIndex = 0
    private var locked = false

    private var audioManager: AudioManager? = null
    private var maxVolume = 1
    private var gestureBaseline = Float.NaN
    private var feedbackHideRunnable: Runnable? = null

    private val subtitlePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // some providers (e.g. a FileProvider for app-internal files) don't support this; fine either way
                }
                externalSubtitle = uri
                initializePlayer(forceRebuild = true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        videoUri = intent?.data
        if (videoUri == null) {
            finish()
            return
        }
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1

        supportActionBar?.hide()
        setContentView(R.layout.activity_video_player)
        val view = findViewById<PlayerView>(R.id.video_view)
        playerView = view
        controlsRoot = findViewById(R.id.video_root)

        val gesture = VideoGestureView(this)
        gesture.listener = gestureListener()
        gesture.controlsView = findViewById(R.id.exo_controller)
        (view.parent as ViewGroup).addView(
            gesture,
            view.layoutParams.let { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) },
        )
        gestureView = gesture

        feedbackText =
            TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                visibility = android.view.View.GONE
                val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.gravity = android.view.Gravity.CENTER
                (controlsRoot as FrameLayout).addView(this, lp)
            }

        backButton =
            TextView(this).apply {
                text = "\u2039"
                textSize = 26f
                setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(0x99000000.toInt())
                contentDescription = getString(R.string.video_player_back)
                setOnClickListener { if (!locked) finish() }
                val lp = FrameLayout.LayoutParams(dp(44), dp(44))
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                lp.setMargins(dp(12), dp(12), 0, 0)
                (controlsRoot as FrameLayout).addView(this, lp)
            }
        // keep the on-screen back button in sync with the real controller (hidden/shown together,
        // including the controller's own auto-hide timer) whenever the screen isn't locked
        view.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!locked) backButton?.visibility = visibility
            },
        )

        floatingUnlockButton =
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_round_lock_24)
                setColorFilter(android.graphics.Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                contentDescription = getString(R.string.unlock_screen)
                visibility = android.view.View.GONE
                setOnClickListener { setLocked(false) }
                val size = dp(48)
                val lp = FrameLayout.LayoutParams(size, size)
                lp.gravity = android.view.Gravity.CENTER
                (controlsRoot as FrameLayout).addView(this, lp)
            }

        findViewById<ImageButton>(R.id.rotate_screen).setOnClickListener { toggleOrientation() }
        findViewById<ImageButton>(R.id.aspect_ratio).setOnClickListener { cycleResizeMode() }
        findViewById<ImageButton>(R.id.playback_speed).setOnClickListener { showSpeedMenu(it) }
        findViewById<ImageButton>(R.id.subtitles).setOnClickListener { showSubtitleMenu(it) }
        lockButton =
            findViewById<ImageButton>(R.id.lock_screen).also {
                it.setOnClickListener { _ -> setLocked(!locked) }
            }
        if (Build.VERSION.SDK_INT >= 26) {
            findViewById<ImageButton>(R.id.enter_pip).setOnClickListener { enterPip() }
        } else {
            findViewById<ImageButton>(R.id.enter_pip).visibility = android.view.View.GONE
        }
        hideSystemBars()

        savedInstanceState?.let {
            resumePosition = it.getLong(KEY_POSITION, 0L)
            resumePlayWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
            speed = it.getFloat(KEY_SPEED, 1f)
            resizeModeIndex = it.getInt(KEY_RESIZE_MODE, 0)
            externalSubtitle = it.getString(KEY_SUBTITLE_URI)?.let(Uri::parse)
            forcedOrientation =
                it.getInt(KEY_FORCED_ORIENTATION, NO_FORCED_ORIENTATION)
                    .takeUnless { o -> o == NO_FORCED_ORIENTATION }
        }
        view.resizeMode = RESIZE_MODES[resizeModeIndex]
    }

    override fun onStart() {
        super.onStart()
        initializePlayer(forceRebuild = false)
    }

    override fun onStop() {
        super.onStop()
        if (!isCurrentlyInPip()) releasePlayer()
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
        outState.putFloat(KEY_SPEED, speed)
        outState.putInt(KEY_RESIZE_MODE, resizeModeIndex)
        externalSubtitle?.let { outState.putString(KEY_SUBTITLE_URI, it.toString()) }
        outState.putInt(KEY_FORCED_ORIENTATION, forcedOrientation ?: NO_FORCED_ORIENTATION)
    }

    // ---------------------------------------------------------------- playback

    private fun initializePlayer(forceRebuild: Boolean) {
        val uri = videoUri ?: return
        val view = playerView ?: return
        if (player != null && !forceRebuild) return
        if (forceRebuild) releasePlayer()

        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        val exoPlayer = ExoPlayer.Builder(this).setTrackSelector(selector).build()
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    log.error("Video playback failed for {}", uri, error)
                    Toast.makeText(this@VideoPlayerActivity, R.string.video_open_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
            },
        )
        view.player = exoPlayer

        val builder = MediaItem.Builder().setUri(uri)
        externalSubtitle?.let { subUri ->
            val mime = if (displayNameOf(subUri).endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            val sub =
                MediaItem.SubtitleConfiguration.Builder(subUri)
                    .setMimeType(mime)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            builder.setSubtitleConfigurations(listOf(sub))
        }
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.playbackParameters = PlaybackParameters(speed)
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
        trackSelector = null
        playerView?.player = null
    }

    // ---------------------------------------------------------------- toolbar actions

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

    private fun cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        playerView?.resizeMode = RESIZE_MODES[resizeModeIndex]
        showFeedback(RESIZE_LABELS[resizeModeIndex])
        hideFeedbackSoon()
    }

    private fun showSpeedMenu(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        for (s in speeds) menu.menu.add(0, s.toRawBits(), 0, "${if (s == s.toInt().toFloat()) s.toInt() else s}x")
        menu.setOnMenuItemClickListener { item ->
            speed = Float.fromBits(item.itemId)
            player?.playbackParameters = PlaybackParameters(speed)
            showFeedback("${if (speed == speed.toInt().toFloat()) speed.toInt() else speed}x")
            hideFeedbackSoon()
            true
        }
        menu.show()
    }

    private fun showSubtitleMenu(anchor: android.view.View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, SUB_OFF, 0, getString(R.string.subtitles_off))
        val tracks = player?.currentTracks
        var trackIndex = 0
        val groupMap = HashMap<Int, Pair<Tracks.Group, Int>>()
        tracks?.groups?.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val id = SUB_OFF + 1 + trackIndex
                    groupMap[id] = group to i
                    menu.menu.add(0, id, 0, format.label ?: format.language ?: "Track ${trackIndex + 1}")
                    trackIndex++
                }
            }
        }
        menu.menu.add(0, SUB_LOAD_FILE, 0, getString(R.string.subtitles_load_file))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                SUB_OFF -> {
                    player?.trackSelectionParameters =
                        player!!.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                }
                SUB_LOAD_FILE -> {
                    try {
                        subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "*/*"))
                    } catch (e: Exception) {
                        toast(getString(R.string.subtitles_load_failed))
                    }
                }
                else -> {
                    val chosen = groupMap[item.itemId] ?: return@setOnMenuItemClickListener true
                    player?.trackSelectionParameters =
                        player!!.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(
                                androidx.media3.common.TrackSelectionOverride(chosen.first.mediaTrackGroup, chosen.second),
                            )
                            .build()
                }
            }
            true
        }
        menu.show()
    }

    private fun setLocked(value: Boolean) {
        locked = value
        gestureView?.gesturesEnabled = !value
        playerView?.useController = !value
        // the floating unlock button is never shown automatically here -- it only appears when the
        // user taps the screen while locked (see toggleLockedOverlay); locking or unlocking just
        // clears any pending show/hide state for it.
        hideLockedOverlay()
        if (value) {
            playerView?.hideController()
            backButton?.visibility = android.view.View.GONE
        } else {
            playerView?.showController()
            backButton?.visibility = android.view.View.VISIBLE
        }
        lockButton?.setImageResource(if (value) R.drawable.ic_round_lock_24 else R.drawable.ic_round_lock_open_24)
        showFeedback(getString(if (value) R.string.screen_locked else R.string.screen_unlocked))
        hideFeedbackSoon()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // a locked screen only unlocks on the lock button, so swallow back presses while locked
        if (locked) return
        super.onBackPressed()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val w = player?.videoSize?.width ?: 16
        val h = player?.videoSize?.height ?: 9
        val params =
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(w.coerceAtLeast(1), h.coerceAtLeast(1)))
                .build()
        try {
            enterPictureInPictureMode(params)
        } catch (e: IllegalStateException) {
            // device does not actually support PiP despite the feature flag
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerView?.useController = !isInPictureInPictureMode && !locked
        controlsRoot?.let { root ->
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child !== playerView) child.visibility = if (isInPictureInPictureMode) android.view.View.GONE else child.visibility
            }
        }
        if (!isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            // the user closed PiP without returning to the app
            releasePlayer()
            finish()
        }
    }

    private fun isCurrentlyInPip(): Boolean = Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode

    // ---------------------------------------------------------------- gestures

    private fun gestureListener() =
        object : VideoGestureView.Listener {
            override fun onVerticalSwipe(
                rightSide: Boolean,
                deltaFraction: Float,
                finished: Boolean,
            ) {
                if (finished) {
                    gestureBaseline = Float.NaN
                    hideFeedbackSoon()
                    return
                }
                val scaled = deltaFraction * 2.2f
                if (rightSide) {
                    val am = audioManager ?: return
                    if (gestureBaseline.isNaN()) gestureBaseline = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    val target = (gestureBaseline + scaled * maxVolume).roundToInt().coerceIn(0, maxVolume)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    showFeedback(getString(R.string.volume_percent, (target * 100 / maxVolume.coerceAtLeast(1))))
                } else {
                    val window = window
                    if (gestureBaseline.isNaN()) {
                        gestureBaseline = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                    }
                    val target = (gestureBaseline + scaled).coerceIn(0.02f, 1f)
                    val attrs = window.attributes
                    attrs.screenBrightness = target
                    window.attributes = attrs
                    showFeedback(getString(R.string.brightness_percent, (target * 100).roundToInt()))
                }
            }

            override fun onHorizontalSeek(
                deltaFraction: Float,
                finished: Boolean,
            ) {
                val p = player ?: return
                val duration = p.duration
                if (duration <= 0 || duration == C.TIME_UNSET) return
                if (gestureBaseline.isNaN()) gestureBaseline = p.currentPosition.toFloat()
                val delta = (deltaFraction * duration * SEEK_SENSITIVITY).toLong()
                val target = (gestureBaseline.toLong() + delta).coerceIn(0, duration)
                showFeedback(formatSeekFeedback(target, delta))
                if (finished) {
                    p.seekTo(target)
                    gestureBaseline = Float.NaN
                    hideFeedbackSoon()
                }
            }

            override fun onDoubleTap(rightSide: Boolean) {
                val p = player ?: return
                val duration = p.duration
                val target =
                    if (rightSide) {
                        (p.currentPosition + 10_000).let { if (duration > 0) it.coerceAtMost(duration) else it }
                    } else {
                        (p.currentPosition - 10_000).coerceAtLeast(0)
                    }
                p.seekTo(target)
                showFeedback(if (rightSide) "+10s" else "-10s")
                hideFeedbackSoon()
            }

            override fun onSingleTap() {
                if (locked) {
                    toggleLockedOverlay()
                    return
                }
                val view = playerView ?: return
                if (view.isControllerFullyVisible) view.hideController() else view.showController()
            }
        }

    /** Tap-to-reveal while locked: shows the unlock button for a few seconds, or hides it early on a second tap. */
    private fun toggleLockedOverlay() {
        val btn = floatingUnlockButton ?: return
        if (btn.visibility == android.view.View.VISIBLE) hideLockedOverlay() else showLockedOverlay()
    }

    private fun showLockedOverlay() {
        val btn = floatingUnlockButton ?: return
        lockOverlayHideRunnable?.let { btn.removeCallbacks(it) }
        btn.visibility = android.view.View.VISIBLE
        val r = Runnable { btn.visibility = android.view.View.GONE }
        lockOverlayHideRunnable = r
        btn.postDelayed(r, 2500)
    }

    private fun hideLockedOverlay() {
        val btn = floatingUnlockButton ?: return
        lockOverlayHideRunnable?.let { btn.removeCallbacks(it) }
        lockOverlayHideRunnable = null
        btn.visibility = android.view.View.GONE
    }

    private fun formatSeekFeedback(
        targetMs: Long,
        deltaMs: Long,
    ): String {
        val sign = if (deltaMs >= 0) "+" else "-"
        return "${formatTime(targetMs)}  ($sign${formatTime(abs(deltaMs))})"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun showFeedback(text: String) {
        feedbackHideRunnable?.let { feedbackText?.removeCallbacks(it) }
        feedbackText?.text = text
        feedbackText?.visibility = android.view.View.VISIBLE
    }

    private fun hideFeedbackSoon() {
        val r = Runnable { feedbackText?.visibility = android.view.View.GONE }
        feedbackHideRunnable = r
        feedbackText?.postDelayed(r, 600)
    }

    // ---------------------------------------------------------------- misc

    private fun displayNameOf(uri: Uri): String =
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: uri.lastPathSegment ?: ""
        } catch (e: Exception) {
            uri.lastPathSegment ?: ""
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val KEY_POSITION = "video_position"
        private const val KEY_PLAY_WHEN_READY = "video_play_when_ready"
        private const val KEY_FORCED_ORIENTATION = "video_forced_orientation"
        private const val KEY_SPEED = "video_speed"
        private const val KEY_RESIZE_MODE = "video_resize_mode"
        private const val KEY_SUBTITLE_URI = "video_subtitle_uri"
        private const val NO_FORCED_ORIENTATION = -100
        private const val SEEK_SENSITIVITY = 1.0
        private const val SUB_OFF = 9000
        private const val SUB_LOAD_FILE = 9001
        private val RESIZE_MODES =
            intArrayOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                AspectRatioFrameLayout.RESIZE_MODE_FILL,
            )
        private val RESIZE_LABELS = arrayOf("Fit", "Crop", "Stretch")
    }
}
