package radio.reply.context

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import radio.reply.planner.RadioSettings

enum class TimeOfDay { MORNING, DAY, EVENING, NIGHT, UNKNOWN }
enum class WeatherCondition { RAIN, SUN, CLOUD, SNOW, UNKNOWN }
enum class WeatherSource { API, SIMULATION }
enum class LocationStatus { NOT_REQUESTED, AVAILABLE, DENIED, UNAVAILABLE, BLOCKED }
enum class TripIntent { NONE, COMMUTE, TRAVEL, LONG_DRIVE }
enum class WeatherScope { LOCATION, SELECTED_REGION }
data class WeatherReading(val condition: WeatherCondition, val observedAt: String, val fetchedAt: String,
    val source: WeatherSource = WeatherSource.API, val scope: WeatherScope = WeatherScope.LOCATION)
data class Weather(val condition: WeatherCondition, val observedAt: String, val fetchedAt: String,
    val source: WeatherSource, val valid: Boolean = true)
data class Preferences(val genres: List<String>, val djEnabled: Boolean, val tripIntent: TripIntent)
data class VehicleContext(val availability: String = "DISABLED", val tripDurationSeconds: Long? = null,
    val dataCapturedAt: String? = null)
data class ContextSnapshot(
    val schemaVersion: String = "1.0", val snapshotId: String, val capturedAt: String,
    val simulationMode: Boolean, val mode: String, val eras: List<String>, val timeOfDay: TimeOfDay,
    val preferences: Preferences, val weather: Weather?, val locationStatus: LocationStatus,
    val vehicle: VehicleContext = VehicleContext(), val omissions: List<String>, val contextFingerprint: String,
)

/** Already collected inputs, deliberately without coordinates, URIs, or vehicle identity. */
data class ContextInput(
    val now: Instant = Instant.EPOCH, val zone: ZoneId? = null, val clockTrusted: Boolean = false,
    val weather: WeatherReading? = null, val locationStatus: LocationStatus = LocationStatus.NOT_REQUESTED,
    val selectedRegionConsented: Boolean = false, val simulationRequested: Boolean = false,
    val developmentBuild: Boolean = false, val weatherFailure: String? = null,
)

object ContextJson {
    val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    fun encode(value: Any): String = gson.toJson(value)
    fun canonical(element: JsonElement): String = when {
        element.isJsonObject -> element.asJsonObject.keySet().sorted().joinToString(",", "{", "}") {
            gson.toJson(it) + ":" + canonical(element.asJsonObject[it])
        }
        element.isJsonArray -> element.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        else -> gson.toJson(element)
    }
}

object WeatherValidityPolicy {
    fun valid(weather: Weather, now: Instant, simulationAllowed: Boolean): Boolean =
        reason(weather, now, simulationAllowed) == null

    fun reason(weather: Weather, now: Instant, simulationAllowed: Boolean): String? {
        if (weather.source == WeatherSource.SIMULATION && !simulationAllowed) return "SIMULATION_DISABLED"
        if (!weather.valid || weather.condition == WeatherCondition.UNKNOWN) return "WEATHER_INVALID"
        val observed = runCatching { Instant.parse(weather.observedAt) }.getOrNull() ?: return "WEATHER_INVALID"
        if (runCatching { Instant.parse(weather.fetchedAt) }.isFailure) return "WEATHER_INVALID"
        val age = Duration.between(observed, now)
        return when {
            age > Duration.ofMinutes(60) -> "WEATHER_STALE"
            age < Duration.ofMinutes(-5) -> "WEATHER_INVALID"
            else -> null
        }
    }
}

object FingerprintFactory {
    fun canonical(snapshot: ContextSnapshot): String {
        val value = ContextJson.gson.toJsonTree(snapshot).asJsonObject
        val projection = JsonObject()
        listOf("mode", "eras", "timeOfDay", "preferences").forEach { projection.add(it, value[it].deepCopy()) }
        val genres = snapshot.preferences.genres.sorted()
        projection.getAsJsonObject("preferences").add("genres", ContextJson.gson.toJsonTree(genres))
        projection.add("weather", ContextJson.gson.toJsonTree(snapshot.weather?.let { mapOf("condition" to it.condition.name) }))
        return ContextJson.canonical(projection)
    }
    fun create(snapshot: ContextSnapshot): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(canonical(snapshot).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class ContextEngine {
    fun snapshot(settings: RadioSettings, input: ContextInput, id: String): ContextSnapshot {
        require(id.isNotBlank())
        val omissions = linkedSetOf<String>()
        val simulation = input.developmentBuild && input.simulationRequested
        val time = if (input.clockTrusted && input.zone != null) classify(input.now.atZone(input.zone).hour)
            else TimeOfDay.UNKNOWN.also { omissions += "TIME_UNKNOWN" }
        when (input.locationStatus) {
            LocationStatus.DENIED -> omissions += "LOCATION_DENIED"
            LocationStatus.UNAVAILABLE -> omissions += "LOCATION_UNAVAILABLE"
            LocationStatus.BLOCKED -> omissions += "LOCATION_BLOCKED"
            else -> Unit
        }
        input.weatherFailure?.takeIf { it in setOf("WEATHER_TIMEOUT", "WEATHER_INVALID") }?.let { omissions += it }
        val weather = input.weather?.let { raw ->
            val permitted = if (raw.scope == WeatherScope.SELECTED_REGION) input.selectedRegionConsented
                else input.locationStatus == LocationStatus.AVAILABLE
            if (!permitted) return@let null
            val candidate = Weather(raw.condition, raw.observedAt, raw.fetchedAt, raw.source)
            val reason = if (!input.clockTrusted) "WEATHER_INVALID"
                else WeatherValidityPolicy.reason(candidate, input.now, simulation)
            if (reason != null) { omissions += reason; null } else candidate
        }
        val genres = settings.preferredGenres.sorted().also {
            require(it.size <= 12 && it.all { genre -> genre.isNotBlank() && genre.length <= 48 })
        }
        val snapshot = ContextSnapshot(snapshotId = id, capturedAt = input.now.toString(), simulationMode = simulation,
            mode = settings.mode.name, eras = frozen(settings.normalizedEras.map { it.label }), timeOfDay = time,
            preferences = Preferences(frozen(genres), settings.djEnabled, settings.tripIntent), weather = weather,
            locationStatus = input.locationStatus, omissions = frozen(omissions.toList()), contextFingerprint = "")
        return snapshot.copy(contextFingerprint = FingerprintFactory.create(snapshot))
    }

    companion object {
        fun classify(hour: Int): TimeOfDay {
            require(hour in 0..23)
            return when (hour) { in 5..10 -> TimeOfDay.MORNING; in 11..16 -> TimeOfDay.DAY
                in 17..20 -> TimeOfDay.EVENING; else -> TimeOfDay.NIGHT }
        }
        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    }
}
