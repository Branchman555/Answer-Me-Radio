package radio.reply.playback

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import radio.reply.orchestrator.*
import radio.reply.planner.Track

@UnstableApi
class Media3Output(private val context: Context) : MusicOutput {
    var player: ExoPlayer = create()
        private set
    var replaced: (ExoPlayer) -> Unit = {}
    private var released = false
    private var token: OutputToken? = null
    private var releaseFailed = false
    var starts = 0; private set
    var errors = 0; private set
    var naturalEnds = 0; private set
    var audioAdvances = 0; private set
    var decodedBuffers = 0; private set
    var lastErrorCode: Int? = null; private set

    private fun create() = ExoPlayer.Builder(context).setReleaseTimeoutMs(2_000).build().apply {
        // Focus is owned by RadioService, never by a second independent auto-resume policy.
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        setHandleAudioBecomingNoisy(false)
        setWakeMode(C.WAKE_MODE_LOCAL)
    }
    override fun positionMs() = player.currentPosition.coerceAtLeast(0)
    override fun start(output: ActiveOutput, emit: (Event) -> Unit) {
        val music = output.segment as? Segment.Music ?: error("VOICE_NOT_CONNECTED")
        if (!released) releasePlayer()
        check(!releaseFailed) { "OUTPUT_STOP_UNCONFIRMED" }
        player = create(); released = false; token = output.token; starts++
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && token == output.token) {
                    naturalEnds++
                    // A voice can follow this callback. Tear down AudioTrack before releasing the output lease.
                    releasePlayer()
                    if (releaseFailed) emit(Event.Failed(output.token))
                    else { token = null; emit(Event.Ended(output.token)) }
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) releaseFailed = true
                if (token == output.token) { errors++; lastErrorCode = error.errorCode; emit(Event.Failed(output.token)) }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) { audioAdvances++ }
            override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, counters: androidx.media3.exoplayer.DecoderCounters) {
                counters.ensureUpdated(); decodedBuffers += counters.renderedOutputBufferCount
            }
        })
        replaced(player)
        player.setMediaItem(item(music.track, includeUri = true), output.positionMs)
        player.prepare(); player.play()
    }
    override fun stop(token: OutputToken, emit: (Event) -> Unit) {
        if (this.token != null && this.token != token) { emit(Event.StopTimedOut(token)); return }
        this.token = null
        releasePlayer()
        emit(if (releaseFailed) Event.StopTimedOut(token) else Event.OutputStopped(token))
    }
    private fun releasePlayer() {
        if (released) return
        // release waits for the playback thread and AudioTrack teardown; STATE_IDLE alone is not a stop acknowledgement.
        player.release(); released = true
    }
    fun close() { token = null; releasePlayer() }
    companion object {
        fun item(track: Track, includeUri: Boolean = false): MediaItem = MediaItem.Builder().setMediaId(track.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
                .setReleaseYear(track.releaseYear).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build())
            .apply { if (includeUri) setUri(track.contentUri) }.build()
    }
}
