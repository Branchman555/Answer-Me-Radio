package radio.reply.playback

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import radio.reply.catalog.*
import radio.reply.orchestrator.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
class PlaybackChecks(private val runner: CatalogInstrumentation) {
    private val context get() = runner.targetContext
    private lateinit var browser: MediaBrowser
    private lateinit var legacyController: android.media.session.MediaController
    private val service get() = checkNotNull(RadioService.active)
    private fun <T> main(action: () -> T): T {
        var value: Result<T>? = null
        runner.runOnMainSync { value = runCatching(action) }
        return value!!.getOrThrow()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) { if (main(condition)) return; Thread.sleep(100) }
        error("TIMEOUT_$label " + main { RadioService.active?.let { "phase=${it.engine.state.phase} starts=${it.output.starts} errors=${it.output.errors} ends=${it.output.naturalEnds} completed=${it.engine.state.completedSinceDj} position=${it.output.player.currentPosition} duration=${it.output.player.duration} playing=${it.output.player.isPlaying} notice=${it.engine.state.notice}" } })
    }
    private fun test(name: String, action: () -> Unit) = runner.test(name, action)
    private fun select(id: String) { main { browser.setMediaItem(MediaItem.Builder().setMediaId(id).build()); browser.prepare() }; await("selected") { (service.engine.state.segment as? Segment.Music)?.track?.id == id } }
    private fun play() { main { browser.play() }; await("playing") { browser.isPlaying && service.output.player.isPlaying && service.output.player.currentPosition > 100 } }
    private fun pause() { main { browser.pause() }; await("paused") { service.engine.state.phase == Phase.PAUSED && !browser.isPlaying && service.engine.state.awaitingStop == null } }
    private fun id() = main { (service.engine.state.segment as? Segment.Music)?.track?.id }
    fun run() {
        runner.helper("music"); runner.helper("revoke", FixtureFiles.names); runner.helper("grant", FixtureFiles.names)
        val store = AndroidCatalogStore(context); store.save(emptyList())
        val rows = LocalMusicCatalog(AndroidDocumentAccess(context), store).importSelection(DocumentSelection(
            (1..6).map { FixtureFiles.uri("song$it").toString() }, true, true)).entries
        check(rows.size == 6 && rows.all { it.track().eligible && (it.durationMs ?: 0) > 10_000 })
        val activity = runner.startActivitySync(Intent(context, PlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val future = main { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, RadioService::class.java))).buildAsync() }
        browser = future.get(10, TimeUnit.SECONDS)
        try {
            await("catalog") { service.engine.state.catalog.size == 6 }
            test("ac044_media3_browse_eras_and_selected_tracks_without_uris") {
                val root = main { browser.getLibraryRoot(null) }.get(5, TimeUnit.SECONDS)
                check(root.value?.mediaId == "root")
                val eras = main { browser.getChildren("root", 0, 10, null) }.get(5, TimeUnit.SECONDS).value!!
                check(eras.map { it.mediaId } == listOf("era:1980", "era:2010"))
                val songs = main { browser.getChildren("era:1980", 0, 20, null) }.get(5, TimeUnit.SECONDS).value!!
                check(songs.size == 3 && songs.all { it.localConfiguration == null && it.mediaMetadata.isPlayable == true })
                check(main { browser.getItem("unknown") }.get(5, TimeUnit.SECONDS).resultCode != 0)
            }
            test("legacy_platform_media_browser_root_and_children") {
                val done = CountDownLatch(1); var valid = false
                lateinit var legacy: android.media.browse.MediaBrowser
                main {
                    legacy = android.media.browse.MediaBrowser(context, ComponentName(context, RadioService::class.java),
                        object : android.media.browse.MediaBrowser.ConnectionCallback() {
                            override fun onConnected() { legacyController = android.media.session.MediaController(context, legacy.sessionToken)
                                legacy.subscribe(legacy.root, object : android.media.browse.MediaBrowser.SubscriptionCallback() {
                                override fun onChildrenLoaded(parentId: String, children: MutableList<android.media.browse.MediaBrowser.MediaItem>) { valid = children.size == 2; done.countDown() }
                                override fun onError(parentId: String) { done.countDown() }
                            }) }
                            override fun onConnectionFailed() { done.countDown() }
                        }, null)
                    legacy.connect()
                }
                try { check(done.await(8, TimeUnit.SECONDS) && valid) } finally { main { legacy.disconnect() } }
            }
            test("library_search_and_mutation_command_boundaries") {
                check(main { browser.search("original", null) }.get(5, TimeUnit.SECONDS).resultCode == 0)
                check(main { browser.getSearchResult("original", 0, 20, null) }.get(5, TimeUnit.SECONDS).value!!.size == 6)
                main { check(!browser.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)); check(!browser.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) }
                runner.helper("untrusted")
            }
            test("mp3_decoder_audio_track_advances_and_pause_preserves_position") {
                play(); pause() // Ordinary PLAY from an empty session also uses the planner.
                select(rows[0].id); play(); await("position") { service.output.player.isPlaying && service.output.player.currentPosition >= 1200 }
                runner.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.noBackupFilesDir, "rr004-playback.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                pause()
                val position = main { service.engine.state.positionMs }
                check(position >= 1000) { "PAUSE_POSITION=$position" }
                Thread.sleep(250)
                main { check(service.engine.state.positionMs == position); check(service.output.audioAdvances > 0); check(service.output.decodedBuffers > 0) }
                play(); main { check(service.output.player.currentPosition >= position - 100) }
            }
            test("ac045_previous_threshold_and_next_do_not_count_completion") {
                main { browser.seekTo(6500) }; await("seek") { service.output.player.isPlaying && service.output.player.currentPosition >= 6500 }
                val original = id(); val completed = main { service.engine.state.completedSinceDj }
                main { browser.seekToPrevious() }; await("restart") { browser.currentPosition < 1500 && browser.isPlaying }
                check(id() == original)
                main { browser.seekToNext() }; await("next") { (service.engine.state.segment as? Segment.Music)?.track?.id != original && browser.isPlaying }
                main { browser.seekToPrevious() }; await("previous") { (service.engine.state.segment as? Segment.Music)?.track?.id == original && browser.isPlaying }
                main { check(service.engine.state.completedSinceDj == completed) }
            }
            test("previous_while_paused_has_no_audio_output") {
                val original = id(); main { browser.seekToNext() }; await("next_again") { idUnsafe() != original && browser.isPlaying }
                pause(); val starts = main { service.output.starts }
                main { browser.seekToPrevious() }; await("paused_previous") { idUnsafe() == original }
                Thread.sleep(250)
                main { check(service.output.starts == starts && service.engine.state.output == null && !browser.isPlaying); check(browser.currentMediaItem?.mediaId == original) }
            }
            test("platform_transport_controls_play_pause_next_previous_share_reducer") {
                main { legacyController.transportControls.play() }; await("legacy_play") { browser.isPlaying }
                val original = id()
                main { legacyController.transportControls.skipToNext() }; await("legacy_next") { idUnsafe() != original && browser.isPlaying }
                main { legacyController.transportControls.skipToPrevious() }; await("legacy_previous") { idUnsafe() == original && browser.isPlaying }
                main { legacyController.transportControls.pause() }; await("legacy_pause") { service.engine.state.phase == Phase.PAUSED && service.engine.state.output == null }
            }
            val manager = context.getSystemService(AudioManager::class.java)
            val competitor = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build()
            test("actual_audio_focus_transient_loss_and_gain_resume_same_position") {
                play(); await("focus_position") { browser.currentPosition >= 1200 }
                check(main { manager.requestAudioFocus(competitor) } == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                await("focus_loss") { service.engine.state.phase == Phase.SUSPENDED && service.engine.state.output == null }
                val position = main { service.engine.state.positionMs }
                main { manager.abandonAudioFocusRequest(competitor) }
                await("focus_gain") { browser.isPlaying && browser.currentPosition >= position - 100 }
            }
            test("user_pause_prevents_focus_gain_auto_resume") {
                main { manager.requestAudioFocus(competitor) }
                await("second_focus_loss") { service.engine.state.phase == Phase.SUSPENDED }
                pause(); main { manager.abandonAudioFocusRequest(competitor) }; Thread.sleep(400)
                main { check(!browser.isPlaying && !service.engine.state.desiredPlaying && service.engine.state.output == null) }
            }
            test("background_foreground_notification_and_actual_natural_end") {
                play(); main { activity.finish() }
                await("notification") { context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty() }
                await("duration") { service.output.player.duration > 0 && service.output.player.isPlaying }
                val completed = main { service.engine.state.completedSinceDj }
                main { browser.seekTo((service.output.player.duration - 400).coerceAtLeast(0)) }
                await("natural_end") { service.engine.state.completedSinceDj > completed && service.output.naturalEnds > 0 && browser.isPlaying }
            }
            test("deleted_selected_file_reports_real_io_error_and_recovers") {
                pause(); runner.helper("delete", listOf("song2"))
                val errors = main { service.output.errors }; val completed = main { service.engine.state.completedSinceDj }
                select(rows[1].id); main { browser.play() }
                await("deleted_recovery") { service.output.errors == errors + 1 && browser.isPlaying && idUnsafe() != rows[1].id }
                main { check(service.engine.state.completedSinceDj == completed); check(service.engine.state.catalog.first { it.id == rows[1].id }.accessible.not()) }
            }
            test("revoked_uri_reports_real_permission_error_and_recovers") {
                pause(); runner.helper("revoke", listOf("song4"))
                val errors = main { service.output.errors }
                select(rows[3].id); main { browser.play() }
                await("revoked_recovery") { service.output.errors == errors + 1 && browser.isPlaying && idUnsafe() != rows[3].id }
            }
            test("ac052_three_real_corrupt_mp3_errors_stop_without_infinite_retry") {
                main { browser.stop() }; await("stopped_for_corruption") { service.engine.state.phase == Phase.STOPPED }
                runner.helper("corrupt", listOf("song1", "song3", "song5"))
                main { service.engine.send(Event.CatalogChanged(listOf(rows[0], rows[2], rows[4]).map { it.track() })) }
                val errors = main { service.output.errors }; val starts = main { service.output.starts }
                main { browser.play() }
                await("error_limit") { service.engine.state.notice == "PLAYBACK_FAILURE_LIMIT" }
                Thread.sleep(500)
                main {
                    check(service.output.errors == errors + 3 && service.output.starts == starts + 3)
                    check(service.engine.state.phase == Phase.STOPPED && service.engine.state.output == null && service.engine.state.awaitingStop == null)
                    check(!browser.isPlaying && service.engine.state.completedSinceDj == 0)
                    runner.sendStatus(0, Bundle().apply { putString("stream", "RR004_AUDIO advances=${service.output.audioAdvances} renderedBuffers=${service.output.decodedBuffers} errors=${service.output.errors}\n") })
                }
            }
        } finally { main { browser.stop(); browser.release(); if (!activity.isDestroyed) activity.finish() } }
        test("service_releases_after_stop_and_last_controller_disconnect") { await("service_destroyed") { RadioService.active == null } }
    }
    private fun idUnsafe() = (service.engine.state.segment as? Segment.Music)?.track?.id
}
