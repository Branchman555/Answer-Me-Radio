package radio.reply.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.*
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import radio.reply.R
import radio.reply.ui.TransportButton
import radio.reply.ui.RadioDial
import radio.reply.ui.RadioTheme as Theme
import radio.reply.ui.RadioTheme.dp

@UnstableApi
class PlaybackActivity : Activity() {
    private var controller: MediaBrowser? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
    private lateinit var status: TextView
    private lateinit var song: TextView
    private lateinit var artist: TextView
    private lateinit var clock: TextView
    private lateinit var djStatus: TextView
    private lateinit var uxStatus: TextView
    private lateinit var details: Button
    private lateinit var dj: Button
    private lateinit var play: TransportButton
    private lateinit var settings: LinearLayout
    private lateinit var dial: RadioDial
    private lateinit var progress: ProgressBar
    private lateinit var ux: radio.reply.ux.VehicleUx
    private val transport = mutableListOf<Button>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 500) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.configure(this)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val root = Theme.column(this, if (landscape) 16 else 20).apply { setBackgroundColor(Theme.background) }
        root.addView(Theme.text(this, "REPLY RADIO", 10f, Theme.amber, true).apply { letterSpacing = .2f })
        root.addView(Theme.gap(this, 6))
        root.addView(Theme.text(this, "응답하라 라디오", 27f, bold = true))
        root.addView(Theme.gap(this, 20))
        val body = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val radioPanel = Theme.column(this, 0)
        dial = RadioDial(this)
        radioPanel.addView(dial, LinearLayout.LayoutParams(-1, dp(if (landscape) 212 else 176)))
        radioPanel.addView(Theme.gap(this, 12))
        radioPanel.addView(Theme.text(this, "1980년대와 2010년대를 번갈아 들어요", 12f, Theme.muted).apply { gravity = Gravity.CENTER })
        val player = Theme.column(this, 18).apply { background = Theme.shape(this@PlaybackActivity, stroke = Theme.surface) }
        status = Theme.text(this, "연결 중", 12f, Theme.amber, true); player.addView(status)
        player.addView(Theme.gap(this, 8))
        song = Theme.text(this, "어떤 음악을 들어볼까요?", 23f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        player.addView(song); player.addView(Theme.gap(this, 8))
        artist = Theme.text(this, "음악 서랍에서 MP3를 추가해 주세요.", 14f, Theme.muted).apply { maxLines = 2 }
        player.addView(artist); player.addView(Theme.gap(this, 12))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(Theme.amber)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Theme.border)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        player.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        clock = Theme.text(this, "00:00  /  --:--", 12f, Theme.muted).apply { gravity = Gravity.END }
        player.addView(Theme.gap(this, 8)); player.addView(clock); player.addView(Theme.gap(this, 12))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun control(label: String, primary: Boolean = false, action: (MediaBrowser) -> Unit): TransportButton =
            TransportButton(this, label, primary).apply {
                isEnabled = false; minHeight = dp(64); minimumHeight = dp(64)
                setOnClickListener { controller?.let(action) }; transport += this
            }
        controls.addView(control("previous") { it.seekToPrevious() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        play = control("play", true) { if (it.playWhenReady) it.pause() else it.play() }
        controls.addView(play, LinearLayout.LayoutParams(dp(76), dp(76)).apply { marginStart = dp(16); marginEnd = dp(16) })
        controls.addView(control("next") { it.seekToNext() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        player.addView(controls); player.addView(Theme.gap(this, 10))
        val lower = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        djStatus = Theme.text(this, "DJ 없이 음악만", 13f, Theme.muted)
        lower.addView(djStatus, LinearLayout.LayoutParams(0, -2, 1f))
        lower.addView(control("stop") { it.stop() }.apply { minHeight = dp(48); minimumHeight = dp(48) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        player.addView(lower)
        if (landscape) {
            body.addView(radioPanel, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(20) })
            body.addView(player, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            body.addView(radioPanel); body.addView(Theme.gap(this, 12)); body.addView(player)
        }
        root.addView(body); root.addView(Theme.gap(this, 16))
        settings = Theme.column(this, 0)
        val actions = LinearLayout(this)
        dj = Theme.button(this, getString(R.string.dj_enable)).apply {
            setOnClickListener {
                ux.refresh()
                if (ux.gate.detailsAllowed) controller?.let { browser ->
                    browser.sendCustomCommand(SessionCommand(RadioService.SET_DJ, Bundle.EMPTY), Bundle().apply {
                        putBoolean("enabled", !browser.sessionExtras.getBoolean("djEnabled"))
                    })
                }
            }
        }
        details = Theme.button(this, getString(R.string.catalog_open)).apply {
            setOnClickListener {
                ux.refresh()
                if (ux.gate.detailsAllowed) startActivity(Intent(this@PlaybackActivity, radio.reply.catalog.CatalogActivity::class.java)
                    .putExtra(radio.reply.catalog.CatalogActivity.DEVELOPMENT_ACCESS, true))
            }
        }
        actions.addView(details, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
        actions.addView(dj, LinearLayout.LayoutParams(0, -2, 1f)); settings.addView(actions)
        root.addView(settings); root.addView(Theme.gap(this, 12))
        uxStatus = Theme.text(this, "", 12f, Theme.muted).apply { gravity = Gravity.CENTER }; root.addView(uxStatus)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; isFillViewport = true; setBackgroundColor(Theme.background); addView(root) })
        ux = radio.reply.ux.VehicleUx(this) { if (::ux.isInitialized && !isDestroyed) renderAccess() }
        renderAccess()
        // Media3 releases the binding asynchronously; Activity teardown must not unbind it first.
        val pending = MediaBrowser.Builder(applicationContext, SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (!isDestroyed) runCatching { pending.get() }.fold({
                controller = it; it.getChildren("root", 0, 10, null); transport.forEach { button -> button.isEnabled = true }; renderAccess(); handler.post(tick)
            }, { status.setText(R.string.playback_connection_failed) })
        }, mainExecutor)
    }
    private fun render() {
        val player = controller ?: return
        val extras = player.sessionExtras
        val voice = extras.getString("phase") == "DJ_SPEAKING"
        status.text = when {
            extras.getString("notice") == "OUTPUT_STOP_UNCONFIRMED" -> "재생 상태를 확인할 수 없습니다"
            player.playerError != null -> "파일을 재생할 수 없습니다"
            voice && player.playWhenReady -> "DJ가 이야기하는 중"
            player.isPlaying -> "●  재생 중"
            player.playbackState == Player.STATE_BUFFERING -> "음악을 불러오는 중"
            player.playWhenReady -> "재생 준비 중"
            player.playbackState == Player.STATE_IDLE -> "재생 대기"
            else -> "일시정지"
        }
        val metadata = player.mediaMetadata
        song.text = if (voice) "다음 곡을 소개합니다" else metadata.title?.take(80) ?: "어떤 음악을 들어볼까요?"
        artist.text = if (voice) "잠시 후 음악이 이어집니다" else metadata.artist?.take(80) ?: "음악 서랍에서 MP3를 추가해 주세요."
        val enabled = extras.getBoolean("djEnabled")
        dj.setText(if (enabled) R.string.dj_disable else R.string.dj_enable)
        djStatus.text = when {
            enabled && extras.getString("notice") == "VOICE_FAILED" -> "DJ를 사용할 수 없어 음악만 재생합니다"
            enabled -> "DJ가 곡을 소개합니다"
            else -> "DJ 없이 음악만"
        }
        play.setSymbol(if (player.playWhenReady) "pause" else "play")
        val duration = player.duration.takeIf { it > 0 }
        val position = if (voice) 0L else player.currentPosition.coerceAtLeast(0)
        progress.progress = if (duration != null && !voice) ((position.toDouble() / duration) * 1000).toInt().coerceIn(0, 1000) else 0
        clock.text = if (voice) "DJ 멘트 재생 중" else "${time(position)}  /  ${duration?.let(::time) ?: "--:--"}"
        dial.era = metadata.releaseYear?.let { it / 10 * 10 }
    }
    private fun time(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60)
    private fun renderAccess() {
        val allowed = ux.gate.detailsAllowed
        details.isEnabled = allowed; dj.isEnabled = allowed && controller != null
        details.visibility = if (allowed) View.VISIBLE else View.GONE
        dj.visibility = if (allowed) View.VISIBLE else View.GONE
        settings.visibility = if (allowed) View.VISIBLE else View.GONE
        uxStatus.text = if (allowed) "내 음악으로 듣는 라디오" else getString(R.string.ux_details_locked)
    }
    override fun onResume() { super.onResume(); if (::ux.isInitialized) { ux.refresh(); renderAccess(); controller?.getChildren("root", 0, 10, null) } }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (::ux.isInitialized) ux.close(); future?.let { androidx.media3.session.MediaController.releaseFuture(it) }; super.onDestroy() }
}
