package dev.alder

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.os.Handler
import android.os.Looper

/**
 * Media3 playback entry point for Jellyfin items and IPTV channels.
 * Stremio continues to use PlayerActivity and its source selection, fallback, and subtitle workflows.
 */
class ProviderPlayerActivity : Screen() {
    private var player: ExoPlayer? = null
    private var jellyfinItemId: String? = null
    private var jellyfinPlaySessionId: String? = null
    private var stoppedReported = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressReporter = object : Runnable {
        override fun run() {
            val id = jellyfinItemId ?: return
            val exo = player ?: return
            val ticks = exo.currentPosition.coerceAtLeast(0L) * 10_000L
            val paused = !exo.isPlaying
            Screen.pool.execute { runCatching { Jellyfin.reportPlayback(applicationContext, id, ticks, paused, jellyfinPlaySessionId) } }
            progressHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("streamUrl").orEmpty()
        val itemId = intent.getStringExtra("jellyfinItemId").orEmpty().takeIf { it.isNotBlank() }
        val title = intent.getStringExtra("title").orEmpty()
        if (url.isBlank() && itemId == null) {
            finish()
            return
        }
        jellyfinItemId = itemId

        val playerView = PlayerView(this).apply {
            useController = true
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            if (title.isNotBlank()) addView(TextView(this@ProviderPlayerActivity).apply {
                text = title
                textSize = 17f
                setTextColor(Color.WHITE)
                setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
                setPadding(dp(22), dp(16), dp(22), dp(16))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        }
        setContentView(root)

        val remembered = itemId?.let { Settings.titleChoice(this, it) }
        val audioLanguage = remembered?.audio ?: Settings.audioLanguage(this)
        val subtitleLanguage = remembered?.subtitles?.takeIf { it.isNotBlank() } ?: Settings.subtitleLanguage(this)
        val trackBuilder = TrackSelectionParameters.Builder(this)
            .setPreferredAudioLanguage(audioLanguage)
            .setPreferredTextLanguage(subtitleLanguage)
        if (remembered?.subtitles == "" || Settings.subPref(this) == "off") trackBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        val trackParameters = trackBuilder.build()
        val headers = linkedMapOf<String, String>()
        intent.getStringExtra("userAgent")?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        intent.getStringExtra("referrer")?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        val dataSource = DefaultHttpDataSource.Factory().setUserAgent(Http.userAgent)
            .setDefaultRequestProperties(headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { exo ->
            playerView.player = exo
            exo.trackSelectionParameters = trackParameters
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.widget.Toast.makeText(this@ProviderPlayerActivity,
                        getString(R.string.player_source_failed), android.widget.Toast.LENGTH_LONG).show()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    val id = jellyfinItemId ?: return
                    val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.getTrackFormat(0)?.language
                    val subtitle = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.language
                    if (audio != null || subtitle != null) {
                        Settings.pickedInPlayer(this@ProviderPlayerActivity, id, audio = audio, subtitles = subtitle)
                    }
                }
            })
        }

        if (itemId != null) {
            background({ Jellyfin.playback(applicationContext, itemId) }) { result ->
                val playback = result.getOrElse {
                    android.widget.Toast.makeText(this, it.message ?: getString(R.string.load_failed),
                        android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    return@background
                }
                jellyfinPlaySessionId = playback.playSessionId
                Screen.pool.execute { runCatching { Jellyfin.reportStarted(applicationContext, itemId, playback.mediaSourceId, playback.playSessionId, playback.directPlay) } }
                prepare(playback.streamUrl)
                progressHandler.postDelayed(progressReporter, 15_000L)
            }
        } else prepare(url)
    }

    private fun prepare(url: String) {
        val exo = player ?: return
        val lower = url.substringBefore('?').lowercase()
        val item = MediaItem.Builder().setUri(url).apply {
            if (lower.endsWith(".m3u8") || lower.contains("/master.m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
    }

    override fun onStop() {
        progressHandler.removeCallbacksAndMessages(null)
        val id = jellyfinItemId
        val exo = player
        if (id != null && exo != null && !stoppedReported) {
            stoppedReported = true
            val ticks = (exo.currentPosition.coerceAtLeast(0L) * 10_000L)
            Screen.pool.execute { runCatching { Jellyfin.reportStopped(applicationContext, id, ticks, jellyfinPlaySessionId) } }
        }
        player?.release()
        player = null
        super.onStop()
    }
}
