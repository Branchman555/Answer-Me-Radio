package radio.reply.playback

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech as AndroidTts
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/** Explicit platform choice, no network mode or credentials. Missing engines fail into music-only playback. */
class PlatformVoice(private val context: Context, private val handler: Handler) {
    val backend = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) "PLEOS_ON_DEVICE" else "ANDROID_OFFLINE"
    var lastStatus = "NOT_REQUESTED"; private set
    var audioBytes = 0L; private set
    var rmsUpdates = 0L; private set
    val output = VoiceOutput({ listener ->
        val report: (String) -> Unit = { lastStatus = it }
        if (backend == "PLEOS_ON_DEVICE") PleosDriver(context, handler, listener, report) { rmsUpdates++ }
        else AndroidDriver(context, handler, listener, report) { audioBytes += it }
    }, VoiceScheduler { delay, action ->
        val task = Runnable(action); handler.postDelayed(task, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(task) }; cancel
    })
    fun close() = output.close()
}

private val voiceAttributes: AudioAttributes get() = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

private class PleosDriver(context: Context, private val handler: Handler,
    private val signal: (VoiceSignal) -> Unit, private val status: (String) -> Unit, private val rms: () -> Unit) : VoiceDriver {
    private val tts = PleosTts(context.applicationContext, Mode.ON_DEVICE)
    private var closed = false
    private var cancelled = false
    private var submitted = false
    private var registered = false
    private fun post(action: () -> Unit) { handler.post { if (!closed) action() } }
    private val listener = object : EventListener {
        override fun onReady() = post { signal(VoiceSignal.READY) }
        override fun onStart() = post { status("SPEAKING"); signal(VoiceSignal.STARTED) }
        override fun onDone() = post { status("DONE"); signal(VoiceSignal.DONE) }
        override fun onStop() = post { status("STOPPED"); signal(VoiceSignal.STOPPED) }
        override fun onError(errorType: ErrorType) = post { status("PLEOS_${errorType.name}"); signal(VoiceSignal.ERROR) }
        override fun onUpdatedRms(rms: Double) = post { if (rms.isFinite() && rms != 0.0) this@PleosDriver.rms() }
    }
    override fun speak(text: String) {
        status("INITIALIZING")
        handler.postDelayed({ if (!closed && !cancelled && !submitted) { status("PLEOS_INIT_TIMEOUT"); signal(VoiceSignal.ERROR) } }, 6_000)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() = post {
                if (cancelled) return@post
                try {
                    tts.addEventListener(listener); registered = true
                    tts.setLocale(Locale.KOREA); tts.setAudioAttributes(voiceAttributes)
                    submitted = true
                    tts.speak(text, true)
                } catch (_: Exception) { status("PLEOS_CALL_FAILED"); signal(VoiceSignal.ERROR) }
            }
            override fun onFailure(errorType: ErrorType) = post { status("PLEOS_INIT_${errorType.name}"); signal(VoiceSignal.ERROR) }
        })
    }
    override fun stop() {
        cancelled = true
        if (submitted) tts.stop() else post { signal(VoiceSignal.STOPPED) } // No speak call was made.
    }
    override fun close() {
        closed = true
        try { if (registered) tts.removeEventListener(listener) } finally { tts.release() }
    }
}

private class AndroidDriver(context: Context, private val handler: Handler,
    private val signal: (VoiceSignal) -> Unit, private val status: (String) -> Unit,
    private val bytes: (Long) -> Unit) : VoiceDriver {
    private var tts: AndroidTts? = null
    private val appContext = context.applicationContext
    private var closed = false
    private var cancelled = false
    private var submitted = false
    private fun post(action: () -> Unit) { handler.post { if (!closed) action() } }
    override fun speak(text: String) {
        status("INITIALIZING")
        handler.postDelayed({ if (!closed && !cancelled && !submitted) { status("ANDROID_INIT_TIMEOUT"); signal(VoiceSignal.ERROR) } }, 6_000)
        tts = AndroidTts(appContext) { result -> post {
            if (cancelled) return@post
            val engine = tts ?: return@post
            if (result != AndroidTts.SUCCESS) { status("ANDROID_INIT_FAILED"); signal(VoiceSignal.ERROR); return@post }
            // No silent language substitution and no network-required voice.
            val voice = engine.voices?.firstOrNull { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
            if (voice == null || engine.setVoice(voice) != AndroidTts.SUCCESS) {
                status("KOREAN_OFFLINE_VOICE_MISSING"); signal(VoiceSignal.ERROR); return@post
            }
            engine.setAudioAttributes(voiceAttributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = post { if (id == "voice") { status("SPEAKING"); signal(VoiceSignal.STARTED) } }
                override fun onDone(id: String?) = post { if (id == "voice") { status("DONE"); signal(VoiceSignal.DONE) } }
                @Deprecated("Required framework callback")
                override fun onError(id: String?) = post { if (id == "voice") { status("ANDROID_OUTPUT_ERROR"); signal(VoiceSignal.ERROR) } }
                override fun onError(id: String?, errorCode: Int) = post { if (id == "voice") { status("ANDROID_ERROR_$errorCode"); signal(VoiceSignal.ERROR) } }
                override fun onStop(id: String?, interrupted: Boolean) = post { if (id == "voice") signal(VoiceSignal.STOPPED) }
                override fun onAudioAvailable(id: String?, audio: ByteArray?) { val size = audio?.size ?: 0; post { bytes(size.toLong()) } }
            })
            submitted = true
            if (engine.speak(text, AndroidTts.QUEUE_FLUSH, null, "voice") == AndroidTts.ERROR) {
                submitted = false // Synchronous rejection: nothing was queued.
                status("ANDROID_SPEAK_REJECTED"); signal(VoiceSignal.ERROR)
            }
        } }
    }
    override fun stop() {
        cancelled = true
        if (submitted) tts?.stop() else post { signal(VoiceSignal.STOPPED) }
    }
    override fun close() { closed = true; tts?.shutdown(); tts = null }
}
