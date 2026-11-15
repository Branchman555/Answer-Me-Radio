package radio.reply

import org.junit.Assert.*
import org.junit.Test
import radio.reply.demo.Simulation
import radio.reply.planner.*

class RadioPlannerTest {
    private val planner = RadioPlanner()
    private val tracks = Simulation.fixtures().catalog
    private val settings = RadioSettings()

    @Test fun ac007_eraIsHardFilter() {
        val result = planner.plan(tracks, settings.copy(preferredGenres = setOf("pop")), 0)
        assertTrue(result.block!!.candidates.all { it.releaseYear in Era(1980) })
        assertFalse(result.block!!.candidates.any { it.id == "story-track" })
    }
    @Test fun ac008_familyAlternatesThreeTrackTargets() {
        val a = planner.plan(tracks, settings, 0)
        val b = planner.plan(tracks, settings, a.nextEraCursor)
        assertEquals(3, a.block!!.targetCompleted)
        assertEquals(Era(2010), b.block!!.era)
        assertEquals(0, b.nextEraCursor)
    }
    @Test fun ac009_twoCandidatesShortenBlock() {
        assertEquals(2, planner.plan(tracks.take(2), settings, 0).block!!.targetCompleted)
    }
    @Test fun ac010_oneCandidateShortensBlock() {
        assertEquals(1, planner.plan(tracks.take(1), settings, 0).block!!.targetCompleted)
    }
    @Test fun ac011_missingEraIsReported() {
        val result = planner.plan(tracks.filter { it.releaseYear in Era(2010) }, settings, 0)
        assertEquals(listOf(Era(1980)), result.missingEras)
        assertEquals(Era(2010), result.block!!.era)
    }
    @Test fun ac012_noTracksBlocksPlanning() { assertNull(planner.plan(emptyList(), settings, 0).block) }
    @Test fun ac014_seedIsDeterministicAndLastTrackMovesBack() {
        val first = planner.plan(tracks, settings, 0)
        repeat(10) { assertEquals(first, planner.plan(tracks.reversed(), settings, 0)) }
        val lastId = first.block!!.candidates.first().id
        val next = planner.plan(tracks, settings, 0, listOf(lastId))
        assertNotEquals(lastId, next.block!!.candidates.first().id)
    }
    @Test fun ac015_sameErasNormalizeToSingle() {
        val same = settings.copy(eras = listOf(Era(1980), Era(1980)))
        assertEquals(Mode.SINGLE, same.mode)
        assertEquals(0, planner.plan(tracks, same, 0).nextEraCursor)
    }
    @Test fun invalidMetadataAndUnavailableTracksAreExcluded() {
        val bad = listOf(tracks[0].copy(releaseYear = null), tracks[1].copy(accessible = false),
            tracks[2].copy(playable = false), tracks[3].copy(title = ""))
        assertNull(planner.plan(bad, settings, 0).block)
    }
}
