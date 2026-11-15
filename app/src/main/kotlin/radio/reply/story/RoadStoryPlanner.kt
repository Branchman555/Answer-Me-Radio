package radio.reply.story

import java.time.LocalDate
import radio.reply.planner.Track

enum class MatchStatus { CONFIRMED, UNKNOWN, OUTSIDE }

/** A local matcher result, never raw coordinates or a planned-route assertion. */
data class SegmentMatch(
    val segmentId: String?, val revision: Long, val status: MatchStatus,
    val measuredAtMonotonic: Long, val expiresAtMonotonic: Long,
    val geometryVerified: Boolean = false, val disambiguated: Boolean = false,
    val accurateConsecutiveSamples: Int = 0,
) {
    fun valid(now: Long) = status == MatchStatus.CONFIRMED && !segmentId.isNullOrBlank()
        && geometryVerified && disambiguated && accurateConsecutiveSamples >= 2
        && measuredAtMonotonic in 0..now && now - measuredAtMonotonic <= 10_000
        && now < expiresAtMonotonic
}

data class StoryPlan(val pack: StoryPack, val script: StoryScript, val tracks: List<Track>, val matchRevision: Long) {
    val sourceIds get() = pack.facts.flatMap { it.sourceIds }.toSet()
}

class RoadStoryPlanner {
    fun select(
        packs: List<StoryPack>, tracks: List<Track>, match: SegmentMatch?, now: Long,
        localDate: LocalDate?, attemptedEvents: Set<String>, remainingSeconds: Long? = null,
    ): StoryPlan? {
        if (match == null || !match.valid(now) || (remainingSeconds != null && remainingSeconds < 30)) return null
        // Newer corrections/revocations shadow previous versions before any eligibility filtering.
        val latest = packs.groupBy { it.storyId }.values.mapNotNull { versions ->
            val newest = versions.filter { it.contentVersion == versions.maxOf { p -> p.contentVersion } }.distinct()
            newest.singleOrNull() // Conflicting identical versions fail closed.
        }
        return latest.asSequence().filter {
            it.approved && it.review == Review.APPROVED && it.rightsReviewed && it.geometryVerified
                && it.segmentRef == match.segmentId && it.canonicalEventId !in attemptedEvents
        }.sortedWith(compareByDescending<StoryPack> {
            localDate != null && it.eventDate.month == localDate.month && it.eventDate.dayOfMonth == localDate.dayOfMonth
        }.thenBy { it.storyId }).mapNotNull { pack ->
            val variant = if (remainingSeconds != null && remainingSeconds < 180) "SHORT" else "LONG"
            val pairs = pack.music.mapNotNull { association ->
                // v0.8 has no broadcast name/date or chart/period fields. Do not invent those labels.
                if (association.basis != MusicBasis.RELEASE_PERIOD || !association.publicPlaybackRightsConfirmed
                    || association.releaseYear != pack.eventDate.year) return@mapNotNull null
                val track = tracks.firstOrNull { it.id == association.localTrackId && it.eligible
                    && it.releaseYear == pack.eventDate.year && it.title == association.title && it.artist == association.artist }
                track?.let { association to it }
            }
            val script = pack.scripts.firstOrNull { script ->
                script.review == Review.APPROVED && script.variant == variant
                    && pairs.any { it.first.id == script.musicAssociationId }
                    && script.factIds.isNotEmpty() && script.factIds.all { id -> pack.facts.any { it.id == id } }
            } ?: return@mapNotNull null
            val introduced = pairs.first { it.first.id == script.musicAssociationId }.second
            val chosen = (listOf(introduced) + pairs.map { it.second }).distinctBy { it.id }.take(3)
            StoryPlan(pack, script, chosen, match.revision)
        }.firstOrNull()
    }

    fun label(association: MusicAssociation): String? = when (association.basis) {
        MusicBasis.RELEASE_PERIOD -> if (association.releaseMonth == null) "같은 해 발표곡" else "${association.releaseYear}년 ${association.releaseMonth}월 발표곡"
        else -> null // More precise claims need a separately reviewed evidence contract.
    }
}
