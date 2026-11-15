package radio.reply.playback

import android.app.*
import android.content.*
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.*
import radio.reply.R
import radio.reply.catalog.CatalogRuntime
import radio.reply.orchestrator.*

@UnstableApi
class RadioService : MediaLibraryService() {
    // Debug APK diagnostics for instrumented lifecycle assertions; cleared on destruction, never persisted.
    companion object {
        internal var active: RadioService? = null; private set
        const val SET_DJ = "radio.reply.SET_DJ"
    }
    lateinit var engine: PlaybackEngine; private set
    lateinit var output: Media3Output; private set
    lateinit var voice: PlatformVoice; private set
    private lateinit var ux: radio.reply.ux.VehicleUx
    private lateinit var sessionPlayer: SessionPlayer
    private lateinit var library: MediaLibrarySession
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var focusHeld = false
    private var destroyed = false
    private var loaded = false
    private var pendingPlay = false
    private var lastPhase = Phase.IDLE
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { command(Event.Pause) }
    }
    override fun onCreate() {
        super.onCreate()
        active = this
        output = Media3Output(this)
        voice = PlatformVoice(this, handler)
        engine = PlaybackEngine(RadioOutput(output, voice.output), SystemClock::elapsedRealtime) { state ->
            if (::sessionPlayer.isInitialized) sessionPlayer.refresh()
            if (::library.isInitialized) library.setSessionExtras(Bundle().apply {
                putString("phase", state.phase.name); putBoolean("djEnabled", state.settings.djEnabled)
                putString("voiceBackend", voice.backend); putString("voiceStatus", voice.lastStatus)
                putString("notice", state.notice)
            })
            if (state.phase == Phase.STOPPED && state.awaitingStop == null && lastPhase != Phase.STOPPED) {
                abandonFocus(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            if (state.awaitingStop == null) lastPhase = state.phase
        }
        sessionPlayer = SessionPlayer(output, engine, ::command)
        audioManager = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener({ change ->
                if (!destroyed) {
                    focusHeld = change == AudioManager.AUDIOFOCUS_GAIN
                    engine.command(Event.FocusChanged(when (change) {
                        AudioManager.AUDIOFOCUS_GAIN -> Focus.GAIN
                        AudioManager.AUDIOFOCUS_LOSS -> Focus.LOSS_PERMANENT
                        else -> Focus.LOSS_TRANSIENT
                    }))
                }
            }, handler).build()
        library = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback()).setSessionActivity(
            PendingIntent.getActivity(this, 0, Intent(this, PlaybackActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        ux = radio.reply.ux.VehicleUx(this) {}
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        refreshCatalog {}
    }
    // Legacy binding first supplies an identity placeholder; validate the real caller in onConnect.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = library

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("reply-radio", "음악 재생", NotificationManager.IMPORTANCE_LOW))
        startForeground(1001, Notification.Builder(this, "reply-radio").setSmallIcon(R.drawable.radio_icon)
            .setContentTitle("응답하라 라디오").setContentText("로컬 라디오 재생 준비")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, PlaybackActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build())
    }
    fun command(event: Event) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (destroyed) return
        if (event == Event.Pause || event == Event.Stop) pendingPlay = false
        if (event == Event.Play) {
            foreground()
            if (!loaded) { pendingPlay = true; return }
            val granted = focusHeld || audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusHeld = granted
            engine.send(Event.FocusChanged(if (granted) Focus.GAIN else Focus.LOSS_TRANSIENT))
        }
        engine.command(event)
        if (event == Event.Pause || event == Event.Stop) abandonFocus()
    }
    private fun abandonFocus() {
        if (::audioManager.isInitialized && ::focusRequest.isInitialized) audioManager.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
    }
    private fun refreshCatalog(done: (Boolean) -> Unit) {
        val runtime = CatalogRuntime.get(this)
        runtime.io.execute {
            val result = runCatching { runtime.load().entries.map { it.track() } }
            handler.post {
                if (destroyed) return@post
                loaded = true
                result.fold({ engine.send(Event.CatalogChanged(it)) }, { engine.send(Event.CatalogChanged(emptyList())) })
                done(result.isSuccess)
                if (pendingPlay) { pendingPlay = false; command(Event.Play) }
            }
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!engine.state.desiredPlaying && engine.state.awaitingStop == null) stopSelf() }
    override fun onDestroy() {
        destroyed = true; pendingPlay = false
        active = null
        engine.command(Event.Stop); voice.close(); output.close(); abandonFocus(); ux.close()
        library.release(); sessionPlayer.release()
        unregisterReceiver(noisy); handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    private fun tracks() = engine.state.catalog.filter { it.eligible && engine.state.settings.normalizedEras.any { era -> it.releaseYear in era } }
    private fun browse(parent: String): List<MediaItem> = when {
        parent == "root" -> engine.state.settings.normalizedEras.map { folder("era:${it.startYear}", it.label) }
        parent.startsWith("era:") -> tracks().filter { it.releaseYear!! / 10 * 10 == parent.removePrefix("era:").toIntOrNull() }.map { Media3Output.item(it) }
        else -> emptyList()
    }
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            if (controller.uid == applicationInfo.uid || controller.isTrusted)
                MediaSession.ConnectionResult.AcceptedResultBuilder(session).apply {
                    if (controller.uid == applicationInfo.uid) setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                            .add(SessionCommand(SET_DJ, Bundle.EMPTY)).build())
                }.build()
            else MediaSession.ConnectionResult.reject()
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.uid != applicationInfo.uid || customCommand.customAction != SET_DJ)
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            ux.refresh()
            if (!ux.gate.detailsAllowed) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            command(Event.SettingsChanged(engine.state.settings.copy(djEnabled = args.getBoolean("enabled"))))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder("root", "로컬 시대별 라디오"), params))
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            refreshCatalog { ok ->
                if (!ok || page < 0 || pageSize <= 0 || (parentId != "root" && engine.state.settings.normalizedEras.none { parentId == "era:${it.startYear}" }))
                    result.set(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                else result.set(LibraryResult.ofItemList(browse(parentId).drop((page.toLong() * pageSize).coerceAtMost(500).toInt()).take(pageSize.coerceAtMost(500)), params))
            }
            return result
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = tracks().firstOrNull { it.id == mediaId }?.let { Media3Output.item(it) }
                ?: if (mediaId == "root") folder("root", "로컬 라디오") else browse("root").firstOrNull { it.mediaId == mediaId }
            return Futures.immediateFuture(if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItem(item, null))
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            if (query.isBlank() || query.length > 100) return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }
        private fun search(query: String) = tracks().filter { it.title.contains(query, true) || it.artist.contains(query, true) }.map { Media3Output.item(it) }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String,
            page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(if (query.isBlank() || query.length > 100 || page < 0 || pageSize !in 1..500)
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(search(query)
                    .drop((page.toLong() * pageSize).coerceAtMost(500).toInt()).take(pageSize), params))
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val track = mediaItems.singleOrNull()?.let { item -> tracks().firstOrNull { it.id == item.mediaId } }
            return if (track == null) Futures.immediateFailedFuture(IllegalArgumentException("UNSELECTED_MEDIA_ID"))
                else Futures.immediateFuture(mutableListOf(Media3Output.item(track)))
        }
    }
}
