package radio.reply

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import radio.reply.context.*
import radio.reply.dj.ScriptContextBuilder
import radio.reply.planner.*

class ContextEngineTest {
    private val now = Instant.parse("2026-11-14T17:30:00Z")
    private val engine = ContextEngine()
    private val settings = RadioSettings(preferredGenres = setOf("BALLAD"), tripIntent = TripIntent.TRAVEL)
    private fun input(ageMinutes: Long = 20) = ContextInput(now, ZoneId.of("Asia/Seoul"), true,
        WeatherReading(WeatherCondition.RAIN, now.minusSeconds(ageMinutes * 60).toString(), now.toString()), LocationStatus.AVAILABLE)
    private fun snapshot(input: ContextInput = input()) = engine.snapshot(settings, input, "ctx-test")

    @Test fun ac036_validObservationUsesModelTimeAndPreservesEraFilter() {
        val context = snapshot()
        assertEquals(WeatherCondition.RAIN, context.weather!!.condition)
        assertEquals(listOf("1980s", "2010s"), context.eras)
        assertEquals(TimeOfDay.NIGHT, context.timeOfDay)
        val plan = RadioPlanner().plan(radio.reply.demo.Simulation.fixtures().catalog, settings, 0)
        assertTrue(plan.block!!.candidates.all { it.releaseYear in Era(1980) })
    }
    @Test fun ac037_sixtyMinuteAndFiveMinuteFutureBoundaries() {
        assertNotNull(snapshot(input(60)).weather)
        assertNull(snapshot(input().copy(weather = input(60).weather!!.copy(observedAt = now.minusSeconds(3601).toString()))).weather)
        assertTrue("WEATHER_STALE" in snapshot(input(61)).omissions)
        assertNotNull(snapshot(input(-5)).weather)
        assertNull(snapshot(input().copy(weather = input(-5).weather!!.copy(observedAt = now.plusSeconds(301).toString()))).weather)
    }
    @Test fun fetchTimeCannotFreshenOldObservation() {
        assertNull(snapshot(input(61).copy(weather = input(61).weather!!.copy(fetchedAt = now.plusSeconds(10).toString()))).weather)
    }
    @Test fun ac038_deniedUnavailableAndBlockedLocationsRemoveWeather() {
        for (status in listOf(LocationStatus.DENIED, LocationStatus.UNAVAILABLE, LocationStatus.BLOCKED, LocationStatus.NOT_REQUESTED)) {
            assertNull(snapshot(input().copy(locationStatus = status)).weather)
        }
    }
    @Test fun explicitlyConsentedRegionIsIndependentOfLocationPermission() {
        val region = input().copy(locationStatus = LocationStatus.DENIED,
            weather = input().weather!!.copy(scope = WeatherScope.SELECTED_REGION))
        assertNull(snapshot(region).weather)
        assertNotNull(snapshot(region.copy(selectedRegionConsented = true)).weather)
    }
    @Test fun ac040_fingerprintMatchesAllEightPreservedFixtureHashes() {
        val fixtures = ContractSchema.document("reply_radio_context_fixtures_v0.6.json")
        fixtures.getAsJsonArray("testCases").forEach { case ->
            val expected = ContextJson.gson.fromJson(case.asJsonObject["snapshot"], ContextSnapshot::class.java)
            assertEquals(case.asJsonObject["id"].asString, expected.contextFingerprint, FingerprintFactory.create(expected))
        }
    }
    @Test fun ac040_captureFetchIdsLocationAndVehicleDoNotChangeFingerprint() {
        val a = snapshot()
        val b = a.copy(snapshotId = "another", capturedAt = now.plusSeconds(10).toString(),
            weather = a.weather!!.copy(fetchedAt = now.plusSeconds(10).toString()), locationStatus = LocationStatus.UNAVAILABLE,
            vehicle = VehicleContext("UNAVAILABLE"), omissions = listOf("VEHICLE_UNAVAILABLE"))
        assertEquals(FingerprintFactory.create(a), FingerprintFactory.create(b))
        assertNotEquals(a.contextFingerprint, snapshot(input().copy(weather = input().weather!!.copy(condition = WeatherCondition.SUN))).contextFingerprint)
        assertNotEquals(a.contextFingerprint, snapshot(input(61)).contextFingerprint)
    }
    @Test fun genresCanonicalizeAndEqualErasBecomeSingle() {
        val first = engine.snapshot(settings.copy(preferredGenres = linkedSetOf("POP", "BALLAD")), input(), "one")
        val second = engine.snapshot(settings.copy(preferredGenres = linkedSetOf("BALLAD", "POP")), input(), "two")
        assertEquals(first.contextFingerprint, second.contextFingerprint)
        val single = engine.snapshot(settings.copy(eras = listOf(Era(1980), Era(1980))), input(), "single")
        assertEquals("SINGLE", single.mode)
        assertEquals(listOf("1980s"), single.eras)
    }
    @Test fun ac041_projectionHasOnlyFiveFieldsAndRechecksTtl() {
        val context = snapshot()
        val projected = ScriptContextBuilder.project(context, Era(2010), Era(1980), now)
        val json = ContextJson.gson.toJsonTree(projected).asJsonObject
        assertEquals(setOf("timeOfDay", "weather", "mode", "previousEra", "nextEra"), json.keySet())
        assertEquals("2010s", projected.previousEra) // Explicit planner blocks, not inferred array order.
        assertEquals(setOf("condition", "observedAt", "valid"), json.getAsJsonObject("weather").keySet())
        assertNull(ScriptContextBuilder.project(context, null, Era(1980), now.plusSeconds(41 * 60)).weather)
    }
    @Test fun ac042_unknownClockOrZoneDoesNotInventContext() {
        assertEquals(TimeOfDay.UNKNOWN, snapshot(input().copy(zone = null)).timeOfDay)
        val untrusted = snapshot(input().copy(clockTrusted = false))
        assertEquals(TimeOfDay.UNKNOWN, untrusted.timeOfDay)
        assertNull(untrusted.weather)
        assertEquals("DISABLED", untrusted.vehicle.availability)
    }
    @Test fun timeBoundariesAndTimezoneChangesUseLocalClock() {
        val expected = mapOf(0 to TimeOfDay.NIGHT, 4 to TimeOfDay.NIGHT, 5 to TimeOfDay.MORNING,
            10 to TimeOfDay.MORNING, 11 to TimeOfDay.DAY, 16 to TimeOfDay.DAY,
            17 to TimeOfDay.EVENING, 20 to TimeOfDay.EVENING, 21 to TimeOfDay.NIGHT, 23 to TimeOfDay.NIGHT)
        expected.forEach { (hour, value) -> assertEquals(value, ContextEngine.classify(hour)) }
        assertEquals(TimeOfDay.EVENING, snapshot(input().copy(zone = ZoneId.of("UTC"))).timeOfDay)
    }
    @Test fun simulationRequiresBothExplicitModeAndDevelopmentBuild() {
        val simulated = input().copy(weather = input().weather!!.copy(source = WeatherSource.SIMULATION))
        for (candidate in listOf(simulated, simulated.copy(simulationRequested = true), simulated.copy(developmentBuild = true))) {
            assertNull(snapshot(candidate).weather)
            assertTrue("SIMULATION_DISABLED" in snapshot(candidate).omissions)
        }
        val allowed = snapshot(simulated.copy(simulationRequested = true, developmentBuild = true))
        assertNotNull(allowed.weather)
        assertTrue(allowed.simulationMode)
    }
    @Test fun invalidConditionDatesAndUnknownClockFailClosed() {
        for (reading in listOf(input().weather!!.copy(condition = WeatherCondition.UNKNOWN),
            input().weather!!.copy(observedAt = "invalid"), input().weather!!.copy(fetchedAt = "invalid"))) {
            assertNull(snapshot(input().copy(weather = reading)).weather)
        }
    }
    @Test fun weatherFailureKeepsOnlyStillValidCache() {
        assertNotNull(snapshot(input().copy(weatherFailure = "WEATHER_TIMEOUT")).weather)
        assertNull(snapshot(input(61).copy(weatherFailure = "WEATHER_TIMEOUT")).weather)
    }
    @Test fun snapshotsDoNotRetainMutablePreferenceCollections() {
        val genres = mutableSetOf("BALLAD")
        val context = engine.snapshot(settings.copy(preferredGenres = genres), input(), "immutable")
        genres += "POP"
        assertEquals(listOf("BALLAD"), context.preferences.genres)
        assertTrue(runCatching { (context.preferences.genres as MutableList<String>).add("POP") }.isFailure)
    }
    @Test fun weatherGatewaySingleFlightRefreshBackoffAndRevocation() {
        val cache = WeatherCache()
        assertNull(cache.begin(0, false))
        val first = cache.begin(0, true)!!
        assertNull(cache.begin(1, true))
        cache.complete(first, input().weather, 0)
        assertNull(cache.begin(899_999, true))
        val second = cache.begin(900_000, true)!!
        cache.complete(second, null, 900_000)
        assertNotNull(cache.reading)
        assertNull(cache.begin(1_199_999, true))
        val third = cache.begin(1_200_000, true)!!
        cache.revoke()
        cache.complete(third, input().weather, 1_200_001)
        assertNull(cache.reading)
    }
}
