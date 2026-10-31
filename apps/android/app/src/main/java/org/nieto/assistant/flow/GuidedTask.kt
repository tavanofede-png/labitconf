package org.nieto.assistant.flow

import org.json.JSONObject

data class TaskStep(val id: String, val instruction: String, val final: Boolean)
data class EmailDraft(val to: String, val subject: String, val body: String)
data class GuidedTask(val sessionId: String, val flowId: String, val title: String, val steps: List<TaskStep>, val completionLabel: String,
    val index: Int = 0, val completed: Boolean = false, val cancelled: Boolean = false) {
    val current get() = steps[index.coerceIn(0, steps.lastIndex)]
    val active get() = !completed && !cancelled
    companion object {
        fun from(reply: JSONObject): GuidedTask {
            val flow = reply.getJSONObject("flow")
            val list = flow.getJSONArray("steps")
            require(list.length() in 1..12)
            val steps = (0 until list.length()).map { i -> val step = list.getJSONObject(i); TaskStep(step.getString("id"), step.getString("instruction"), step.optBoolean("final")) }
            return GuidedTask(reply.getString("id"), flow.getString("id"), flow.getString("title"), steps, flow.getString("completionLabel"))
        }
    }
}
