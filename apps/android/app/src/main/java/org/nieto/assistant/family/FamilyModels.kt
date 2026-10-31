package org.nieto.assistant.family

import org.json.JSONObject
import java.time.Instant

data class FamilyMember(val id: String, val name: String, val shareProgress: Boolean, val allowHelp: Boolean,
    val reportedFlows: Int?, val practiceReports: Int?, val accompaniedSeconds: Long?)
data class FamilyAlert(val id: String, val memberId: String, val requesterName: String, val createdAt: String,
    val resolved: Boolean)
data class FamilySummary(val groupName: String, val members: List<FamilyMember>, val alerts: List<FamilyAlert>) {
    companion object {
        fun parse(json: JSONObject): FamilySummary {
            val members = json.getJSONArray("members")
            val alerts = json.getJSONArray("alerts")
            return FamilySummary(json.getJSONObject("group").getString("name").take(60),
                (0 until members.length()).map { index ->
                    val member = members.getJSONObject(index); val permissions = member.getJSONObject("permissions")
                    val progress = permissions.optBoolean("progress", false)
                    FamilyMember(member.getString("id"), member.optString("name").take(60), progress,
                        permissions.optBoolean("help", false), if (progress) member.optInt("reportedFlows") else null,
                        if (progress) member.optInt("practiceReports") else null,
                        if (progress) member.optLong("accompaniedSeconds") else null)
                },
                (0 until alerts.length()).map { index ->
                    val alert = alerts.getJSONObject(index)
                    FamilyAlert(alert.getString("id"), alert.optString("memberId"), alert.optString("requesterName").take(60),
                        alert.optString("createdAt"), !alert.optString("resolvedAt").let { it.isBlank() || it == "null" })
                })
        }
    }
}

data class FamilyCheckpoint(val initialized: Boolean = false, val time: Long = 0, val idsAtTime: Set<String> = emptySet())
data class FamilyNotificationPlan(val checkpoint: FamilyCheckpoint, val eligible: List<FamilyAlert>, val fresh: List<FamilyAlert>)

/** Only a currently consenting member's pending request can produce a notification.
 * A watermark plus all IDs at that timestamp prevents repeats, including equal-time requests.
 * The first successful snapshot is a baseline: existing requests remain visible in the app. */
object FamilyNotificationPolicy {
    fun evaluate(summary: FamilySummary, previous: FamilyCheckpoint): FamilyNotificationPlan {
        val consenting = summary.members.filter { it.allowHelp }.map { it.id }.toSet()
        val stamped = summary.alerts.mapNotNull { alert ->
            runCatching { Instant.parse(alert.createdAt).toEpochMilli() }.getOrNull()?.let { alert to it }
        }
        val eligible = stamped.filter { (alert, _) -> !alert.resolved && alert.memberId in consenting }
        val fresh = if (!previous.initialized) emptyList() else eligible.filter { (alert, time) ->
            time > previous.time || (time == previous.time && alert.id !in previous.idsAtTime)
        }.map { it.first }
        val latest = maxOf(previous.time, stamped.maxOfOrNull { it.second } ?: previous.time)
        val ids = stamped.filter { it.second == latest }.map { it.first.id }.toSet() +
            (if (latest == previous.time) previous.idsAtTime else emptySet())
        return FamilyNotificationPlan(FamilyCheckpoint(true, latest, ids), eligible.map { it.first }, fresh)
    }
}
