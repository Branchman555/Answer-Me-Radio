package radio.reply

import org.junit.Assert.*
import org.junit.Test
import radio.reply.orchestrator.*
import radio.reply.playback.*
import radio.reply.planner.*

class VoiceOutputTest {
    private class Driver(val callback: (VoiceSignal) -> Unit) : VoiceDriver {
        var stops = 0; var closed = false; var text = ""
        override fun speak(text: String) { this.text = text }
        override fun stop() { stops++ }
        override fun close() { closed = true }
    }
    private class Rig {
        val drivers = mutableListOf<Driver>()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val events = mutableListOf<Event>()
        val voice = VoiceOutput({ Driver(it).also(drivers::add) }, VoiceScheduler { ms, fn ->
            val task = ms to fn; tasks += task
            val cancel: () -> Unit = { tasks.remove(task); Unit }; cancel
        })
        fun expire(ms: Long) { tasks.filter { it.first == ms }.toList().forEach { tasks.remove(it); it.second() } }
        fun start(epoch: Long = 1) = output(epoch).also { voice.start(it, events::add) }
    }
    companion object {
        private val song = Track("a", "title", "artist", 1988, "content://test/a")
        private fun output(epoch: Long = 1) = ActiveOutput(OutputToken(1, epoch), Segment.Voice("검증용 음성입니다.", Segment.Music(song)))
    }
    @Test fun readyIsNotCompletion() { val r = Rig(); r.start(); r.drivers[0].callback(VoiceSignal.READY); assertTrue(r.events.isEmpty()); assertTrue(r.voice.isActive) }
    @Test fun doneClosesBeforeExactlyOneEnd() {
        val r = Rig(); val o = r.start(); r.drivers[0].callback(VoiceSignal.DONE); r.drivers[0].callback(VoiceSignal.DONE)
        assertEquals(listOf(Event.Ended(o.token)), r.events); assertTrue(r.drivers[0].closed); assertFalse(r.voice.isActive)
    }
    @Test fun stopWaitsForRealAcknowledgement() {
        val r = Rig(); val o = r.start(); r.voice.stop(o.token, r.events::add)
        assertTrue(r.events.isEmpty()); assertTrue(r.voice.isActive); assertEquals(1, r.drivers[0].stops)
        r.drivers[0].callback(VoiceSignal.STOPPED); assertEquals(listOf(Event.OutputStopped(o.token)), r.events)
    }
    @Test fun doneDuringStopIsStopNotNaturalEnd() {
        val r = Rig(); val o = r.start(); r.voice.stop(o.token, r.events::add); r.drivers[0].callback(VoiceSignal.DONE)
        assertEquals(listOf(Event.OutputStopped(o.token)), r.events); assertEquals(0, r.voice.done)
    }
    @Test fun errorDuringStopCannotProveSilence() {
        val r = Rig(); val o = r.start(); r.voice.stop(o.token, r.events::add); r.drivers[0].callback(VoiceSignal.ERROR)
        assertTrue(r.events.isEmpty()); r.expire(2000); assertEquals(listOf(Event.StopTimedOut(o.token)), r.events); assertTrue(r.voice.isActive)
    }
    @Test fun timeoutDoesNotPermitAnotherVoice() {
        val r = Rig(); val o = r.start(); r.voice.stop(o.token, r.events::add); r.expire(2000)
        assertTrue(runCatching { r.start(2) }.isFailure); assertEquals(1, r.drivers.size)
    }
    @Test fun lateAcknowledgementAfterTimeoutCanOnlyReleaseOldLease() {
        val r = Rig(); val o = r.start(); r.voice.stop(o.token, r.events::add); r.expire(2000); r.drivers[0].callback(VoiceSignal.STOPPED)
        assertEquals(listOf(Event.StopTimedOut(o.token), Event.OutputStopped(o.token)), r.events)
    }
    @Test fun staleCallbacksCannotFinishNewVoice() {
        val r = Rig(); r.start(); r.drivers[0].callback(VoiceSignal.DONE); r.start(2); r.events.clear()
        VoiceSignal.entries.forEach(r.drivers[0].callback); assertTrue(r.events.isEmpty()); assertTrue(r.voice.isActive)
    }
    @Test fun synthesisDeadlineRequestsFailureButNeverClaimsSilence() {
        val r = Rig(); val o = r.start(); r.expire(45000)
        assertEquals(listOf(Event.Failed(o.token)), r.events); assertTrue(r.voice.isActive)
    }
    @Test fun wrongStopTokenCannotStopCurrentSpeech() {
        val r = Rig(); r.start(); val wrong = OutputToken(9, 9); r.voice.stop(wrong, r.events::add)
        assertEquals(listOf(Event.StopTimedOut(wrong)), r.events); assertEquals(0, r.drivers[0].stops)
    }
    @Test fun terminalCallbacksCancelDeadlines() {
        val r = Rig(); r.start(); r.drivers[0].callback(VoiceSignal.DONE); assertTrue(r.tasks.isEmpty())
    }
    @Test fun closeRetiresAllCallbacksWithoutPlaybackEvents() {
        val r = Rig(); r.start(); r.voice.close(); VoiceSignal.entries.forEach(r.drivers[0].callback)
        assertTrue(r.events.isEmpty()); assertTrue(r.tasks.isEmpty()); assertTrue(r.drivers[0].closed)
    }
    private class Music : MusicOutput {
        var starts = 0
        override fun positionMs() = 0L
        override fun start(output: ActiveOutput, emit: (Event) -> Unit) { starts++ }
        override fun stop(token: OutputToken, emit: (Event) -> Unit) { emit(Event.OutputStopped(token)) }
    }
    private fun engine(r: Rig, music: Music): PlaybackEngine = PlaybackEngine(RadioOutput(music, r.voice), { 0L }).apply {
        send(Event.CatalogChanged(listOf(song)))
        command(Event.SettingsChanged(RadioSettings(djEnabled = true)))
        command(Event.Play)
    }
    @Test fun nextDuringVoiceStartsIntroducedSongOnlyAfterStop() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.Next)
        assertEquals(0,m.starts); r.drivers[0].callback(VoiceSignal.STOPPED)
        assertEquals(1,m.starts); assertEquals(song,(e.state.segment as Segment.Music).track)
    }
    @Test fun pauseAndLateDoneStayPausedUntilUserPlay() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.Pause); r.drivers[0].callback(VoiceSignal.DONE)
        e.command(Event.FocusChanged(Focus.GAIN)); assertEquals(Phase.PAUSED,e.state.phase); assertEquals(0,m.starts)
        e.command(Event.Play); assertEquals(2,r.drivers.size)
    }
    @Test fun stoppedVoiceCannotResumeOnLateCallbacks() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.Stop); r.drivers[0].callback(VoiceSignal.DONE)
        assertEquals(Phase.STOPPED,e.state.phase); assertEquals(0,m.starts)
    }
    @Test fun voiceFailureSkipsOnceAfterStop() {
        val r = Rig(); val m = Music(); val e = engine(r,m); r.drivers[0].callback(VoiceSignal.ERROR)
        assertEquals(0,m.starts); r.drivers[0].callback(VoiceSignal.STOPPED)
        assertEquals(1,m.starts); assertEquals("VOICE_FAILED",e.state.notice); assertEquals(1,r.drivers.size)
    }
    @Test fun unsolicitedStopIsFailureAndSafeMusicRecovery() {
        val r = Rig(); val m = Music(); val e = engine(r,m); r.drivers[0].callback(VoiceSignal.STOPPED)
        assertEquals(1,m.starts); assertEquals("VOICE_FAILED",e.state.notice)
    }
    @Test fun stopTimeoutPreventsPlayEvenWithFocusGain() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.Next); r.expire(2000)
        e.command(Event.Play); e.command(Event.FocusChanged(Focus.GAIN))
        assertEquals(Phase.STOPPED,e.state.phase); assertEquals("OUTPUT_STOP_UNCONFIRMED",e.state.notice); assertEquals(0,m.starts)
    }
    @Test fun djOffWaitsForStopBeforeMusic() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.SettingsChanged(e.state.settings.copy(djEnabled=false)))
        assertEquals(0,m.starts); r.drivers[0].callback(VoiceSignal.STOPPED); assertEquals(1,m.starts)
    }
    @Test fun focusLossStopsVoiceAndGainResumesOnlyAfterSilence() {
        val r = Rig(); val m = Music(); val e = engine(r,m); e.command(Event.FocusChanged(Focus.LOSS_TRANSIENT))
        e.command(Event.FocusChanged(Focus.GAIN)); assertEquals(1,r.drivers.size)
        r.drivers[0].callback(VoiceSignal.STOPPED); assertEquals(2,r.drivers.size); assertEquals(0,m.starts)
    }
    @Test fun factoryFailureRecoversWithoutFakeVoiceCompletion() {
        val voice = VoiceOutput({ error("UNAVAILABLE") }, VoiceScheduler { _,_ -> {} })
        val m = Music(); val e = PlaybackEngine(RadioOutput(m,voice),{0L})
        e.send(Event.CatalogChanged(listOf(song))); e.command(Event.SettingsChanged(RadioSettings(djEnabled=true))); e.command(Event.Play)
        assertEquals(1,m.starts); assertEquals(0,voice.done); assertEquals("VOICE_FAILED",e.state.notice)
    }
}
