package radio.reply.ux

enum class UxAccess { UNKNOWN, RESTRICTED, ALLOWED, NON_VEHICLE }

/** Only a platform unrestricted observation allows vehicle details. No speed or debug override. */
class UxSafetyGate(automotive: Boolean) {
    var access = if (automotive) UxAccess.UNKNOWN else UxAccess.NON_VEHICLE
        private set
    val detailsAllowed get() = access == UxAccess.ALLOWED || access == UxAccess.NON_VEHICLE
    var revision = 0L; private set
    @Synchronized fun observed(requiresOptimization: Boolean?, activeRestrictions: Int?) {
        if (access == UxAccess.NON_VEHICLE) return
        val next = when {
            requiresOptimization == null || activeRestrictions == null -> UxAccess.UNKNOWN
            requiresOptimization || activeRestrictions != 0 -> UxAccess.RESTRICTED
            else -> UxAccess.ALLOWED
        }
        if (next != access) { access = next; revision++ }
    }
    @Synchronized fun allowsResult(startRevision: Long) = detailsAllowed && revision == startRevision
}
