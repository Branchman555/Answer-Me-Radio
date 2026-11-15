package radio.reply.context

/** Single-flight boundary for a future gateway. No provider, permissions, or HTTP are invoked here. */
class WeatherCache {
    data class Ticket(val revision: Long)
    private var revision = 0L
    private var inFlight: Ticket? = null
    private var nextAllowedAt = Long.MIN_VALUE
    var reading: WeatherReading? = null
        private set

    @Synchronized fun begin(nowMonotonic: Long, permitted: Boolean): Ticket? {
        if (!permitted || inFlight != null || nowMonotonic < nextAllowedAt) return null
        return Ticket(++revision).also { inFlight = it }
    }
    @Synchronized fun complete(ticket: Ticket, result: WeatherReading?, nowMonotonic: Long) {
        if (ticket != inFlight) return
        inFlight = null
        if (result != null) reading = result
        nextAllowedAt = nowMonotonic + if (result == null) 300_000 else 900_000
    }
    @Synchronized fun revoke() {
        revision++; inFlight = null; reading = null; nextAllowedAt = Long.MIN_VALUE
    }
}
