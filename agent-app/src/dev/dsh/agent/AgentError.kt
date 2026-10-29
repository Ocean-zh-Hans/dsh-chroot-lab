package dev.dsh.agent

import org.json.JSONObject

internal class AgentError @JvmOverloads constructor(
    val code: String,
    message: String,
    val via: String = "none",
    val retryable: Boolean = false
) : Exception(message) {
    val detail: JSONObject = JSONObject()
    var resultState: String = "OK"
    var replayed: Boolean = false

    fun detail(key: String, value: Any?): AgentError {
        try {
            detail.put(key, value)
        } catch (_: Exception) {
        }
        return this
    }

    companion object {
        @JvmStatic
        fun unavailable(message: String): AgentError = AgentError("UNAVAILABLE", message)

        @JvmStatic
        fun confirm(params: JSONObject) {
            if (params.optString("confirm") != "YES") {
                throw AgentError("CONFIRM_REQUIRED", "set confirm to YES for this operation")
            }
        }
    }
}
