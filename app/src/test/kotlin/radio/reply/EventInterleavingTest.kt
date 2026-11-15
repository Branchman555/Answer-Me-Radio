package radio.reply

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test
import radio.reply.demo.Simulation
import radio.reply.orchestrator.*

class EventInterleavingTest {
    @Test fun seededUserAndLateCallbackInterleavingsPreserveSingleOutputAndUserHold() {
        repeat(20) { seed ->
            val sim = Simulation()
            sim.autoConfirmStop = false
            val random = Random(seed)
            val oldTokens = mutableListOf<OutputToken>()
            repeat(100) {
                sim.state.output?.token?.let { oldTokens.add(it) }
                when (random.nextInt(11)) {
                    0 -> sim.send(Event.Play)
                    1 -> sim.send(Event.Pause)
                    2 -> sim.send(Event.Stop)
                    3 -> sim.send(Event.Next)
                    4 -> sim.send(Event.FocusChanged(Focus.LOSS_TRANSIENT))
                    5 -> sim.send(Event.FocusChanged(Focus.GAIN))
                    6 -> if (sim.state.output != null) sim.complete()
                    7 -> if (sim.state.awaitingStop != null) sim.confirmStop()
                    8 -> oldTokens.firstOrNull()?.let { sim.complete(it) }
                    9 -> sim.send(Event.MatchChanged(Simulation.match(sim.now)))
                    10 -> sim.send(Event.SettingsChanged(sim.state.settings.copy(djEnabled = random.nextBoolean())))
                }
                assertFalse(sim.state.output != null && sim.state.awaitingStop != null)
                if (sim.state.phase in setOf(Phase.IDLE, Phase.PAUSED, Phase.STOPPED)) {
                    assertFalse(sim.state.desiredPlaying)
                    assertNull(sim.state.output)
                }
                sim.state.output?.let { assertEquals(it, sim.physicalOutput) }
            }
        }
    }
}
