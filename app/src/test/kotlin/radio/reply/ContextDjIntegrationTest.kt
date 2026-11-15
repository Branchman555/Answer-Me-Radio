package radio.reply

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import radio.reply.context.*
import radio.reply.demo.*
import radio.reply.dj.*
import radio.reply.orchestrator.*

class ContextDjIntegrationTest {
    private val wallNow = Instant.parse("2026-11-14T17:30:00Z")
    private fun input(condition: WeatherCondition = WeatherCondition.RAIN, ageSeconds: Long = 1200) =
        ContextInput(wallNow, ZoneId.of("Asia/Seoul"), true,
            WeatherReading(condition, wallNow.minusSeconds(ageSeconds).toString(), wallNow.toString()), LocationStatus.AVAILABLE)
    private fun playing(result: FakeDjResult = FakeDjResult.SUCCESS, delay: Long = 0): Simulation = Simulation().also {
        it.djProvider.result = result; it.djProvider.delayMillis = delay
        it.send(Event.ContextChanged(input()))
        it.send(Event.Play); it.complete()
    }
    private fun Simulation.lastSong() { complete(); complete() }
    private fun Simulation.requests() = effects.filterIsInstance<Effect.RequestDjScript>()

    @Test fun ac039_midTrackWeatherDoesNotInterruptAndAdoptsOnlyAtBlockBoundary() {
        val sim = playing()
        val output = sim.state.output
        sim.send(Event.ContextChanged(input(WeatherCondition.SUN)))
        assertEquals(output, sim.state.output)
        assertEquals(WeatherCondition.RAIN, sim.state.activeContext!!.weather!!.condition)
        sim.lastSong()
        assertEquals(WeatherCondition.SUN, sim.state.djPlan!!.request.context.weather!!.condition)
        assertEquals(WeatherCondition.RAIN, sim.state.activeContext!!.weather!!.condition)
        sim.complete()
        assertEquals(WeatherCondition.SUN, sim.state.activeContext!!.weather!!.condition)
        assertTrue((sim.state.segment as Segment.Voice).text.contains("맑은"))
    }
    @Test fun lastSongStartPrefetchesExactlyOnceAndResponseCannotStartAudio() {
        val sim = playing()
        assertTrue(sim.requests().isEmpty())
        sim.complete()
        assertTrue(sim.requests().isEmpty())
        sim.complete()
        assertEquals(1, sim.requests().size)
        assertEquals(RequestStatus.READY, sim.state.djPlan!!.status)
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
        val planned = sim.state.djPlan!!.request.nextTracks.first().id
        sim.complete()
        assertEquals(planned, (sim.state.segment as Segment.Voice).next.track.id)
        assertNotNull((sim.state.segment as Segment.Voice).scriptPlan)
    }
    @Test fun ac040_sameWeatherFetchDoesNotDuplicateRequestsOrChangeGeneration() {
        val sim = playing()
        sim.lastSong()
        val generation = sim.state.generation
        sim.send(Event.ContextChanged(input().copy(weather = input().weather!!.copy(fetchedAt = wallNow.plusSeconds(1).toString()))))
        assertEquals(1, sim.requests().size)
        assertEquals(generation, sim.state.generation)
    }
    @Test fun changedContextCancelsOldRequestAndRejectsLateResponse() {
        val sim = playing(FakeDjResult.NEVER)
        sim.lastSong()
        val old = sim.state.djPlan!!
        val output = sim.state.output
        sim.send(Event.ContextChanged(input(WeatherCondition.SUN)))
        assertEquals(2, sim.requests().size)
        assertEquals(output, sim.state.output)
        sim.send(Event.DjResponseReceived(old.key, ContextJson.encode(LocalDjScripts.mock(old.request, false))))
        assertEquals(RequestStatus.PENDING, sim.state.djPlan!!.status)
        sim.complete()
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
        assertFalse((sim.state.segment as Segment.Voice).text.contains("비"))
    }
    @Test fun threeSecondTimeoutNeverRetriesOrBlocksPlayback() {
        val sim = playing(FakeDjResult.NEVER)
        sim.lastSong()
        sim.advanceTimeBy(2999)
        assertEquals(RequestStatus.PENDING, sim.state.djPlan!!.status)
        sim.advanceTimeBy(1)
        assertEquals(DjFailure.AI_TIMEOUT, sim.state.djPlan!!.failure)
        assertEquals(1, sim.requests().size)
        sim.advanceTimeBy(9000)
        assertEquals(1, sim.requests().size)
        sim.complete()
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
        sim.complete()
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
    }
    @Test fun earlyBoundaryUsesFallbackAndLateReplyCannotReplaceSpeakingVoice() {
        val sim = playing(delay = 2500)
        sim.lastSong()
        val plan = sim.state.djPlan!!
        sim.complete()
        val output = sim.state.output
        sim.advanceTimeBy(2500)
        sim.send(Event.DjResponseReceived(plan.key, ContextJson.encode(LocalDjScripts.mock(plan.request, false))))
        assertEquals(output, sim.state.output)
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
    }
    @Test fun exactDeadlineReplyIsTooLateButEarlierReplyIsUsable() {
        for (delay in listOf(2999L, 3000L)) {
            val sim = playing(delay = delay)
            sim.lastSong(); sim.advanceTimeBy(delay); sim.complete()
            assertEquals(delay < 3000, (sim.state.segment as Segment.Voice).scriptPlan != null)
        }
    }
    @Test fun providerErrorAndInvalidJsonFallbackWithoutRetries() {
        for (result in listOf(FakeDjResult.ERROR, FakeDjResult.INVALID_JSON)) {
            val sim = playing(result)
            sim.lastSong()
            assertEquals(RequestStatus.FAILED, sim.state.djPlan!!.status)
            assertEquals(if (result == FakeDjResult.ERROR) DjFailure.PROVIDER_FAILED else DjFailure.JSON_INVALID, sim.state.djPlan!!.failure)
            sim.complete()
            assertNull((sim.state.segment as Segment.Voice).scriptPlan)
            assertEquals(1, sim.requests().size)
        }
    }
    @Test fun pauseCanCacheReplyButCannotPlayUntilUserPlay() {
        val sim = playing(delay = 1000)
        sim.lastSong(); sim.send(Event.Pause); sim.advanceTimeBy(1000)
        assertEquals(RequestStatus.READY, sim.state.djPlan!!.status)
        assertEquals(Phase.PAUSED, sim.state.phase)
        assertNull(sim.physicalOutput)
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertEquals(Phase.PAUSED, sim.state.phase)
        sim.send(Event.Play); sim.complete()
        assertNotNull((sim.state.segment as Segment.Voice).scriptPlan)
    }
    @Test fun staleWeatherDuringSongOrVoicePauseFallsBackAtSpeechStart() {
        val sim = playing()
        sim.send(Event.ContextChanged(input(ageSeconds = 3599)))
        sim.lastSong()
        assertEquals(RequestStatus.READY, sim.state.djPlan!!.status)
        sim.advanceTimeBy(2000); sim.complete()
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
        val other = playing()
        other.lastSong(); other.complete()
        assertNotNull((other.state.segment as Segment.Voice).scriptPlan)
        other.send(Event.Pause); other.advanceTimeBy(41 * 60_000); other.send(Event.Play)
        assertNull((other.state.segment as Segment.Voice).scriptPlan)
        assertFalse((other.state.segment as Segment.Voice).text.contains("비"))
    }
    @Test fun stopAndDjOffCancelAndLateRepliesNeverReviveOutput() {
        for (stop in listOf(true, false)) {
            val sim = playing(FakeDjResult.NEVER)
            sim.lastSong()
            val plan = sim.state.djPlan!!
            if (stop) sim.send(Event.Stop) else sim.send(Event.SettingsChanged(sim.state.settings.copy(djEnabled = false)))
            val output = sim.state.output
            sim.send(Event.DjResponseReceived(plan.key, ContextJson.encode(LocalDjScripts.mock(plan.request, false))))
            assertEquals(output, sim.state.output)
            assertNull(sim.state.djPlan)
            if (stop) assertEquals(Phase.STOPPED, sim.state.phase)
        }
    }
    @Test fun nextInvalidatesPrefetchWithoutChangingCompletionCount() {
        val sim = playing(FakeDjResult.NEVER)
        sim.lastSong()
        val old = sim.state.djPlan!!
        sim.send(Event.Next)
        assertEquals(2, sim.state.completedSinceDj)
        assertNotEquals(old.key, sim.state.djPlan?.key)
        sim.send(Event.DjResponseReceived(old.key, ContextJson.encode(LocalDjScripts.mock(old.request, false))))
        assertFalse(sim.state.djPlan?.status == RequestStatus.READY)
    }
    @Test fun historyCornerCancelsOrdinaryPrefetchAndNeverUsesHistoryApiType() {
        val sim = playing()
        sim.send(Event.SettingsChanged(sim.state.settings.copy(roadTimeTravelEnabled = true, roadLocationConsent = true)))
        sim.lastSong() // Ordinary request already ready on third track.
        sim.send(Event.MatchChanged(Simulation.match(sim.now)))
        sim.complete()
        assertNotNull((sim.state.segment as Segment.Voice).storyId)
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
        assertNull(sim.state.djPlan)
        assertTrue(sim.djProvider.requestBodies.none { it.contains("HISTORY") || it.contains("fictional-story") })
    }
    @Test fun locationRevocationBeforeBoundaryRemovesWeatherAndPreservesMusic() {
        val sim = playing()
        sim.lastSong()
        val output = sim.state.output
        sim.send(Event.ContextChanged(input().copy(locationStatus = LocationStatus.DENIED, weather = null)))
        assertEquals(output, sim.state.output)
        assertNull(sim.state.djPlan!!.request.context.weather)
        sim.complete()
        assertFalse((sim.state.segment as Segment.Voice).text.contains("비"))
    }
    @Test fun revokedLocationWeatherIsRemovedFromMemoryEvenIfAdapterSuppliesCachedReading() {
        val sim = playing()
        sim.lastSong()
        sim.send(Event.ContextChanged(input().copy(locationStatus = LocationStatus.DENIED)))
        assertNull(sim.state.contextInput.weather)
        assertNull(sim.state.djPlan!!.snapshot.weather)
        assertNull(sim.state.djPlan!!.request.context.weather)
        assertEquals(Phase.MUSIC_PLAYING, sim.state.phase)
    }
    @Test fun catalogChangeInvalidatesIntroducedTrackAndUsesCurrentMetadataOnly() {
        val sim = playing()
        sim.lastSong()
        val old = sim.state.djPlan!!
        sim.send(Event.CatalogChanged(sim.state.catalog.map {
            if (it.id == old.request.nextTracks.first().id) it.copy(accessible = false) else it
        }))
        sim.complete()
        assertNull((sim.state.segment as Segment.Voice).scriptPlan)
        assertNotEquals(old.request.nextTracks.first().id, (sim.state.segment as Segment.Voice).next.track.id)
    }
    @Test fun djOffEmitsNoProviderRequestsAndRawLegacyTextHasNoPlaybackAuthority() {
        val sim = playing()
        val key = RequestKey(sim.state.sessionId, sim.state.generation, "probe", "segment", "untrusted")
        sim.send(Event.ScriptRequested(key)); sim.send(Event.ScriptReady(key, "절대로 발화하면 안 되는 문장"))
        sim.lastSong(); sim.complete()
        assertFalse((sim.state.segment as Segment.Voice).text.contains("절대로"))
        val off = Simulation()
        off.send(Event.SettingsChanged(off.state.settings.copy(djEnabled = false)))
        off.send(Event.Play); repeat(9) { off.complete() }
        assertTrue(off.djProvider.requestBodies.isEmpty())
    }
    @Test fun malformedTrackMetadataSkipsNetworkButKeepsEraFallback() {
        val sim = playing()
        sim.send(Event.CatalogChanged(sim.state.catalog.map { it.copy(title = "a".repeat(101)) }))
        sim.lastSong(); sim.complete()
        assertTrue(sim.djProvider.requestBodies.isEmpty())
        assertEquals(Phase.DJ_SPEAKING, sim.state.phase)
        assertTrue((sim.state.segment as Segment.Voice).text.contains("2010년대"))
    }
}
