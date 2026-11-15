package radio.reply.catalog

import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import radio.reply.R

/** Internal development screen. No speed-based or assumed vehicle UX authorization. */
class CatalogActivity : Activity() {
    private lateinit var runtime: CatalogRuntime
    private lateinit var rows: LinearLayout
    private lateinit var message: TextView
    private lateinit var add: Button
    private lateinit var refresh: Button
    private var alive = false
    private var busy = false
    private var pickerPending = false
    private var generation = 0
    companion object {
        const val DEVELOPMENT_ACCESS = "developmentCatalog"
        private const val PICK_DOCUMENTS = 3003
        fun allowed(debuggable: Boolean, explicitlyDevelopment: Boolean) = debuggable && explicitlyDevelopment
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allowed(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                intent.getBooleanExtra(DEVELOPMENT_ACCESS, false))) { finish(); return }
        alive = true
        pickerPending = savedInstanceState?.getBoolean("pickerPending") ?: false
        runtime = CatalogRuntime.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        message = TextView(this).apply { setText(R.string.catalog_scope); textSize = 18f }
        root.addView(message)
        add = Button(this).apply {
            setText(R.string.catalog_add)
            setOnClickListener {
                if (!busy && !pickerPending) {
                    pickerPending = true; updateButtons()
                    try { startActivityForResult(SafSelection.intent(), PICK_DOCUMENTS) }
                    catch (_: ActivityNotFoundException) {
                        pickerPending = false; message.setText(R.string.catalog_picker_missing); updateButtons()
                    }
                }
            }
        }
        refresh = Button(this).apply { setText(R.string.catalog_refresh); setOnClickListener { work { runtime.load() } } }
        root.addView(add); root.addView(refresh)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        work { runtime.load() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pickerPending", pickerPending)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform callback retained without adding a new activity framework dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DOCUMENTS || !pickerPending || !alive) return
        pickerPending = false
        val selection = SafSelection.result(resultCode, data)
        if (selection == null) { message.setText(R.string.catalog_cancelled); updateButtons(); return }
        work { runtime.catalog.importSelection(selection) }
    }
    private fun work(action: () -> CatalogResult) {
        val requestGeneration = ++generation
        busy = true; updateButtons(); message.setText(R.string.catalog_loading)
        // Serial IO; obsolete view callbacks cannot update a recreated screen.
        runtime.io.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (!alive || isFinishing || isDestroyed || requestGeneration != generation) return@runOnUiThread
                busy = false; updateButtons()
                result.fold(::render) { message.setText(R.string.catalog_failure) }
            }
        }
    }
    private fun updateButtons() { add.isEnabled = !busy && !pickerPending; refresh.isEnabled = !busy && !pickerPending }
    private fun render(result: CatalogResult) {
        message.text = getString(R.string.catalog_summary, result.entries.size, result.entries.count { it.track().eligible }, result.rejected)
        rows.removeAllViews()
        for (entry in result.entries) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = getString(R.string.catalog_entry, entry.title.ifBlank { getString(R.string.catalog_unknown_title) },
                    entry.artist.ifBlank { getString(R.string.catalog_unknown_artist) }, entry.releaseYear?.toString() ?: "—",
                    statusLabel(entry.status), if (entry.access == ReadAccess.SESSION_ONLY) getString(R.string.catalog_session_only) else "")
                textSize = 17f
            })
            row.addView(Button(this).apply {
                setText(R.string.catalog_remove)
                setOnClickListener { if (!busy) work { runtime.catalog.remove(entry.id) } }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row)
        }
    }
    private fun statusLabel(status: CatalogStatus) = getString(when (status) {
        CatalogStatus.READY -> R.string.catalog_ready
        CatalogStatus.MISSING_TITLE -> R.string.catalog_missing_title
        CatalogStatus.MISSING_ARTIST -> R.string.catalog_missing_artist
        CatalogStatus.MISSING_YEAR -> R.string.catalog_missing_year
        CatalogStatus.INVALID_YEAR -> R.string.catalog_invalid_year
        CatalogStatus.INVALID_METADATA -> R.string.catalog_invalid_metadata
        CatalogStatus.NOT_MP3 -> R.string.catalog_not_mp3
        CatalogStatus.UNREADABLE -> R.string.catalog_unreadable
        CatalogStatus.NEEDS_RESELECT -> R.string.catalog_reselect
    })
    override fun onDestroy() { alive = false; super.onDestroy() }
}
