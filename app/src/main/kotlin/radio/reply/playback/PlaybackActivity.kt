package radio.reply.playback

import android.app.Activity
import android.content.ComponentName
import android.os.*
import android.widget.*
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import radio.reply.R

@UnstableApi
class PlaybackActivity : Activity() {
    private var controller: MediaBrowser? = null
    private var future: com.google.common.util.concurrent.ListenableFuture<MediaBrowser>? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); handler.postDelayed(this, 500) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 20f; setText(R.string.playback_connecting) }; root.addView(status)
        listOf<Pair<String, (MediaBrowser) -> Unit>>("재생" to { it.play() }, "일시정지" to { it.pause() },
            "이전" to { it.seekToPrevious() }, "다음" to { it.seekToNext() }, "정지" to { it.stop() }).forEach { (label, action) ->
            root.addView(Button(this).apply { text = label; setOnClickListener { controller?.let(action) } })
        }
        root.addView(TextView(this).apply { setText(R.string.playback_scope) })
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        val pending = MediaBrowser.Builder(this, SessionToken(this, ComponentName(this, RadioService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (!isDestroyed) runCatching { pending.get() }.fold({ controller = it; handler.post(tick) }, { status.setText(R.string.playback_connection_failed) })
        }, mainExecutor)
    }
    private fun render() {
        val player = controller ?: return
        val state = when { player.playerError != null -> "파일을 재생할 수 없습니다."
            player.isPlaying -> "재생 중"
            player.playbackState == Player.STATE_BUFFERING -> "준비 중"
            player.playbackState == Player.STATE_IDLE -> "정지 · 재생 가능한 파일과 권한을 확인하세요."
            else -> "일시정지" }
        status.text = getString(R.string.playback_status, state, player.mediaMetadata.title ?: "로컬 라디오", player.currentPosition / 1000)
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); future?.let { androidx.media3.session.MediaController.releaseFuture(it) }; super.onDestroy() }
}
