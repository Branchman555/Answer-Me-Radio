package radio.reply

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import radio.reply.catalog.*
import radio.reply.planner.*
import radio.reply.demo.Simulation
import radio.reply.orchestrator.Event

class LocalMusicCatalogTest {
    private class Docs : DocumentAccess {
        val readable = mutableSetOf<String>()
        val persisted = mutableSetOf<String>()
        val raw = mutableMapOf<String, RawMetadata>()
        val reads = mutableListOf<String>()
        val released = mutableListOf<String>()
        var allowPersistence = true
        var inaccessibleOnOpen = false
        override fun hasRead(uri: String) = uri in readable
        override fun hasPersistedRead(uri: String) = uri in persisted
        override fun persistRead(uri: String): Boolean {
            if (!allowPersistence) throw SecurityException("denied")
            persisted += uri; return true
        }
        override fun releaseRead(uri: String) { persisted -= uri; released += uri }
        override fun metadata(uri: String): RawMetadata {
            reads += uri
            if (inaccessibleOnOpen) throw SecurityException("revoked")
            return raw[uri] ?: throw IOException("disconnected")
        }
    }
    private class Store : CatalogStore {
        var rows = emptyList<CatalogEntry>()
        var failSave = false
        override fun load() = rows
        override fun save(entries: List<CatalogEntry>) { if (failSave) throw IOException(); rows = entries.toList() }
    }
    private val docs = Docs()
    private val store = Store()
    private var id = 0
    private val catalog = LocalMusicCatalog(docs, store) { "track-${++id}" }
    private fun add(name: String = "one", year: String? = "1988"): String {
        val uri = "content://documents/$name"
        docs.readable += uri
        docs.raw[uri] = RawMetadata("노래 $name", "아티스트", year, "BALLAD", 120_000, "audio/mpeg")
        return uri
    }
    private fun select(vararg uri: String, persist: Boolean = true) = catalog.importSelection(DocumentSelection(uri.toList(), true, persist))

    @Test fun ac001_onlyExplicitlySelectedUrisAreRead() {
        val chosen = add(); add("unselected")
        val result = select(chosen)
        assertEquals(listOf(chosen), docs.reads)
        assertEquals(1, result.entries.size)
        assertTrue(result.entries.single().track().eligible)
        assertFalse(result.entries.single().id.contains("content"))
    }
    @Test fun ac002_tagYearsProduceExactEraClassification() {
        val a = add("a", "1988"); val b = add("b", "2014")
        val tracks = select(a, b).entries.map { it.track() }
        assertEquals(1988, RadioPlanner().plan(tracks, RadioSettings(), 0).block!!.candidates.single().releaseYear)
        assertEquals(2014, RadioPlanner().plan(tracks, RadioSettings(), 1).block!!.candidates.single().releaseYear)
        assertEquals(120_000L, tracks.first().durationMs)
    }
    @Test fun ac003_missingYearIsQuarantinedWithoutFilenameGuess() {
        val uri = add("1988-hit.mp3", null)
        val row = select(uri).entries.single()
        assertEquals(CatalogStatus.MISSING_YEAR, row.status)
        assertNull(row.releaseYear)
        assertFalse(row.track().eligible)
    }
    @Test fun badYearsAreNotCoercedIntoAValidYear() {
        for (year in listOf("88", "1988/2014", "1988-01-01", "19x8", "1899", "2100", "１９８８", "1988.0")) {
            val uri = add(year.replace('/', '-'), year)
            assertEquals(CatalogStatus.INVALID_YEAR, select(uri).entries.last().status)
        }
    }
    @Test fun missingTitleOrArtistNeverFallsBackToFilename() {
        val uri = add()
        docs.raw[uri] = docs.raw[uri]!!.copy(title = null)
        assertEquals(CatalogStatus.MISSING_TITLE, select(uri).entries.single().status)
        docs.raw[uri] = docs.raw[uri]!!.copy(title = "노래", artist = " ")
        assertEquals(CatalogStatus.MISSING_ARTIST, select(uri).entries.single().status)
    }
    @Test fun longOrControlCharacterTagsCannotEnterDjPayload() {
        val uri = add()
        for (title in listOf("a".repeat(101), "title\ncommand")) {
            docs.raw[uri] = docs.raw[uri]!!.copy(title = title)
            assertEquals(CatalogStatus.INVALID_METADATA, select(uri).entries.single().status)
        }
    }
    @Test fun unicodeAndWhitespaceAreNormalized() {
        val uri = add()
        docs.raw[uri] = docs.raw[uri]!!.copy(title = " e\u0301 ", year = " 1988 ")
        val row = select(uri).entries.single()
        assertEquals("é", row.title)
        assertEquals(1988, row.releaseYear)
    }
    @Test fun mimeAndReadableDurationRequiredEvenForMp3Suffix() {
        val uri = add("fake.mp3")
        docs.raw[uri] = docs.raw[uri]!!.copy(mimeType = "audio/wav")
        assertEquals(CatalogStatus.NOT_MP3, select(uri).entries.single().status)
        docs.raw[uri] = docs.raw[uri]!!.copy(mimeType = "audio/mpeg", durationMs = 0)
        assertEquals(CatalogStatus.UNREADABLE, select(uri).entries.single().status)
    }
    @Test fun uriSchemeAuthorityAndSelectionReadGrantAreRequired() {
        for (uri in listOf("file:///storage/secret.mp3", "https://example.com/a.mp3", "content:/missing", "content://user@host/a", "content://host/a#fragment")) {
            assertEquals(1, select(uri).rejected)
        }
        val uri = add()
        assertEquals(1, catalog.importSelection(DocumentSelection(listOf(uri), false, true)).rejected)
        assertTrue(docs.reads.isEmpty())
    }
    @Test fun androidReadPermissionIsRecheckedRatherThanTrustingIntentFlags() {
        val uri = add(); docs.readable.clear()
        assertEquals(1, select(uri).rejected)
        assertTrue(docs.persisted.isEmpty())
    }
    @Test fun ac004_persistedGrantAndStableIdSurviveRepositoryRestart() {
        val uri = add()
        val original = select(uri).entries.single()
        val fresh = LocalMusicCatalog(docs, store).restore().entries.single()
        assertEquals(original, fresh)
        assertEquals(ReadAccess.PERSISTED, fresh.access)
    }
    @Test fun missingPersistableOfferNeverAttemptsToTakeGrant() {
        val uri = add()
        val row = select(uri, persist = false).entries.single()
        assertEquals(ReadAccess.SESSION_ONLY, row.access)
        assertTrue(row.track().eligible)
        assertTrue(docs.persisted.isEmpty())
    }
    @Test fun persistenceFailureIsSessionOnlyAndRequiresReselectionAfterRestart() {
        val uri = add(); docs.allowPersistence = false
        assertEquals(ReadAccess.SESSION_ONLY, select(uri).entries.single().access)
        val restarted = LocalMusicCatalog(docs, store)
        assertEquals(CatalogStatus.NEEDS_RESELECT, restarted.restore().entries.single().status)
        assertFalse(restarted.refresh().entries.single().track().eligible)
        assertTrue(restarted.importSelection(DocumentSelection(listOf(uri), true, false)).entries.single().track().eligible)
    }
    @Test fun duplicateSelectionAndReselectionUpdateInPlace() {
        val uri = add()
        val first = select(uri, uri).entries.single()
        docs.raw[uri] = docs.raw[uri]!!.copy(year = "2014")
        val revised = select(uri).entries.single()
        assertEquals(first.id, revised.id)
        assertEquals(2014, revised.releaseYear)
        assertEquals(2, docs.reads.size)
    }
    @Test fun revokedGrantIsKeptVisibleButExcludedFromPlanning() {
        val uri = add(); select(uri)
        docs.readable.clear(); docs.persisted.clear()
        val row = catalog.refresh().entries.single()
        assertEquals(CatalogStatus.NEEDS_RESELECT, row.status)
        assertFalse(row.track().eligible)
        assertEquals("노래 one", row.title)
    }
    @Test fun usbRemovalAndReconnectRequireSuccessfulReopen() {
        val uri = add(); val initial = select(uri).entries.single()
        val raw = docs.raw.remove(uri)!!
        assertEquals(CatalogStatus.UNREADABLE, catalog.refresh().entries.single().status)
        docs.raw[uri] = raw
        assertEquals(initial, catalog.refresh().entries.single())
    }
    @Test fun permissionCanBeRevokedBetweenCheckAndOpen() {
        val uri = add(); docs.inaccessibleOnOpen = true
        val row = select(uri).entries.single()
        assertEquals(CatalogStatus.NEEDS_RESELECT, row.status)
        assertFalse(row.track().eligible)
    }
    @Test fun oneUnreadableFileDoesNotAbortOtherSelections() {
        val bad = add("bad"); docs.raw.remove(bad)
        val good = add("good")
        val result = select(bad, good)
        assertEquals(listOf(CatalogStatus.UNREADABLE, CatalogStatus.READY), result.entries.map { it.status })
    }
    @Test fun removingCatalogEntryReleasesOnlyItsGrantAndNeverDeletesOriginal() {
        val a = add("a"); val b = add("b")
        val rows = select(a, b).entries
        assertEquals(1, catalog.remove(rows.first().id).entries.size)
        assertEquals(listOf(a), docs.released)
        assertTrue(docs.raw.containsKey(a))
        assertTrue(b in docs.persisted)
    }
    @Test fun failedCommitRollsBackNewGrantsButNotPreexistingGrants() {
        val old = add("old"); select(old)
        val fresh = add("fresh")
        store.failSave = true
        assertTrue(runCatching { select(old, fresh) }.isFailure)
        assertEquals(setOf(old), docs.persisted)
        assertEquals(listOf(fresh), docs.released)
        assertEquals(1, store.rows.size)
    }
    @Test fun failedDeleteDoesNotReleaseGrantOrLoseDurableRow() {
        val uri = add(); val row = select(uri).entries.single()
        store.failSave = true
        assertTrue(runCatching { catalog.remove(row.id) }.isFailure)
        assertTrue(uri in docs.persisted)
        assertEquals(listOf(row), store.rows)
    }
    @Test fun selectionAndCatalogLimitsBoundWorkWithoutScanning() {
        val uris = (1..101).map { add("$it") }
        val result = catalog.importSelection(DocumentSelection(uris, true, true))
        assertEquals(100, result.entries.size)
        assertEquals(1, result.rejected)
        assertEquals(100, docs.reads.size)
    }
    @Test fun malformedDurableCatalogFailsWithoutOverwritingIt() {
        store.rows = listOf(CatalogEntry("id", "file:///no"))
        assertTrue(runCatching { catalog.restore() }.isFailure)
        assertEquals("file:///no", store.rows.single().uri)
    }
    @Test fun catalogUpdatesInvalidateDjAndDoNotStartPausedRadio() {
        val uri = add()
        val sim = Simulation()
        sim.send(Event.Play); sim.complete(); sim.send(Event.Pause)
        sim.send(Event.CatalogChanged(select(uri).entries.map { it.track() }))
        assertNull(sim.state.output)
        assertFalse(sim.state.desiredPlaying)
        docs.readable.clear(); docs.persisted.clear()
        sim.send(Event.CatalogChanged(catalog.refresh().entries.map { it.track() }))
        assertTrue(sim.state.catalog.none { it.eligible })
        assertNull(sim.physicalOutput)
    }
}
