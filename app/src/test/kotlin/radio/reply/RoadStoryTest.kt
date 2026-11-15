package radio.reply

import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import radio.reply.demo.Simulation
import radio.reply.orchestrator.*
import radio.reply.planner.*
import radio.reply.story.*

class RoadStoryTest {
    private val fixture = Simulation.fixtures()
    private val pack = fixture.packs.single()
    private val planner = RoadStoryPlanner()
    private fun select(
        packs: List<StoryPack> = listOf(pack), tracks: List<Track> = fixture.catalog,
        match: SegmentMatch? = Simulation.match(), date: LocalDate? = fixture.localDate,
        attempted: Set<String> = emptySet(), eta: Long? = null, now: Long = 1000,
    ) = planner.select(packs, tracks, match, now, date, attempted, eta)

    private fun playing(enabled: Boolean = true): Simulation = Simulation().also {
        it.send(Event.SettingsChanged(it.state.settings.copy(roadTimeTravelEnabled = enabled, roadLocationConsent = enabled)))
        it.send(Event.Play); it.complete()
        it.send(Event.MatchChanged(Simulation.match()))
    }
    private fun Simulation.beginStory() { complete(); complete(); assertTrue(state.segment is Segment.Voice) }

    @Test fun rs001_rs002_defaultOffPreservesBaseEras() {
        val sim = playing(false)
        repeat(8) {
            if (sim.state.segment is Segment.Music) assertTrue((sim.state.segment as Segment.Music).track.releaseYear != 2001)
            sim.complete()
        }
        assertTrue(sim.state.history.isEmpty())
        assertFalse(sim.state.locationSubscribed)
    }
    @Test fun rs004_otherRoadIsExcludedEvenOnAnniversary() {
        assertNull(select(packs = listOf(pack.copy(segmentRef = "another-road"))))
    }
    @Test fun rs005_rs007_anniversaryUsesEventDateNotPublication() {
        val other = pack.copy(storyId = "a-first", canonicalEventId = "another", eventDate = LocalDate.of(2001, 1, 1),
            publicationDate = LocalDate.of(2001, 11, 15))
        assertEquals(pack.storyId, select(packs = listOf(other, pack))!!.pack.storyId)
    }
    @Test fun rs006_noAnniversaryStillAllowsSameRoadStory() {
        assertEquals(pack.storyId, select(date = LocalDate.of(2026, 3, 4))!!.pack.storyId)
        assertNotNull(select(date = null))
    }
    @Test fun rs008_rs009_missingStaleFutureLocationIsRejected() {
        assertNull(select(match = null))
        assertNull(select(now = 11_001))
        assertNull(select(match = Simulation.match().copy(measuredAtMonotonic = 1001)))
        assertNull(select(match = Simulation.match().copy(status = MatchStatus.UNKNOWN)))
    }
    @Test fun rs010_rs011_ambiguousGeometryAndRouteOnlyCannotConfirm() {
        assertNull(select(match = Simulation.match().copy(disambiguated = false)))
        assertNull(select(match = Simulation.match().copy(geometryVerified = false)))
        assertNull(select(match = Simulation.match().copy(accurateConsecutiveSamples = 1)))
        assertNull(select(match = Simulation.match().copy(status = MatchStatus.OUTSIDE)))
    }
    @Test fun rs012_rs013_canonicalEventDeduplicatesArticles() {
        assertNull(select(packs = listOf(pack, pack.copy(storyId = "second-article")), attempted = setOf(pack.canonicalEventId)))
    }
    @Test fun rs014_rs015_entryDoesNotInterruptAndOneCompletionDoesNotSpeak() {
        val sim = playing()
        val first = sim.state.output
        sim.send(Event.MatchChanged(Simulation.match()))
        assertEquals(first, sim.state.output)
        assertNotNull(sim.state.pendingStory)
        sim.complete()
        assertTrue(sim.state.segment is Segment.Music)
        assertTrue(sim.state.history.isEmpty())
    }
    @Test fun rs016_secondBoundaryReplacesRegularDj() {
        val sim = playing()
        sim.beginStory()
        assertEquals(pack.storyId, (sim.state.segment as Segment.Voice).storyId)
        assertEquals(1, sim.state.history.size)
        assertEquals(2, sim.effects.filterIsInstance<Effect.StartOutput>().count { it.output.segment is Segment.Voice })
    }
    @Test fun rs017_exitOrExpiryCancelsPending() {
        for (exit in listOf(true, false)) {
            val sim = playing()
            sim.complete()
            if (exit) sim.send(Event.MatchChanged(null)) else sim.now += 11_000
            sim.complete()
            assertTrue(sim.state.segment is Segment.Music)
            assertTrue(sim.state.history.isEmpty())
        }
    }
    @Test fun rs018_pausedEntryAndFocusCannotStartAnything() {
        val sim = playing()
        sim.send(Event.Pause)
        sim.send(Event.MatchChanged(Simulation.match()))
        sim.send(Event.FocusChanged(Focus.GAIN))
        assertNull(sim.state.pendingStory)
        assertNull(sim.physicalOutput)
        assertFalse(sim.state.locationSubscribed)
    }
    @Test fun rs019_rs020_interruptedHistoryIsPartialAndDoesNotDoubleSkip() {
        val sim = playing()
        sim.beginStory()
        val token = sim.state.output!!.token
        sim.send(Event.Next)
        sim.complete(token)
        assertEquals("story-track", (sim.state.segment as Segment.Music).track.id)
        assertEquals(StoryPlayback.PARTIAL, sim.state.history.single().status)
        assertEquals(setOf(pack.canonicalEventId), sim.state.attemptedEvents)
    }
    @Test fun rs021_unapprovedContentOrScriptNeverRuns() {
        assertNull(select(packs = listOf(pack.copy(approved = false))))
        assertNull(select(packs = listOf(pack.copy(review = Review.PENDING))))
        assertNull(select(packs = listOf(pack.copy(rightsReviewed = false))))
        assertNull(select(packs = listOf(pack.copy(scripts = pack.scripts.map { it.copy(review = Review.PENDING) }))))
    }
    @Test fun rs023_rs025_labelsDoNotInventChartOrBroadcastClaims() {
        val association = pack.music.single()
        assertEquals("같은 해 발표곡", planner.label(association))
        assertEquals("2001년 11월 발표곡", planner.label(association.copy(releaseMonth = 11)))
        for (basis in listOf(MusicBasis.BROADCAST_VERIFIED, MusicBasis.CHART_VERIFIED, MusicBasis.ERA_ONLY)) {
            assertNull(planner.label(association.copy(basis = basis)))
            assertNull(select(packs = listOf(pack.copy(music = listOf(association.copy(basis = basis))))))
        }
    }
    @Test fun rs024_noExactYearTrackKeepsBaseMusic() {
        assertNull(select(tracks = fixture.catalog.filter { it.releaseYear != 2001 }))
        assertNull(select(tracks = fixture.catalog.map { if (it.id == "story-track") it.copy(releaseYear = 2002) else it }))
        assertNull(select(packs = listOf(pack.copy(eventDate = LocalDate.of(2002, 11, 15)))))
    }
    @Test fun rs027_rs028_etaUsesReviewedShortOrSkips() {
        assertEquals("LONG", select()!!.script.variant)
        assertEquals("SHORT", select(eta = 120)!!.script.variant)
        assertNull(select(eta = 29))
        assertNull(select(eta = 120, packs = listOf(pack.copy(scripts = pack.scripts.filter { it.variant == "LONG" }))))
    }
    @Test fun rs033_revocationShadowsOlderApprovedVersion() {
        val revoked = pack.copy(contentVersion = 2, review = Review.REVOKED)
        assertNull(select(packs = listOf(pack, revoked)))
        val sim = playing()
        sim.send(Event.PacksChanged(listOf(pack, revoked)))
        assertNull(sim.state.pendingStory)
        sim.complete(); sim.complete()
        assertTrue(sim.state.segment is Segment.Music)
    }
    @Test fun rs034_bundledDraftLoadsButCannotExecute() {
        val json = File(System.getProperty("designDir"), "reply_radio_road_story_content_sample_v0.8.json").readText()
        val loaded = StoryContentReader.read(json, (1..13).map { "S$it" }.toSet()).getOrThrow()
        assertFalse(loaded.approved)
        assertNull(select(packs = listOf(loaded)))
    }
    @Test fun rs003_rs035_rs038_storyRestoresExactBaseQueueAndCursor() {
        val sim = playing()
        sim.complete()
        val expectedRemaining = sim.state.block!!.remaining
        val cursor = sim.state.eraCursor
        sim.complete() // second base music ends; history starts before consuming next base track
        assertTrue(sim.state.segment is Segment.Voice)
        assertEquals(expectedRemaining, sim.state.block!!.remaining)
        sim.complete() // story voice
        assertEquals(2001, (sim.state.segment as Segment.Music).track.releaseYear)
        sim.complete() // single story song; no immediate DJ
        assertEquals(expectedRemaining.first().id, (sim.state.segment as Segment.Music).track.id)
        assertEquals(cursor, sim.state.eraCursor)
        assertEquals(1, sim.state.completedSinceDj)
        assertEquals(StoryPlayback.COMPLETE, sim.state.history.single().status)
    }
    @Test fun rs037_djOffOrConsentWithdrawalReleasesSubscription() {
        for (djOff in listOf(true, false)) {
            val sim = playing()
            assertTrue(sim.state.locationSubscribed)
            sim.send(Event.SettingsChanged(if (djOff) sim.state.settings.copy(djEnabled = false)
                else sim.state.settings.copy(roadLocationConsent = false)))
            assertFalse(sim.state.locationSubscribed)
            assertNull(sim.state.pendingStory)
            assertNull(sim.state.match)
            sim.complete(); sim.complete()
            assertTrue(sim.state.history.isEmpty())
        }
    }
    @Test fun rs039_usbRemovalBeforeSpeechCancelsIntroduction() {
        val sim = playing()
        sim.complete()
        sim.send(Event.CatalogChanged(sim.state.catalog.map { if (it.id == "story-track") it.copy(accessible = false) else it }))
        sim.complete()
        assertTrue(sim.state.segment is Segment.Music)
        assertTrue(sim.state.history.isEmpty())
    }
    @Test fun rs040_newRouteReplacesPendingWithoutStoppingMusic() {
        val sim = playing()
        val output = sim.state.output
        sim.send(Event.MatchChanged(Simulation.match(revision = 2).copy(segmentId = "other-road")))
        assertNull(sim.state.pendingStory)
        assertEquals(output, sim.state.output)
    }
    @Test fun repeatedSamplesDoNotChurnGeneration() {
        val sim = playing()
        val generation = sim.state.generation
        repeat(5) {
            sim.now += 1
            sim.send(Event.MatchChanged(Simulation.match(now = sim.now)))
        }
        assertEquals(generation, sim.state.generation)
    }
    @Test fun clearHistoryKeepsTripDedupeAndInterruptedVoiceDoesNotRepeatOnPlay() {
        val sim = playing()
        sim.beginStory()
        sim.send(Event.Pause)
        assertEquals(StoryPlayback.PARTIAL, sim.state.history.single().status)
        sim.send(Event.ClearHistory)
        sim.send(Event.Play)
        assertTrue(sim.state.segment is Segment.Music)
        assertEquals(setOf(pack.canonicalEventId), sim.state.attemptedEvents)
    }
    @Test fun conflictingSameVersionFailsClosed() {
        assertNull(select(packs = listOf(pack, pack.copy(rightsReviewed = false))))
    }
    @Test fun contentReaderRejectsUnknownFieldsTypesDatesAndDanglingReferences() {
        val json = File(System.getProperty("designDir"), "reply_radio_road_story_content_sample_v0.8.json").readText()
        val sources = (1..13).map { "S$it" }.toSet()
        val variants = listOf(
            json.replace("\"schemaVersion\": \"0.8\"", "\"schemaVersion\": \"0.9\""),
            json.replace("\"geometryVerified\": false", "\"geometryVerified\": \"false\""),
            json.replace("\"contentVersion\": 1", "\"contentVersion\": 1.5"),
            json.replace("2001-11-28", "2001-02-30"),
            json.replace("\"S3\"", "\"S999\""),
            json.replace("\"factIds\": [", "\"factIds\": [\"missing\","),
            json.replace("\"schemaVersion\":", "\"unexpected\": 1, \"schemaVersion\":"),
        )
        variants.forEach { assertTrue(StoryContentReader.read(it, sources).isFailure) }
    }
}
