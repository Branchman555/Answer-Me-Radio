package radio.reply.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.util.UnstableApi
import radio.reply.catalog.*
import radio.reply.playback.PlaybackActivity
import radio.reply.playback.RadioService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated UI smoke checks. Existing 177 JVM / 115 backend / 38 native cases are unchanged. */
@UnstableApi
class UiPolishInstrumentation(private val runner: Instrumentation) {
    private val targetContext get() = runner.targetContext
    private val context get() = runner.context
    private lateinit var activity: Activity
    private var passed = 0
    private fun <T> main(action: () -> T): T {
        var result: Result<T>? = null
        runner.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < until) { if (main(condition)) return; Thread.sleep(80) }
        error("TIMEOUT $label")
    }
    private fun buttons(view: View): List<Button> = if (view is Button) listOf(view) else if (view is ViewGroup)
        (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
    private fun press(label: String) = main {
        buttons(activity.window.decorView).single { it.contentDescription?.toString() == label }.let {
            check(it.isEnabled && it.isShown); check(it.performClick())
        }
    }
    private fun test(name: String, check: () -> Unit) {
        check(); passed++; runner.sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }
    private fun helper(operation: String) {
        val done = CountDownLatch(1); var code = 0
        val reply = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { code = resultCode; done.countDown() }
        }
        context.startActivity(Intent().setComponent(ComponentName(context.packageName, FixtureGrantActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("operation", operation)
            .putStringArrayListExtra("names", ArrayList(FixtureFiles.names)).putExtra("reply", reply))
        check(done.await(10, TimeUnit.SECONDS) && code == 1)
    }
    private fun screenshot(name: String) {
        runner.waitForIdleSync(); Thread.sleep(600)
        val bitmap = checkNotNull(runner.uiAutomation.takeScreenshot())
        java.io.File(targetContext.noBackupFilesDir, "ui002-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    fun run() {
        try {
            val store = AndroidCatalogStore(targetContext); store.save(emptyList())
            activity = runner.startActivitySync(Intent(targetContext, PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("ready") { buttons(activity.window.decorView).any { it.contentDescription == "재생" && it.isEnabled } }
            test("empty_launch_never_autoplays") {
                check(main { RadioService.active!!.output.starts == 0 })
                screenshot("empty")
            }
            test("accessible_transport_targets_fit_viewport") {
                main {
                    val controls = buttons(activity.window.decorView).filterIsInstance<TransportButton>()
                    check(controls.size == 4)
                    val viewport = android.graphics.Rect(); check(activity.window.decorView.getGlobalVisibleRect(viewport))
                    controls.forEach {
                        val visible = android.graphics.Rect(); check(it.getGlobalVisibleRect(visible))
                        check(visible.width() == it.width && visible.height() == it.height)
                        check(it.width >= 48 * it.resources.displayMetrics.density && it.height >= 48 * it.resources.displayMetrics.density)
                        check(!it.contentDescription.isNullOrBlank())
                    }
                }
            }
            main { activity.finish() }; helper("music"); helper("grant")
            CatalogRuntime.get(targetContext).catalog.importSelection(DocumentSelection(
                (1..6).map { FixtureFiles.uri("song$it").toString() }, true, true))
            activity = runner.startActivitySync(Intent(targetContext, PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            await("catalog") { RadioService.active!!.engine.state.catalog.size == 6 && buttons(activity.window.decorView).any { it.contentDescription == "재생" && it.isEnabled } }
            test("play_icon_starts_actual_audio") {
                press("재생"); await("audio") { RadioService.active!!.output.player.isPlaying && RadioService.active!!.output.player.currentPosition > 100 }
            }
            test("pause_icon_stops_and_keeps_position") {
                await("pause icon") { buttons(activity.window.decorView).any { it.contentDescription == "일시정지" } }
                press("일시정지"); await("paused") { RadioService.active!!.engine.state.phase == radio.reply.orchestrator.Phase.PAUSED && RadioService.active!!.engine.state.awaitingStop == null }
                val position = main { RadioService.active!!.engine.state.positionMs }; Thread.sleep(200)
                check(main { RadioService.active!!.engine.state.positionMs == position && !RadioService.active!!.output.player.isPlaying })
            }
            test("next_previous_icons_change_tracks_without_autoplay") {
                val id = main { (RadioService.active!!.engine.state.segment as radio.reply.orchestrator.Segment.Music).track.id }
                press("다음 곡"); await("next") { (RadioService.active!!.engine.state.segment as? radio.reply.orchestrator.Segment.Music)?.track?.id != id }
                press("이전 곡"); await("previous") { (RadioService.active!!.engine.state.segment as? radio.reply.orchestrator.Segment.Music)?.track?.id == id }
                check(main { !RadioService.active!!.output.player.isPlaying })
            }
            test("stop_icon_ends_output") {
                await("play icon") { buttons(activity.window.decorView).any { it.contentDescription == "재생" } }
                press("재생"); await("resumed") { RadioService.active!!.output.player.isPlaying }
                press("정지"); await("stopped") { RadioService.active!!.engine.state.output == null && RadioService.active!!.engine.state.awaitingStop == null }
                check(main { !RadioService.active!!.output.player.isPlaying })
            }
            test("screen_reopen_releases_browser_without_crash_or_autoplay") {
                repeat(3) {
                    main { activity.finish() }; runner.waitForIdleSync()
                    activity = runner.startActivitySync(Intent(targetContext, PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    await("reopened") { buttons(activity.window.decorView).any { it.contentDescription == "재생" && it.isEnabled } }
                }
                // Covers Media3 1.6.1's 30-second deferred service unbind timeout.
                Thread.sleep(32_000)
                check(main { !activity.isDestroyed && !RadioService.active!!.output.player.isPlaying })
            }
            runner.sendStatus(0, Bundle().apply { putString("stream", "UI002_PASS_COUNT=$passed\n") })
        } catch (failure: Throwable) {
            throw failure
        } finally { if (::activity.isInitialized) main { activity.finish() } }
    }
}
