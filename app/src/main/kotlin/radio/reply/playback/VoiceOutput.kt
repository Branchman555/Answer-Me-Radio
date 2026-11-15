package radio.reply.playback

import radio.reply.orchestrator.*

enum class VoiceSignal { READY, STARTED, DONE, STOPPED, ERROR }

/** One driver instance per utterance. Callbacks must be dispatched on the owner's looper. */
interface VoiceDriver {
    fun speak(text: String)
    fun stop()
    fun close()
}

fun interface VoiceScheduler { fun after(delayMs: Long, action: () -> Unit): () -> Unit }

/** No callback without a request id is ever rebound to a newer utterance. */
class VoiceOutput(
    private val factory: ((VoiceSignal) -> Unit) -> VoiceDriver,
    private val scheduler: VoiceScheduler,
) : MusicOutput {
    private class Session(val output: ActiveOutput, val emit: (Event) -> Unit) {
        lateinit var driver: VoiceDriver
        var stopping = false
        var silent = false
        var deadline: () -> Unit = {}
    }
    private var active: Session? = null
    var starts = 0; private set
    var done = 0; private set
    var stopAcks = 0; private set
    var errors = 0; private set
    var stopTimeouts = 0; private set
    val isActive get() = active != null
    override fun positionMs() = 0L

    override fun start(output: ActiveOutput, emit: (Event) -> Unit) {
        check(active == null) { "VOICE_STILL_ACTIVE" }
        val voice = output.segment as Segment.Voice
        val session = Session(output, emit)
        active = session
        try { session.driver = factory { signal -> receive(session, signal) } }
        catch (_: Exception) {
            session.silent = true // The driver never existed and speak was never invoked.
            errors++; emit(Event.Failed(output.token)); return
        }
        session.deadline = scheduler.after(45_000) {
            if (active === session && !session.stopping) { errors++; emit(Event.Failed(output.token)) }
        }
        try { session.driver.speak(voice.text) }
        catch (_: Exception) { errors++; emit(Event.Failed(output.token)) }
    }

    private fun receive(session: Session, signal: VoiceSignal) {
        if (active !== session) return
        when (signal) {
            VoiceSignal.READY -> Unit // Preparation is never completion or permission to start music.
            VoiceSignal.STARTED -> starts++
            VoiceSignal.DONE, VoiceSignal.STOPPED -> {
                session.silent = true
                if (session.stopping) finish(session, stopped = true)
                else if (signal == VoiceSignal.DONE) finish(session, stopped = false)
                else { errors++; session.emit(Event.Failed(session.output.token)) }
            }
            VoiceSignal.ERROR -> if (!session.stopping) { errors++; session.emit(Event.Failed(session.output.token)) }
            // An error while stopping is not an acknowledgement of silence. Keep the stop deadline.
        }
    }

    override fun stop(token: OutputToken, emit: (Event) -> Unit) {
        val session = active
        if (session == null || session.output.token != token) { emit(Event.StopTimedOut(token)); return }
        if (session.stopping) return
        session.stopping = true
        session.deadline()
        if (session.silent) { finish(session, stopped = true); return }
        session.deadline = scheduler.after(2_000) {
            if (active === session) { stopTimeouts++; emit(Event.StopTimedOut(token)) }
        }
        try { session.driver.stop() } catch (_: Exception) { /* Wait for acknowledgement or deadline. */ }
    }

    private fun finish(session: Session, stopped: Boolean) {
        session.deadline()
        // Terminal callback establishes silence; disconnect the old listener before permitting new output.
        runCatching { session.driver.close() }
        active = null
        if (stopped) { stopAcks++; session.emit(Event.OutputStopped(session.output.token)) }
        else { done++; session.emit(Event.Ended(session.output.token)) }
    }

    fun close() {
        val session = active ?: return
        active = null; session.deadline()
        runCatching { session.driver.stop() }; runCatching { session.driver.close() }
    }
}

/** The reducer requests stops before switching; this also rejects accidental overlap at the adapter boundary. */
class RadioOutput(private val music: MusicOutput, private val voice: MusicOutput) : MusicOutput {
    private var owner: Pair<OutputToken, MusicOutput>? = null
    override fun positionMs() = if (owner?.second === music) music.positionMs() else 0L
    override fun start(output: ActiveOutput, emit: (Event) -> Unit) {
        check(owner == null) { "OUTPUT_OVERLAP" }
        val adapter = if (output.segment is Segment.Voice) voice else music
        owner = output.token to adapter
        adapter.start(output) { event -> forward(output.token, event, emit) }
    }
    override fun stop(token: OutputToken, emit: (Event) -> Unit) {
        val adapter = owner?.takeIf { it.first == token }?.second
        if (adapter == null) { emit(Event.StopTimedOut(token)); return }
        adapter.stop(token) { event -> forward(token, event, emit) }
    }
    private fun forward(token: OutputToken, event: Event, emit: (Event) -> Unit) {
        if (owner?.first == token && (event is Event.Ended || event is Event.OutputStopped)) owner = null
        emit(event)
    }
}
