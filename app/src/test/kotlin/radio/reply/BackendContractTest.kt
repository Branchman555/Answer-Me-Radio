package radio.reply

import com.google.gson.JsonParser
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import radio.reply.context.*
import radio.reply.demo.Simulation
import radio.reply.dj.*
import radio.reply.orchestrator.RequestKey
import radio.reply.planner.*

/** Actual Uvicorn HTTP captures, containing synthetic metadata only. No Android networking. */
class BackendContractTest {
    private val root = requireNotNull(File(requireNotNull(System.getProperty("designDir"))).parentFile?.parentFile)
    private val cases = JsonParser.parseString(File(root,
        "backend/fixtures/android-contracts.json").readText()).asJsonObject.getAsJsonArray("cases")
    private val schema = ContractSchema.document("reply_radio_dj_script_schema_v0.5.json")

    @Test fun actualServerPayloadsConformToOriginalContractAndStrictAndroidDecoder() {
        assertEquals(9, cases.size())
        cases.forEach { item ->
            val sample = item.asJsonObject
            assertTrue(ContractSchema.accepts(sample["request"], schema.getAsJsonObject("request"), schema))
            assertTrue(ContractSchema.accepts(sample["response"], schema.getAsJsonObject("response"), schema))
            val response = DjResponseCodec.decode(sample["response"].toString()).getOrThrow()
            assertEquals(sample["result"].asString != "VALID", response.isFallback)
        }
    }

    @Test fun realServerMockAndFailureFallbacksPassExistingEditorialValidator() {
        cases.forEach { item ->
            val sample = item.asJsonObject
            val request = ContextJson.gson.fromJson(sample["request"], DjRequest::class.java)
            val now = Instant.parse(sample["now"].asString)
            val weather = request.context.weather?.let { WeatherReading(it.condition, it.observedAt, it.observedAt) }
            val snapshot = ContextEngine().snapshot(RadioSettings(), ContextInput(now, ZoneId.of("Asia/Seoul"), true,
                weather, LocationStatus.AVAILABLE), "server-contract")
            val block = RadioPlanner().plan(Simulation.fixtures().catalog, RadioSettings(), 1).block!!
            val key = RequestKey(1, request.generation, request.requestId, request.segmentId, snapshot.contextFingerprint)
            val plan = DjPlan(key, request, block, 3000, snapshot)
            val response = DjResponseCodec.decode(sample["response"].toString()).getOrThrow()
            assertNull(sample["name"].asString, DjResponseValidator.reject(response, plan, now))
            assertEquals(DjFailure.CONTEXT_MISMATCH,
                DjResponseValidator.reject(response.copy(generation = response.generation + 1), plan, now))
        }
    }

    @Test fun serverFallbackCannotReorderTracksOrIntroduceUnprovidedMusic() {
        val fallbacks = cases.filter { it.asJsonObject["response"].asJsonObject["isFallback"].asBoolean }
        assertEquals(3, fallbacks.size)
        fallbacks.forEach { item ->
            val sample = item.asJsonObject
            val request = ContextJson.gson.fromJson(sample["request"], DjRequest::class.java)
            val response = DjResponseCodec.decode(sample["response"].toString()).getOrThrow()
            assertEquals(LocalDjScripts.fallback(request), response)
        }
    }
}
