package org.nieto.assistant.family

import org.junit.Assert.*
import org.junit.Test

class FamilyNotificationPolicyTest {
    private val time = "2026-10-31T12:00:00Z"
    private val member = FamilyMember("member", "Persona", false, true, null, null, null)
    private fun alert(id: String, createdAt: String = time, resolved: Boolean = false, memberId: String = "member") =
        FamilyAlert(id, memberId, "Persona", createdAt, resolved)
    private fun summary(alerts: List<FamilyAlert>, members: List<FamilyMember> = listOf(member)) = FamilySummary("Grupo", members, alerts)

    @Test fun firstSnapshotEstablishesBaselineWithoutNotifyingExistingRequests() {
        val plan = FamilyNotificationPolicy.evaluate(summary(listOf(alert("old"))), FamilyCheckpoint())
        assertTrue(plan.checkpoint.initialized)
        assertEquals(listOf("old"), plan.eligible.map { it.id })
        assertTrue(plan.fresh.isEmpty())
    }
    @Test fun retriesNeverRepeatAndNewIdsAtTheSameTimestampStillNotify() {
        val first = FamilyNotificationPolicy.evaluate(summary(listOf(alert("one"))), FamilyCheckpoint(true))
        assertEquals(listOf("one"), first.fresh.map { it.id })
        val retried = FamilyNotificationPolicy.evaluate(summary(listOf(alert("one"))), first.checkpoint)
        assertTrue(retried.fresh.isEmpty())
        val second = FamilyNotificationPolicy.evaluate(summary(listOf(alert("one"), alert("two"))), retried.checkpoint)
        assertEquals(listOf("two"), second.fresh.map { it.id })
    }
    @Test fun revokedHelpOrMissingMemberCannotNotifyOrRemainEligible() {
        val noHelp = FamilyNotificationPolicy.evaluate(summary(listOf(alert("one")), listOf(member.copy(allowHelp = false))), FamilyCheckpoint(true))
        assertTrue(noHelp.fresh.isEmpty()); assertTrue(noHelp.eligible.isEmpty())
        val removed = FamilyNotificationPolicy.evaluate(summary(listOf(alert("one")), emptyList()), FamilyCheckpoint(true))
        assertTrue(removed.fresh.isEmpty()); assertTrue(removed.eligible.isEmpty())
    }
    @Test fun resolvedRequestsNeverNotifyAndConsentGrantDoesNotReplayOldRequests() {
        val initial = FamilyNotificationPolicy.evaluate(summary(listOf(alert("resolved", resolved = true), alert("private")),
            listOf(member.copy(allowHelp = false))), FamilyCheckpoint(true))
        assertTrue(initial.fresh.isEmpty())
        val granted = FamilyNotificationPolicy.evaluate(summary(listOf(alert("resolved", resolved = true), alert("private"))), initial.checkpoint)
        assertTrue(granted.fresh.isEmpty())
        assertEquals(listOf("private"), granted.eligible.map { it.id })
    }
    @Test fun invalidDatesCannotTriggerNotificationsOrEraseTheCursor() {
        val baseline = FamilyNotificationPolicy.evaluate(summary(listOf(alert("old"))), FamilyCheckpoint()).checkpoint
        val malformed = FamilyNotificationPolicy.evaluate(summary(listOf(alert("invalid", "not-a-date"))), baseline)
        assertTrue(malformed.fresh.isEmpty())
        assertEquals(baseline, malformed.checkpoint)
    }
    @Test fun emptyOrOlderSnapshotsDoNotRewindAndReplayAlreadySeenRequests() {
        val baseline = FamilyNotificationPolicy.evaluate(summary(listOf(alert("current"))), FamilyCheckpoint()).checkpoint
        val empty = FamilyNotificationPolicy.evaluate(summary(emptyList()), baseline)
        assertEquals(baseline, empty.checkpoint)
        val old = FamilyNotificationPolicy.evaluate(summary(listOf(alert("past", "2026-10-30T12:00:00Z"))), empty.checkpoint)
        assertTrue(old.fresh.isEmpty())
        assertEquals(baseline, old.checkpoint)
    }
}
