package dev.alder

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class IptvActivity : Screen() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private var playlist = IptvPlaylist(emptyList())
    private var selectedGroup = ""
    private var playlistDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = column(Gravity.START).apply {
            setBackgroundColor(BASE)
            setPadding(dp(34), dp(24), dp(34), dp(20))
        }
        root.addView(text(getString(R.string.provider_live_tv), SERIF_TITLE, serif = true))
        status = text(getString(R.string.iptv_loading), SMALL, color = TEXT_2).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val configure = action(getString(R.string.iptv_playlist_settings)) { editPlaylist() }
        val refresh = action(getString(R.string.provider_retry)) { loadPlaylist(forceRefresh = true) }
        actions.addView(configure)
        actions.addView(refresh)
        root.addView(actions)
        list = column(Gravity.START)
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadPlaylist()
    }

    private fun action(label: String, click: () -> Unit) = text(label, BODY, color = TUNGSTEN).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isFocusable = true
        rowFocus(8)
        setOnClickListener { click() }
    }

    private fun loadPlaylist(forceRefresh: Boolean = false) {
        status.text = getString(R.string.iptv_loading)
        list.removeAllViews()
        background({ Iptv.load(applicationContext, forceRefresh) }) { result ->
            result.onSuccess {
                playlist = it
                status.text = getString(R.string.iptv_channel_count, it.channels.size)
                selectedGroup = getString(R.string.filter_all)
                showGroups()
            }.onFailure {
                status.text = getString(R.string.iptv_load_failed)
                list.addView(action(getString(R.string.iptv_playlist_settings), ::editPlaylist))
            }
        }
    }

    private fun showGroups() {
        list.removeAllViews()
        val groups = listOf(getString(R.string.filter_all), getString(R.string.iptv_favorites)) + playlist.groups()
        val groupRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        groups.forEach { group ->
            groupRow.addView(action(group) {
                selectedGroup = group
                showChannels()
            })
        }
        list.addView(groupRow)
        showChannels()
        groupRow.getChildAt(0)?.requestFocus()
    }

    private fun showChannels() {
        while (list.childCount > 1) list.removeViewAt(1)
        val channels = when (selectedGroup) {
            getString(R.string.filter_all) -> playlist.channels
            getString(R.string.iptv_favorites) -> playlist.channels.filter {
                it.streamUrl in WatchStatuses.items(this, "iptv", WatchStatus.FAVORITE)
            }
            else -> playlist.inGroup(selectedGroup)
        }
        channels.forEach { channel ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = glass(8)
                isFocusable = true
                rowFocus(8)
                val logo = ImageView(this@IptvActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0x33222222)
                }
                addView(logo, LinearLayout.LayoutParams(dp(48), dp(40)))
                Images.load(channel.logoUrl, logo, dp(96))
                addView(text(channel.name, BODY, color = TEXT), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(14)
                })
                setOnClickListener {
                    startActivity(android.content.Intent(this@IptvActivity, ProviderPlayerActivity::class.java)
                        .putExtra("streamUrl", channel.streamUrl)
                        .putExtra("title", channel.name)
                        .putExtra("userAgent", channel.userAgent)
                        .putExtra("referrer", channel.referrer))
                }
            }
            row.setOnLongClickListener { setWatchStatus(channel); true }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(4) })
        }
        if (channels.isEmpty()) list.addView(text(getString(R.string.iptv_no_channels), BODY, color = TEXT_2))
    }

    private fun setWatchStatus(channel: IptvChannel) {
        val statuses = WatchStatus.entries
        val labels = statuses.map { status ->
            getString(when (status) {
                WatchStatus.NONE -> R.string.watch_status_none
                WatchStatus.FAVORITE -> R.string.watch_status_favorite
                WatchStatus.WATCHING -> R.string.watch_status_watching
                WatchStatus.ON_HOLD -> R.string.watch_status_on_hold
                WatchStatus.DROPPED -> R.string.watch_status_dropped
                WatchStatus.WATCHED -> R.string.watch_status_watched
            })
        }
        choose(getString(R.string.watch_status_title), labels, channel.name,
            checked = WatchStatuses.get(this, "iptv", channel.streamUrl).ordinal) { index ->
            WatchStatuses.set(this, "iptv", channel.streamUrl, statuses[index])
            showGroups()
        }
    }

    private fun editPlaylist() {
        val layout = column(Gravity.START)
        val input = EditText(this).apply {
            hint = getString(R.string.iptv_playlist_url_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Iptv.playlistUrl(this@IptvActivity))
            setSelection(text.length)
        }
        layout.addView(input)
        layout.addView(action(getString(R.string.iptv_browse_file)) {
            startActivityForResult(Iptv.playlistPickerIntent(), REQUEST_PLAYLIST_FILE)
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.iptv_playlist_settings)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                Iptv.setPlaylistUrl(this, input.text.toString())
                loadPlaylist()
            }
            .create()
        playlistDialog = dialog
        dialog.show()
    }

    @Deprecated("Use the document picker result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PLAYLIST_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Iptv.setPlaylistFile(this, uri)
        }.onSuccess {
            playlistDialog?.dismiss()
            loadPlaylist()
        }.onFailure {
            status.text = it.message ?: getString(R.string.iptv_load_failed)
        }
    }

    private companion object {
        const val REQUEST_PLAYLIST_FILE = 7301
    }
}
