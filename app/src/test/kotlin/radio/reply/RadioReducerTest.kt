package radio.reply

import org.junit.Assert.*
import org.junit.Test
import radio.reply.demo.Simulation
import radio.reply.orchestrator.*
import radio.reply.planner.*

class RadioReducerTest {
    private fun playing(dj: Boolean = true): Simulation = Simulation().also {
        it.send(Event.SettingsChanged(it.state.settings.copy(djEnabled = dj)))
        it.send(Event.Play)
        if (dj) it.complete()
    }
    private fun Simulation.starts() = effects.filterIsInstance<Effect.StartOutput>()

    @Test fun ac024_openingOnceThenMusic() {
        val sim = Simulation()
        sim.send(Event.Play)
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
        sim.send(Event.Play)
        assertEquals(1, sim.starts().size)
        sim.complete()
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
        assertEquals(2, sim.starts().size)
    }
    @Test fun ac025_threeNaturalEndsBridgeToNextEra() {
        val sim = playing()
        repeat(3) { sim.complete() }
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
        val voice = sim.state.segment as Segment.Voice
        assertTrue(voice.next.track.releaseYear in Era(2010))
        assertEquals(2, sim.starts().count { it.output.segment is Segment.Voice })
    }
    @Test fun ac013_skipReplenishesWithinEraWithoutCompletion() {
        val sim = playing()
        sim.send(Event.Next)
        assertEquals(0, sim.state.completedSinceDj)
        assertTrue((sim.state.segment as Segment.Music).track.releaseYear in Era(1980))
        repeat(3) { sim.complete() }
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
    }
    @Test fun ac026_allSkippedBlockDoesNotSpeak() {
        val sim = playing()
        repeat(4) { sim.send(Event.Next) }
        assertEquals(0, sim.state.completedSinceDj)
        assertEquals(1, sim.starts().count { it.output.segment is Segment.Voice })
        assertTrue((sim.state.segment as Segment.Music).track.releaseYear in Era(2010))
    }
    @Test fun ac027_pauseWinsLateVoiceDone() {
        val sim = Simulation()
        sim.send(Event.Play)
        val token = sim.state.output!!.token
        sim.send(Event.Pause)
        sim.complete(token)
        assertEquals(Phase.PAUSED, sim.state.phase)
        assertNull(sim.physicalOutput)
        assertEquals(1, sim.starts().size)
    }
    @Test fun ac028_nextWaitsForStopThenStartsIntroducedTrackOnce() {
        val sim = Simulation()
        sim.autoConfirmStop = false
        sim.send(Event.Play)
        val token = sim.state.output!!.token
        val expected = (sim.state.segment as Segment.Voice).next.track.id
        sim.send(Event.Next)
        assertEquals(1, sim.starts().size)
        sim.confirmStop()
        sim.complete(token)
        assertEquals(2, sim.starts().size)
        assertEquals(expected, (sim.state.segment as Segment.Music).track.id)
    }
    @Test fun ac029_focusGainDoesNotResumeUserPause() {
        val sim = playing()
        sim.send(Event.Pause)
        sim.send(Event.FocusChanged(Focus.LOSS_TRANSIENT))
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertEquals(Phase.PAUSED, sim.state.phase)
        assertNull(sim.physicalOutput)
    }
    @Test fun ac030_transientFocusResumesSameMusicOnlyAfterStop() {
        val sim = playing()
        sim.autoConfirmStop = false
        val expected = sim.state.segment
        sim.send(Event.FocusChanged(Focus.LOSS_TRANSIENT))
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertNull(sim.state.output)
        sim.confirmStop()
        assertEquals(expected, sim.state.segment)
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
    }
    @Test fun ac031_permanentFocusNeedsExplicitPlay() {
        val sim = playing()
        sim.send(Event.FocusChanged(Focus.LOSS_PERMANENT))
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertNull(sim.state.output)
        sim.send(Event.Play)
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
    }
    @Test fun playWithoutFocusWaitsThenResumesOnGain() {
        val sim = Simulation()
        sim.send(Event.FocusChanged(Focus.LOSS_TRANSIENT))
        sim.send(Event.Play)
        assertEquals(Phase.SUSPENDED, sim.state.phase)
        assertNull(sim.physicalOutput)
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
    }
    @Test fun ac032_duplicateEndDoesNotCountTwice() {
        val sim = playing()
        val token = sim.state.output!!.token
        sim.complete(token)
        sim.complete(token)
        assertEquals(1, sim.state.completedSinceDj)
        assertEquals(3, sim.starts().size)
    }
    @Test fun ac033_oldGenerationAndEveryCorrelationFieldAreRejected() {
        val sim = playing()
        val key = RequestKey(sim.state.sessionId, sim.state.generation, "request", "segment", "fingerprint")
        sim.send(Event.ScriptRequested(key))
        listOf(key.copy(sessionId = -1), key.copy(generation = -1), key.copy(requestId = "old"),
            key.copy(segmentId = "old"), key.copy(contextFingerprint = "old")).forEach {
            sim.send(Event.ScriptReady(it, "must not accept")); assertNull(sim.state.preparedScript)
        }
        sim.send(Event.ScriptReady(key, "valid cache"))
        assertEquals("valid cache", sim.state.preparedScript)
        sim.send(Event.Next)
        sim.send(Event.ScriptReady(key, "late"))
        assertNull(sim.state.preparedScript)
    }
    @Test fun stopTimeoutNeverStartsMusicEvenAfterPlay() {
        val sim = Simulation()
        sim.autoConfirmStop = false
        sim.send(Event.Play)
        sim.send(Event.Next)
        sim.send(Event.StopTimedOut(sim.state.awaitingStop!!))
        sim.send(Event.Play)
        assertEquals(Phase.STOPPED, sim.state.phase)
        assertEquals(1, sim.starts().size)
        sim.confirmStop()
        assertNull(sim.physicalOutput)
    }
    @Test fun ac035_djOffNeverStartsVoice() {
        val sim = playing(false)
        repeat(8) { sim.complete() }
        assertTrue(sim.starts().all { it.output.segment is Segment.Music })
        assertNull(sim.state.pendingRequest)
    }
    @Test fun singleTrackRepeatDoesNotSpamDj() {
        val fixture = Simulation.fixtures()
        val sim = Simulation(fixture.copy(catalog = fixture.catalog.take(1)))
        sim.send(Event.Play); sim.complete()
        repeat(10) { sim.complete() }
        assertEquals(1, sim.starts().count { it.output.segment is Segment.Voice })
        assertEquals("REDUCED_BROADCAST", sim.state.notice)
    }
    @Test fun nextWhilePausedChangesPositionWithoutOutput() {
        val sim = playing()
        val first = sim.state.segment
        sim.send(Event.Pause)
        sim.send(Event.Next)
        assertNotEquals(first, sim.state.segment)
        assertEquals(Phase.PAUSED, sim.state.phase)
        assertNull(sim.physicalOutput)
    }
    @Test fun stopCannotBeResurrectedByOldSession() {
        val sim = playing()
        val old = sim.state.output!!.token
        sim.send(Event.Stop)
        sim.complete(old)
        assertEquals(Phase.STOPPED, sim.state.phase)
        sim.send(Event.Play)
        val current = sim.state.output
        sim.complete(old)
        assertEquals(current, sim.state.output)
    }
    @Test fun threeConsecutiveMediaFailuresStopAndExcludeBadTracks() {
        val sim = playing(false)
        repeat(3) { sim.send(Event.Failed(sim.state.output!!.token)) }
        assertEquals(Phase.STOPPED, sim.state.phase)
        assertEquals("PLAYBACK_FAILURE_LIMIT", sim.state.notice)
        assertNull(sim.physicalOutput)
    }
    @Test fun voiceErrorWaitsForConfirmedSilence() {
        val sim = Simulation()
        sim.autoConfirmStop = false
        sim.send(Event.Play)
        sim.send(Event.Failed(sim.state.output!!.token))
        assertEquals(1, sim.starts().size)
        sim.confirmStop()
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
    }
    @Test fun emptyCatalogStopsCleanly() {
        val sim = Simulation(RadioState())
        sim.send(Event.Play)
        assertEquals(Phase.STOPPED, sim.state.phase)
        assertEquals("NO_PLAYABLE_TRACKS", sim.state.notice)
        assertNull(sim.physicalOutput)
    }
    @Test fun pauseBeforeFirstPlayAndAfterStopDoesNotStrandSession() {
        val sim = Simulation()
        sim.send(Event.Pause)
        assertEquals(Phase.IDLE, sim.state.phase)
        sim.send(Event.Play)
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
        sim.send(Event.Stop)
        sim.send(Event.Pause)
        assertEquals(Phase.STOPPED, sim.state.phase)
        sim.send(Event.Play)
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
    }
    @Test fun eraChangeInvalidatesOldOutputAndKeepsPause() {
        val sim = playing()
        val old = sim.state.output!!.token
        sim.send(Event.Pause)
        sim.send(Event.SettingsChanged(sim.state.settings.copy(eras = listOf(Era(2010)))))
        sim.complete(old)
        assertEquals(Phase.PAUSED, sim.state.phase)
        assertTrue((sim.state.segment as Segment.Music).track.releaseYear in Era(2010))
    }
}
