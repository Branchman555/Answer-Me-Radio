package radio.reply.planner

import java.security.MessageDigest

data class Era(val startYear: Int) {
    init { require(startYear in 1900..2090 && startYear % 10 == 0) }
    val label get() = "${startYear}s"
    operator fun contains(year: Int?) = year != null && year in startYear..startYear + 9
}

data class Track(
    val id: String, val title: String, val artist: String, val releaseYear: Int?,
    val contentUri: String, val accessible: Boolean = true, val playable: Boolean = true,
    val genre: String? = null,
    val durationMs: Long? = null,
) {
    val eligible get() = accessible && playable && title.isNotBlank() && artist.isNotBlank()
        && releaseYear in 1900..2100
}

enum class Mode { SINGLE, FAMILY }

data class RadioSettings(
    val eras: List<Era> = listOf(Era(1980), Era(2010)),
    val djEnabled: Boolean = true,
    val roadTimeTravelEnabled: Boolean = false,
    val roadLocationConsent: Boolean = false,
    val preferredGenres: Set<String> = emptySet(),
    val seed: Long = 8,
    val tripIntent: radio.reply.context.TripIntent = radio.reply.context.TripIntent.NONE,
) {
    init { require(eras.size in 1..2) }
    val normalizedEras get() = eras.distinct()
    val mode get() = if (normalizedEras.size == 1) Mode.SINGLE else Mode.FAMILY
}

data class MusicBlock(val era: Era, val candidates: List<Track>) {
    val targetCompleted = minOf(3, candidates.size)
}

data class PlannedBlock(val block: MusicBlock?, val nextEraCursor: Int, val missingEras: List<Era>)

class RadioPlanner {
    fun plan(
        tracks: List<Track>, settings: RadioSettings, eraCursor: Int,
        recentIds: List<String> = emptyList(),
    ): PlannedBlock {
        val eras = settings.normalizedEras
        val missing = eras.filter { era -> tracks.none { it.eligible && it.releaseYear in era } }
        for (offset in eras.indices) {
            val index = (eraCursor + offset) % eras.size
            val era = eras[index]
            val candidates = rank(tracks.filter { it.eligible && it.releaseYear in era }, settings, recentIds)
            if (candidates.isNotEmpty()) return PlannedBlock(MusicBlock(era, candidates), (index + 1) % eras.size, missing)
        }
        return PlannedBlock(null, eraCursor, missing)
    }

    private fun rank(tracks: List<Track>, settings: RadioSettings, recentIds: List<String>): List<Track> {
        val recentArtists = tracks.filter { it.id in recentIds.takeLast(3) }.map { it.artist }.toSet()
        // Era is already a hard filter. Missing mood metadata contributes no invented score.
        return tracks.distinctBy { it.id }.sortedWith(
            compareBy<Track> { it.id == recentIds.lastOrNull() }
                .thenBy { it.id in recentIds.takeLast(3) }
                .thenByDescending { it.genre != null && it.genre in settings.preferredGenres }
                .thenBy { it.artist in recentArtists }
                .thenBy { stableOrder(settings.seed, it.id) }.thenBy { it.id }
        )
    }

    private fun stableOrder(seed: Long, id: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$seed:$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
