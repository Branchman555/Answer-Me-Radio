package radio.reply.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import radio.reply.orchestrator.*

/** All advertised mutations terminate at the reducer; controllers cannot inject URI playlists. */
@UnstableApi
class SessionPlayer(output: Media3Output, private val engine: PlaybackEngine, private val command: (Event) -> Unit) : SimpleBasePlayer(android.os.Looper.getMainLooper()) {
    private class Snapshot(player: Player) : ForwardingSimpleBasePlayer(player) { fun read() = super.getState() }
    private var snapshot = Snapshot(output.player)
    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { invalidateState() } }
    init {
        output.player.addListener(listener)
        output.replaced = { snapshot = Snapshot(it); it.addListener(listener); invalidateState() }
    }
    fun refresh() = invalidateState()
    override fun getState(): State {
        val state = snapshot.read()
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE,
            Player.COMMAND_STOP, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_MEDIA_ITEM).build()
        return state.buildUpon().setAvailableCommands(commands).apply {
            setPlayWhenReady(engine.state.desiredPlaying && engine.state.focusAllowed, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            val track = (engine.state.segment as? Segment.Music)?.track
                ?: radio.reply.planner.RadioPlanner().plan(engine.state.catalog, engine.state.settings, 0).block?.candidates?.firstOrNull()
            if (track != null) {
                setPlaylist(listOf(MediaItemData.Builder(track.id).setMediaItem(Media3Output.item(track))
                    .setIsSeekable(true).setDurationUs(track.durationMs?.times(1000) ?: C.TIME_UNSET).build()))
                setCurrentMediaItemIndex(0)
            } else {
                setPlaylist(emptyList()); setCurrentMediaItemIndex(C.INDEX_UNSET); setPlaybackState(Player.STATE_IDLE)
                setContentPositionMs(0); setContentBufferedPositionMs { 0L }; setTotalBufferedDurationMs { 0L }
            }
            if (engine.state.phase in setOf(Phase.IDLE, Phase.STOPPED)) { setPlaybackState(Player.STATE_IDLE); setContentPositionMs(0) }
            if (engine.state.phase in setOf(Phase.PAUSED, Phase.SUSPENDED)) {
                setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                setContentPositionMs(engine.state.positionMs)
                if (track != null) setPlaybackState(Player.STATE_READY)
            }
        }.build()
    }
    private fun send(event: Event): ListenableFuture<*> { command(event); return Futures.immediateVoidFuture() }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = send(if (playWhenReady) Event.Play else Event.Pause)
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop() = send(Event.Stop)
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = send(when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> Event.Next
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> Event.Previous
        else -> Event.SeekTo(positionMs.coerceAtLeast(0))
    })
    override fun handleSetMediaItems(items: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        if (items.size == 1) { command(Event.SelectTrack(items.single().mediaId)); if (startPositionMs > 0) command(Event.SeekTo(startPositionMs)) }
        return Futures.immediateVoidFuture()
    }
}
