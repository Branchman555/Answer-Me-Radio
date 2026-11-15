package radio.reply

import org.junit.Assert.*
import org.junit.Test
import radio.reply.playback.*
import radio.reply.orchestrator.*
import radio.reply.planner.*

class PlaybackEngineTest {
    private class Audio : MusicOutput {
        var active: ActiveOutput? = null
        var position = 0L
        var starts = 0
        var confirm = true
        var pending: OutputToken? = null
        override fun positionMs() = position
        override fun start(output: ActiveOutput, emit: (Event) -> Unit) {
            check(active == null); active = output; position = output.positionMs; starts++
        }
        override fun stop(token: OutputToken, emit: (Event) -> Unit) {
            pending = token
            if (confirm) { active = null; emit(Event.OutputStopped(token)) }
        }
    }
    private val audio = Audio()
    private val engine = PlaybackEngine(audio, { 0 })
    private val tracks = (1..5).map { Track("t$it", "title$it", "artist", 1988, "content://music/$it") }
    private fun start() { engine.send(Event.CatalogChanged(tracks)); engine.command(Event.Play) }
    private fun music() = (engine.state.segment as Segment.Music).track
    @Test fun realPlaybackStartsWithMusicAndNoSpeech() { start(); assertTrue(engine.state.segment is Segment.Music); assertFalse(engine.state.settings.djEnabled) }
    @Test fun pauseAndResumePreservePositionWithNewToken() {
        start(); val token = audio.active!!.token; audio.position = 4210
        engine.command(Event.Pause); assertNull(audio.active); assertEquals(4210L, engine.state.positionMs)
        engine.command(Event.Play); assertEquals(4210L, audio.active!!.positionMs); assertNotEquals(token, audio.active!!.token)
    }
    @Test fun transientFocusRestoresPositionButExplicitPauseWins() {
        start(); audio.position = 2100; engine.command(Event.FocusChanged(Focus.LOSS_TRANSIENT))
        assertNull(audio.active); engine.command(Event.FocusChanged(Focus.GAIN)); assertEquals(2100L, audio.active!!.positionMs)
        engine.command(Event.Pause); engine.command(Event.FocusChanged(Focus.GAIN)); assertNull(audio.active)
    }
    @Test fun permanentFocusDoesNotAutoResume() {
        start(); engine.command(Event.FocusChanged(Focus.LOSS_PERMANENT)); engine.command(Event.FocusChanged(Focus.GAIN)); assertNull(audio.active)
    }
    @Test fun ac045_previousOverFiveSecondsRestartsSameSong() {
        start(); val original = music(); audio.position = 5001; engine.command(Event.Previous)
        assertEquals(original, music()); assertEquals(0L, audio.active!!.positionMs); assertEquals(0, engine.state.completedSinceDj)
    }
    @Test fun previousAtFiveSecondsReturnsPriorThenPreservesForwardQueue() {
        start(); val first = music(); engine.command(Event.Next); val second = music()
        audio.position = 5000; engine.command(Event.Previous); assertEquals(first, music())
        engine.command(Event.Next); assertEquals(second, music()); assertEquals(0, engine.state.completedSinceDj)
    }
    @Test fun previousWhilePausedChangesSelectionWithoutOutput() {
        start(); val first = music(); engine.command(Event.Next); engine.command(Event.Pause)
        val starts = audio.starts; engine.command(Event.Previous); assertEquals(first, music()); assertNull(audio.active); assertEquals(starts, audio.starts)
    }
    @Test fun seekWhilePausedDoesNotPlay() {
        start(); engine.command(Event.Pause); engine.command(Event.SeekTo(7000)); assertNull(audio.active)
        engine.command(Event.Play); assertEquals(7000L, audio.active!!.positionMs)
    }
    @Test fun stoppedOutputMustBeConfirmedBeforeNextStarts() {
        start(); audio.confirm = false; val starts = audio.starts; engine.command(Event.Next)
        assertEquals(starts, audio.starts); val pending = audio.pending!!; audio.active = null; engine.send(Event.OutputStopped(pending)); assertEquals(starts + 1, audio.starts)
    }
    @Test fun timedOutStopCannotBeBypassedByPlay() {
        start(); audio.confirm = false; engine.command(Event.Next); engine.send(Event.StopTimedOut(audio.pending!!))
        val count = audio.starts; engine.command(Event.Play); assertEquals(count, audio.starts); assertEquals("OUTPUT_STOP_UNCONFIRMED", engine.state.notice)
    }
    @Test fun staleEndAndPositionCannotMutateReplacement() {
        start(); val old = audio.active!!.token; engine.command(Event.Next); val current = music()
        engine.send(Event.Ended(old)); engine.send(Event.PositionUpdated(old, 9000)); assertEquals(current, music()); assertEquals(0L, engine.state.positionMs)
    }
    @Test fun ac052_threeFailuresStopWithoutRetryOrFalseCompletion() {
        start(); repeat(3) { engine.send(Event.Failed(audio.active!!.token)) }
        assertEquals(3, audio.starts); assertNull(audio.active); assertEquals(Phase.STOPPED, engine.state.phase)
        assertEquals("PLAYBACK_FAILURE_LIMIT", engine.state.notice); assertEquals(0, engine.state.completedSinceDj)
    }
    @Test fun normalEndAloneCountsCompletionAndStartsNextAtZero() {
        start(); val token = audio.active!!.token; audio.position = 8000; audio.active = null; engine.send(Event.Ended(token))
        assertEquals(1, engine.state.completedSinceDj); assertEquals(0L, audio.active!!.positionMs)
    }
    @Test fun selectOnlyEligibleEraTrackAndNeverAutoplaysFromStopped() {
        engine.send(Event.CatalogChanged(tracks + Track("wrong", "x", "y", 1999, "content://music/wrong")))
        engine.command(Event.SelectTrack("wrong")); assertNull(engine.state.segment)
        engine.command(Event.SelectTrack("t3")); assertEquals("t3", music().id); assertNull(audio.active)
        engine.command(Event.Play); assertEquals("t3", music().id)
    }
    @Test fun previousCannotResurrectRevokedCatalogTrack() {
        start(); val first = music(); engine.command(Event.Next); val second = music()
        engine.send(Event.CatalogChanged(tracks.map { if (it.id == first.id) it.copy(accessible = false) else it }))
        engine.command(Event.Previous); assertEquals(second, music())
    }
    @Test fun stopClearsResumePositionAndHistory() {
        start(); audio.position = 7000; engine.command(Event.Next); engine.command(Event.Stop)
        assertEquals(0L, engine.state.positionMs); assertTrue(engine.state.previousTracks.isEmpty()); assertNull(audio.active)
    }
    @Test fun selectedTrackLeavesOtherCandidatesForNext() {
        engine.send(Event.CatalogChanged(tracks)); engine.command(Event.SelectTrack("t3")); engine.command(Event.Play)
        engine.command(Event.Next); assertNotEquals("t3", music().id); assertEquals(0, engine.state.completedSinceDj)
    }
}
