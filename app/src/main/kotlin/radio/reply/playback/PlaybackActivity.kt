package radio.reply.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.*
import android.widget.*
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import radio.reply.R

@UnstableApi
class PlaybackActivity : Activity() {
    private var controller: MediaBrowser? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
    private lateinit var status: TextView
    private lateinit var uxStatus: TextView
    private lateinit var details: Button
    private lateinit var dj: Button
    private lateinit var scope: TextView
    private lateinit var ux: radio.reply.ux.VehicleUx
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 500) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 20f; setText(R.string.playback_connecting) }; root.addView(status)
        listOf<Pair<String, (MediaBrowser) -> Unit>>("재생" to { it.play() }, "일시정지" to { it.pause() }, "정지" to { it.stop() },
            "이전" to { it.seekToPrevious() }, "다음" to { it.seekToNext() }).chunked(3).forEach { controls ->
            val row = LinearLayout(this)
            controls.forEach { (label, action) -> row.addView(Button(this).apply {
                text = label; minHeight = (64 * resources.displayMetrics.density).toInt(); setOnClickListener { controller?.let(action) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            root.addView(row)
        }
        uxStatus = TextView(this); root.addView(uxStatus)
        dj = Button(this).apply {
            setText(R.string.dj_enable)
            setOnClickListener {
                ux.refresh()
                if (ux.gate.detailsAllowed) controller?.let { browser ->
                    val enabled = !browser.sessionExtras.getBoolean("djEnabled")
                    browser.sendCustomCommand(SessionCommand(RadioService.SET_DJ, Bundle.EMPTY), Bundle().apply { putBoolean("enabled", enabled) })
                }
            }
        }; root.addView(dj)
        details = Button(this).apply {
            setText(R.string.catalog_open)
            setOnClickListener {
                ux.refresh()
                if (ux.gate.detailsAllowed) startActivity(Intent(this@PlaybackActivity, radio.reply.catalog.CatalogActivity::class.java)
                    .putExtra(radio.reply.catalog.CatalogActivity.DEVELOPMENT_ACCESS, true))
            }
        }; root.addView(details)
        scope = TextView(this).apply { setText(R.string.playback_scope) }; root.addView(scope)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        ux = radio.reply.ux.VehicleUx(this) { if (::ux.isInitialized && !isDestroyed) renderAccess() }
        renderAccess()
        val pending = MediaBrowser.Builder(this, SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (!isDestroyed) runCatching { pending.get() }.fold({ controller = it; handler.post(tick) }, { status.setText(R.string.playback_connection_failed) })
        }, mainExecutor)
    }
    private fun render() {
        val player = controller ?: return
        val extras = player.sessionExtras
        val state = when { extras.getString("notice") == "OUTPUT_STOP_UNCONFIRMED" -> "출력 정지를 확인하지 못했습니다. 방송을 중단합니다."
            extras.getString("phase") == "DJ_SPEAKING" -> "DJ 멘트 · 음성 준비/재생 중"
            player.playerError != null -> "파일을 재생할 수 없습니다."
            player.isPlaying -> "재생 중"
            player.playbackState == Player.STATE_BUFFERING -> "준비 중"
            player.playbackState == Player.STATE_IDLE -> "정지 · 재생 가능한 파일과 권한을 확인하세요."
            else -> "일시정지" }
        val enabled = extras.getBoolean("djEnabled")
        dj.setText(if (enabled) R.string.dj_disable else R.string.dj_enable)
        val voiceStatus = if (enabled) when {
            extras.getString("notice") == "VOICE_FAILED" -> "DJ 음성을 사용할 수 없어 음악만 이어갑니다."
            else -> "DJ ON · 기기 내 한국어 음성"
        } else "DJ OFF"
        status.text = getString(R.string.playback_status, state, (player.mediaMetadata.title ?: "로컬 라디오").take(60), player.currentPosition / 1000,
            if (ux.gate.detailsAllowed) voiceStatus else if (enabled) "DJ ON" else "DJ OFF")
    }
    private fun renderAccess() {
        val allowed = ux.gate.detailsAllowed
        details.isEnabled = allowed; dj.isEnabled = allowed
        details.visibility = if (allowed) View.VISIBLE else View.GONE
        dj.visibility = if (allowed) View.VISIBLE else View.GONE
        scope.visibility = if (allowed) View.VISIBLE else View.GONE
        uxStatus.text = if (allowed) if (ux.gate.access == radio.reply.ux.UxAccess.NON_VEHICLE) "일반 Android 개발 환경" else "플랫폼 상세 조작 허용"
            else getString(R.string.ux_details_locked)
    }
    override fun onResume() { super.onResume(); if (::ux.isInitialized) { ux.refresh(); renderAccess() } }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); if (::ux.isInitialized) ux.close(); future?.let { androidx.media3.session.MediaController.releaseFuture(it) }; super.onDestroy() }
}
