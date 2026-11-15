package radio.reply.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import radio.reply.catalog.*
import radio.reply.orchestrator.*
import radio.reply.ux.*
import java.util.concurrent.TimeUnit

@UnstableApi
class VoiceUxChecks(private val runner: CatalogInstrumentation) {
    private val context get() = runner.targetContext
    private lateinit var browser: MediaBrowser
    private val service get() = checkNotNull(RadioService.active)
    private fun <T> main(action: () -> T): T {
        var value: Result<T>? = null; runner.runOnMainSync { value = runCatching(action) }; return value!!.getOrThrow()
    }
    private fun await(label: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (main(condition)) return; Thread.sleep(100) }
        error("TIMEOUT_$label " + main { "phase=${service.engine.state.phase} voice=${service.voice.lastStatus} starts=${service.voice.output.starts} done=${service.voice.output.done} errors=${service.voice.output.errors} stopTimeouts=${service.voice.output.stopTimeouts}" })
    }
    private fun test(name: String, action: () -> Unit) = runner.test(name, action)
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun dj(enabled: Boolean) {
        val result = main { browser.sendCustomCommand(SessionCommand(RadioService.SET_DJ, Bundle.EMPTY), Bundle().apply { putBoolean("enabled", enabled) }) }.get(5, TimeUnit.SECONDS)
        check(result.resultCode == SessionResult.RESULT_SUCCESS) { "DJ_CONTROL_DENIED_${result.resultCode}" }
    }
    private fun stop() { main { browser.stop() }; await("stop") { service.engine.state.phase == Phase.STOPPED && service.engine.state.awaitingStop == null } }
    private fun startVoice() {
        val before = main { service.voice.output.starts }
        main { browser.play() }
        await("voice_start") { service.voice.output.starts > before && service.engine.state.phase == Phase.DJ_SPEAKING }
    }
    fun run() {
        runner.helper("music"); runner.helper("revoke", FixtureFiles.names); runner.helper("grant", FixtureFiles.names)
        val store = AndroidCatalogStore(context); store.save(emptyList())
        check(LocalMusicCatalog(AndroidDocumentAccess(context),store).importSelection(DocumentSelection(
            (1..6).map { FixtureFiles.uri("song$it").toString() },true,true)).entries.size == 6)
        val activity = runner.startActivitySync(Intent(context,PlaybackActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        browser = main { MediaBrowser.Builder(context,SessionToken(context,ComponentName(context,RadioService::class.java))).buildAsync() }.get(10,TimeUnit.SECONDS)
        val ux = main { VehicleUx(context) {} }
        try {
            await("catalog") { service.engine.state.catalog.size == 6 }
            test("platform_ux_subscription_and_unknown_fail_closed") {
                Thread.sleep(1000)
                main { ux.refresh(); check(ux.gate.detailsAllowed == (ux.gate.access in setOf(UxAccess.ALLOWED,UxAccess.NON_VEHICLE))) }
                runner.sendStatus(0, Bundle().apply { putString("stream", "RR005_UX=${main { ux.gate.access.name }}\n") })
            }
            test("binding_does_not_start_music_or_voice") { main { check(service.output.starts == 0); check(service.voice.output.starts == 0) } }
            if (!main { ux.gate.detailsAllowed }) {
                test("unknown_or_restricted_rejects_dj_setting_at_service") {
                    check(main { browser.sendCustomCommand(SessionCommand(RadioService.SET_DJ, Bundle.EMPTY), Bundle().apply { putBoolean("enabled",true) }) }.get(5,TimeUnit.SECONDS).resultCode == SessionError.ERROR_PERMISSION_DENIED)
                }
                // Direct engine path is an instrumentation probe, never a production UX bypass.
                main { service.command(Event.SettingsChanged(service.engine.state.settings.copy(djEnabled=true))) }
            } else dj(true)
            test("platform_voice_initialization_and_music_fallback_probe") {
                main { browser.play() }
                await("voice_probe") { service.voice.output.starts > 0 || service.engine.state.phase == Phase.MUSIC_PLAYING }
                runner.sendStatus(0, Bundle().apply { putString("stream", "RR005_BACKEND=${main { service.voice.backend }} STATUS=${main { service.voice.lastStatus }}\n") })
            }
            val available = main { service.voice.output.starts > 0 }
            if (available) {
                test("actual_voice_done_then_media3_audio_without_overlap") {
                    await("voice_done",30_000) { service.voice.output.done > 0 && service.output.player.isPlaying && service.output.audioAdvances > 0 }
                    main { check(!service.voice.output.isActive); check(service.voice.output.stopTimeouts == 0) }
                }
                stop()
                test("actual_voice_pause_stop_ack_and_no_auto_resume") {
                    startVoice(); main { browser.pause() }
                    await("voice_paused") { service.engine.state.phase == Phase.PAUSED && service.engine.state.awaitingStop == null }
                    val starts = main { service.output.starts }; Thread.sleep(600)
                    main { check(service.output.starts == starts); check(!service.engine.state.desiredPlaying); check(service.voice.output.stopAcks > 0) }
                }
                test("actual_voice_resume_then_next_uses_introduced_track") {
                    startVoice()
                    val nextId = main { (service.engine.state.segment as Segment.Voice).next.track.id }
                    main { browser.seekToNext() }
                    await("voice_next") { service.engine.state.phase == Phase.MUSIC_PLAYING && service.output.player.isPlaying }
                    main { check((service.engine.state.segment as Segment.Music).track.id == nextId); check(!service.voice.output.isActive) }
                }
                stop()
                test("actual_voice_stop_never_starts_next_music") {
                    startVoice(); val count = main { service.output.starts }; stop(); Thread.sleep(500)
                    main { check(service.output.starts == count); check(!service.voice.output.isActive) }
                }
                val manager = context.getSystemService(AudioManager::class.java)
                val competitor = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener { }.build()
                test("actual_focus_loss_stops_voice_gain_restarts_serially") {
                    startVoice()
                    val count = main { service.voice.output.starts }
                    try {
                        check(main { manager.requestAudioFocus(competitor) } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        await("voice_focus_stop") { service.engine.state.phase == Phase.SUSPENDED && service.engine.state.awaitingStop == null && !service.voice.output.isActive }
                    } finally { main { manager.abandonAudioFocusRequest(competitor) } }
                    await("voice_focus_resume") { service.voice.output.starts > count }
                    stop()
                }
                test("user_pause_during_voice_focus_loss_blocks_gain_resume") {
                    startVoice()
                    try {
                        check(main { manager.requestAudioFocus(competitor) } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        await("voice_second_focus_stop") { service.engine.state.phase == Phase.SUSPENDED && service.engine.state.awaitingStop == null }
                        main { browser.pause() }
                        await("voice_user_pause") { service.engine.state.phase == Phase.PAUSED }
                    } finally { main { manager.abandonAudioFocusRequest(competitor) } }
                    val count = main { service.voice.output.starts }; Thread.sleep(500)
                    main { check(service.voice.output.starts == count); check(!service.engine.state.desiredPlaying) }; stop()
                }
                test("three_natural_music_completions_trigger_serial_era_bridge") {
                    main { browser.play() }
                    await("opening_before_bridge",30_000) { service.engine.state.phase == Phase.MUSIC_PLAYING && service.output.player.isPlaying }
                    val count = main { service.voice.output.starts }
                    repeat(3) { n ->
                        await("bridge_music_$n") { service.output.player.isPlaying && service.output.player.duration > 0 }
                        val ended = main { service.output.naturalEnds }
                        main { browser.seekTo(service.output.player.duration - 300) }
                        await("bridge_end_$n") { service.output.naturalEnds > ended }
                    }
                    await("era_bridge") { service.voice.output.starts > count && service.engine.state.segment is Segment.Voice }
                    main { check(!service.output.player.isPlaying); check(service.output.decodedBuffers > 0) }
                    runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(context.noBackupFilesDir,"rr005-speaking.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                    }
                    stop()
                }
            } else {
                test("unavailable_voice_skips_once_and_real_music_continues") {
                    await("music_after_voice_failure") { service.output.player.isPlaying && service.output.audioAdvances > 0 }
                    main { check(service.voice.output.done == 0); check(service.voice.output.errors > 0); check(service.engine.state.notice == "VOICE_FAILED"); check(!service.voice.output.isActive) }
                }
                stop()
            }
            test("dj_off_makes_no_additional_voice_request") {
                main { service.command(Event.SettingsChanged(service.engine.state.settings.copy(djEnabled=false))) }
                val starts = main { service.voice.output.starts }; main { browser.play() }
                await("music_dj_off") { service.output.player.isPlaying }
                main { check(service.voice.output.starts == starts); check(!service.voice.output.isActive) }; stop()
            }
            if (main { ux.gate.access == UxAccess.ALLOWED }) {
                test("pleos_driving_restriction_locks_details_and_service_settings") {
                        runner.sendStatus(0,Bundle().apply { putString("stream","RR005_WAIT_CONSOLE_D\n") })
                        await("ux_restricted",120_000) { ux.gate.access == UxAccess.RESTRICTED }
                        await("ux_buttons_hidden") {
                            val controls = buttons(activity.window.decorView).filter { it.text.toString() in listOf("DJ 켜기","DJ 끄기",context.getString(radio.reply.R.string.catalog_open)) }
                            controls.size == 2 && controls.all { !it.isEnabled && it.visibility != View.VISIBLE }
                        }
                        check(main { browser.sendCustomCommand(SessionCommand(RadioService.SET_DJ,Bundle.EMPTY),Bundle().apply { putBoolean("enabled",true) }) }.get(5,TimeUnit.SECONDS).resultCode == SessionError.ERROR_PERMISSION_DENIED)
                        main { browser.play() }; await("restricted_standard_play") { service.output.player.isPlaying }
                        main { browser.pause() }; await("restricted_standard_pause") { service.engine.state.phase == Phase.PAUSED }
                        runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                            java.io.File(context.noBackupFilesDir,"rr005-restricted.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                        }
                    runner.sendStatus(0,Bundle().apply { putString("stream","RR005_WAIT_CONSOLE_P\n") })
                    await("ux_unrestricted",120_000) { ux.gate.access == UxAccess.ALLOWED }
                    stop()
                }
            }
            runner.sendStatus(0,Bundle().apply { putString("stream", main {
                "RR005_VOICE available=$available starts=${service.voice.output.starts} done=${service.voice.output.done} stopAcks=${service.voice.output.stopAcks} errors=${service.voice.output.errors} timeouts=${service.voice.output.stopTimeouts} audioBytes=${service.voice.audioBytes} rmsUpdates=${service.voice.rmsUpdates}\n"
            }) })
            runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.noBackupFilesDir,"rr005-voice-ux.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
        } finally { main { ux.close(); browser.release(); activity.finish() } }
    }
}
