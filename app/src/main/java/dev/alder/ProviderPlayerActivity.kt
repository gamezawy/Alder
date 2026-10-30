package dev.alder

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * Jellyfin playback using Alder's glass/tungsten visual language and the same core viewing controls
 * as the Stremio player: seeking, aspect ratio, audio/subtitle track selection, remembered languages,
 * and subtitle appearance settings.
 */
class ProviderPlayerActivity : Screen() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var root: FrameLayout
    private lateinit var controls: LinearLayout
    private lateinit var progress: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var playPause: TextView
    private lateinit var subtitleButton: TextView
    private lateinit var aspectButton: TextView
    private lateinit var speedButton: TextView
    private var jellyfinItemId: String? = null
    private var seriesId: String? = null
    private var jellyfinPlaySessionId: String? = null
    private var stoppedReported = false
    private var controlsVisible = true
    private var scrubbing = false
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var startAtMs = 0L
    private val progressHandler = Handler(Looper.getMainLooper())
    private val hideControlsTask = Runnable { hideControls() }
    private val progressTask = object : Runnable {
        override fun run() {
            updateProgress()
            if (player != null) progressHandler.postDelayed(this, 1_000L)
        }
    }
    private val progressReporter = object : Runnable {
        override fun run() {
            val id = jellyfinItemId ?: return
            val exo = player ?: return
            val ticks = exo.currentPosition.coerceAtLeast(0L) * 10_000L
            Screen.pool.execute {
                runCatching { Jellyfin.reportPlayback(applicationContext, id, ticks, !exo.isPlaying, jellyfinPlaySessionId) }
            }
            progressHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("streamUrl").orEmpty()
        val itemId = intent.getStringExtra("jellyfinItemId").orEmpty().takeIf { it.isNotBlank() }
        val title = intent.getStringExtra("title").orEmpty()
        val episodeTitle = intent.getStringExtra("episodeTitle").orEmpty()
        if (url.isBlank() && itemId == null) { finish(); return }
        jellyfinItemId = itemId
        seriesId = intent.getStringExtra("seriesId")
        startAtMs = intent.getLongExtra("resumeAtMs", 0L).coerceAtLeast(0L)

        val remembered = (seriesId ?: itemId)?.let { Settings.titleChoice(this, it) }
        val audioLanguage = remembered?.audio ?: Settings.audioLanguage(this)
        val subtitleLanguage = remembered?.subtitles?.takeIf { it.isNotBlank() } ?: Settings.subtitleLanguage(this)
        val trackBuilder = androidx.media3.common.TrackSelectionParameters.Builder(this)
            .setPreferredAudioLanguage(audioLanguage)
            .setPreferredTextLanguage(subtitleLanguage)
        if (remembered?.subtitles == "" || Settings.subPref(this) == "off") {
            trackBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }

        val headers = linkedMapOf<String, String>()
        intent.getStringExtra("userAgent")?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        intent.getStringExtra("referrer")?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        if (itemId != null) headers.putAll(Jellyfin.authorizationHeaders(this))
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource)

        playerView = PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            resizeMode = this@ProviderPlayerActivity.resizeMode
            subtitleView?.setFractionalTextSize(SubtitleViewSize())
            subtitleView?.setStyle(CaptionStyleCompat(Settings.subtitleColor(context),
                if (Settings.subtitleBackground(context)) 0xB3000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, null))
            setOnClickListener { showControls(focus = true) }
        }
        controls = buildControls(title, episodeTitle)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        playPause.post { if (!isFinishing && !isDestroyed) playPause.requestFocus() }

        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { exo ->
            playerView.player = exo
            exo.trackSelectionParameters = trackBuilder.build()
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.widget.Toast.makeText(this@ProviderPlayerActivity,
                        getString(R.string.player_source_failed), android.widget.Toast.LENGTH_LONG).show()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPause()
                    if (isPlaying) scheduleHide() else showControls()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateTrackButton()
                    rememberSelectedTracks(tracks)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateProgress()
                }
            })
        }

        if (itemId != null) {
            background({ Jellyfin.playback(applicationContext, itemId) }) { result ->
                if (isFinishing || isDestroyed) return@background
                val playback = result.getOrElse {
                    android.widget.Toast.makeText(this, it.message ?: getString(R.string.load_failed),
                        android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    return@background
                }
                jellyfinPlaySessionId = playback.playSessionId
                Screen.pool.execute {
                    runCatching {
                        Jellyfin.reportStarted(applicationContext, itemId, playback.mediaSourceId,
                            playback.playSessionId, playback.directPlay, startAtMs * 10_000L)
                    }
                }
                prepare(playback.streamUrl, playback.subtitles)
                progressHandler.postDelayed(progressReporter, 15_000L)
            }
        } else prepare(url)
        progressHandler.post(progressTask)
        showControls()
    }

    private fun SubtitleViewSize() =
        androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Settings.subtitleSize(this) / 100f

    private fun buildControls(title: String, episodeTitle: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(PAD), dp(24), dp(PAD), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x66000000, 0xD90E0C0A.toInt()))
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        val heading = column(Gravity.START).apply {
            addView(caps(R.string.player_now_playing, TUNGSTEN))
            addView(text(title, SERIF_TITLE, serif = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }.marginTop(4))
            if (episodeTitle.isNotBlank()) addView(text(episodeTitle, SMALL, color = TEXT_2).marginTop(3))
        }
        panel.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val timeline = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("00:00", SMALL, color = TEXT_2).also { elapsed = it })
            addView(SeekBar(this@ProviderPlayerActivity).apply {
                max = 10_000
                isFocusable = true
                contentDescription = getString(R.string.player_seek)
                progressTintList = android.content.res.ColorStateList.valueOf(TUNGSTEN)
                thumbTintList = android.content.res.ColorStateList.valueOf(TEXT)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66FFFFFF)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                        if (fromUser) elapsed.text = clock((player?.duration?.takeIf { it > 0 } ?: 0L) * value / maxOf(seekBar.max, 1))
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) { scrubbing = true; showControls() }
                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        val exo = player
                        if (exo != null && exo.duration > 0) exo.seekTo(exo.duration * seekBar.progress / maxOf(seekBar.max, 1))
                        scrubbing = false
                        updateProgress()
                        scheduleHide()
                    }
                })
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                        val delta = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                        player?.let { exo -> if (exo.duration > 0) exo.seekTo((exo.currentPosition + delta).coerceIn(0L, exo.duration)) }
                        true
                    } else false
                }
            }.also { progress = it }, LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                marginStart = dp(12); marginEnd = dp(12)
            })
            addView(text("00:00", SMALL, color = TEXT_2).also { duration = it })
        }
        panel.addView(timeline, LinearLayout.LayoutParams(-1, dp(38)))

        fun addAction(target: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val view = text(label, SMALL, bold = true, color = if (primary) BASE else TEXT).apply {
                gravity = Gravity.CENTER
                minWidth = dp(if (primary) 88 else 104)
                minHeight = dp(44)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = if (primary) rounded(TUNGSTEN, 18) else glass(18)
                isFocusable = true
                rowFocus(18)
                setOnClickListener { action() }
            }
            target.addView(view, LinearLayout.LayoutParams(-2, dp(46)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })
            return view
        }

        val transport = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        addAction(transport, getString(R.string.player_rewind_10)) { seekBy(-10_000L) }
        playPause = addAction(transport, getString(R.string.action_play), primary = true) {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        addAction(transport, getString(R.string.player_forward_10)) { seekBy(10_000L) }
        panel.addView(transport, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        val options = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        subtitleButton = addAction(options, getString(R.string.tab_subtitles)) { openTrackOptions(C.TRACK_TYPE_TEXT) }
        addAction(options, getString(R.string.player_audio)) { openTrackOptions(C.TRACK_TYPE_AUDIO) }
        aspectButton = addAction(options, getString(R.string.player_aspect_ratio) + " · " + getString(R.string.player_aspect_fit)) { openAspectOptions() }
        speedButton = addAction(options, getString(R.string.player_speed) + " · 1×") { openSpeedOptions() }
        val optionsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(options, ViewGroup.LayoutParams(-2, -2))
        }
        panel.addView(optionsScroll, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(5) })
        return panel
    }

    private fun prepare(url: String, subtitles: List<JellyfinSubtitle> = emptyList()) {
        val exo = player ?: return
        val lower = url.substringBefore('?').lowercase(Locale.ROOT)
        val subtitleConfigurations = subtitles.map { subtitle ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                .setMimeType(subtitle.mimeType)
                .setLanguage(subtitle.language)
                .setLabel(subtitle.label)
                .setSelectionFlags(if (subtitle.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitleConfigurations).apply {
            if (lower.endsWith(".m3u8") || lower.contains("/master.m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        exo.setMediaItem(item, startAtMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun seekBy(delta: Long) {
        player?.let { exo ->
            if (exo.duration > 0) exo.seekTo((exo.currentPosition + delta).coerceIn(0L, exo.duration))
            showControls()
            updateProgress()
        }
    }

    private fun openAspectOptions() {
        val modes = listOf(
            getString(R.string.player_aspect_fit),
            getString(R.string.player_aspect_fill),
            getString(R.string.player_aspect_zoom)
        )
        val selected = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> 1
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> 2
            else -> 0
        }
        choose(getString(R.string.player_aspect_ratio), modes, checked = selected) { index ->
            resizeMode = when (index) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            playerView.resizeMode = resizeMode
            aspectButton.text = getString(R.string.player_aspect_ratio) + " · " + modes[index]
            showControls()
        }
    }

    private fun openSpeedOptions() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = speeds.map { speed -> if (speed == 1f) "1×" else "${speed}×" }
        val current = player?.playbackParameters?.speed ?: 1f
        val selected = speeds.indices.minByOrNull { kotlin.math.abs(speeds[it] - current) } ?: 2
        choose(getString(R.string.player_speed), labels, checked = selected) { index ->
            player?.setPlaybackSpeed(speeds[index])
            speedButton.text = getString(R.string.player_speed) + " · " + labels[index]
            showControls(focus = true)
        }
    }

    private data class TrackChoice(val group: Tracks.Group, val index: Int, val format: Format, val label: String)

    private fun trackChoices(type: Int): List<TrackChoice> {
        val tracks = player?.currentTracks ?: return emptyList()
        return tracks.groups.filter { it.type == type }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                val format = group.getTrackFormat(index)
                val language = format.language?.takeIf { it != "und" }?.let {
                    Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault())
                }
                val label = listOfNotNull(
                    language?.takeIf { it.isNotBlank() },
                    format.label?.takeIf { it.isNotBlank() && !it.equals(language, true) },
                    if (type == C.TRACK_TYPE_AUDIO) audioFormat(format) else null
                ).joinToString(" · ").ifBlank { getString(R.string.track_number, index + 1) }
                TrackChoice(group, index, format, label)
            }
        }
    }

    private fun openTrackOptions(type: Int) {
        val exo = player ?: return
        val choices = trackChoices(type)
        if (type == C.TRACK_TYPE_TEXT) {
            if (choices.isEmpty()) {
                choose(getString(R.string.tab_subtitles), listOf(getString(R.string.player_no_tracks))) {}
                return
            }
            val labels = listOf(getString(R.string.subtitles_off)) + choices.map { it.label }
            val selected = if (C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes) 0
                else choices.indexOfFirst { choice -> choice.group.isTrackSelected(choice.index) }.let { if (it < 0) 0 else it + 1 }
            choose(getString(R.string.tab_subtitles), labels, checked = selected) { index ->
                val builder = exo.trackSelectionParameters.buildUpon()
                if (index == 0) {
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    Settings.pickedInPlayer(this, seriesId ?: jellyfinItemId.orEmpty(), subtitles = "")
                } else {
                    val choice = choices[index - 1]
                    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    builder.setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index)))
                    Settings.pickedInPlayer(this, seriesId ?: jellyfinItemId.orEmpty(),
                        subtitles = choice.format.language?.takeIf { it != "und" } ?: "")
                }
                exo.trackSelectionParameters = builder.build()
                updateTrackButton()
                showControls()
            }
        } else {
            val labels = choices.map { it.label }
            if (labels.isEmpty()) {
                choose(getString(R.string.player_audio), listOf(getString(R.string.player_no_tracks))) {}
                return
            }
            val selected = choices.indexOfFirst { it.group.isTrackSelected(it.index) }.coerceAtLeast(0)
            choose(getString(R.string.player_audio), labels, checked = selected) { index ->
                val choice = choices[index]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index)))
                    .build()
                Settings.pickedInPlayer(this, seriesId ?: jellyfinItemId.orEmpty(),
                    audio = choice.format.language?.takeIf { it != "und" } ?: "")
                showControls()
            }
        }
    }

    private fun rememberSelectedTracks(tracks: Tracks) {
        val key = seriesId ?: jellyfinItemId ?: return
        val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.getTrackFormat(0)?.language?.takeIf { it != "und" }
        val text = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            ?.getTrackFormat(0)?.language?.takeIf { it != "und" }
        if (audio != null) Settings.pickedInPlayer(this, key, audio = audio)
        if (text != null) Settings.pickedInPlayer(this, key, subtitles = text)
    }

    private fun updateTrackButton() {
        val exo = player ?: return
        val selected = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        subtitleButton.text = if (selected == null || C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes)
            getString(R.string.subtitles_off) else getString(R.string.tab_subtitles)
    }

    private fun audioFormat(format: Format): String? = when (format.sampleMimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        else -> null
    }

    private fun updatePlayPause() {
        playPause.text = if (player?.isPlaying == true) getString(R.string.player_pause) else getString(R.string.action_play)
    }

    private fun updateProgress() {
        val exo = player ?: return
        val length = exo.duration.takeIf { it > 0L } ?: 0L
        val position = exo.currentPosition.coerceAtLeast(0L)
        if (!scrubbing) progress.progress = if (length > 0) (position * progress.max / length).toInt().coerceIn(0, progress.max) else 0
        elapsed.text = clock(position)
        duration.text = clock(length)
        updatePlayPause()
    }

    private fun clock(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1_000L).toInt()
        return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
    }

    private fun showControls(focus: Boolean = false) {
        controlsVisible = true
        controls.visibility = View.VISIBLE
        controls.animate().cancel()
        controls.alpha = 1f
        updateProgress()
        if (focus || !hasControlsFocus()) playPause.requestFocus()
        if (player?.isPlaying == true) scheduleHide() else progressHandler.removeCallbacks(hideControlsTask)
    }

    private fun hasControlsFocus(): Boolean {
        var view = currentFocus
        while (view != null) {
            if (view === controls) return true
            view = view.parent as? View
        }
        return false
    }

    private fun scheduleHide() {
        progressHandler.removeCallbacks(hideControlsTask)
        if (player?.isPlaying == true) progressHandler.postDelayed(hideControlsTask, 5_000L)
    }

    private fun hideControls() {
        if (!controlsVisible || player?.isPlaying != true) return
        controlsVisible = false
        controls.animate().alpha(0f).setDuration(220).withEndAction {
            if (!controlsVisible) controls.visibility = View.GONE
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    showControls(focus = true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); showControls(focus = true); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); showControls(focus = true); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (!controlsVisible) {
                        seekBy(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
                        return true
                    }
                    if (!hasControlsFocus()) {
                        showControls(focus = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!controlsVisible || !hasControlsFocus()) {
                        showControls(focus = true)
                        return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP && controlsVisible) scheduleHide()
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        progressHandler.removeCallbacksAndMessages(null)
        val id = jellyfinItemId
        val exo = player
        if (id != null && exo != null && !stoppedReported) {
            stoppedReported = true
            Jellyfin.invalidateCache(applicationContext)
            val ticks = exo.currentPosition.coerceAtLeast(0L) * 10_000L
            Screen.pool.execute {
                runCatching { Jellyfin.reportStopped(applicationContext, id, ticks, jellyfinPlaySessionId) }
            }
        }
        playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
