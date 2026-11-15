package radio.reply

import org.junit.Assert.*
import org.junit.Test
import radio.reply.ux.*

class UxSafetyGateTest {
    @Test fun automotiveStartsUnknownAndLocked() { val g=UxSafetyGate(true); assertEquals(UxAccess.UNKNOWN,g.access); assertFalse(g.detailsAllowed) }
    @Test fun onlyCompleteUnrestrictedObservationUnlocks() { val g=UxSafetyGate(true); g.observed(false,0); assertTrue(g.detailsAllowed) }
    @Test fun optimizationFlagAloneLocks() { val g=UxSafetyGate(true); g.observed(true,0); assertFalse(g.detailsAllowed) }
    @Test fun anyActiveRestrictionLocksDetails() { val g=UxSafetyGate(true); for (flag in listOf(1,2,4,8,16,32,64,128,256,512)) { g.observed(false,flag); assertFalse(g.detailsAllowed) } }
    @Test fun missingEitherObservationIsUnknown() { val g=UxSafetyGate(true); g.observed(null,0); assertEquals(UxAccess.UNKNOWN,g.access); g.observed(false,null); assertFalse(g.detailsAllowed) }
    @Test fun disconnectRevokesPreviouslyAllowedDetails() { val g=UxSafetyGate(true); g.observed(false,0); g.observed(null,null); assertFalse(g.detailsAllowed) }
    @Test fun pickerResultAfterRestrictionAndRestoreIsRejected() { val g=UxSafetyGate(true); g.observed(false,0); val revision=g.revision; g.observed(true,1); g.observed(false,0); assertFalse(g.allowsResult(revision)) }
    @Test fun sameStateRefreshDoesNotInvalidatePicker() { val g=UxSafetyGate(true); g.observed(false,0); val revision=g.revision; g.observed(false,0); assertTrue(g.allowsResult(revision)) }
    @Test fun phoneIsExplicitlyNonVehicleNotAssumedParked() { val g=UxSafetyGate(false); g.observed(null,null); assertEquals(UxAccess.NON_VEHICLE,g.access); assertTrue(g.detailsAllowed) }
}
