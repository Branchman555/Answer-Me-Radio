package radio.reply.orchestrator

import radio.reply.planner.RadioPlanner
import radio.reply.story.RoadStoryPlanner
import radio.reply.context.*
import radio.reply.dj.*
import radio.reply.planner.Era

/** Pure, synchronous transition. Only the effect runner may touch actual audio adapters. */
class RadioReducer(
    private val planner: RadioPlanner = RadioPlanner(), private val stories: RoadStoryPlanner = RoadStoryPlanner(),
) {
    fun reduce(state: RadioState, event: Event, now: Long): Transition = Step(state, now).run(event)

    private inner class Step(var s: RadioState, val now: Long) {
        val effects = mutableListOf<Effect>()

        fun run(event: Event): Transition {
            when (event) {
                Event.Play -> play()
                Event.Pause -> if (s.phase !in setOf(Phase.IDLE, Phase.STOPPED)) {
                    partialHistory()
                    // Interrupted history never restarts automatically or on resume.
                    val voice = s.segment as? Segment.Voice
                    if (voice?.storyId != null) s = s.copy(segment = voice.next)
                    s = s.copy(desiredPlaying = false, phase = Phase.PAUSED, pendingStory = null)
                    halt()
                }
                Event.Stop -> stop(null)
                Event.Next -> next()
                Event.Previous -> previous()
                is Event.PositionUpdated -> if (s.output?.token == event.token && s.segment is Segment.Music)
                    s = s.copy(positionMs = event.positionMs.coerceAtLeast(0))
                is Event.SeekTo -> if (s.segment is Segment.Music) { halt(); s = s.copy(positionMs = event.positionMs.coerceAtLeast(0)); start() }
                is Event.SelectTrack -> selectTrack(event.id)
                is Event.Ended -> when {
                    s.awaitingStop == event.token -> stopped(event.token)
                    s.output?.token == event.token && s.desiredPlaying -> ended()
                }
                is Event.OutputStopped -> stopped(event.token)
                is Event.StopTimedOut -> if (s.awaitingStop == event.token) {
                    // Keep awaitingStop: even a new PLAY cannot bypass unconfirmed physical silence.
                    stop("OUTPUT_STOP_UNCONFIRMED")
                }
                is Event.Failed -> if (s.output?.token == event.token) failed()
                is Event.FocusChanged -> focus(event.focus)
                is Event.SettingsChanged -> settings(event.settings)
                is Event.CatalogChanged -> {
                    require(event.tracks.distinctBy { it.id }.size == event.tracks.size)
                    if (event.tracks != s.catalog) invalidate()
                    s = s.copy(catalog = event.tracks)
                    refreshPending()
                }
                is Event.MatchChanged -> {
                    s = s.copy(match = event.match)
                    refreshPending()
                }
                is Event.PacksChanged -> { s = s.copy(packs = event.packs); refreshPending() }
                is Event.TimingChanged -> {
                    s = s.copy(localDate = event.date, remainingSeconds = event.remainingSeconds?.takeIf { it >= 0 })
                    refreshPending()
                }
                is Event.ScriptRequested -> if (s.settings.djEnabled && event.key.sessionId == s.sessionId
                    && event.key.generation == s.generation && s.phase !in setOf(Phase.IDLE, Phase.STOPPED)) {
                    s = s.copy(pendingRequest = event.key, preparedScript = null)
                }
                is Event.ScriptReady -> if (event.key == s.pendingRequest && event.key.sessionId == s.sessionId
                    && event.key.generation == s.generation && s.settings.djEnabled) {
                    // Legacy correlation probe only. Raw text never enters the validated playback path.
                    s = s.copy(preparedScript = event.text)
                }
                is Event.ContextChanged -> {
                    val input = event.input.let {
                        if (it.locationStatus != LocationStatus.AVAILABLE && it.weather?.scope == WeatherScope.LOCATION)
                            it.copy(weather = null) else it
                    }
                    s = s.copy(contextInput = input, contextReceivedAt = now)
                    if (s.djPlan != null && s.djPlan!!.key.contextFingerprint != snapshot().contextFingerprint) {
                        invalidate()
                        prefetch()
                    }
                }
                is Event.DjResponseReceived -> receiveDj(event)
                is Event.DjRequestFailed -> if (currentRequest(event.key)) failDj(DjFailure.PROVIDER_FAILED)
                is Event.DjDeadline -> if (currentRequest(event.key) && now >= s.djPlan!!.deadlineMonotonic) failDj(DjFailure.AI_TIMEOUT)
                Event.ClockTick -> Unit
                Event.ClearHistory -> s = s.copy(history = emptyList()) // Dedupe survives UI deletion.
            }
            syncLocation()
            return Transition(s, effects.toList())
        }

        fun play() {
            if (s.desiredPlaying && s.phase !in setOf(Phase.PAUSED, Phase.SUSPENDED, Phase.STOPPED, Phase.IDLE)) return
            if (s.awaitingStop != null && s.phase == Phase.STOPPED) return
            if (s.phase in setOf(Phase.IDLE, Phase.STOPPED)) {
                s = RadioState(settings = s.settings, catalog = s.catalog, packs = s.packs,
                    sessionId = s.sessionId + 1, generation = s.generation + 1, playbackEpoch = s.playbackEpoch + 1,
                    desiredPlaying = true, phase = Phase.PREPARING, focusAllowed = s.focusAllowed,
                    localDate = s.localDate, remainingSeconds = s.remainingSeconds,
                    contextInput = s.contextInput, contextReceivedAt = s.contextReceivedAt,
                    requestSequence = s.requestSequence)
                val music = baseMusic() ?: return
                s = s.copy(segment = if (s.settings.djEnabled) ordinaryVoice(music, null, null) else music)
            } else s = s.copy(desiredPlaying = true, phase = Phase.PREPARING)
            start()
        }

        fun start() {
            if (!s.desiredPlaying || s.output != null) return
            if (!s.focusAllowed) { s = s.copy(phase = Phase.SUSPENDED); return }
            if (s.awaitingStop != null) { s = s.copy(phase = Phase.PREPARING); return }
            var segment = s.segment ?: return
            if (segment is Segment.Voice && segment.scriptPlan != null) {
                val plan = segment.scriptPlan!!
                // A paused voice may outlive its weather. Revalidate on every output restart.
                if (plan.key.contextFingerprint != snapshot().contextFingerprint) {
                    segment = segment.copy(text = LocalDjScripts.fallback(plan.request).text, scriptPlan = null)
                    s = s.copy(segment = segment)
                }
            }
            val track = when (segment) { is Segment.Music -> segment.track; is Segment.Voice -> segment.next.track }
            if (s.catalog.none { it.id == track.id && it.eligible }) {
                // No speech about a disappeared local file. Drop any historical insertion as a whole.
                partialHistory()
                s = s.copy(segment = null, storyQueue = emptyList(), notice = "TRACK_UNAVAILABLE")
                val music = baseMusic() ?: return
                s = s.copy(segment = music)
                start()
                return
            }
            val epoch = s.playbackEpoch + 1
            val output = ActiveOutput(OutputToken(s.sessionId, epoch), segment, s.positionMs)
            s = s.copy(playbackEpoch = epoch, output = output,
                phase = if (segment is Segment.Voice) Phase.DJ_SPEAKING else Phase.MUSIC_PLAYING)
            if (segment is Segment.Voice) s = s.copy(completedSinceDj = 0, distinctCompletedSinceDj = emptySet())
            effects += Effect.StartOutput(output)
            if (segment is Segment.Music) prefetch()
        }

        fun halt() {
            val active = s.output ?: return
            s = s.copy(output = null, awaitingStop = active.token, playbackEpoch = s.playbackEpoch + 1)
            effects += Effect.StopOutput(active.token)
        }

        fun stopped(token: OutputToken) {
            if (s.awaitingStop != token) return
            s = s.copy(awaitingStop = null)
            start()
        }

        fun ended() {
            val segment = s.output!!.segment
            s = s.copy(output = null)
            when (segment) {
                is Segment.Voice -> {
                    if (segment.storyId != null) historyStatus(segment.storyId, StoryPlayback.COMPLETE)
                    s = s.copy(segment = segment.next)
                    start()
                }
                is Segment.Music -> {
                    remember(segment.track)
                    s = s.copy(segment = null, consecutiveErrors = 0,
                        completedSinceDj = s.completedSinceDj + 1,
                        distinctCompletedSinceDj = s.distinctCompletedSinceDj + segment.track.id,
                        recentIds = (s.recentIds + segment.track.id).takeLast(12),
                        block = if (segment.story) s.block else s.block?.copy(completed = s.block!!.completed + 1))
                    advance(segment.story, natural = true)
                }
            }
        }

        fun next() {
            val segment = s.segment ?: return
            invalidate()
            partialHistory()
            halt()
            when (segment) {
                is Segment.Voice -> { s = s.copy(segment = segment.next); start() }
                is Segment.Music -> { remember(segment.track); s = s.copy(segment = null); advance(segment.story, natural = false) }
            }
        }

        fun remember(track: radio.reply.planner.Track) {
            s = s.copy(previousTracks = (s.previousTracks + track).takeLast(30), positionMs = 0)
        }

        fun previous() {
            val music = s.segment as? Segment.Music ?: return
            invalidate(); halt()
            val previous = if (s.positionMs <= 5_000) s.previousTracks.lastOrNull { old ->
                s.catalog.any { it.id == old.id && it.eligible && s.settings.normalizedEras.any { era -> it.releaseYear in era } }
            } else null
            if (previous != null) s = s.copy(segment = Segment.Music(previous),
                previousTracks = s.previousTracks.take(s.previousTracks.lastIndexOf(previous)),
                replayQueue = listOf(music.track) + s.replayQueue)
            s = s.copy(positionMs = 0, pendingStory = null)
            start()
        }

        fun selectTrack(id: String) {
            val track = s.catalog.firstOrNull { it.id == id && it.eligible && s.settings.normalizedEras.any { era -> it.releaseYear in era } } ?: return
            if (s.phase in setOf(Phase.IDLE, Phase.STOPPED)) {
                s = s.copy(sessionId = s.sessionId + 1, phase = Phase.PAUSED, desiredPlaying = false)
            }
            invalidate(); partialHistory(); halt()
            val era = s.settings.normalizedEras.first { track.releaseYear in it }
            val candidates = planner.plan(s.catalog, s.settings.copy(eras = listOf(era)), 0, s.recentIds).block!!.candidates
            s = s.copy(segment = Segment.Music(track), positionMs = 0, previousTracks = emptyList(), replayQueue = emptyList(),
                block = BlockCursor(era, candidates.filterNot { it.id == track.id }, minOf(3, candidates.size)),
                eraCursor = (s.settings.normalizedEras.indexOf(era) + 1) % s.settings.normalizedEras.size,
                storyQueue = emptyList(), pendingStory = null, consecutiveErrors = 0)
            start()
        }

        fun advance(wasStory: Boolean, natural: Boolean) {
            if (wasStory && s.storyQueue.isNotEmpty()) {
                val next = s.storyQueue.first()
                s = s.copy(segment = Segment.Music(next, true), storyQueue = s.storyQueue.drop(1))
                start()
                return
            }
            if (!wasStory && natural && s.completedSinceDj >= 2 && s.distinctCompletedSinceDj.size >= 2) {
                refreshPending() // Revalidate location, content version, and every introduced track at the boundary.
                val plan = s.pendingStory
                if (plan != null && s.focusAllowed && s.awaitingStop == null) {
                    val first = Segment.Music(plan.tracks.first(), true)
                    s = s.copy(segment = Segment.Voice(plan.script.text, first, plan.pack.storyId),
                        storyQueue = plan.tracks.drop(1), pendingStory = null,
                        attemptedEvents = s.attemptedEvents + plan.pack.canonicalEventId,
                        history = (s.history + StoryHistory(plan.pack.storyId, plan.pack.contentVersion,
                            (s.history.lastOrNull()?.order ?: 0) + 1, StoryPlayback.STARTED)).takeLast(50))
                    invalidate()
                    start()
                    return
                }
            }
            val oldBlock = s.block
            val boundary = oldBlock == null || oldBlock.completed >= oldBlock.target || availableRemaining().isEmpty()
            val scriptPlan = s.djPlan
            val music = baseMusic() ?: return
            val bridge = !wasStory && boundary && natural && (oldBlock?.completed ?: 0) > 0
                && s.settings.djEnabled && s.completedSinceDj >= 2 && s.distinctCompletedSinceDj.size >= 2
            s = s.copy(segment = if (bridge) ordinaryVoice(music, oldBlock?.era, scriptPlan) else music)
            start()
        }

        fun availableRemaining() = s.block?.remaining.orEmpty().mapNotNull { candidate ->
            s.catalog.firstOrNull { it.id == candidate.id && it.eligible && it.releaseYear in s.block!!.era }
        }

        fun baseMusic(): Segment.Music? {
            s = s.copy(positionMs = 0)
            while (s.replayQueue.isNotEmpty()) {
                val next = s.replayQueue.first()
                s = s.copy(replayQueue = s.replayQueue.drop(1))
                val current = s.catalog.firstOrNull { it.id == next.id && it.eligible && s.settings.normalizedEras.any { era -> it.releaseYear in era } }
                if (current != null) return Segment.Music(current)
            }
            var remaining = availableRemaining()
            if (s.block == null || s.block!!.completed >= s.block!!.target || remaining.isEmpty()) {
                val plan = planner.plan(s.catalog, s.settings, s.eraCursor, s.recentIds)
                val block = plan.block ?: run { stop("NO_PLAYABLE_TRACKS"); return null }
                invalidate()
                s = s.copy(eraCursor = plan.nextEraCursor,
                    activeContext = snapshot(),
                    block = BlockCursor(block.era, block.candidates, block.targetCompleted),
                    notice = if (plan.missingEras.isNotEmpty()) "REDUCED_BROADCAST" else if (block.candidates.size == 1) "SINGLE_TRACK_REPEAT" else null)
                remaining = block.candidates
            }
            val next = remaining.first()
            s = s.copy(block = s.block!!.copy(remaining = remaining.drop(1)))
            return Segment.Music(next)
        }

        fun failed() {
            val segment = s.output!!.segment
            partialHistory()
            halt() // Actual silence must be acknowledged before any recovery output.
            if (segment is Segment.Voice) {
                s = s.copy(segment = segment.next, notice = "VOICE_FAILED")
            } else if (segment is Segment.Music) {
                val count = s.consecutiveErrors + 1
                s = s.copy(consecutiveErrors = count,
                    catalog = s.catalog.map { if (it.id == segment.track.id) it.copy(accessible = false) else it }, segment = null)
                if (count >= 3) stop("PLAYBACK_FAILURE_LIMIT") else advance(segment.story, natural = false)
            }
        }

        fun focus(focus: Focus) {
            if (focus == Focus.GAIN) {
                s = s.copy(focusAllowed = true)
                if (s.phase == Phase.SUSPENDED && s.desiredPlaying) start()
            } else {
                partialHistory()
                val voice = s.segment as? Segment.Voice
                if (voice?.storyId != null) s = s.copy(segment = voice.next)
                s = s.copy(focusAllowed = false,
                    desiredPlaying = s.desiredPlaying && focus == Focus.LOSS_TRANSIENT,
                    phase = if (s.phase in setOf(Phase.PAUSED, Phase.STOPPED, Phase.IDLE)) s.phase else Phase.SUSPENDED,
                    pendingStory = null)
                halt()
            }
        }

        fun settings(settings: radio.reply.planner.RadioSettings) {
            val erasChanged = settings.normalizedEras != s.settings.normalizedEras
                || settings.preferredGenres != s.settings.preferredGenres || settings.tripIntent != s.settings.tripIntent
            s = s.copy(settings = settings)
            invalidate()
            if (erasChanged && s.segment != null) {
                partialHistory(); halt()
                s = s.copy(block = null, eraCursor = 0, storyQueue = emptyList(), pendingStory = null, previousTracks = emptyList(), replayQueue = emptyList(), positionMs = 0)
                s = s.copy(segment = baseMusic())
                start()
            } else if (!settings.djEnabled && s.segment is Segment.Voice) {
                next()
            }
            refreshPending()
        }

        fun refreshPending() {
            val eligible = s.desiredPlaying && s.settings.djEnabled && s.settings.roadTimeTravelEnabled
                && s.settings.roadLocationConsent && s.phase == Phase.MUSIC_PLAYING
                && (s.segment as? Segment.Music)?.story != true && s.storyQueue.isEmpty()
            val candidate = if (eligible) stories.select(s.packs, s.catalog, s.match, now, s.localDate, s.attemptedEvents, s.remainingSeconds) else null
            if (candidate != s.pendingStory) { s = s.copy(pendingStory = candidate); invalidate() }
        }

        fun syncLocation() {
            val needed = s.desiredPlaying && s.focusAllowed && s.settings.djEnabled
                && s.settings.roadTimeTravelEnabled && s.settings.roadLocationConsent
                && s.phase in setOf(Phase.MUSIC_PLAYING, Phase.DJ_SPEAKING, Phase.PREPARING)
            if (needed != s.locationSubscribed) {
                s = s.copy(locationSubscribed = needed, match = if (needed) s.match else null, pendingStory = null)
                effects += Effect.SubscribeLocation(needed)
            }
        }

        fun invalidate() {
            s.djPlan?.let { effects += Effect.CancelDjRequest(it.key) }
            s = s.copy(generation = s.generation + 1, pendingRequest = null, preparedScript = null, djPlan = null)
        }

        fun inputNow(): ContextInput = if (now >= s.contextReceivedAt)
            s.contextInput.copy(now = s.contextInput.now.plusMillis(now - s.contextReceivedAt))
            else s.contextInput.copy(clockTrusted = false)

        fun snapshot(): ContextSnapshot = ContextEngine().snapshot(s.settings, inputNow(), "ctx-${s.sessionId}-$now")

        fun prefetch() {
            val music = s.output?.segment as? Segment.Music ?: return
            val block = s.block ?: return
            if (!s.desiredPlaying || !s.settings.djEnabled || music.story || s.djPlan != null) return
            if (block.completed + 1 < block.target && availableRemaining().isNotEmpty()) return
            if (s.completedSinceDj + 1 < 2 || (s.distinctCompletedSinceDj + music.track.id).size < 2) return
            val next = planner.plan(s.catalog, s.settings, s.eraCursor, (s.recentIds + music.track.id).takeLast(12)).block ?: return
            val context = snapshot()
            val sequence = s.requestSequence + 1
            val key = RequestKey(s.sessionId, s.generation, "dj-${s.sessionId}-$sequence", "before-block-$sequence", context.contextFingerprint)
            val previous = s.recentIds.mapNotNull { id -> s.catalog.firstOrNull { it.id == id } } + music.track
            val request = runCatching { ScriptContextBuilder.request(key, context, block.era, next, previous,
                inputNow().now, s.recentTopics) }.getOrElse {
                // Unusable metadata is never sent. A normalized-era fallback remains available.
                s = s.copy(lastDjFailure = DjFailure.SCHEMA_INVALID); return
            }
            val plan = DjPlan(key, request, next, now + 3_000, context)
            s = s.copy(djPlan = plan, requestSequence = sequence, pendingRequest = key, preparedScript = null)
            effects += Effect.RequestDjScript(plan)
        }

        fun currentRequest(key: RequestKey): Boolean = s.djPlan?.let {
            it.key == key && it.status == RequestStatus.PENDING && key.sessionId == s.sessionId
                && key.generation == s.generation && s.settings.djEnabled
        } == true

        fun receiveDj(event: Event.DjResponseReceived) {
            if (!currentRequest(event.key)) return
            val plan = s.djPlan!!
            if (now >= plan.deadlineMonotonic) { failDj(DjFailure.AI_TIMEOUT); return }
            if (plan.key.contextFingerprint != snapshot().contextFingerprint) { failDj(DjFailure.CONTEXT_MISMATCH); return }
            val response = DjResponseCodec.decode(event.json).getOrElse {
                failDj(if (it is InvalidDjJson) DjFailure.JSON_INVALID else DjFailure.SCHEMA_INVALID); return
            }
            val rejection = DjResponseValidator.reject(response, plan, inputNow().now)
            if (rejection != null) { failDj(rejection); return }
            s = s.copy(djPlan = plan.copy(status = RequestStatus.READY, response = response), preparedScript = response.text)
            effects += Effect.CancelDjRequest(plan.key)
        }

        fun failDj(reason: DjFailure) {
            val plan = s.djPlan ?: return
            s = s.copy(djPlan = plan.copy(status = RequestStatus.FAILED, response = null, failure = reason),
                preparedScript = null, lastDjFailure = reason)
            effects += Effect.CancelDjRequest(plan.key)
        }

        fun ordinaryVoice(music: Segment.Music, previousEra: Era?, plan: DjPlan?): Segment.Voice {
            val context = snapshot()
            val block = s.block!!
            val actualIds = (listOf(music.track) + block.remaining).take(block.target).map { it.id }
            val usable = plan != null && plan.key.contextFingerprint == context.contextFingerprint
                && plan.status == RequestStatus.READY && plan.request.context.nextEra == block.era.label
                && plan.request.nextTracks.map { it.id } == actualIds
                && plan.response != null && DjResponseValidator.reject(plan.response, plan, inputNow().now) == null
            val request = if (usable) plan!!.request else DjRequest(requestId = "local-${s.sessionId}-${s.generation}",
                sessionId = s.sessionId.toString(), generation = s.generation, segmentId = "local-bridge-${s.generation}",
                type = if (previousEra == null) DjType.OPENING else if (previousEra == block.era) DjType.SAME_ERA_BRIDGE else DjType.ERA_BRIDGE,
                maxSpokenSeconds = if (previousEra == null) 25 else 20,
                context = ScriptContextBuilder.project(context, previousEra, block.era, inputNow().now),
                previousTracks = emptyList(), nextTracks = emptyList(), recentTopics = s.recentTopics)
            val response = if (usable) plan!!.response!! else LocalDjScripts.fallback(request)
            s = s.copy(recentTopics = (s.recentTopics + response.topic).takeLast(3),
                lastDjFailure = if (usable || previousEra == null) null else plan?.failure ?: DjFailure.NOT_READY)
            return Segment.Voice(response.text, music, scriptPlan = if (usable) plan else null)
        }
        fun historyStatus(id: String, status: StoryPlayback) {
            s = s.copy(history = s.history.map { if (it.storyId == id && it.status == StoryPlayback.STARTED) it.copy(status = status) else it })
        }
        fun partialHistory() { (s.segment as? Segment.Voice)?.storyId?.let { historyStatus(it, StoryPlayback.PARTIAL) } }
        fun stop(reason: String?) {
            partialHistory(); halt(); invalidate()
            s = s.copy(phase = Phase.STOPPED, desiredPlaying = false, segment = null, block = null,
                pendingStory = null, storyQueue = emptyList(), notice = reason, positionMs = 0, previousTracks = emptyList(), replayQueue = emptyList())
            if (reason != null) effects += Effect.Notice(reason)
        }
    }
}
