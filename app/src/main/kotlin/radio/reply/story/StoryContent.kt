package radio.reply.story

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

enum class Review { PENDING, APPROVED, REVOKED }
enum class MusicBasis { BROADCAST_VERIFIED, CHART_VERIFIED, RELEASE_PERIOD, ERA_ONLY }
data class Fact(val id: String, val text: String, val sourceIds: Set<String>)
data class MusicAssociation(
    val id: String, val title: String, val artist: String, val releaseYear: Int,
    val releaseMonth: Int?, val basis: MusicBasis, val sourceIds: Set<String>,
    val localTrackId: String?, val publicPlaybackRightsConfirmed: Boolean,
)
data class StoryScript(
    val id: String, val variant: String, val text: String,
    val factIds: Set<String>, val musicAssociationId: String, val review: Review,
)
data class StoryPack(
    val packId: String, val approved: Boolean, val storyId: String, val canonicalEventId: String,
    val contentVersion: Int, val placeName: String, val eventDate: LocalDate, val publicationDate: LocalDate,
    val segmentRef: String?, val geometryVerified: Boolean, val review: Review, val rightsReviewed: Boolean,
    val facts: List<Fact>, val music: List<MusicAssociation>, val scripts: List<StoryScript>,
)

/** Strict local contract reader; approving JSON is not editorial or geographic verification. */
object StoryContentReader {
    fun read(json: String, knownSourceIds: Set<String>): Result<StoryPack> = runCatching {
        require(json.length <= 100_000) { "PACK_TOO_LARGE" }
        val root = JsonParser.parseString(json).asJsonObject
        root.fields("schemaVersion packId status story")
        require(root.string("schemaVersion") == "0.8")
        val status = root.string("status").also { require(it in setOf("DRAFT", "APPROVED")) }
        val s = root.getAsJsonObject("story")
        s.fields("storyId canonicalEventId contentVersion placeName eventDate publicationDate segmentRef geometryVerified editorialStatus contentRightsStatus facts musicAssociations scripts")
        val facts = s.getAsJsonArray("facts").map { value ->
            val f = value.asJsonObject
            f.fields("id text sourceIds")
            Fact(f.string("id"), f.string("text", 500), f.ids("sourceIds", knownSourceIds))
        }
        val music = s.getAsJsonArray("musicAssociations").map { value ->
            val m = value.asJsonObject
            m.fields("id title artist releaseYear releaseMonth basis evidenceSourceIds localTrackId publicPlaybackRightsConfirmed")
            MusicAssociation(m.string("id"), m.string("title"), m.string("artist"),
                m.integer("releaseYear", 1900..2100), m.nullableInt("releaseMonth", 1..12),
                MusicBasis.valueOf(m.string("basis")), m.ids("evidenceSourceIds", knownSourceIds),
                m.nullableString("localTrackId"), m.boolean("publicPlaybackRightsConfirmed"))
        }
        val scripts = s.getAsJsonArray("scripts").map { value ->
            val t = value.asJsonObject
            t.fields("id variant text factIds musicAssociationId editorialStatus")
            StoryScript(t.string("id"), t.string("variant").also { require(it in setOf("LONG", "SHORT")) },
                t.string("text", 600), t.ids("factIds", facts.map { it.id }.toSet(), false),
                t.string("musicAssociationId").also { require(it in music.map { m -> m.id }) },
                Review.valueOf(t.string("editorialStatus")))
        }
        require(facts.isNotEmpty() && music.isNotEmpty() && scripts.isNotEmpty())
        require(facts.distinctBy { it.id }.size == facts.size && music.distinctBy { it.id }.size == music.size
            && scripts.distinctBy { it.id }.size == scripts.size)
        val rights = s.string("contentRightsStatus").also { require(it in setOf("UNCONFIRMED", "REVIEWED", "REVOKED")) }
        StoryPack(root.string("packId"), status == "APPROVED", s.string("storyId"), s.string("canonicalEventId"),
            s.integer("contentVersion", 1..Int.MAX_VALUE), s.string("placeName"),
            LocalDate.parse(s.string("eventDate")), LocalDate.parse(s.string("publicationDate")),
            s.nullableString("segmentRef"), s.boolean("geometryVerified"), Review.valueOf(s.string("editorialStatus")),
            rights == "REVIEWED", facts, music, scripts)
    }

    private fun JsonObject.fields(names: String) { require(keySet() == names.split(' ').toSet()) { "INVALID_FIELDS" } }
    private fun JsonElement.strictString(): String {
        require(isJsonPrimitive && asJsonPrimitive.isString)
        return asString
    }
    private fun JsonObject.string(key: String, max: Int = 1000) = get(key).strictString().also {
        require(it.isNotBlank() && it.length <= max)
    }
    private fun JsonObject.nullableString(key: String) = if (get(key).isJsonNull) null else string(key)
    private fun JsonObject.boolean(key: String): Boolean {
        require(get(key).isJsonPrimitive && get(key).asJsonPrimitive.isBoolean)
        return get(key).asBoolean
    }
    private fun JsonObject.integer(key: String, range: IntRange): Int {
        val value = get(key)
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        val exact = value.asBigDecimal.intValueExact()
        require(exact in range)
        return exact
    }
    private fun JsonObject.nullableInt(key: String, range: IntRange) = if (get(key).isJsonNull) null else integer(key, range)
    private fun JsonObject.ids(key: String, known: Set<String>, source: Boolean = true): Set<String> {
        val ids = getAsJsonArray(key).map { it.strictString() }
        require(ids.isNotEmpty() && ids.size == ids.distinct().size)
        require(ids.all { it in known && (!source || it.matches(Regex("S[0-9]+"))) })
        return ids.toSet()
    }
}
