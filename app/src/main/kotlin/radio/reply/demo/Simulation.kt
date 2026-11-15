package radio.reply.demo

import java.time.LocalDate
import radio.reply.orchestrator.*
import radio.reply.planner.Track
import radio.reply.story.*

/** Silent fake ports. The same reducer is used by tests and the development screen. */
class Simulation(initial: RadioState = fixtures()) {
    private val reducer = RadioReducer()
    private val events = ArrayDeque<Event>()
    private var draining = false
    var state = initial
        private set
    var now = 1000L
    var autoConfirmStop = true
    var physicalOutput: ActiveOutput? = null
        private set
    val effects = mutableListOf<Effect>()
    val djProvider = FakeDjScriptProvider()
    private data class Scheduled(val at: Long, val key: RequestKey, val event: Event)
    private val scheduled = mutableListOf<Scheduled>()

    private fun enqueueDue() {
        val due = scheduled.filter { it.at <= now }.sortedBy { it.at }
        scheduled.removeAll(due.toSet())
        due.forEach { events.addLast(it.event) }
    }

    fun advanceTimeBy(millis: Long) {
        require(millis >= 0)
        now += millis
        send(Event.ClockTick)
    }

    fun send(event: Event) {
        enqueueDue()
        events.addLast(event)
        if (draining) return
        draining = true
        try {
            while (events.isNotEmpty()) {
                val result = reducer.reduce(state, events.removeFirst(), now)
                state = result.state
                result.effects.forEach { effect ->
                    effects += effect
                    when (effect) {
                        is Effect.StartOutput -> {
                            check(physicalOutput == null) { "OVERLAPPING_AUDIO_OUTPUT" }
                            physicalOutput = effect.output
                        }
                        is Effect.StopOutput -> if (autoConfirmStop) {
                            if (physicalOutput?.token == effect.token) physicalOutput = null
                            events.addLast(Event.OutputStopped(effect.token))
                        }
                        is Effect.RequestDjScript -> {
                            val plan = effect.plan
                            val response = djProvider.response(plan.request, plan.snapshot.simulationMode)
                            scheduled += Scheduled(plan.deadlineMonotonic, plan.key, Event.DjDeadline(plan.key))
                            if (response != null) scheduled += Scheduled(now + djProvider.delayMillis, plan.key,
                                Event.DjResponseReceived(plan.key, response))
                            else if (djProvider.result == FakeDjResult.ERROR) scheduled += Scheduled(now + djProvider.delayMillis,
                                plan.key, Event.DjRequestFailed(plan.key))
                        }
                        is Effect.CancelDjRequest -> scheduled.removeAll { it.key == effect.key }
                        else -> Unit
                    }
                }
                enqueueDue()
            }
        } finally { draining = false }
    }

    fun complete(token: OutputToken = state.output?.token ?: error("No simulated output")) {
        if (physicalOutput?.token == token) physicalOutput = null
        send(Event.Ended(token))
    }

    fun confirmStop(token: OutputToken = state.awaitingStop ?: error("No stop pending")) {
        if (physicalOutput?.token == token) physicalOutput = null
        send(Event.OutputStopped(token))
    }

    companion object {
        fun fixtures(): RadioState {
            val tracks = (1..4).map { Track("a$it", "모의 80년대 곡 $it", "가상 아티스트 A$it", 1980 + it, "fixture:a$it") } +
                (1..4).map { Track("b$it", "모의 10년대 곡 $it", "가상 아티스트 B$it", 2010 + it, "fixture:b$it") } +
                Track("story-track", "모의 2001년 곡", "가상 아티스트 C", 2001, "fixture:story")
            val story = StoryPack("SIMULATION_ONLY", true, "fictional-story", "fictional-event", 1,
                "가상 구간 (실제 지리 아님)", LocalDate.of(2001, 11, 15), LocalDate.of(2001, 11, 16),
                "simulation-segment", true, Review.APPROVED, true,
                listOf(Fact("fact", "모의 사건입니다.", setOf("S1"))),
                listOf(MusicAssociation("music", "모의 2001년 곡", "가상 아티스트 C", 2001, null,
                    MusicBasis.RELEASE_PERIOD, setOf("S1"), "story-track", true)),
                listOf("LONG", "SHORT").map { StoryScript("script-$it", it,
                    "개발용 모의 역사 코너입니다. 실제 장소나 사건을 설명하지 않습니다.",
                    setOf("fact"), "music", Review.APPROVED) })
            return RadioState(catalog = tracks, packs = listOf(story), localDate = LocalDate.of(2026, 11, 15))
        }

        fun match(now: Long = 1000, revision: Long = 1) = SegmentMatch("simulation-segment", revision,
            MatchStatus.CONFIRMED, now, now + 10_000, true, true, 2)
    }
}
