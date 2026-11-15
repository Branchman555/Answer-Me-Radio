package radio.reply.catalog

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import radio.reply.R

class CatalogInstrumentation : Instrumentation() {
    private var stage = "write"
    private var passed = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); stage = arguments?.getString("stage") ?: "write"; start() }
    override fun onStart() {
        try {
            when (stage) { "write" -> writeTests(); "restore" -> restoreTests(); "picker" -> pickerTest(); "playback" -> radio.reply.playback.PlaybackChecks(this).run(); "voiceux" -> radio.reply.playback.VoiceUxChecks(this).run(); "ui002" -> radio.reply.ui.UiPolishInstrumentation(this).run(); else -> error("INVALID_STAGE") }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "RR003_PASS_COUNT=$passed STAGE=$stage\n") })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "RR003_FAILED $stage ${failure.javaClass.simpleName}: ${failure.message}\n${failure.stackTrace.take(5).joinToString("\n")}\n") })
        }
    }
    internal fun test(name: String, action: () -> Unit) {
        sendStatus(0, Bundle().apply { putString("stream", "RUN $name\n") })
        action(); passed++
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }
    internal fun helper(operation: String, names: List<String> = emptyList()) {
        val signal = CountDownLatch(1)
        var code = 0
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code = resultCode; signal.countDown() }
        }
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, FixtureGrantActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("operation", operation)
            .putStringArrayListExtra("names", ArrayList(names)).putExtra("reply", receiver))
        check(signal.await(10, TimeUnit.SECONDS) && code == 1) { "TEST_HELPER_FAILED operation=$operation code=$code" }
    }
    private fun selected(vararg names: String) = DocumentSelection(names.map { FixtureFiles.uri(it).toString() }, true, true)
    private fun store() = AndroidCatalogStore(targetContext, "rr003-native-persist.json")
    private fun writeTests() {
        helper("reset"); helper("revoke", FixtureFiles.names)
        val documents = AndroidDocumentAccess(targetContext)
        test("picker_contract_local_only_read_only_multiple") {
            val intent = SafSelection.intent()
            check(intent.action == Intent.ACTION_OPEN_DOCUMENT && intent.type == "audio/mpeg")
            check(intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false) && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            check(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
            check(intent.categories.contains(Intent.CATEGORY_OPENABLE))
        }
        test("cancel_and_clipdata_results") {
            check(SafSelection.result(Activity.RESULT_CANCELED, Intent()) == null)
            val clip = ClipData.newRawUri("synthetic", FixtureFiles.uri("song1"))
            clip.addItem(ClipData.Item(FixtureFiles.uri("song2")))
            val result = SafSelection.result(Activity.RESULT_OK, Intent().apply {
                clipData = clip; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })!!
            check(result.uris.size == 2 && result.readGranted && result.persistableOffered)
        }
        test("unknown_detail_access_is_denied") { check(!CatalogActivity.allowed(true, false)); check(!CatalogActivity.allowed(false, true)) }
        helper("grant", FixtureFiles.names)
        store().save(emptyList())
        val catalog = LocalMusicCatalog(documents, store())
        test("six_real_mp3_metadata_and_persisted_read_grants") {
            val rows = catalog.importSelection(selected("song1", "song2", "song3", "song4", "song5", "song6")).entries
            check(rows.size == 6 && rows.all { it.status == CatalogStatus.READY && it.access == ReadAccess.PERSISTED }) {
                "METADATA_STATUSES=" + rows.map { "${it.status}:${it.releaseYear}:${it.durationMs}" }
            }
            check(rows.map { it.releaseYear } == listOf(1988, 2014, 1988, 2014, 1988, 2014))
            check(rows.first().title == "검증 곡 song1" && rows.first().artist == "합성 테스트")
            check(rows.all { (it.durationMs ?: 0) > 0 })
        }
        test("missing_year_and_broken_files_are_individual_failures") {
            val rows = catalog.importSelection(selected("missing-year", "bad-year", "broken", "wav")).entries
            check(rows.first { it.uri == FixtureFiles.uri("missing-year").toString() }.status == CatalogStatus.MISSING_YEAR)
            check(rows.first { it.uri == FixtureFiles.uri("bad-year").toString() }.status in setOf(CatalogStatus.MISSING_YEAR, CatalogStatus.INVALID_YEAR))
            check(rows.first { it.uri == FixtureFiles.uri("broken").toString() }.status == CatalogStatus.UNREADABLE)
            check(rows.first { it.uri == FixtureFiles.uri("wav").toString() }.status == CatalogStatus.NOT_MP3)
            check(rows.count { it.track().eligible } == 6)
        }
        test("native_store_roundtrip_stable_ids_and_no_backup_path") {
            val original = catalog.refresh().entries
            val loaded = LocalMusicCatalog(documents, store()).restore().entries
            check(original == loaded)
            check(File(targetContext.noBackupFilesDir, "rr003-native-persist.json").exists())
            check(!File(targetContext.filesDir, "rr003-native-persist.json").exists())
        }
        test("corrupt_store_is_not_silently_overwritten") {
            val file = File(targetContext.noBackupFilesDir, "rr003-corrupt.json")
            file.writeText("{broken")
            check(runCatching { AndroidCatalogStore(targetContext, file.name).load() }.isFailure)
            check(file.readText() == "{broken")
            check(file.delete())
        }
    }
    private fun restoreTests() {
        val docs = AndroidDocumentAccess(targetContext)
        val catalog = LocalMusicCatalog(docs, store())
        var original = emptyList<CatalogEntry>()
        test("persisted_rights_survive_target_process_death") {
            original = catalog.restore().entries
            check(original.size == 10 && original.count { it.track().eligible } == 6)
            check(original.filter { it.track().eligible }.all { docs.hasPersistedRead(it.uri) })
        }
        test("revoked_rights_require_reselection") {
            helper("revoke", listOf("song1"))
            check(catalog.refresh().entries.first().status == CatalogStatus.NEEDS_RESELECT)
        }
        test("reselection_keeps_id_and_does_not_duplicate") {
            helper("grant", listOf("song1"))
            val rows = catalog.importSelection(selected("song1")).entries
            check(rows.first().id == original.first().id && rows.size == original.size && rows.first().track().eligible)
        }
        test("deleted_document_is_excluded_after_reopen") {
            helper("delete", listOf("song2"))
            check(catalog.refresh().entries[1].status == CatalogStatus.UNREADABLE)
        }
        test("remove_releases_persisted_grant_and_keeps_source_file") {
            val row = original.first()
            catalog.remove(row.id)
            check(!docs.hasPersistedRead(row.uri))
            helper("grant", listOf("song1"))
            check(docs.metadata(row.uri).title == "검증 곡 song1")
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = uiAutomation.rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { list += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(root); return list
    }
    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(30) { nodes().firstOrNull(predicate)?.let { return it }; Thread.sleep(100) }
        error("UI_NODE_MISSING: " + nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.joinToString(" | ").take(1200))
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        error("UI_CLICK_FAILED")
    }
    private fun pickerTest() {
        fun progress(value: String) { sendStatus(0, Bundle().apply { putString("stream", "PICKER_STEP=$value\n") }) }
        progress("reset")
        helper("reset"); helper("revoke", FixtureFiles.names)
        AndroidCatalogStore(targetContext).save(emptyList())
        val docs = AndroidDocumentAccess(targetContext)
        test("system_picker_and_actual_catalog_activity_import") {
            check(!docs.hasRead(FixtureFiles.uri("song3").toString()))
            progress("launch_catalog")
            val activity = startActivitySync(Intent(targetContext, CatalogActivity::class.java)
                .putExtra(CatalogActivity.DEVELOPMENT_ACCESS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            progress("open_picker")
            click(awaitNode { it.text?.toString() == targetContext.getString(R.string.catalog_add) && it.isEnabled })
            awaitNode { it.packageName?.toString()?.contains("documentsui") == true }
            progress("select_root")
            // DocumentsUI's drawer exposes the test-only local provider.
            if (nodes().none { it.text?.toString() == "RR003 fixtures" }) {
                click(awaitNode { val desc = it.contentDescription?.toString().orEmpty()
                    desc.contains("Show roots", true) || desc.contains("Open navigation", true) || desc.contains("루트") })
                click(awaitNode { it.text?.toString() == "RR003 fixtures" })
            }
            // DocumentsUI remembers both the directory and grid/list mode between launches.
            nodes().firstOrNull { it.contentDescription?.toString() == "List view" }?.let(::click)
            progress("select_file")
            click(awaitNode { it.text?.toString() == "RR003 song3.mp3" })
            awaitNode { it.text?.toString()?.contains("검증 곡 song3") == true }
            progress("verify_import")
            check(docs.hasPersistedRead(FixtureFiles.uri("song3").toString()))
            check(!docs.hasRead(FixtureFiles.uri("song4").toString()))
            val rows = CatalogRuntime.get(targetContext).io.submit<CatalogResult> { CatalogRuntime.get(targetContext).load() }.get(10, TimeUnit.SECONDS).entries
            check(rows.any { it.title == "검증 곡 song3" && it.status == CatalogStatus.READY })
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.noBackupFilesDir, "rr003-catalog.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            runOnMainSync { activity.finish() }
        }
    }
}
