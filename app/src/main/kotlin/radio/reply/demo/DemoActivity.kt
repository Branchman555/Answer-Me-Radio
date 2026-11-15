package radio.reply.demo

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
import android.widget.*
import radio.reply.orchestrator.*
import radio.reply.R
import android.content.Intent
import radio.reply.catalog.CatalogActivity
import radio.reply.catalog.CatalogRuntime

/** Development harness only. No audio, location permission, network, or vehicle claims. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DemoActivity : Activity() {
    private val simulation = Simulation()
    private lateinit var status: TextView
    private var localCatalog = false
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            setBackgroundColor(Color.rgb(22, 31, 34))
        }
        fun text(value: String, size: Float, color: Int) = TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, 8, 0, 8)
            layout.addView(this)
        }
        text("응답하라 라디오", 30f, Color.rgb(249, 208, 125))
        text(getString(R.string.demo_scope), 16f, Color.LTGRAY)
        status = text("", 19f, Color.WHITE)
        layout.addView(Button(this).apply {
            setText(R.string.playback_open)
            setOnClickListener { startActivity(Intent(this@DemoActivity, radio.reply.playback.PlaybackActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            setText(R.string.catalog_open)
            setOnClickListener { startActivity(Intent(this@DemoActivity, CatalogActivity::class.java)
                .putExtra(CatalogActivity.DEVELOPMENT_ACCESS, true)) }
        })
        layout.addView(Switch(this).apply {
            setText(R.string.catalog_mode)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, enabled ->
                localCatalog = enabled; loadGeneration++
                simulation.send(Event.Stop)
                simulation.send(Event.CatalogChanged(if (enabled) emptyList() else Simulation.fixtures().catalog))
                simulation.send(Event.PacksChanged(if (enabled) emptyList() else Simulation.fixtures().packs))
                if (enabled) loadCatalog() else render()
            }
        })
        fun row(vararg actions: Pair<String, () -> Unit>) {
            val row = LinearLayout(this)
            actions.forEach { (label, action) -> row.addView(Button(this).apply {
                text = label
                setOnClickListener { action(); render() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            layout.addView(row)
        }
        row("재생" to { simulation.send(Event.Play) }, "일시정지" to { simulation.send(Event.Pause) },
            "정지" to { simulation.send(Event.Stop) }, "다음" to { simulation.send(Event.Next) })
        row("현재 음악/음성 완료 주입" to { if (simulation.state.output != null) simulation.complete() })
        val road = Switch(this).apply {
            setText(R.string.road_opt_in)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                simulation.send(Event.SettingsChanged(simulation.state.settings.copy(
                    roadTimeTravelEnabled = checked, roadLocationConsent = checked)))
                render()
            }
        }
        layout.addView(road)
        row("모의 구간 진입" to { simulation.send(Event.MatchChanged(Simulation.match(simulation.now))) },
            "구간 이탈" to { simulation.send(Event.MatchChanged(null)) })
        row("포커스 상실" to { simulation.send(Event.FocusChanged(Focus.LOSS_TRANSIENT)) },
            "포커스 복귀" to { simulation.send(Event.FocusChanged(Focus.GAIN)) })
        row("위치 11초 경과" to { simulation.now += 11_000 }, "기록 삭제" to { simulation.send(Event.ClearHistory) })
        text("시대 A 3곡 → DJ → 시대 B. 모의 구간 진입 시 정상 완료 2곡 경계에서 역사 코너를 검사합니다.", 15f, Color.LTGRAY)
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(layout) })
        render()
    }

    override fun onStop() {
        simulation.send(Event.Pause)
        super.onStop()
    }
    override fun onResume() { super.onResume(); if (localCatalog) loadCatalog() }
    override fun onDestroy() { loadGeneration++; super.onDestroy() }
    private fun loadCatalog() {
        val generation = ++loadGeneration
        val runtime = CatalogRuntime.get(this)
        runtime.io.execute {
            val result = runCatching { runtime.load() }
            runOnUiThread {
                if (isDestroyed || !localCatalog || generation != loadGeneration) return@runOnUiThread
                result.fold({ simulation.send(Event.CatalogChanged(it.entries.map { entry -> entry.track() })); render() },
                    { simulation.send(Event.CatalogChanged(emptyList())); status.setText(R.string.catalog_failure) })
            }
        }
    }

    private fun render() {
        val s = simulation.state
        val current = when (val segment = s.segment) {
            is Segment.Music -> "${segment.track.title} · ${segment.track.releaseYear}"
            is Segment.Voice -> segment.text
            null -> "재생 버튼으로 모의 방송을 시작하세요."
        }
        status.text = getString(R.string.simulation_status, s.phase.name, s.settings.mode.name, current,
            s.completedSinceDj, if (s.pendingStory == null) 0 else 1,
            if (s.locationSubscribed) "ON" else "OFF", s.history.size, s.notice ?: "")
    }
}
