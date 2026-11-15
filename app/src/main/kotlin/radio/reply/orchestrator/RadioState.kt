package radio.reply.orchestrator

import java.time.LocalDate
import radio.reply.planner.*
import radio.reply.story.*
import radio.reply.context.*
import radio.reply.dj.*

enum class Phase { IDLE, PREPARING, DJ_SPEAKING, MUSIC_PLAYING, PAUSED, SUSPENDED, RECOVERING, STOPPED }
enum class Focus { GAIN, LOSS_TRANSIENT, LOSS_PERMANENT }
data class OutputToken(val sessionId: Long, val epoch: Long)
sealed interface Segment {
    data class Music(val track: Track, val story: Boolean = false) : Segment
    data class Voice(val text: String, val next: Music, val storyId: String? = null, val scriptPlan: DjPlan? = null) : Segment
}
data class ActiveOutput(val token: OutputToken, val segment: Segment, val positionMs: Long = 0)
data class BlockCursor(val era: Era, val remaining: List<Track>, val target: Int, val completed: Int = 0)
data class RequestKey(val sessionId: Long, val generation: Long, val requestId: String, val segmentId: String, val contextFingerprint: String)
enum class StoryPlayback { STARTED, COMPLETE, PARTIAL }
data class StoryHistory(val storyId: String, val contentVersion: Int, val order: Int, val status: StoryPlayback)

data class RadioState(
    val settings: RadioSettings = RadioSettings(), val catalog: List<Track> = emptyList(),
    val packs: List<StoryPack> = emptyList(),
    val phase: Phase = Phase.IDLE, val desiredPlaying: Boolean = false, val focusAllowed: Boolean = true,
    val sessionId: Long = 0, val generation: Long = 0, val playbackEpoch: Long = 0,
    val segment: Segment? = null, val output: ActiveOutput? = null, val awaitingStop: OutputToken? = null,
    val eraCursor: Int = 0, val block: BlockCursor? = null,
    val completedSinceDj: Int = 0, val distinctCompletedSinceDj: Set<String> = emptySet(),
    val recentIds: List<String> = emptyList(), val consecutiveErrors: Int = 0,
    val match: SegmentMatch? = null, val pendingStory: StoryPlan? = null,
    val storyQueue: List<Track> = emptyList(), val attemptedEvents: Set<String> = emptySet(),
    val history: List<StoryHistory> = emptyList(), val localDate: LocalDate? = null,
    val remainingSeconds: Long? = null, val locationSubscribed: Boolean = false,
    val pendingRequest: RequestKey? = null, val preparedScript: String? = null,
    val contextInput: ContextInput = ContextInput(), val contextReceivedAt: Long = 0,
    val activeContext: ContextSnapshot? = null, val djPlan: DjPlan? = null,
    val requestSequence: Long = 0, val recentTopics: List<Topic> = emptyList(),
    val lastDjFailure: DjFailure? = null,
    val notice: String? = null,
    val positionMs: Long = 0, val previousTracks: List<radio.reply.planner.Track> = emptyList(),
    val replayQueue: List<radio.reply.planner.Track> = emptyList(),
)

sealed interface Event {
    data object Play : Event
    data object Pause : Event
    data object Stop : Event
    data object Next : Event
    data object Previous : Event
    data class PositionUpdated(val token: OutputToken, val positionMs: Long) : Event
    data class SeekTo(val positionMs: Long) : Event
    data class SelectTrack(val id: String) : Event
    data class Ended(val token: OutputToken) : Event
    data class OutputStopped(val token: OutputToken) : Event
    data class StopTimedOut(val token: OutputToken) : Event
    /** Error alone is not proof of silence; the adapter must confirm before OutputStopped. */
    data class Failed(val token: OutputToken) : Event
    data class FocusChanged(val focus: Focus) : Event
    data class SettingsChanged(val settings: RadioSettings) : Event
    data class CatalogChanged(val tracks: List<Track>) : Event
    data class MatchChanged(val match: SegmentMatch?) : Event
    data class PacksChanged(val packs: List<StoryPack>) : Event
    data class TimingChanged(val date: LocalDate?, val remainingSeconds: Long?) : Event
    data class ScriptRequested(val key: RequestKey) : Event
    data class ScriptReady(val key: RequestKey, val text: String) : Event
    data class ContextChanged(val input: ContextInput) : Event
    data class DjResponseReceived(val key: RequestKey, val json: String) : Event
    data class DjRequestFailed(val key: RequestKey) : Event
    data class DjDeadline(val key: RequestKey) : Event
    data object ClockTick : Event
    data object ClearHistory : Event
}

sealed interface Effect {
    data class StartOutput(val output: ActiveOutput) : Effect
    data class StopOutput(val token: OutputToken) : Effect
    data class SubscribeLocation(val enabled: Boolean) : Effect
    data class Notice(val code: String) : Effect
    data class RequestDjScript(val plan: DjPlan) : Effect
    data class CancelDjRequest(val key: RequestKey) : Effect
}
data class Transition(val state: RadioState, val effects: List<Effect>)
