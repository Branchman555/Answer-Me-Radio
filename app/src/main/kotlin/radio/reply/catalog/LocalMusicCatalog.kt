package radio.reply.catalog

import java.net.URI
import java.text.Normalizer
import java.util.UUID
import radio.reply.planner.Track

enum class CatalogStatus { READY, MISSING_TITLE, MISSING_ARTIST, MISSING_YEAR, INVALID_YEAR,
    INVALID_METADATA, NOT_MP3, UNREADABLE, NEEDS_RESELECT }
enum class ReadAccess { PERSISTED, SESSION_ONLY, NONE }
data class RawMetadata(val title: String?, val artist: String?, val year: String?, val genre: String? = null,
    val durationMs: Long? = null, val mimeType: String? = null)
data class CatalogEntry(val id: String, val uri: String, val title: String = "", val artist: String = "",
    val releaseYear: Int? = null, val genre: String? = null, val durationMs: Long? = null,
    val status: CatalogStatus = CatalogStatus.NEEDS_RESELECT, val access: ReadAccess = ReadAccess.NONE) {
    fun track() = Track(id, title, artist, releaseYear, uri, accessible = access != ReadAccess.NONE,
        playable = status == CatalogStatus.READY, genre = genre, durationMs = durationMs)
}
data class DocumentSelection(val uris: List<String>, val readGranted: Boolean, val persistableOffered: Boolean)
data class CatalogResult(val entries: List<CatalogEntry>, val rejected: Int = 0)

interface DocumentAccess {
    fun hasRead(uri: String): Boolean
    fun hasPersistedRead(uri: String): Boolean
    fun persistRead(uri: String): Boolean
    fun releaseRead(uri: String)
    fun metadata(uri: String): RawMetadata
}
interface CatalogStore {
    fun load(): List<CatalogEntry>
    /** Must replace the entire document atomically, or throw without altering the previous document. */
    fun save(entries: List<CatalogEntry>)
}

object MetadataPolicy {
    fun normalize(entry: CatalogEntry, raw: RawMetadata, access: ReadAccess): CatalogEntry {
        fun text(value: String?) = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFC).trim()
        val title = text(raw.title)
        val artist = text(raw.artist)
        val year = raw.year?.trim().orEmpty()
        val parsed = year.takeIf { it.matches(Regex("[0-9]{4}")) }?.toIntOrNull()?.takeIf { it in 1900..2099 }
        val status = when {
            raw.mimeType != "audio/mpeg" -> CatalogStatus.NOT_MP3
            raw.durationMs == null || raw.durationMs <= 0 -> CatalogStatus.UNREADABLE
            title.isBlank() -> CatalogStatus.MISSING_TITLE
            artist.isBlank() -> CatalogStatus.MISSING_ARTIST
            listOf(title, artist).any { it.codePointCount(0, it.length) > 100 || it.any(Char::isISOControl) } -> CatalogStatus.INVALID_METADATA
            year.isEmpty() -> CatalogStatus.MISSING_YEAR
            parsed == null -> CatalogStatus.INVALID_YEAR
            else -> CatalogStatus.READY
        }
        return entry.copy(title = title.take(200), artist = artist.take(200), releaseYear = parsed,
            genre = raw.genre?.trim()?.takeIf { it.isNotBlank() && it.length <= 48 }, durationMs = raw.durationMs,
            status = status, access = access)
    }
}

/** Serialized by its owner; only user-selected document URIs enter this catalog. */
class LocalMusicCatalog(private val documents: DocumentAccess, private val store: CatalogStore,
    private val newId: () -> String = { UUID.randomUUID().toString() }) {
    companion object {
        const val MAX_ITEMS = 500
        const val MAX_SELECTION = 100
        fun validUri(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 4096 && uri.scheme == "content" && !uri.authority.isNullOrBlank()
                && uri.fragment == null && uri.userInfo == null
        }.getOrDefault(false)
    }
    private var entries: List<CatalogEntry>? = null
    private fun current(): List<CatalogEntry> = entries ?: store.load().also {
        require(it.size <= MAX_ITEMS && it.map { e -> e.uri }.distinct().size == it.size
            && it.map { e -> e.id }.distinct().size == it.size)
        require(it.all { e -> validUri(e.uri) && e.id.matches(Regex("[a-zA-Z0-9-]{1,100}")) })
        entries = it
    }

    @Synchronized fun restore(): CatalogResult {
        val refreshed = current().map { inspect(it, requirePersistent = true) }
        commit(refreshed)
        return CatalogResult(refreshed)
    }

    @Synchronized fun refresh(): CatalogResult {
        val refreshed = current().map { inspect(it, requirePersistent = it.access != ReadAccess.SESSION_ONLY) }
        commit(refreshed)
        return CatalogResult(refreshed)
    }

    @Synchronized fun importSelection(selection: DocumentSelection): CatalogResult {
        val before = current()
        if (!selection.readGranted) return CatalogResult(before, selection.uris.size)
        val acquired = mutableListOf<String>()
        val updated = before.toMutableList()
        var rejected = (selection.uris.distinct().size - MAX_SELECTION).coerceAtLeast(0)
        try {
            for (uri in selection.uris.distinct().take(MAX_SELECTION)) {
                if (!validUri(uri) || !runCatching { documents.hasRead(uri) }.getOrDefault(false)) { rejected++; continue }
                val index = updated.indexOfFirst { it.uri == uri }
                if (index < 0 && updated.size >= MAX_ITEMS) { rejected++; continue }
                val persisted = documents.hasPersistedRead(uri)
                if (!persisted && selection.persistableOffered && runCatching { documents.persistRead(uri) }.getOrDefault(false)) acquired += uri
                val entry = if (index >= 0) updated[index] else CatalogEntry(newId(), uri)
                val inspected = inspect(entry, requirePersistent = false)
                if (index >= 0) updated[index] = inspected else updated += inspected
            }
            commit(updated.toList())
        } catch (failure: Exception) {
            // Roll back only grants acquired by this operation; keep previously owned rights intact.
            acquired.forEach { runCatching { documents.releaseRead(it) } }
            throw failure
        }
        return CatalogResult(updated.toList(), rejected)
    }

    @Synchronized fun remove(id: String): CatalogResult {
        val old = current()
        val removed = old.firstOrNull { it.id == id } ?: return CatalogResult(old)
        val remaining = old.filterNot { it.id == id }
        commit(remaining) // Do not lose access to a row if the durable delete fails.
        runCatching { documents.releaseRead(removed.uri) }
        return CatalogResult(remaining)
    }

    private fun commit(value: List<CatalogEntry>) { store.save(value); entries = value }

    private fun inspect(entry: CatalogEntry, requirePersistent: Boolean): CatalogEntry {
        return try {
            val persisted = documents.hasPersistedRead(entry.uri)
            if ((requirePersistent && !persisted) || !documents.hasRead(entry.uri))
                entry.copy(status = CatalogStatus.NEEDS_RESELECT, access = ReadAccess.NONE)
            else MetadataPolicy.normalize(entry, documents.metadata(entry.uri),
                if (persisted) ReadAccess.PERSISTED else ReadAccess.SESSION_ONLY)
        } catch (_: SecurityException) {
            entry.copy(status = CatalogStatus.NEEDS_RESELECT, access = ReadAccess.NONE)
        } catch (_: Exception) {
            entry.copy(status = CatalogStatus.UNREADABLE, access = ReadAccess.NONE)
        }
    }
}
