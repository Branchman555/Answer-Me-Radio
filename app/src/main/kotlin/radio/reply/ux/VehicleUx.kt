package radio.reply.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** Activity-scoped subscription. Disconnects and query failures immediately erase authorization. */
class VehicleUx(context: Context, private val changed: (UxSafetyGate) -> Unit) {
    val gate = UxSafetyGate(context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
    private var connection: CarConnection? = null
    private var closed = false
    init {
        if (gate.access != UxAccess.NON_VEHICLE) {
            try { connection = CarConnection(context, ::update) }
            catch (_: Exception) { update(null, null) }
            catch (_: LinkageError) { update(null, null) }
        }
    }
    private fun update(optimization: Boolean?, flags: Int?) { if (!closed) { gate.observed(optimization, flags); changed(gate) } }
    fun refresh() { connection?.refresh() }
    fun close() { closed = true; gate.observed(null, null); connection?.close(); connection = null }
}

/** Kept in a separate class so phones without android.car never load car classes. */
private class CarConnection(context: Context, private val update: (Boolean?, Int?) -> Unit) {
    private var closed = false
    private var generation = 0L
    private var manager: CarUxRestrictionsManager? = null
    private val car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
        if (!closed) {
            val callbackGeneration = ++generation
            if (!ready) { manager = null; update(null, null) }
            else try {
                manager = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                manager!!.registerListener {
                    if (!closed && generation == callbackGeneration) update(it.isRequiresDistractionOptimization, it.activeRestrictions)
                }
                refresh()
            } catch (_: Exception) { manager = null; update(null, null) }
        }
    }
    fun refresh() {
        if (closed) return
        try {
            val value = manager?.currentCarUxRestrictions
            update(value?.isRequiresDistractionOptimization, value?.activeRestrictions)
        } catch (_: Exception) { update(null, null) }
    }
    fun close() {
        closed = true
        runCatching { manager?.unregisterListener() }; runCatching { car.disconnect() }
        update(null, null)
    }
}
