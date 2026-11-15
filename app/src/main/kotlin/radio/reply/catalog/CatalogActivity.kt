package radio.reply.catalog

import android.app.Activity
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import radio.reply.R
import radio.reply.ui.RadioTheme as Theme
import radio.reply.ui.RadioTheme.dp

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
    private lateinit var ux: radio.reply.ux.VehicleUx
    private var pickerRevision = -1L
    companion object {
        const val DEVELOPMENT_ACCESS = "developmentCatalog"
        private const val PICK_DOCUMENTS = 3003
        fun allowed(debuggable: Boolean, explicitlyDevelopment: Boolean) = debuggable && explicitlyDevelopment
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.configure(this)
        if (!allowed(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                intent.getBooleanExtra(DEVELOPMENT_ACCESS, false))) { finish(); return }
        alive = true
        pickerPending = savedInstanceState?.getBoolean("pickerPending") ?: false
        runtime = CatalogRuntime.get(this)
        val root = Theme.column(this).apply { setBackgroundColor(Theme.background) }
        root.addView(Theme.button(this, "‹  라디오").apply {
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20708070),
                Theme.shape(this@CatalogActivity, Theme.background, 12, Theme.background), null)
            minHeight = dp(48); minimumHeight = dp(48); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(Theme.gap(this, 16))
        root.addView(Theme.text(this, "LIBRARY", 11f, Theme.amber, true))
        root.addView(Theme.gap(this, 8))
        root.addView(Theme.text(this, "음악 서랍", 30f, bold = true))
        root.addView(Theme.gap(this, 10))
        root.addView(Theme.text(this, getString(R.string.catalog_scope), 14f, Theme.muted))
        root.addView(Theme.gap(this, 20))
        message = Theme.text(this, "음악을 확인하고 있어요", 14f, Theme.amber)
        root.addView(message)
        root.addView(Theme.gap(this, 16))
        add = Theme.button(this, getString(R.string.catalog_add), true).apply {
            setText(R.string.catalog_add)
            setOnClickListener {
                ux.refresh()
                if (ux.gate.detailsAllowed && !busy && !pickerPending) {
                    pickerRevision = ux.gate.revision
                    pickerPending = true; updateButtons()
                    try { startActivityForResult(SafSelection.intent(), PICK_DOCUMENTS) }
                    catch (_: ActivityNotFoundException) {
                        pickerPending = false; message.setText(R.string.catalog_picker_missing); updateButtons()
                    }
                }
            }
        }
        refresh = Theme.button(this, getString(R.string.catalog_refresh)).apply { setOnClickListener { work { runtime.load() } } }
        root.addView(add); root.addView(Theme.gap(this, 10)); root.addView(refresh); root.addView(Theme.gap(this, 22))
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows)
        root.addView(Theme.gap(this, 12))
        root.addView(Theme.text(this, "목록에서 빼도 원본 파일은 지워지지 않아요.", 12f, Theme.muted))
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; isFillViewport = true; setBackgroundColor(Theme.background); addView(root) })
        ux = radio.reply.ux.VehicleUx(this) { if (::ux.isInitialized && alive) updateAccess() }
        updateAccess()
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
        ux.refresh()
        if (!ux.gate.allowsResult(pickerRevision)) { updateAccess(); return }
        val selection = SafSelection.result(resultCode, data)
        if (selection == null) { message.setText(R.string.catalog_cancelled); updateButtons(); return }
        work { runtime.catalog.importSelection(selection) }
    }
    private fun work(action: () -> CatalogResult) {
        if (!::ux.isInitialized || !ux.gate.detailsAllowed) return
        val requestGeneration = ++generation
        val permissionRevision = ux.gate.revision
        busy = true; updateButtons(); message.setText(R.string.catalog_loading)
        // Serial IO; obsolete view callbacks cannot update a recreated screen.
        runtime.io.execute {
            val result = runCatching { check(ux.gate.allowsResult(permissionRevision)); action() }
            runOnUiThread {
                if (!alive || isFinishing || isDestroyed || requestGeneration != generation) return@runOnUiThread
                busy = false; updateButtons()
                if (!ux.gate.allowsResult(permissionRevision)) { updateAccess(); return@runOnUiThread }
                result.fold(::render) { message.setText(R.string.catalog_failure) }
            }
        }
    }
    private fun updateButtons() {
        val allowed = ::ux.isInitialized && ux.gate.detailsAllowed
        add.isEnabled = allowed && !busy && !pickerPending; refresh.isEnabled = allowed && !busy && !pickerPending
    }
    private fun updateAccess() {
        updateButtons()
        if (!ux.gate.detailsAllowed) {
            generation++; busy = false; pickerRevision = -1
            rows.removeAllViews(); message.setText(R.string.ux_details_locked)
            if (pickerPending) { runCatching { finishActivity(PICK_DOCUMENTS) }; pickerPending = false }
        } else if (!busy && !pickerPending) work { runtime.load() }
    }
    private fun render(result: CatalogResult) {
        if (!ux.gate.detailsAllowed) { updateAccess(); return }
        message.text = getString(R.string.catalog_summary, result.entries.size, result.entries.count { it.track().eligible }, result.rejected)
        rows.removeAllViews()
        if (result.entries.isEmpty()) {
            rows.addView(Theme.column(this, 24).apply {
                background = Theme.shape(this@CatalogActivity)
                addView(Theme.text(this@CatalogActivity, "아직 담은 음악이 없어요", 21f, bold = true))
                addView(Theme.gap(this@CatalogActivity, 12))
                addView(Theme.text(this@CatalogActivity, "1980년대와 2010년대 MP3를 추가해 주세요.\n파일에 제목·아티스트·연도가 있어야 해요.", 15f, Theme.muted))
            })
        }
        for (entry in result.entries) {
            val row = Theme.column(this, 18).apply { background = Theme.shape(this@CatalogActivity) }
            row.addView(Theme.text(this, entry.releaseYear?.let { "${it / 10 * 10}s  /  $it" } ?: "연도를 확인해 주세요", 11f, Theme.amber, true))
            row.addView(Theme.gap(this, 10))
            row.addView(Theme.text(this, entry.title.ifBlank { getString(R.string.catalog_unknown_title) }, 21f, bold = true))
            row.addView(Theme.gap(this, 6))
            row.addView(Theme.text(this, entry.artist.ifBlank { getString(R.string.catalog_unknown_artist) }, 14f, Theme.muted))
            row.addView(Theme.gap(this, 14))
            row.addView(Theme.text(this, statusLabel(entry.status), 12f, Theme.amber))
            if (entry.access == ReadAccess.SESSION_ONLY) row.addView(Theme.text(this, getString(R.string.catalog_session_only), 12f, Theme.muted))
            row.addView(Theme.gap(this, 14))
            row.addView(Theme.button(this, getString(R.string.catalog_remove)).apply {
                setText(R.string.catalog_remove)
                setOnClickListener { ux.refresh(); if (!busy && ux.gate.detailsAllowed) work { runtime.catalog.remove(entry.id) } }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            rows.addView(row)
            rows.addView(Theme.gap(this, 12))
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
    override fun onResume() { super.onResume(); if (::ux.isInitialized) { ux.refresh(); updateAccess() } }
    override fun onDestroy() { alive = false; if (::ux.isInitialized) ux.close(); super.onDestroy() }
}
