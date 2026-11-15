package radio.reply.dj

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Instant
import radio.reply.context.*
import radio.reply.orchestrator.RequestKey
import radio.reply.planner.Era
import radio.reply.planner.MusicBlock
import radio.reply.planner.Track

enum class DjType { OPENING, ERA_BRIDGE, SAME_ERA_BRIDGE }
enum class Topic { ERA_TRANSITION, MUSIC_METADATA, WEATHER, GENERAL_NOSTALGIA, LIGHT_HUMOR }
data class DjWeather(val condition: WeatherCondition, val observedAt: String, val valid: Boolean = true)
data class DjContext(val timeOfDay: TimeOfDay, val weather: DjWeather?, val mode: String,
    val previousEra: String?, val nextEra: String?)
data class DjTrack(val id: String, val title: String, val artist: String, val year: Int)
data class DjRequest(
    val schemaVersion: String = "1.0", val requestId: String, val sessionId: String, val generation: Long,
    val segmentId: String, val type: DjType, val personaId: String = "retro_fm_90_v1",
    val locale: String = "ko-KR", val maxSpokenSeconds: Int, val context: DjContext,
    val previousTracks: List<DjTrack>, val nextTracks: List<DjTrack>, val recentTopics: List<Topic>,
)
data class Delivery(val tone: String = "WARM", val pace: String = "RELAXED")
data class DjResponse(
    val schemaVersion: String = "1.0", val requestId: String, val sessionId: String, val generation: Long,
    val segmentId: String, val type: DjType, val personaId: String = "retro_fm_90_v1",
    val text: String, val topic: Topic, val delivery: Delivery, val groundingFields: List<String>, val isFallback: Boolean,
)
enum class DjFailure { JSON_INVALID, SCHEMA_INVALID, CONTEXT_MISMATCH, WEATHER_UNGROUNDED,
    FACT_UNGROUNDED, CONTENT_UNSAFE, DURATION_EXCEEDED, AI_TIMEOUT, PROVIDER_FAILED, NOT_READY }
enum class RequestStatus { PENDING, READY, FAILED }
data class DjPlan(val key: RequestKey, val request: DjRequest, val nextBlock: MusicBlock,
    val deadlineMonotonic: Long, val snapshot: ContextSnapshot, val status: RequestStatus = RequestStatus.PENDING,
    val response: DjResponse? = null, val failure: DjFailure? = null)

object ScriptContextBuilder {
    fun project(snapshot: ContextSnapshot, previousEra: Era?, nextEra: Era?, now: Instant): DjContext {
        val weather = snapshot.weather?.takeIf { WeatherValidityPolicy.valid(it, now, snapshot.simulationMode) }
        return DjContext(snapshot.timeOfDay, weather?.let { DjWeather(it.condition, it.observedAt) },
            snapshot.mode, previousEra?.label, nextEra?.label)
    }

    fun request(key: RequestKey, snapshot: ContextSnapshot, previousEra: Era?, nextBlock: MusicBlock,
        previousTracks: List<Track>, now: Instant, recentTopics: List<Topic> = emptyList()): DjRequest {
        val type = when (previousEra) { null -> DjType.OPENING; nextBlock.era -> DjType.SAME_ERA_BRIDGE; else -> DjType.ERA_BRIDGE }
        return DjRequest(requestId = key.requestId, sessionId = key.sessionId.toString(), generation = key.generation,
            segmentId = key.segmentId, type = type, maxSpokenSeconds = if (type == DjType.OPENING) 25 else 20,
            context = project(snapshot, previousEra, nextBlock.era, now),
            previousTracks = previousTracks.takeLast(3).map(::track),
            nextTracks = nextBlock.candidates.take(nextBlock.targetCompleted).map(::track), recentTopics = recentTopics.takeLast(3))
    }

    private fun track(track: Track): DjTrack {
        require(track.eligible && track.id.isNotBlank() && track.id.length <= 100)
        require(!track.id.contains(Regex("[:/\\\\]"))) { "NON_OPAQUE_TRACK_ID" }
        return DjTrack(track.id, metadata(track.title), metadata(track.artist), track.releaseYear!!)
    }
    private fun metadata(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).filterNot { it.isISOControl() }.trim()
        require(normalized.isNotBlank() && normalized.codePointCount(0, normalized.length) <= 100)
        return normalized
    }
}

/** Known local text only in RR-002. Unrestricted model prose requires RR-006 editorial validation. */
object LocalDjScripts {
    fun fallback(request: DjRequest): DjResponse {
        val era = request.context.nextEra?.replace("s", "년대") ?: "선택하신 시대의"
        val text = when (request.type) {
            DjType.OPENING -> "반갑습니다. 응답하라 라디오입니다. ${era} 음악부터 차례로 만나보겠습니다. 편안하게 함께해 주세요."
            DjType.ERA_BRIDGE -> "이제 ${era} 음악으로 이어가겠습니다. 다음 곡도 함께하시죠."
            DjType.SAME_ERA_BRIDGE -> "계속해서 ${era} 음악을 들려드리겠습니다. 다음 곡도 함께하시죠."
        }
        return response(request, text, Topic.ERA_TRANSITION, listOf("context.nextEra"), true)
    }

    fun mock(request: DjRequest, simulation: Boolean): DjResponse {
        val base = fallback(request)
        val condition = request.context.weather?.condition ?: return base.copy(isFallback = false)
        val weatherText = when (condition) {
            WeatherCondition.RAIN -> "비 소식이 있네요. "
            WeatherCondition.SUN -> "맑은 날씨 소식입니다. "
            WeatherCondition.CLOUD -> "구름 낀 날씨 소식입니다. "
            WeatherCondition.SNOW -> "눈 소식이 있네요. "
            WeatherCondition.UNKNOWN -> return base.copy(isFallback = false)
        }
        // The v0.5 API has no simulation field. The local provider must label simulated speech itself.
        return response(request, (if (simulation) "모의 날씨입니다. " else "") + weatherText + base.text,
            Topic.WEATHER, listOf("context.weather.condition", "context.nextEra"), false)
    }

    private fun response(r: DjRequest, text: String, topic: Topic, fields: List<String>, fallback: Boolean) =
        DjResponse(requestId = r.requestId, sessionId = r.sessionId, generation = r.generation,
            segmentId = r.segmentId, type = r.type, text = text, topic = topic, delivery = Delivery(),
            groundingFields = fields, isFallback = fallback)
}

class InvalidDjJson(cause: Throwable) : IllegalArgumentException("JSON_INVALID", cause)

object DjResponseCodec {
    fun decode(json: String): Result<DjResponse> = runCatching {
        val parsed = runCatching { StrictJson.parse(json) }.getOrElse { throw InvalidDjJson(it) }
        val value = parsed.asJsonObject
        require(value.keySet() == setOf("schemaVersion", "requestId", "sessionId", "generation", "segmentId", "type",
            "personaId", "text", "topic", "delivery", "groundingFields", "isFallback"))
        fun string(key: String, min: Int = 1, max: Int = 100): String {
            val v = value[key]
            require(v.isJsonPrimitive && v.asJsonPrimitive.isString)
            return v.asString.also { require(it.codePointCount(0, it.length) in min..max) }
        }
        require(string("schemaVersion") == "1.0" && string("personaId") == "retro_fm_90_v1")
        val generationValue = value["generation"].asJsonPrimitive
        require(generationValue.isNumber)
        val generation = generationValue.asBigDecimal.longValueExact().also { require(it >= 0) }
        val delivery = value.getAsJsonObject("delivery")
        require(delivery.keySet() == setOf("tone", "pace"))
        require(delivery["tone"].asJsonPrimitive.isString && delivery["pace"].asJsonPrimitive.isString)
        require(delivery["tone"].asString in setOf("WARM", "CALM", "BRIGHT"))
        require(delivery["pace"].asString in setOf("RELAXED", "NORMAL"))
        val fields = value.getAsJsonArray("groundingFields").map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString
        }
        val pattern = Regex("^(context\\.(timeOfDay|previousEra|nextEra|weather\\.condition)|previousTracks\\[[0-2]\\]\\.(title|artist|year)|nextTracks\\[[0-2]\\]\\.(title|artist|year))$")
        require(fields.size <= 12 && fields.distinct().size == fields.size && fields.all { pattern.matches(it) })
        require(value["isFallback"].asJsonPrimitive.isBoolean)
        DjResponse(requestId = string("requestId"), sessionId = string("sessionId"), generation = generation,
            segmentId = string("segmentId"), type = DjType.valueOf(string("type")), text = string("text", 10, 160),
            topic = Topic.valueOf(string("topic")), delivery = Delivery(delivery["tone"].asString, delivery["pace"].asString),
            groundingFields = fields, isFallback = value["isFallback"].asBoolean)
    }
}

object DjResponseValidator {
    fun reject(response: DjResponse, plan: DjPlan, now: Instant): DjFailure? {
        val r = plan.request
        if (response.requestId != r.requestId || response.sessionId != r.sessionId || response.generation != r.generation
            || response.segmentId != r.segmentId || response.type != r.type || response.personaId != r.personaId) return DjFailure.CONTEXT_MISMATCH
        if (response.topic == Topic.WEATHER || "context.weather.condition" in response.groundingFields) {
            val weather = plan.snapshot.weather ?: return DjFailure.WEATHER_UNGROUNDED
            if (!WeatherValidityPolicy.valid(weather, now, plan.snapshot.simulationMode)) return DjFailure.WEATHER_UNGROUNDED
        }
        val approved = listOf(LocalDjScripts.fallback(r), LocalDjScripts.mock(r, plan.snapshot.simulationMode))
        // Matching a claimed grounding path never authorizes arbitrary text or unseen facts.
        if (approved.none { it.text == response.text && it.topic == response.topic && it.groundingFields == response.groundingFields })
            return DjFailure.FACT_UNGROUNDED
        return null
    }
}

/** Strict JSON, including duplicate-key and trailing-data rejection; bounded before recursion. */
object StrictJson {
    fun parse(json: String): JsonElement {
        require(json.length <= 32_768)
        val reader = JsonReader(StringReader(json))
        fun read(depth: Int): JsonElement {
            require(depth <= 16)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) { val key = reader.nextName(); require(!has(key)); add(key, read(depth + 1)) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                    reader.beginArray(); while (reader.hasNext()) add(read(depth + 1)); reader.endArray()
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> error("JSON_INVALID")
            }
        }
        return reader.use { read(0).also { require(reader.peek() == JsonToken.END_DOCUMENT) } }
    }
}
