package radio.reply.playback

import radio.reply.orchestrator.*
import radio.reply.planner.RadioSettings

interface MusicOutput {
    fun positionMs(): Long
    fun start(output: ActiveOutput, emit: (Event) -> Unit)
    fun stop(token: OutputToken, emit: (Event) -> Unit)
}

/** One serialized event queue for UI, media session and decoder callbacks. Owner calls on its looper. */
class PlaybackEngine(private val audio: MusicOutput, private val clock: () -> Long,
    private val changed: (RadioState) -> Unit = {}) {
    var state = RadioState(settings = RadioSettings(djEnabled = false))
        private set
    private val reducer = RadioReducer()
    private val queue = ArrayDeque<Event>()
    private var draining = false
    fun command(event: Event) {
        state.output?.takeIf { it.segment is Segment.Music }?.let {
            send(Event.PositionUpdated(it.token, audio.positionMs()))
        }
        send(event)
    }
    fun send(event: Event) {
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) {
                val transition = reducer.reduce(state, queue.removeFirst(), clock())
                state = transition.state
                transition.effects.forEach { effect -> when (effect) {
                    is Effect.StartOutput -> try { audio.start(effect.output, ::send) }
                        catch (_: Exception) { send(Event.Failed(effect.output.token)) }
                    is Effect.StopOutput -> try { audio.stop(effect.token, ::send) }
                        catch (_: Exception) { send(Event.StopTimedOut(effect.token)) }
                    is Effect.RequestDjScript -> send(Event.DjRequestFailed(effect.plan.key))
                    else -> Unit
                } }
                changed(state)
            }
        } finally { draining = false }
    }
}
